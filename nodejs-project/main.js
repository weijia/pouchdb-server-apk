'use strict';

// PouchDB Server（nodejs-mobile 内嵌版，单入口）
//
// 本文件 = pouchdb-https-server 的 server.js 新功能（HTTPS / 网页管理页 /_admin /
// 网页注册 /_register / 已配对对端 peers / 同步状态聚合 / 设备身份 / 数据目录探测）
// + 安卓 App 特有适配（boot 诊断、心跳文件、--data-dir/--log-file、split pouchdb、
//   LevelStore 统计、/start /stop /flush /shutdown 控制端点）。
//
// 监听：
//   1) 数据端口（默认 5984，0.0.0.0，HTTP）— CouchDB 兼容 API，供局域网客户端同步，
//      同时承载 _utils(Fauxton) 与 _admin(管理页)。
//   2) HTTPS 端口（默认 8223，可选）— 证书缺失时自动跳过，不阻塞启动。
//   3) 控制端口（默认 5985，127.0.0.1）— 启停数据端口、管理账号、配对审批、同步指令。
//
// 与 https-server 的差异（均为安卓适配）：
//   - 保留 /start /stop /flush /shutdown 控制端点与 state.running（https-server 已移除，
//     但安卓侧 selftest / 旧契约依赖）；
//   - 保留心跳写文件（App 侧 Watchdog 依据心跳过期判断 Node 存活）；
//   - 保留 --data-dir / --log-file 参数与 boot 诊断日志；
//   - 使用 split pouchdb（pouchdb-core + 插件 + adapter-leveldb），不用 pouchdb 全家桶；
//   - peer 出站请求用内置 http/https（https-server 用全局 fetch，nodejs-mobile v18 不可靠）；
//   - 不自动创建 localOnly admin（默认 open-LAN，用户经管理页/App 自建账号）；
//   - 默认端口沿用 5984/5985（https-server 为 8224/8225）；
//   - Node 侧 mDNS 默认关闭（安卓已有 Java 侧 MdnsAdvertiser，避免重复宣告）。

// ==================== 启动诊断（必须最先执行）====================
// 只用内置模块，不依赖任何第三方库。Node 的 stdout/stderr 在安卓上不可见（会进
// logcat 而不在 node.log），所以启动链路每一步都显式写文件，读 node.log 即可定位：
//   - 连 [boot] 第一行都没有 → node::Start 根本没把脚本跑起来；
//   - 只有前几行 [boot]      → 卡在后面某处，最后一条 [boot] 就是卡住的位置；
//   - 出现 fatal/异常堆栈     → 这就是根因。
function bootLogFile(argv) {
  const logArg = (argv || []).find(function (a) { return a.indexOf('--log-file=') === 0; });
  return logArg ? logArg.slice('--log-file='.length) : '';
}

function bootLog(msg) {
  try {
    const logFile = bootLogFile(process.argv.slice(2));
    if (!logFile) return;
    const fsMod = require('fs');
    fsMod.mkdirSync(require('path').dirname(logFile), { recursive: true });
    fsMod.appendFileSync(logFile, new Date().toISOString() + ' [boot] ' + msg + '\n');
  } catch (e) { /* 诊断失败绝不能影响启动 */ }
}

bootLog('main.js 已加载 argv=' + JSON.stringify(process.argv));
bootLog('运行时 node=' + process.version + ' platform=' + process.platform
  + ' arch=' + process.arch + ' napi=' + (process.versions.napi || '-')
  + ' cwd=' + process.cwd() + ' NODE_PATH=' + (process.env.NODE_PATH || '-'));

// 原生模块探针：leveldown 是本服务唯一的原生依赖，也最容易在设备上加载失败
// （架构/ABI/预编译产物不匹配）。这里只记录诊断信息、不改变失败语义。
(function probeNativeLeveldown() {
  try {
    const fsMod = require('fs');
    const pathMod = require('path');
    const modDir = pathMod.dirname(require.resolve('leveldown/package.json'));
    const prebuildDir = pathMod.join(modDir, 'prebuilds', process.platform + '-' + process.arch);
    bootLog('leveldown 目录=' + modDir);
    try {
      bootLog('leveldown 预编译产物[' + prebuildDir + ']=' + JSON.stringify(fsMod.readdirSync(prebuildDir)));
    } catch (e) {
      bootLog('leveldown 预编译目录不可读(' + prebuildDir + '): ' + e.message);
    }
    require('leveldown');
    bootLog('leveldown 原生绑定加载成功');
  } catch (e) {
    bootLog('leveldown 原生绑定加载失败: ' + (e && e.stack ? e.stack : e));
  }
})();

const ROOT_DIR = __dirname;

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const os = require('os');
const crypto = require('crypto');
const express = require('express');
const cors = require('cors');
const ExpressPouchDB = require('express-pouchdb');

bootLog('已加载 express / cors / express-pouchdb');

// split pouchdb：与 https-server 用 pouchdb 全家桶不同，这里沿用安卓侧验证过的
// 组合（pouchdb-core + 插件 + adapter-leveldb），不改变原生 leveldb 加载路径。
const PouchDB = require('pouchdb-core')
  .plugin(require('pouchdb-mapreduce'))
  .plugin(require('pouchdb-replication'))
  .plugin(require('pouchdb-adapter-http'))
  .plugin(require('pouchdb-find'));

bootLog('已加载 pouchdb-core / mapreduce / replication / adapter-http / find');

// leveldb 适配器单独 try/catch：要求 leveldown 原生绑定可用，是设备上最容易失败的一步。
try {
  PouchDB.plugin(require('pouchdb-adapter-leveldb'));
  bootLog('已加载 pouchdb-adapter-leveldb');
} catch (e) {
  bootLog('fatal: pouchdb-adapter-leveldb 加载失败: ' + (e && e.stack ? e.stack : e));
  throw e;
}

const { LevelStore } = require('./storage');
const { UserStore } = require('./users');
const { Discovery } = require('./discovery');
bootLog('已加载 ./storage ./users ./discovery');

const DEFAULT_PORT = 5984;          // 局域网数据端口（HTTP），与 App 侧一致
const DEFAULT_HTTPS_PORT = 8223;    // HTTPS（可选）
const DEFAULT_CONTROL_PORT = 5985;  // 控制端口（loopback），与 App 侧一致
const COUCHDB_VERSION = '3.3.3';    // 对外伪装成 CouchDB 3.3.3

// ==================== 全局异常兜底 ====================
// 任何未捕获异常/未处理的 Promise 拒绝都会让 node::Start 直接返回，表现为「服务起来
// 后直接退出」。这里全部接住并写日志，让进程继续存活。连续异常过多才主动退出，
// 交由 Android 侧自愈重启，避免 CPU 被打满。
let guardErrorCount = 0;
const GUARD_MAX_ERRORS = 1000;
process.on('uncaughtException', function (err) {
  guardErrorCount += 1;
  console.error('[guard] uncaughtException(' + guardErrorCount + '): '
    + (err && err.stack ? err.stack : err));
  if (guardErrorCount > GUARD_MAX_ERRORS) {
    console.error('[guard] 未捕获异常过多，主动退出以便由系统自愈重启');
    process.exit(70);
  }
});
if (!process.__pouchUnhandledRejectionHooked) {
  process.__pouchUnhandledRejectionHooked = true;
  process.on('unhandledRejection', function (reason) {
    console.error('[guard] unhandledRejection: '
      + (reason && reason.stack ? reason.stack : reason));
  });
}

