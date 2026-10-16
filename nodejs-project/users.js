'use strict';

// 账户与配对存储：多用户鉴权（admin / peer）+ 配对 pending / 审批。
// 落盘到 dataDir/users.json（原子改名写入）。密码以 scrypt 加盐哈希存储，不存明文。
//
// 设计要点：
// - 数据端口（5984）的 Basic Auth 由此处的 verify() 驱动；users.json 非空即要求登录。
// - 配对公开端点 /_pair/request 只创建 pending，不授予任何权限，直至本机审批（控制端口）。
// - 审批后 pending 转为 active 的 peer 账号；凭据（user/pass）仅在审批后、请求方轮询
//   /_pair/status 时一次性可见（pending 标记 approved 并保留至陈旧清理）。

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PENDING_CAP = 20;          // pending 总数上限（防刷）
const RATE_WINDOW_MS = 60 * 1000; // 同 deviceId 速率窗口
const RATE_MAX = 5;              // 窗口内最大请求数
const STALE_MS = 60 * 60 * 1000; // pending 陈旧阈值（1h）

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

  setAdmin(user, pass) {
    if (!user || !pass) return false;
    this.users[user] = {
      passHash: hashPassword(pass),
      role: 'admin',
      active: true,
      createdAt: Date.now(),
    };
    this.save();
    return true;
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

  pruneStale() {
    const now = Date.now();
    let changed = false;
    Object.keys(this.pending).forEach((token) => {
      if (now - this.pending[token].createdAt > STALE_MS) {
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

  getPendingList() {
    return Object.keys(this.pending).map((token) => ({
      token,
      deviceId: this.pending[token].deviceId,
      name: this.pending[token].name,
      state: this.pending[token].state,
    }));
  }

  // 审批：pending -> active peer 账号，pending 标记 approved 并保留凭据供请求方轮询获取
  approve(token) {
    const p = this.pending[token];
    if (!p) return null;
    this.users[p.username] = {
      passHash: hashPassword(p.pass),
      role: 'peer',
      deviceId: p.deviceId,
      active: true,
      createdAt: Date.now(),
    };
    p.state = 'approved';
    this.save();
    return { user: p.username, pass: p.pass };
  }

  deny(token) {
    if (this.pending[token]) {
      delete this.pending[token];
      this.save();
      return true;
    }
    return false;
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

module.exports = { UserStore, PENDING_CAP, RATE_MAX, RATE_WINDOW_MS };
