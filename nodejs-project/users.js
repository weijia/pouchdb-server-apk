'use strict';

// 账户与配对/注册存储：多用户鉴权（admin / peer）+ 配对 pending / 审批 + 网页注册审批。
// 落盘到 dataDir/users.json（原子改名写入）。密码以 scrypt 加盐哈希存储，不存明文。
// 与 pouchdb-server-apk/nodejs-project/users.js 保持同一套契约，便于两端互通。
//
// 设计要点：
// - 数据端口的 Basic Auth 由此处的 verify() 驱动；users.json 非空即要求登录。
// - 配对公开端点 /_pair/request 只创建 pending，不授予任何权限，直至本机审批（控制端口）。
// - 网页注册端点 /_register 同样只创建 pending（type='register'），须本机 admin 审批后才落成账号。
// - 审批后 pending 转为 active 的 peer 账号；凭据（user/pass）仅在审批后、请求方轮询
//   /_pair/status 时一次性可见（pending 标记 approved 并保留至陈旧清理）。
// - 注册 pending 的密码在提交时即哈希（passHash），不落明文；审批时直接复用该哈希。

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PENDING_CAP = 20;          // pending 总数上限（防刷）
const RATE_WINDOW_MS = 60 * 1000; // 同 deviceId / IP 速率窗口
const RATE_MAX = 5;              // 窗口内最大请求数
const STALE_MS = 60 * 60 * 1000; // pending 陈旧阈值（1h）
// 已批准/已拒绝条目的保留时长：够请求方轮询 /_pair/status 取凭据即可，
// 过期即删，避免长期占用 PENDING_CAP。
const HANDLED_KEEP_MS = 5 * 60 * 1000;

// 注册相关限制
const REGISTER_RATE_MAX = 10;      // 同一 IP 每窗口最多提交注册数
const REGISTER_RATE_WINDOW_MS = 60 * 60 * 1000; // 注册限流窗口（1h）
const USERNAME_RE = /^[A-Za-z0-9_.-]{3,32}$/;    // 用户名：3-32 位，字母数字 _ . -
const RESERVED_USERNAMES = ['admin', 'root', 'administrator', 'system', 'superuser'];
const MIN_PASSWORD_LEN = 6;

// 校验注册用户名：格式、保留名、peer- 前缀（自动生成的配对账号占位）、是否已存在。
// 返回 { ok:true } 或 { ok:false, reason }
function validateUsername(store, username) {
  if (!username || typeof username !== 'string') return { ok: false, reason: 'username required' };
  if (!USERNAME_RE.test(username)) {
    return { ok: false, reason: 'username must be 3-32 chars of A-Z a-z 0-9 _ . -' };
  }
  const lower = username.toLowerCase();
  if (RESERVED_USERNAMES.indexOf(lower) >= 0) return { ok: false, reason: 'username reserved' };
  // peer-xxxx 是配对审批时自动生成的账号名，注册不得占用该前缀以免混淆
  if (lower.indexOf('peer-') === 0) return { ok: false, reason: 'username prefix reserved' };
  if (store.userExists(username)) return { ok: false, reason: 'username already exists' };
  // 与其它待审批注册重名也拒绝，避免两条 pending 审批后互相覆盖
  const takenInPending = Object.keys(store.pending).some((t) => {
    const p = store.pending[t];
    return p && p.type === 'register' && p.username === username && p.state === 'pending';
  });
  if (takenInPending) return { ok: false, reason: 'username already pending' };
  return { ok: true };
}

function validatePassword(pass) {
  if (!pass || typeof pass !== 'string') return { ok: false, reason: 'password required' };
  if (pass.length < MIN_PASSWORD_LEN) {
    return { ok: false, reason: 'password must be at least ' + MIN_PASSWORD_LEN + ' chars' };
  }
  if (pass.length > 128) return { ok: false, reason: 'password too long' };
  return { ok: true };
}

function hashPassword(pass) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(pass, salt, 64).toString('hex');
  return salt + ':' + hash;
}