// 把 console.* 重定向到日志文件（安卓 stdout 不可见，只有写文件界面才读得到）。
function setupLogger(logFile) {
  try {
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
    const stream = fs.createWriteStream(logFile, { flags: 'a' });
    const orig = { log: console.log, warn: console.warn, error: console.error };
    const write = (level, args) => {
      const line = args
        .map((a) => (typeof a === 'string' ? a : JSON.stringify(a)))
        .join(' ');
      stream.write(new Date().toISOString() + ' [' + level + '] ' + line + '\n');
    };
    ['log', 'warn', 'error'].forEach((level) => {
      console[level] = function () {
        write(level, Array.prototype.slice.call(arguments));
        orig[level].apply(console, arguments);
      };
    });
  } catch (e) { /* 日志不可用不影响服务 */ }
}

// ==================== 数据目录探测 ====================
function isLevelDbDir(dir) {
  try {
    const files = fs.readdirSync(dir);
    return files.indexOf('CURRENT') >= 0
      && files.indexOf('LOG') >= 0
      && files.some((f) => f.indexOf('MANIFEST-') === 0);
  } catch (e) {
    return false;
  }
}

function looksLikeDataDir(dir) {
  try {
    if (!fs.statSync(dir).isDirectory()) return false;
    const entries = fs.readdirSync(dir);
    for (const name of entries) {
      if (name.indexOf('.') === 0) continue;
      if (isLevelDbDir(path.join(dir, name))) return true;
    }
  } catch (e) { /* 不可读即视为不匹配 */ }
  return false;
}

function resolveDataDir(explicit) {
  if (explicit) return { dir: path.resolve(explicit), source: 'explicit' };
  const cwd = process.cwd();
  if (looksLikeDataDir(cwd)) return { dir: cwd, source: 'cwd' };

  const candidates = [];
  try {
    fs.readdirSync(cwd).forEach((name) => {
      if (name.indexOf('.') === 0) return;
      const p = path.join(cwd, name);
      if (fs.statSync(p).isDirectory()) candidates.push(p);
    });
  } catch (e) { /* ignore */ }
  ['pouchdb-data-http', 'pouchdb-data'].forEach((name) => {
    candidates.push(path.join(cwd, name));
    candidates.push(path.join(path.dirname(cwd), name));
  });

  for (const c of candidates) {
    if (looksLikeDataDir(c)) return { dir: c, source: 'probe' };
  }
  return { dir: cwd, source: 'default' };
}

// ==================== 设备身份 ====================
// 返回 { deviceId, name }；App 侧已通过 --device-id 传稳定身份，优先用它。
function loadDevice(dataDir, optsDeviceId) {
  const file = path.join(dataDir, 'device.json');
  let saved = null;
  try {
    const d = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (d && d.deviceId) saved = d;
  } catch (e) { /* 不存在或损坏则重建 */ }
  const deviceId = optsDeviceId || (saved && saved.deviceId) || crypto.randomUUID();
  const name = (saved && saved.name)
    || (os.hostname() || 'pouchdb-server').replace(/[^A-Za-z0-9._-]/g, '-');
  const device = { deviceId, name };
  try {
    fs.writeFileSync(file, JSON.stringify(device, null, 2));
  } catch (e) { /* 写不进也不影响运行，只是每次启动换个 id */ }
  return device;
}

// ==================== 工具 ====================
function jsonBody(req, res, next) {
  if (req.method !== 'POST' && req.method !== 'PUT') return next();
  let data = '';
  req.on('data', (c) => { data += c; });
  req.on('end', () => {
    try { req.body = data ? JSON.parse(data) : {}; } catch (e) { req.body = {}; }
    next();
  });
}

// 是否来自回环地址（仅本机）
function isLocalIp(req) {
  const ip = (req && req.socket && req.socket.remoteAddress) || '';
  return ip === '127.0.0.1' || ip === '::1'
    || ip === '::ffff:127.0.0.1' || ip === '::ffff:0:127.0.0.1';
}

// 多用户 Basic Auth：数据端口统一鉴权入口。users.json 非空即要求登录，
// 任何 active 的 admin / peer 账号均可放行（库级 ACL 留待后续）。
// 标记 localOnly 的账号（如预设 admin）仅允许从回环地址登录，远程直接拒。
function multiUserBasicAuth(userStore) {
  return function (req, res, next) {
    const header = req.headers.authorization || '';
    let valid = false;
    let username = '';
    if (header.startsWith('Basic ')) {
      const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      if (idx >= 0) {
        username = decoded.slice(0, idx);
        const pass = decoded.slice(idx + 1);
        if (userStore.verify(username, pass)) {
          if (userStore.isLocalOnly(username) && !isLocalIp(req)) {
            valid = false; // 仅本地账号不能从远程登录
          } else {
            valid = true;
          }
        }
      }
    }
    if (!valid) {
      res.set('WWW-Authenticate', 'Basic realm="pouchdb-server"');
      res.status(401).json({ error: 'unauthorized', reason: 'Authentication required.' });
      return;
    }
    // 已由本层放行后，剥掉 Authorization 再交给 express-pouchdb：
    // 它自带一套基于 _users 库 / 配置 admins 的鉴权，不认识 users.json 里的账户。
    delete req.headers.authorization;
    next();
  };
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.removeListener('error', reject);
      resolve(server.address().port);
    });
  });
}

// 关闭所有已打开的库，释放 leveldb 对库目录持有的 LOCK 文件（优雅退出用）。
async function closeAllDbs() {
  let names = [];
  try {
    names = (typeof PouchDB.allDbs === 'function') ? await PouchDB.allDbs() : [];
  } catch (e) { /* 插件未就绪时按空列表处理 */ }
  const all = names.concat(['pouch__all_dbs__']);
  await Promise.all(all.map((n) => PouchDB(n).close().catch(() => {})));
  return all;
}

// ==================== 数据端口 ====================
function createApp(opts, state) {
  const app = express();

  // 反射 Origin 的 CORS（管理页 /_admin 与局域网客户端跨端口调用都需要）。
  const corsOptionsDelegate = (req, callback) => {
    callback(null, {
      origin: req.header('Origin'),
      credentials: true,
      methods: 'GET,HEAD,PUT,PATCH,POST,DELETE,OPTIONS',
    });
  };
  app.use(cors(corsOptionsDelegate));

  // ---------------- 自有状态与配对端点（先于 express-pouchdb 注册） ----------------
  app.get('/_status', async (req, res) => {
    res.json({
      status: 'running',
      couchdb: COUCHDB_VERSION,
      instance: state.instance,
      deviceId: state.deviceId,
      port: state.port,
      httpsPort: state.httpsPort || null,
      controlPort: state.controlPort,
      scheme: 'http',
      pairingEnabled: !!state.pairingEnabled,
      registerEnabled: !!state.registerEnabled,
      authRequired: state.userStore.hasAnyUser(),
      uptimeMs: Date.now() - state.startedAt,
      node: process.version,
      dataDir: state.dataDir,
      dataDirSource: state.dataDirSource,
      mdns: state.discovery && state.discovery.available
        ? { available: true, registered: state.discovery.registered }
        : { available: false, error: state.discovery ? state.discovery.error : '' },
      databases: state.store.stats(),
    });
  });

  // 公开配对端点：免数据端口鉴权，但受「允许配对」开关与限流约束。
  app.post('/_pair/request', jsonBody, (req, res) => {
    if (!state.pairingEnabled) {
      res.status(403).json({ error: 'forbidden', reason: 'pairing disabled' });
      return;
    }
    const deviceId = (req.body && req.body.device_id) || '';
    const name = (req.body && req.body.name) || '';
    const r = state.userStore.addPending(deviceId, name);
    if (r.error) {
      res.status(429).json({ error: r.error, reason: 'too many pending requests' });
      return;
    }
    console.log('[pair] 收到配对请求 device=' + deviceId + ' name=' + name + ' token=' + r.token);
    res.json({ token: r.token });
  });

  app.get('/_pair/status', (req, res) => {
    const token = req.query.token || '';
    const s = state.userStore.status(token);
    if (s.state === 'notfound') {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    res.json(s);
  });

  // ---------------- 网页注册（免鉴权，默认开启，需本机 admin 审批） ----------------
  app.post('/_register', jsonBody, (req, res) => {
    if (!state.registerEnabled) {
      res.status(403).json({ error: 'forbidden', reason: 'registration disabled' });
      return;
    }
    const b = req.body || {};
    const ip = (req.socket && req.socket.remoteAddress) || '';
    const r = state.userStore.addRegisterPending(b.username, b.password, ip);
    if (r.error) {
      const code = (r.error === 'rate_limited' || r.error === 'too_many') ? 429 : 400;
      res.status(code).json({ error: r.error, reason: r.reason });
      return;
    }
    console.log('[register] 收到注册申请 user=' + b.username + ' ip=' + ip + ' token=' + r.token);
    res.json({ token: r.token, pending: true });
  });

  app.get('/_register/status', (req, res) => {
    const token = req.query.token || '';
    const s = state.userStore.registerStatus(token);
    if (s.state === 'notfound') {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    res.json(s);
  });

  // 远程管理代理：经数据端口 /_admin/api 转发控制端口（需 admin 登录）。
  // 必须注册在 basicAuthGate 之前——basicAuthGate 会剥掉 Authorization 头，
  // 若代理在其后注册就拿不到凭据（表现为带正确账号也 401）。
  if (opts.adminRemote) {
    app.use('/_admin/api', (req, res, next) => {
      if (!state.userStore.hasAnyUser()) {
        res.status(403).json({ error: 'forbidden', reason: 'admin api disabled (no account configured)' });
        return;
      }
      const header = req.headers.authorization || '';
      if (!header.startsWith('Basic ')) {
        res.status(401).json({ error: 'unauthorized', reason: 'Authentication required.' });
        return;
      }
      const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      const user = idx >= 0 ? decoded.slice(0, idx) : '';
      const pass = idx >= 0 ? decoded.slice(idx + 1) : '';
      if (state.userStore.roleOf(user) !== 'admin' || !state.userStore.verify(user, pass)) {
        res.status(403).json({ error: 'forbidden', reason: 'admin role required' });
        return;
      }
      req.__ctlProxy = true;
      next();
    }, (req, res, next) => state.controlApp(req, res, next));
  }

  // 多用户鉴权：users.json 非空即要求登录。每次请求动态判断。
  const basicAuthGate = multiUserBasicAuth(state.userStore);
  app.use((req, res, next) => {
    if (!state.userStore.hasAnyUser()) return next();
    return basicAuthGate(req, res, next);
  });

  // ---------------- 管理页 ----------------
  app.use('/_admin', express.static(path.join(ROOT_DIR, 'public')));

  // 如果没有自有路由命中，使用 express-pouchdb（含 /_utils Fauxton）。
  app.use('/', ExpressPouchDB(PouchDB, {
    mode: 'fullCouchDB',
    configPath: path.join(state.dataDir, 'config.json'),
    logPath: path.join(state.dataDir, 'log.txt'),
    overrideMode: {
      include: ['routes/404'],  // 确保404处理被包含
    },
  }));

  // 全局错误兜底：必须放在所有路由之后（Express 的 next(err) 只向后扫描错误中间件，
  // 放在前面是死代码，抓不到 express-pouchdb 抛出的错误）。
  app.use((err, req, res, next) => {
    console.error('Error caught by global handler:', err);
    if (err.status === 404 && err.reason === 'missing') {
      return res.status(404).json({
        error: 'not_found',
        reason: err.message || 'Resource not found',
        details: err,
      });
    }
    res.status(err.status || 500).json({
      error: err.error || 'internal_server_error',
      reason: err.reason || err.message || 'An unexpected error occurred',
      details: process.env.NODE_ENV === 'development' ? err : undefined,
    });
  });

  return app;
}

// ==================== 控制端口（loopback） ====================
function createControlApp(opts, state) {
  const app = express();

  app.use((req, res, next) => {
    const ip = (req.socket && req.socket.remoteAddress) || '';
    const local = ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
    if (!local && req.__ctlProxy !== true) {
      res.status(403).json({ error: 'forbidden', reason: 'control port is loopback only' });
      return;
    }
    next();
  });

  // 本机浏览器打开管理页时，页面需要跨端口调用控制端口。来源 IP 仍是 loopback，
  // 这里只放宽 CORS 到本机页面。
  app.use((req, res, next) => {
    const origin = req.header('Origin') || '';
    if (/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(origin)) {
      res.header('Access-Control-Allow-Origin', origin);
      res.header('Access-Control-Allow-Credentials', 'true');
      res.header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
      res.header('Access-Control-Allow-Headers', 'Content-Type,Authorization');
      if (req.method === 'OPTIONS') return res.sendStatus(204);
    }
    next();
  });

  app.get('/status', async (req, res) => {
    res.json({
      running: state.running,
      port: state.port,
      httpsPort: state.httpsPort || null,
      controlPort: state.controlPort,
      instance: state.instance,
      deviceId: state.deviceId,
      pairingEnabled: !!state.pairingEnabled,
      registerEnabled: !!state.registerEnabled,
      authRequired: state.userStore.hasAnyUser(),
      uptimeMs: Date.now() - state.startedAt,
      dataDir: state.dataDir,
      dataDirSource: state.dataDirSource,
      databases: state.store.stats(),
    });
  });

  // ---------------- 数据端口启停（保留：安卓 selftest / 旧契约依赖） ----------------
  app.post('/start', function (req, res) {
    startListening(state, function (err) {
      if (err) return res.status(500).json({ error: 'server_error', reason: err.message });
      res.json({ ok: true, running: state.running, port: state.port });
    });
  });

  app.post('/stop', function (req, res) {
    stopListening(state, function (err) {
      if (err) return res.status(500).json({ error: 'server_error', reason: err.message });
      res.json({ ok: true, running: state.running });
    });
  });

  // leveldb 已自动持久化，/flush 无需做任何落盘动作，仅保留接口兼容。
  app.post('/flush', async function (req, res) {
    try {
      res.json({ ok: true, note: 'leveldb 自动持久化，无需手动 flush' });
    } catch (e) {
      res.status(500).json({ error: 'server_error', reason: e.message });
    }
  });

  // 关闭所有库并退出进程（用于 CI / 本机测试；安卓侧不应调用）。
  app.post('/shutdown', async function (req, res) {
    try {
      await closeAllDbs();
      res.json({ ok: true, shuttingDown: true });
      setTimeout(function () { process.exit(0); }, 50);
    } catch (e) {
      res.status(500).json({ error: 'server_error', reason: e.message });
    }
  });

  // ---------------- 本机管理账号 ----------------
  app.post('/admin/set', jsonBody, (req, res) => {
    const user = (req.body && req.body.user) || '';
    const pass = (req.body && req.body.pass) || '';
    if (!user || !pass) {
      res.status(400).json({ error: 'bad_request', reason: 'user and pass required' });
      return;
    }
    // 保留该账号原有的 localOnly 约束（如预设 admin 只能本地访问），重设密码不丢失
    const keepLocal = state.userStore.isLocalOnly(user);
    state.userStore.setAdmin(user, pass, keepLocal);
    res.json({ ok: true });
  });

  app.get('/admin/status', (req, res) => {
    res.json(state.userStore.getAdminStatus());
  });

  // ---------------- 配对开关 ----------------
  app.post('/pairing/set', jsonBody, (req, res) => {
    state.pairingEnabled = !!(req.body && req.body.enabled);
    writeStateFile(state);
    console.log('[pair] 配对开关: ' + (state.pairingEnabled ? '开启' : '关闭'));
    res.json({ ok: true, pairingEnabled: state.pairingEnabled });
  });

  app.get('/pairing/status', (req, res) => {
    res.json({ pairingEnabled: !!state.pairingEnabled });
  });

  // ---------------- 配对待审与审批 ----------------
  app.get('/pair/requests', (req, res) => {
    res.json({ requests: state.userStore.getPendingList() });
  });

  app.post('/pair/approve', jsonBody, (req, res) => {
    const token = (req.body && req.body.token) || '';
    const r = state.userStore.approve(token);
    if (!r) {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    if (r.error) {
      res.status(409).json({ error: r.error, reason: r.reason });
      return;
    }
    console.log('[pair] 已批准配对 token=' + token + ' user=' + r.user);
    res.json({ ok: true, user: r.user, pass: r.pass });
  });

  app.post('/pair/deny', jsonBody, (req, res) => {
    const token = (req.body && req.body.token) || '';
    res.json({ ok: state.userStore.deny(token) });
  });

  // ---------------- 网页注册：开关与审批 ----------------
  app.post('/register/set', jsonBody, (req, res) => {
    state.registerEnabled = !!(req.body && req.body.enabled);
    writeStateFile(state);
    console.log('[register] 注册开关: ' + (state.registerEnabled ? '开启' : '关闭'));
    res.json({ ok: true, registerEnabled: state.registerEnabled });
  });

  app.get('/register/status', (req, res) => {
    res.json({ registerEnabled: !!state.registerEnabled });
  });

  app.get('/register/requests', (req, res) => {
    res.json({ enabled: !!state.registerEnabled, requests: state.userStore.getRegisterPendingList() });
  });

  app.post('/register/approve', jsonBody, (req, res) => {
    const token = (req.body && req.body.token) || '';
    const r = state.userStore.approve(token);
    if (!r) {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    if (r.error) {
      res.status(409).json({ error: r.error, reason: r.reason });
      return;
    }
    console.log('[register] 已批准注册 user=' + r.user);
    res.json({ ok: true, user: r.user });
  });

  app.post('/register/deny', jsonBody, (req, res) => {
    const token = (req.body && req.body.token) || '';
    res.json({ ok: state.userStore.deny(token) });
  });

  // ---------------- 局域网设备（发现结果 + 已配对标记） ----------------
  app.get('/peers', (req, res) => {
    const discovered = state.discovery ? state.discovery.list() : [];
    const paired = state.peerStore ? state.peerStore.peers : {};
    // 发现靠安卓：把安卓上报的 Java mDNS 结果合并进来（按 deviceId/host:port 去重）。
    (state.androidPeers || []).forEach((p) => {
      if (p && !discovered.some((d) => d.deviceId === p.deviceId
        || (d.host + ':' + d.port) === (p.host + ':' + p.port))) {
        discovered.push(Object.assign({ source: 'android' }, p));
      }
    });
    res.json({
      available: !!(state.discovery && state.discovery.available) || (state.androidPeers || []).length > 0,
      error: state.discovery ? state.discovery.error : '',
      androidReportedAt: state.androidPeersAt || 0,
      peers: discovered.map((p) => Object.assign({}, p, {
        paired: !!paired[p.deviceId] || !!paired[p.host + ':' + p.port],
      })),
      // 已配对但当前不在网的对端也列出来，方便直接同步
      paired: Object.keys(paired).map((key) => ({
        key,
        deviceId: paired[key].deviceId || '',
        instance: paired[key].instance || key,
        host: paired[key].host,
        port: paired[key].port,
        scheme: paired[key].scheme || 'http',
        paired: true,
        online: discovered.some((p) => p.deviceId === key || (p.host + ':' + p.port) === key),
      })),
    });
  });

  // 安卓上报 Java mDNS 发现的对端列表（发现靠安卓，Node 只缓存展示）。
  app.post('/peers/report', jsonBody, (req, res) => {
    const list = (req.body && Array.isArray(req.body.devices)) ? req.body.devices : [];
    state.androidPeers = list
      .filter((d) => d && d.host && d.port)
      .map((d) => ({
        deviceId: d.deviceId || '',
        instance: d.instance || (d.host + ':' + d.port),
        host: d.host,
        port: Number(d.port),
        scheme: d.scheme || 'http',
        source: 'android',
      }));
    state.androidPeersAt = Date.now();
    res.json({ ok: true, count: state.androidPeers.length });
  });

  // 安卓上报已配对结果（doPair 成功后调用），写入 peers.json，供按 peer 同步与 /sync/status。
  app.post('/peer/register', jsonBody, (req, res) => {
    const b = req.body || {};
    const host = b.host;
    const port = Number(b.port);
    if (!host || !port) {
      res.status(400).json({ error: 'bad_request', reason: 'host, port required' });
      return;
    }
    const key = peerKeyOf(b.deviceId || '', host, port);
    state.peerStore.peers[key] = {
      deviceId: b.deviceId || '',
      instance: b.instance || key,
      host, port,
      scheme: b.scheme || 'http',
      user: b.user || '',
      pass: b.pass || '',
      pairedAt: Date.now(),
    };
    savePeers(state.peerStore);
    console.log('[peer] 安卓已上报配对: ' + key + ' user=' + (b.user || '-'));
    res.json({ ok: true, key });
  });

  // 向对端发起配对并在服务端等待审批：避免浏览器直连对端的跨域问题。
  app.post('/peer/pair', jsonBody, async (req, res) => {
    const b = req.body || {};
    const host = b.host;
    const port = Number(b.port);
    if (!host || !port) {
      res.status(400).json({ error: 'bad_request', reason: 'host, port required' });
      return;
    }
    const waitMs = Number(b.waitMs || 90000);
    const target = { host, port, scheme: b.scheme || 'http' };
    try {
      const r = await peerRequest(target, '/_pair/request', {
        method: 'POST',
        body: { device_id: state.deviceId, name: state.instance },
      });
      const token = r.json && r.json.token;
      if (!token) {
        res.status(r.status || 502).json({
          error: 'pair_failed',
          reason: r.json ? (r.json.reason || 'no token') : r.text.slice(0, 200),
        });
        return;
      }
      console.log('[pair] 已向 ' + host + ':' + port + ' 发起配对，等待对方审批...');
      const deadline = Date.now() + waitMs;
      while (Date.now() < deadline) {
        await new Promise((r2) => setTimeout(r2, 2000));
        const s = await peerRequest(target, '/_pair/status?token=' + encodeURIComponent(token));
        const st = s.json && s.json.state;
        if (st === 'approved') {
          const key = peerKeyOf(b.deviceId || s.json.deviceId || '', host, port);
          state.peerStore.peers[key] = {
            deviceId: b.deviceId || '',
            instance: b.instance || (host + ':' + port),
            host, port, scheme: target.scheme,
            user: s.json.user, pass: s.json.pass,
            pairedAt: Date.now(),
          };
          savePeers(state.peerStore);
          console.log('[pair] 配对成功 ' + key + ' user=' + s.json.user);
          res.json({ ok: true, key, user: s.json.user });
          return;
        }
        if (st === 'denied' || st === 'notfound') {
          res.status(403).json({ error: 'pair_denied', reason: st });
          return;
        }
      }
      res.status(504).json({ error: 'pair_timeout', reason: '对方未审批' });
    } catch (e) {
      res.status(502).json({ error: 'pair_error', reason: e.message });
    }
  });

  // 用已保存凭据拉取对端数据库列表（服务端代发，规避跨域）
  app.get('/peer/dbs', async (req, res) => {
    const key = req.query.key || '';
    const peer = state.peerStore.peers[key];
    if (!peer) {
      res.status(404).json({ error: 'not_found', reason: 'unknown peer key' });
      return;
    }
    let dbs = [];
    try {
      const r = await peerRequest(peer, '/_all_dbs');
      if (r.status === 200 && Array.isArray(r.json)) dbs = r.json.filter((d) => d.charAt(0) !== '_');
    } catch (e) { /* 对端不可达 */ }
    if (!dbs.length) {
      const local = await state.databases();
      dbs = local.filter((d) => d.charAt(0) !== '_');
    }
    res.json({ key, databases: dbs });
  });

  app.post('/peer/forget', jsonBody, (req, res) => {
    const key = (req.body && req.body.key) || '';
    if (state.peerStore.peers[key]) {
      delete state.peerStore.peers[key];
      savePeers(state.peerStore);
    }
    res.json({ ok: true, key });
  });

  // ---------------- 同步 ----------------
  app.post('/replicate', jsonBody, (req, res) => {
    const b = req.body || {};
    const db = b.db;
    // 传 peer（已配对的 key）时可省略 host/port/凭据，直接用保存的配对信息
    const saved = b.peer ? state.peerStore.peers[b.peer] : null;
    const remoteHost = b.remoteHost || (saved && saved.host) || '';
    const remotePort = b.remotePort || (saved && saved.port) || '';
    if (!db || !remoteHost || !remotePort) {
      res.status(400).json({ error: 'bad_request', reason: 'db, remoteHost, remotePort required' });
      return;
    }
    const scheme = b.remoteScheme || (saved && saved.scheme) || 'http';
    const remoteUser = b.remoteUser || (saved && saved.user) || '';
    const remotePass = b.remotePass || (saved && saved.pass) || '';
    const auth = (remoteUser && remotePass)
      ? encodeURIComponent(remoteUser) + ':' + encodeURIComponent(remotePass) + '@'
      : '';
    const remote = scheme + '://' + auth + remoteHost + ':' + remotePort + '/' + encodeURIComponent(db);
    const key = db + '@' + remoteHost + ':' + remotePort;
    const peerKey = b.peer || (remoteHost + ':' + remotePort);
    if (state.replications[key]) {
      res.json({ ok: true, key, note: 'already running' });
      return;
    }
    const local = new PouchDB(db);
    // create_target: 远端（对端）还没有这个库时自动创建——手机上勾选同步时，
    // 对端通常并不存在同名库，默认 false 会直接让同步失败。
    const rep = local.sync(remote, { live: !!b.live, retry: !!b.live, create_target: true });
    const entry = {
      rep, db, remote, live: !!b.live,
      status: 'starting', lastSeq: null, error: null,
    };
    rep.on('change', (info) => {
      entry.status = 'active';
      if (info && info.last_seq) entry.lastSeq = info.last_seq;
    });
    rep.on('active', () => { entry.status = 'active'; });
    rep.on('paused', (err) => {
      entry.status = err ? 'error' : 'paused';
      entry.error = err ? String(err) : null;
      if (!err) recordSync(state, mkSyncEv(peerKey, db, 'paused', 'paused', entry.lastSeq));
    });
    rep.on('denied', (err) => {
      entry.status = 'denied';
      entry.error = String(err);
      recordSync(state, mkSyncEv(peerKey, db, 'denied', 'denied', entry.lastSeq));
    });
    rep.on('error', (err) => {
      entry.status = 'error';
      entry.error = String(err);
      recordSync(state, mkSyncEv(peerKey, db, 'error', 'error', entry.lastSeq));
    });
    state.replications[key] = entry;
    console.log('[replicate] 启动 ' + key + ' live=' + !!b.live);
    res.json({ ok: true, key });
  });

  app.get('/replications', (req, res) => {
    res.json({
      replications: Object.keys(state.replications).map((key) => {
        const e = state.replications[key];
        return {
          key, db: e.db, remote: e.remote, live: e.live,
          status: e.status, lastSeq: e.lastSeq, error: e.error,
        };
      }),
    });
  });

  app.post('/replicate/stop', jsonBody, (req, res) => {
    const key = (req.body && req.body.key) || '';
    const e = state.replications[key];
    if (!e) {
      res.status(404).json({ error: 'not_found', reason: 'unknown replication key' });
      return;
    }
    try { e.rep.cancel(); } catch (e2) { /* ignore */ }
    delete state.replications[key];
    recordSync(state, mkSyncEv(hostPortOfRemote(e.remote), e.db, 'stop', 'paused', e.lastSeq));
    console.log('[replicate] 停止 ' + key);
    res.json({ ok: true, key });
  });

  // ---------------- 同步状态聚合 ----------------
  // 每个已配对节点 × 每个库：本地/远端文档数、是否一致、关联 replication 状态、
  // 最近一次同步时间与事件。一眼判断各节点间是否同步了完整数据。
  app.get('/sync/status', async (req, res) => {
    if (state.syncStatusCache && Date.now() - state.syncStatusCache.at < 5000) {
      return res.json(state.syncStatusCache.data);
    }
    const peers = Object.keys(state.peerStore.peers || {}).map((k) =>
      Object.assign({ key: k }, state.peerStore.peers[k]));
    const discovered = state.discovery ? state.discovery.list() : [];
    const out = [];
    for (const peer of peers) {
      let dbs = [];
      try {
        const r = await peerRequest(peer, '/_all_dbs');
        if (r.status === 200 && Array.isArray(r.json)) dbs = r.json.filter((d) => d.charAt(0) !== '_');
      } catch (e) { /* 对端不可达 */ }
      if (!dbs.length) {
        const local = await state.databases();
        dbs = local.filter((d) => d.charAt(0) !== '_');
      }
      const databases = [];
      for (const db of dbs) {
        let localDocs = null;
        let remoteDocs = null;
        let remoteStatus = 'noreq';
        let remoteText = '';
        try {
          const info = await new PouchDB(db).info();
          localDocs = info.doc_count != null ? info.doc_count : null;
        } catch (e) { localDocs = null; }
        try {
          const r = await peerRequest(peer, '/' + encodeURIComponent(db));
          remoteStatus = r.status;
          remoteText = (r.text || '').slice(0, 160);
          remoteDocs = (r.status === 200 && r.json && r.json.doc_count != null) ? r.json.doc_count : null;
        } catch (e) { remoteStatus = 'ERR:' + String(e.message || e).slice(0, 120); }
        const rep = Object.keys(state.replications).map((k) => state.replications[k])
          .find((e) => e.db === db && e.remote.indexOf(peer.host + ':' + peer.port) >= 0) || null;
        const recent = (state.syncHistory || [])
          .filter((h) => h.peerKey === peer.key && h.db === db)
          .sort((a, b) => b.at - a.at)[0];
        const consistent = (localDocs != null && remoteDocs != null && localDocs === remoteDocs
          && (!rep || (rep.status !== 'error' && rep.status !== 'denied')));
        databases.push({
          db,
          localDocs,
          remoteDocs,
          consistent,
          status: rep ? rep.status
            : (String(remoteStatus).startsWith('ERR') ? 'unreachable'
              : (localDocs === null ? 'local-missing' : 'idle')),
          remoteStatus,
          remoteText,
          lastSeq: rep ? rep.lastSeq : (recent ? recent.lastSeq : null),
          lastSyncAt: recent ? recent.at : null,
          lastEvent: recent ? recent.event : null,
        });
      }
      out.push({
        key: peer.key,
        instance: peer.instance || peer.key,
        host: peer.host,
        port: peer.port,
        scheme: peer.scheme || 'http',
        online: discovered.some((p) => p.deviceId === peer.deviceId
          || (p.host + ':' + p.port) === peer.key),
        databases,
      });
    }
    const data = { peers: out, generatedAt: Date.now() };
    state.syncStatusCache = { at: Date.now(), data };
    res.json(data);
  });

  return app;
}

// ==================== 数据端口启停 ====================
function startListening(state, cb) {
  if (state.running) return cb(null);
  listen(state.server, state.requestedPort, '0.0.0.0')
    .then(function (port) {
      state.port = port;
      state.requestedPort = port;
      state.running = true;
      writeStateFile(state);
      console.log('[server] 监听 0.0.0.0:' + port);
      cb(null);
    })
    .catch(cb);
}

function stopListening(state, cb) {
  if (!state.running) return cb(null);
  state.server.close(function () {
    state.running = false;
    writeStateFile(state);
    console.log('[server] 已停止监听');
    cb(null);
  });
}

// ==================== 已配对对端（凭据） ====================
function loadPeers(dataDir) {
  const file = path.join(dataDir, 'peers.json');
  try {
    return { file, peers: JSON.parse(fs.readFileSync(file, 'utf8')) || {} };
  } catch (e) {
    return { file, peers: {} };
  }
}

function savePeers(store) {
  try {
    fs.writeFileSync(store.file, JSON.stringify(store.peers, null, 2));
  } catch (e) { /* ignore */ }
}

function peerKeyOf(deviceId, host, port) {
  return deviceId || (host + ':' + port);
}

// 服务端代发 HTTP 请求：避免浏览器直连对端时的跨域/鉴权头限制。
// 注意：用内置 http/https 而非全局 fetch —— nodejs-mobile v18 不保证提供全局 fetch。
function peerRequest(peer, urlPath, opts) {
  const scheme = peer.scheme || 'http';
  const host = peer.host;
  const port = peer.port;
  const method = (opts && opts.method) || 'GET';
  const headers = {};
  let body = null;
  if (peer.user && peer.pass) {
    headers.Authorization = 'Basic ' + Buffer.from(peer.user + ':' + peer.pass).toString('base64');
  }
  if (opts && opts.body) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
    if (body !== undefined) headers['Content-Length'] = Buffer.byteLength(body);
  }
  return new Promise((resolve, reject) => {
    const mod = scheme === 'https' ? https : http;
    const req = mod.request({ host, port, path: urlPath, method, headers }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { text += c; });
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(text); } catch (e) { /* 非 JSON 响应 */ }
        resolve({ status: res.statusCode, json, text });
      });
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// ==================== 同步历史 ====================
function loadSyncHistory(dataDir) {
  const file = path.join(dataDir, 'sync-history.json');
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')) || [];
  } catch (e) {
    return [];
  }
}

function hostPortOfRemote(remote) {
  const m = /^https?:\/\/[^/]*@([^/:]+:\d+)\//.exec(remote || '');
  return m ? m[1] : '';
}

function recordSync(state, ev) {
  try {
    state.syncHistory.push(ev);
    if (state.syncHistory.length > 300) state.syncHistory.shift();
    if (!state._lastHistorySave || Date.now() - state._lastHistorySave > 1000) {
      fs.writeFileSync(state.syncHistoryFile, JSON.stringify(state.syncHistory));
      state._lastHistorySave = Date.now();
    }
  } catch (e) { /* ignore */ }
}

function mkSyncEv(peerKey, db, event, status, lastSeq) {
  return { at: Date.now(), peerKey, db, event, status, lastSeq: lastSeq || null };
}

// 命令行是否显式给出了注册开关（--allow-register / --no-register）
function argvHasRegisterFlag(argv) {
  return (argv || []).some((arg) => arg === '--allow-register'
    || arg.indexOf('--allow-register=') === 0
    || arg === '--no-register'
    || arg.indexOf('--no-register=') === 0);
}

// 回读 server.json 中持久化的注册开关；文件不存在或字段缺失时返回 null
function readPersistedRegister(stateFile) {
  try {
    const data = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    if (data && typeof data.registerEnabled === 'boolean') return data.registerEnabled;
  } catch (e) { /* 无文件或损坏，按未持久化处理 */ }
  return null;
}

function writeStateFile(state) {
  try {
    fs.writeFileSync(state.stateFile, JSON.stringify({
      pid: process.pid,
      running: state.running,
      port: state.port,
      httpsPort: state.httpsPort || null,
      controlPort: state.controlPort,
      instance: state.instance,
      deviceId: state.deviceId,
      pairingEnabled: !!state.pairingEnabled,
      registerEnabled: !!state.registerEnabled,
      startedAt: state.startedAt,
      dataDir: state.dataDir,
      dataDirSource: state.dataDirSource,
      updatedAt: Date.now(),
    }));
  } catch (e) { /* ignore */ }
}

// ==================== 启动 ====================
function parseArgs(argv) {
  const map = {
    'data-dir': 'dataDir',
    port: 'port',
    'https-port': 'httpsPort',
    'control-port': 'controlPort',
    instance: 'instance',
    'auth-user': 'authUser',
    'auth-pass': 'authPass',
    'cors-origin': 'corsOrigin',
    'snapshot-ms': 'snapshotIntervalMs',
    'device-id': 'deviceId',
    'log-file': 'logFile',
    'allow-register': 'allowRegister',
  };
  const numeric = { port: 1, 'https-port': 1, 'control-port': 1, 'snapshot-ms': 1 };
  const flags = {
    https: 'https',
    'no-https': 'noHttps',
    mdns: 'mdns',
    'no-mdns': 'noMdns',
    'admin-remote': 'adminRemote',
    'probe-only': 'probeOnly',
    'no-register': 'noRegister',
  };
  const out = {};
  (argv || []).forEach((arg) => {
    if (!arg || arg.indexOf('--') !== 0) return;
    const eq = arg.indexOf('=');
    const key = eq < 0 ? arg.slice(2) : arg.slice(2, eq);
    const val = eq < 0 ? 'true' : arg.slice(eq + 1);
    if (flags[key]) {
      out[flags[key]] = val !== 'false';
      return;
    }
    if (map[key]) out[map[key]] = numeric[key] ? Number(val) : val;
  });
  // --no-register 是 --allow-register 的反面开关，统一折算成 allowRegister
  if (out.noRegister === true) out.allowRegister = false;
  return out;
}

function defaults() {
  const dataDir = process.env.POUCH_DATA_DIR || '';
  return {
    dataDir,
    port: Number(process.env.POUCH_PORT || DEFAULT_PORT),
    httpsPort: Number(process.env.POUCH_HTTPS_PORT || DEFAULT_HTTPS_PORT),
    controlPort: Number(process.env.POUCH_CONTROL_PORT || DEFAULT_CONTROL_PORT),
    instance: process.env.POUCH_INSTANCE || '',
    authUser: process.env.POUCH_AUTH_USER || '',
    authPass: process.env.POUCH_AUTH_PASS || '',
    corsOrigin: process.env.POUCH_CORS_ORIGIN || true,
    snapshotIntervalMs: Number(process.env.POUCH_SNAPSHOT_MS || 5000),
    deviceId: process.env.POUCH_DEVICE_ID || '',
    logFile: process.env.POUCH_LOG_FILE || '',
    https: process.env.POUCH_HTTPS !== 'false',
    mdns: process.env.POUCH_MDNS !== 'false',
    adminRemote: process.env.POUCH_ADMIN_REMOTE === 'true',
    // 网页注册默认开启；POUCH_ALLOW_REGISTER=false 或 --no-register 可关闭
    allowRegister: process.env.POUCH_ALLOW_REGISTER !== 'false',
    probeOnly: false,
  };
}

function findCertFiles() {
  const dirs = [ROOT_DIR, path.join(ROOT_DIR, 'app'), process.env.CERT_DIR].filter(Boolean);
  for (const d of dirs) {
    const key = path.join(d, 'privkey1.pem');
    const cert = path.join(d, 'fullchain1.pem');
    if (fs.existsSync(key) && fs.existsSync(cert)) return { key, cert };
  }
  return null;
}

async function start(opts) {
  opts = Object.assign(defaults(), parseArgs(process.argv.slice(2)), opts || {});

  const resolved = resolveDataDir(opts.dataDir);
  const dataDir = resolved.dir;
  fs.mkdirSync(dataDir, { recursive: true });

  if (opts.probeOnly) {
    console.log('[dataDir] 探测结果: ' + dataDir + ' (source=' + resolved.source + ')');
    console.log('[dataDir] 库: ' + JSON.stringify(
      fs.readdirSync(dataDir).filter((n) => isLevelDbDir(path.join(dataDir, n)))));
    return { dataDir, dataDirSource: resolved.source };
  }

  // leveldb 把库写在 cwd 下，切到数据目录保证落盘位置可控
  process.chdir(dataDir);
  setupLogger(opts.logFile || path.join(dataDir, 'server.log'));
  bootLog('start(): dataDir=' + dataDir + ' (source=' + resolved.source + ')'
    + ' port=' + opts.port + ' controlPort=' + opts.controlPort
    + ' instance=' + opts.instance + ' deviceId=' + opts.deviceId
    + ' https=' + (opts.https !== false && !opts.noHttps) + ' mdns=' + (opts.mdns !== false && !opts.noMdns));

  const store = new LevelStore(dataDir);

  // 设备身份：优先用 App 侧传的稳定 --device-id；否则读/建 device.json。
  const device = loadDevice(dataDir, opts.deviceId);
  if (opts.deviceId && device.deviceId !== opts.deviceId) {
    // App 每次传同一 id，正常情况下 device.json 已一致；不一致时以 App 侧为准。
    device.deviceId = opts.deviceId;
  }

  const userStore = new UserStore(dataDir);
  // 启动兼容：旧 --auth-user/--auth-pass 在 users.json 为空时 seed 为 admin。
  // 注意：不自动创建默认 admin（保持局域网 open 访问，由 App/管理页自建账号）。
  userStore.seedAdminIfEmpty(opts.authUser, opts.authPass);
  const peerStore = loadPeers(dataDir);
  const syncHistory = loadSyncHistory(dataDir);

  // 注册开关优先级：显式命令行/环境变量 > server.json 中持久化的上次状态 > 默认开启。
  const registerExplicit = argvHasRegisterFlag(process.argv.slice(2))
    || process.env.POUCH_ALLOW_REGISTER !== undefined;
  let registerEnabled = opts.allowRegister !== false && String(opts.allowRegister) !== 'false';
  if (!registerExplicit) {
    const persisted = readPersistedRegister(path.join(dataDir, 'server.json'));
    if (persisted !== null) registerEnabled = persisted;
  }

  const state = {
    dataDir,
    dataDirSource: resolved.source,
    instance: opts.instance || device.name,
    deviceId: device.deviceId,
    port: opts.port,
    requestedPort: opts.port,
    httpsPort: 0,
    controlPort: opts.controlPort,
    startedAt: Date.now(),
    running: false,
    store,
    userStore,
    peerStore,
    syncHistory: syncHistory,
    syncHistoryFile: path.join(dataDir, 'sync-history.json'),
    syncStatusCache: null,
    replications: {},
    // 安卓侧（Java mDNS）发现的对端缓存：发现靠安卓，这里只接收上报并供 /peers 展示。
    androidPeers: [],
    androidPeersAt: 0,
    pairingEnabled: false, // 默认关闭配对（App 内开关/管理页可开启），避免局域网被刷
    registerEnabled,
    stateFile: path.join(dataDir, 'server.json'),
    databases: async () => {
      try {
        return typeof PouchDB.allDbs === 'function' ? await PouchDB.allDbs() : [];
      } catch (e) {
        return [];
      }
    },
  };

  state.app = createApp(opts, state);
  state.controlApp = createControlApp(opts, state);

  state.server = http.createServer(state.app);
  state.controlServer = http.createServer(state.controlApp);

  // 控制端口先起来：它是唯一常驻通道。
  state.controlPort = await listen(state.controlServer, opts.controlPort, '127.0.0.1');
  console.log('[server] 控制端口 127.0.0.1:' + state.controlPort);

  // 数据端口启动：等 pouchdb-all-dbs 就绪后再迁移旧快照，避免迁移的库 HTTP 层看不到。
  const allDbsReady = await waitForAllDbs(5000);
  if (!allDbsReady) {
    console.error('[server] 警告: PouchDB.allDbs 未就绪，迁移的旧库可能无法被 HTTP 层发现');
  }
  try {
    const restored = await store.restoreAll(PouchDB);
    if (restored.length) {
      console.log('[server] 已迁移旧快照到 leveldb: ' + JSON.stringify(restored));
    }
  } catch (e) {
    console.error('[server] 旧快照迁移失败（忽略）: ' + e.message);
  }
  await new Promise(function (resolve, reject) {
    startListening(state, function (err) { (err ? reject(err) : resolve()); });
  });
  // 明确的「就绪」标记：Android 侧据此判断页面可以打开。
  bootLog('HTTP 已就绪: 数据端口=' + state.port + ' 控制端口=' + state.controlPort);

  // HTTPS：证书缺失时跳过，不阻塞启动（HTTPS 只服务外网浏览器，局域网互通走 HTTP）。
  if (opts.https !== false && !opts.noHttps) {
    const certs = findCertFiles();
    if (certs) {
      try {
        state.httpsServer = https.createServer({
          key: fs.readFileSync(certs.key),
          cert: fs.readFileSync(certs.cert),
        }, state.app);
        state.httpsPort = await listen(state.httpsServer, opts.httpsPort, '0.0.0.0');
        console.log('[server] HTTPS 端口 0.0.0.0:' + state.httpsPort);
        bootLog('HTTPS 已就绪: ' + state.httpsPort);
      } catch (e) {
        console.error('[server] HTTPS 启动失败，已跳过: ' + e.message);
      }
    } else {
      console.log('[server] 未找到证书（privkey1.pem/fullchain1.pem），跳过 HTTPS');
    }
  }

  // 局域网发现（可降级）。安卓 App 侧默认传 --no-mdns（Java 已有 mDNS），
  // 独立运行时默认开启；bonjour-service 未安装时 discovery.start() 直接降级。
  const mdnsEnabled = opts.mdns !== false && !opts.noMdns;
  if (mdnsEnabled) {
    state.discovery = new Discovery({
      instance: state.instance,
      instanceName: state.instance,
      deviceId: state.deviceId,
      port: state.port,
      scheme: 'http',
    });
    const r = state.discovery.start();
    if (r.ok) {
      console.log('[mdns] 已宣告 _couchdb._tcp: ' + state.instance + ':' + state.port
        + ' device=' + state.deviceId);
    } else {
      console.warn('[mdns] 不可用，已降级: ' + r.error);
    }
  } else {
    console.log('[mdns] 已通过 --no-mdns 关闭');
  }

  writeStateFile(state);

  // 心跳：定期写时间戳文件。App 侧 Watchdog 依据心跳过期判断 Node 是否还活着
  // （Node 线程静默退出/卡死时该文件停止更新，Watchdog 便能发现并自愈重启）。
  const writeHeartbeat = function () {
    try {
      fs.writeFileSync(path.join(dataDir, 'heartbeat'), String(Date.now()));
    } catch (e) { /* 心跳写失败不影响服务 */ }
  };
  writeHeartbeat();
  state.heartbeatTimer = setInterval(writeHeartbeat, 10000);

  const shutdown = async (signal) => {
    console.log('[server] 收到 ' + signal + '，正在关闭...');
    try { if (state.discovery) state.discovery.stop(); } catch (e) { /* ignore */ }
    try { if (state.heartbeatTimer) clearInterval(state.heartbeatTimer); } catch (e) { /* ignore */ }
    Object.keys(state.replications).forEach((k) => {
      try { state.replications[k].rep.cancel(); } catch (e) { /* ignore */ }
    });
    try {
      const closed = await closeAllDbs();
      console.log('[server] 数据库已关闭 (' + closed.length + ' 个)');
    } catch (e) {
      console.error('[server] 退出关闭异常: ' + e.message);
    }
    writeStateFile(state);
    process.exit(0);
  };
  process.on('SIGTERM', () => { shutdown('SIGTERM'); });
  process.on('SIGINT', () => { shutdown('SIGINT'); });

  console.log('[server] 已启动 instance=' + state.instance + ' device=' + state.deviceId);
  return {
    app: state.app, server: state.server, controlServer: state.controlServer,
    httpsServer: state.httpsServer, state, PouchDB, closeAllDbs,
  };
}

// express-pouchdb 会在启动时异步安装 pouchdb-all-dbs，
// 只有在它安装之后创建的 db 才会出现在 allDbs() 里（HTTP 才能看到）。
async function waitForAllDbs(timeoutMs) {
  const end = Date.now() + (timeoutMs || 5000);
  while (Date.now() < end) {
    if (typeof PouchDB.allDbs === 'function') return true;
    await new Promise(function (r) { setTimeout(r, 20); });
  }
  return false;
}

module.exports = { start, createApp, defaults, resolveDataDir, looksLikeDataDir, COUCHDB_VERSION };

if (require.main === module) {
  start().catch(function (e) {
    console.error('[server] 启动失败: ' + (e && e.stack ? e.stack : e));
    process.exit(1);
  });
}