function verifyPassword(pass, stored) {
  if (!stored || stored.indexOf(':') < 0) return false;
  const idx = stored.indexOf(':');
  const salt = stored.slice(0, idx);
  const expected = stored.slice(idx + 1);
  const actual = crypto.scryptSync(pass, salt, 64).toString('hex');
  const a = Buffer.from(actual, 'hex');
  const b = Buffer.from(expected, 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

class UserStore {
  constructor(dataDir) {
    this.file = path.join(dataDir, 'users.json');
    this.users = {};   // username -> { passHash, role:'admin'|'peer', deviceId?, active, createdAt }
    this.pending = {};  // token -> { deviceId, name, username, pass, state:'pending'|'approved', createdAt }
    this._rate = {};   // deviceId -> [timestamp,...]（仅内存，无需持久化）
    this.load();
    this.pruneStale();
  }

  load() {
    try {
      const data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.users = data.users || {};
      this.pending = data.pending || {};
    } catch (e) {
      this.users = {};
      this.pending = {};
    }
  }

  save() {
    try {
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify({ users: this.users, pending: this.pending }, null, 2));
      fs.renameSync(tmp, this.file);
    } catch (e) { /* 落盘失败不影响内存态，仅下次启动可能丢失未持久化账户 */ }
  }

  hasAnyUser() {
    return Object.keys(this.users).length > 0;
  }

  // 仅校验 active 用户（pending 不算可用凭据）
  verify(user, pass) {
    const u = this.users[user];
    return !!(u && u.active !== false && verifyPassword(pass, u.passHash));
  }

  // 账号角色：用于判断是否有权执行管理类操作（未知账号返回 null）
  roleOf(user) {
    const u = this.users[user];
    return u && u.active !== false ? (u.role || 'peer') : null;
  }

  // 用户名是否已被占用（含已停用账号，避免注册覆盖历史账号）
  userExists(username) {
    return Object.prototype.hasOwnProperty.call(this.users, username);
  }

  // 仅本地账号（localOnly）：只允许从回环地址登录，用于高风险的高权限账号，
  // 防止凭据一旦泄露在网络上被远程直接打数据端口 API。
  isLocalOnly(user) {
    const u = this.users[user];
    return !!(u && u.localOnly === true);
  }

  setAdmin(user, pass, localOnly = false) {
    if (!user || !pass) return false;
    const u = {
      passHash: hashPassword(pass),
      role: 'admin',
      active: true,
      createdAt: Date.now(),
    };
    if (localOnly) u.localOnly = true;
    this.users[user] = u;
    this.save();
    return true;
  }

  // 确保存在一个“仅本地”的 admin 账号（预设为 admin/admin123）。
  // - 用户名不存在：直接创建 localOnly 的 admin。
  // - 用户名已存在：升级为 localOnly 的 admin（保留原密码），满足“只能本地访问”。
  ensureLocalAdmin(user, pass) {
    if (!user || !pass) return;
    const existing = this.users[user];
    if (!existing) {
      this.users[user] = {
        passHash: hashPassword(pass),
        role: 'admin',
        active: true,
        localOnly: true,
        createdAt: Date.now(),
      };
      this.save();
      return;
    }
    existing.role = 'admin';
    existing.active = true;
    existing.localOnly = true;
    this.save();
  }

  // 启动兼容：旧 --auth-user/--auth-pass 在 users.json 为空时 seed 为 admin
  seedAdminIfEmpty(user, pass) {
    if (!user || !pass) return;
    if (!this.hasAnyUser()) this.setAdmin(user, pass);
  }

  getAdminStatus() {
    const found = Object.keys(this.users).some((u) => this.users[u].role === 'admin');
    return { configured: found };
  }

  // 同 deviceId 速率限制（窗口内超过 RATE_MAX 拒绝）
  _rateLimited(deviceId) {
    const now = Date.now();
    const arr = (this._rate[deviceId] || []).filter((t) => now - t < RATE_WINDOW_MS);
    arr.push(now);
    this._rate[deviceId] = arr;
    return arr.length > RATE_MAX;
  }

  // 同 IP 注册速率限制（独立计数与窗口，弱于配对限流：窗口 1h、上限 10）
  _rateLimitedRegister(ip) {
    const now = Date.now();
    const key = 'reg:' + (ip || 'unknown');
    const arr = (this._rate[key] || []).filter((t) => now - t < REGISTER_RATE_WINDOW_MS);
    arr.push(now);
    this._rate[key] = arr;
    return arr.length > REGISTER_RATE_MAX;
  }

  // 提交网页注册申请：校验 → 生成 pending(type='register')。
  // 密码当场 scrypt 哈希写入 pending（passHash），不落明文；审批时直接复用该哈希。
  // 返回 { token } 或 { error, reason }。
  addRegisterPending(username, password, ip) {
    this.pruneStale();
    const uname = validateUsername(this, username);
    if (!uname.ok) return { error: 'bad_username', reason: uname.reason };
    const pwd = validatePassword(password);
    if (!pwd.ok) return { error: 'bad_password', reason: pwd.reason };
    if (Object.keys(this.pending).length >= PENDING_CAP) {
      return { error: 'too_many', reason: 'too many pending requests' };
    }
    if (this._rateLimitedRegister(ip)) {
      return { error: 'rate_limited', reason: 'too many registration attempts from this ip' };
    }
    const token = crypto.randomBytes(16).toString('hex');
    this.pending[token] = {
      type: 'register',
      username,
      passHash: hashPassword(password),
      ip: ip || '',
      state: 'pending',
      createdAt: Date.now(),
    };
    this.save();
    return { token };
  }

  // 仅列出待审批的注册申请（不含配对），供控制端口 /register/requests 与管理页使用
  getRegisterPendingList() {
    return Object.keys(this.pending)
      .filter((token) => this.pending[token] && this.pending[token].type === 'register')
      .map((token) => ({
        token,
        username: this.pending[token].username,
        state: this.pending[token].state,
        ip: this.pending[token].ip || '',
        createdAt: this.pending[token].createdAt,
      }))
      .sort((a, b) => b.createdAt - a.createdAt);
  }

  // 注册者轮询审批结果：pending 前返回 pending；审批后返回 approved+username（不含密码）。
  // 密码已在提交时哈希并只以 passHash 形式存在，客户端本就持有自己提交的密码。
  registerStatus(token) {
    const p = this.pending[token];
    if (!p || p.type !== 'register') return { state: 'notfound' };
    if (p.state === 'approved') {
      return { state: 'approved', username: p.username };
    }
    if (p.state === 'denied') {
      return { state: 'denied' };
    }
    return { state: 'pending' };
  }

  pruneStale() {
    const now = Date.now();
    let changed = false;
    Object.keys(this.pending).forEach((token) => {
      const p = this.pending[token];
      // 已批准/已拒绝的条目只需保留一小段时间，供请求方轮询 /_pair/status 取凭据；
      // 之后必须清掉，否则会一直占着 PENDING_CAP（20 条），新配对请求会被 429 拒绝。
      const handled = p.state && p.state !== 'pending';
      const ttl = handled ? HANDLED_KEEP_MS : STALE_MS;
      if (now - p.createdAt > ttl) {
        delete this.pending[token];
        changed = true;
      }
    });
    if (changed) this.save();
  }

  // 创建配对待审。返回 { token } 或 { error:'too_many'|'rate_limited' }
  addPending(deviceId, name) {
    this.pruneStale();
    if (Object.keys(this.pending).length >= PENDING_CAP) {
      return { error: 'too_many' };
    }
    if (this._rateLimited(deviceId)) {
      return { error: 'rate_limited' };
    }
    const token = crypto.randomBytes(16).toString('hex');
    const username = 'peer-' + crypto.randomBytes(4).toString('hex');
    const pass = crypto.randomBytes(12).toString('base64').replace(/[^A-Za-z0-9]/g, '');
    this.pending[token] = {
      type: 'pair',
      deviceId: deviceId || '',
      name: name || '',
      username,
      pass,
      state: 'pending',
      createdAt: Date.now(),
    };
    this.save();
    return { token };
  }

  // 仅列出「待审批」的配对请求：
  // - 过滤 state !== 'pending'：批准/拒绝后的条目只是留给请求方轮询取凭据，
  //   不应再出现在待审列表（否则 App 点「允许」后该行不会消失，看起来像没反应）；
  // - 过滤 type === 'register'：注册申请走 /register/requests，避免混进配对列表。
  getPendingList() {
    this.pruneStale();
    return Object.keys(this.pending)
      .filter((token) => this.pending[token]
        && this.pending[token].state === 'pending'
        && this.pending[token].type !== 'register')
      .map((token) => ({
        token,
        type: this.pending[token].type || 'pair',
        deviceId: this.pending[token].deviceId,
        name: this.pending[token].name,
        username: this.pending[token].username,
        state: this.pending[token].state,
        createdAt: this.pending[token].createdAt,
      }))
      .sort((a, b) => b.createdAt - a.createdAt);
  }

  // 审批：pending -> active peer 账号，pending 标记 approved 并保留凭据供请求方轮询获取。
  // 兼容两类 pending：
  // - type='pair'：密码是自动生成的明文（p.pass），此处哈希后落库并把明文留给轮询方。
  // - type='register'：密码在提交时已哈希（p.passHash），直接复用，不再持有明文。
  approve(token) {
    const p = this.pending[token];
    if (!p) return null;
    const isRegister = p.type === 'register';
    // 注册账号在待审期间用户名可能被本机 admin 通过 /admin/set 占用，审批时兜底拒绝
    if (isRegister && this.userExists(p.username)) {
      return { error: 'username_taken', reason: 'username already exists' };
    }
    const passHash = isRegister ? p.passHash : hashPassword(p.pass);
    this.users[p.username] = {
      passHash,
      role: 'peer',
      deviceId: p.deviceId || '',
      active: true,
      createdBy: isRegister ? 'register' : 'pair',
      createdAt: Date.now(),
    };
    p.state = 'approved';
    // 注册类不保留明文密码；配对类需保留 p.pass 供配对方轮询 /_pair/status 取凭据
    if (isRegister) delete p.passHash;
    this.save();
    if (isRegister) return { user: p.username };
    return { user: p.username, pass: p.pass };
  }

  // 拒绝：配对类直接删除；注册类标记 denied，让注册者轮询能拿到明确结果（随后陈旧清理）。
  deny(token) {
    const p = this.pending[token];
    if (!p) return false;
    if (p.type === 'register') {
      p.state = 'denied';
      delete p.passHash;
    } else {
      delete this.pending[token];
    }
    this.save();
    return true;
  }

  // 请求方轮询：pending 前返回 pending；审批后返回 approved+凭据；token 不存在返回 notfound
  status(token) {
    const p = this.pending[token];
    if (!p) return { state: 'notfound' };
    if (p.state === 'approved') {
      return { state: 'approved', user: p.username, pass: p.pass };
    }
    return { state: 'pending' };
  }
}

module.exports = {
  UserStore,
  PENDING_CAP,
  RATE_MAX,
  RATE_WINDOW_MS,
  REGISTER_RATE_MAX,
  REGISTER_RATE_WINDOW_MS,
  MIN_PASSWORD_LEN,
  USERNAME_RE,
  validateUsername,
  validatePassword,
};
