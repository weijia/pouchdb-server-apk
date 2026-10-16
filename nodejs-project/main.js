'use strict';

// PouchDB Server (nodejs-mobile)
// 存储/语义层：PouchDB（leveldb adapter，leveldown 原生持久化）
// 协议层：express-pouchdb（CouchDB 兼容 REST API）
//
// 两个监听端口：
//   1) 数据端口（默认 5984，0.0.0.0）— CouchDB 兼容 API，供局域网客户端同步
//   2) 控制端口（默认 5985，仅 127.0.0.1）— 供 Android 侧启停数据端口，
//      因为数据端口关闭后就再也无法用 HTTP 自我唤醒，必须有一个常驻通道。

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
    // 目录可能还没建（start() 里的 mkdirSync 在这之后才执行），先补上再写。
    fsMod.mkdirSync(require('path').dirname(logFile), { recursive: true });
    fsMod.appendFileSync(logFile, new Date().toISOString() + ' [boot] ' + msg + '\n');
  } catch (e) { /* 诊断失败绝不能影响启动 */ }
}

bootLog('main.js 已加载 argv=' + JSON.stringify(process.argv));
bootLog('运行时 node=' + process.version + ' platform=' + process.platform
  + ' arch=' + process.arch + ' napi=' + (process.versions.napi || '-')
  + ' cwd=' + process.cwd() + ' NODE_PATH=' + (process.env.NODE_PATH || '-'));

// 原生模块探针：leveldown 是本服务唯一的原生依赖，也最容易在设备上加载失败
// （架构/ABI/预编译产物不匹配）。这里只记录诊断信息、不改变失败语义——真正致命与否
// 由下面加载 leveldb 适配器的 try/catch 决定。
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

const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const cors = require('cors');
const ExpressPouchDB = require('express-pouchdb');

bootLog('已加载 express / cors / express-pouchdb');

const PouchDB = require('pouchdb-core')
  .plugin(require('pouchdb-mapreduce'))
  .plugin(require('pouchdb-replication'))
  .plugin(require('pouchdb-find'));

bootLog('已加载 pouchdb-core / mapreduce / replication / find');

// leveldb 适配器单独 try/catch：它要求 leveldown 原生绑定可用，是设备上最容易失败的一步。
// 失败必须把堆栈写进 node.log —— 否则顶层异常只会打到安卓不可见的 stderr，现象就是
// 「线程起来了、页面打不开、日志里却什么都没有」。
try {
  PouchDB.plugin(require('pouchdb-adapter-leveldb'));
  bootLog('已加载 pouchdb-adapter-leveldb');
} catch (e) {
  bootLog('fatal: pouchdb-adapter-leveldb 加载失败: ' + (e && e.stack ? e.stack : e));
  throw e;
}

const { LevelStore } = require('./storage');
bootLog('已加载 ./storage');

const { UserStore } = require('./users');
bootLog('已加载 ./users');

const DEFAULT_PORT = 5984;
const DEFAULT_CONTROL_PORT = 5985;
// 对外伪装成 CouchDB 3.3.3，部分客户端会检查该版本号
const COUCHDB_VERSION = '3.3.3';

// ==================== 全局异常兜底（防止 Node 进程异常退出）====================
// Node 运行在 Android App 进程内（nodejs-mobile），任何未捕获异常/未处理的
// Promise 拒绝都会让 node::Start 直接返回，表现为「服务起来后直接退出」。
// 这里把它们全部接住并写日志，让进程继续存活、服务不中断。
// 连续异常过多（说明进入异常风暴）时才主动退出，交由 Android 侧自愈重启，
// 避免 CPU 被打满。
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
process.on('unhandledRejection', function (reason) {
  console.error('[guard] unhandledRejection: '
    + (reason && reason.stack ? reason.stack : reason));
});

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
  } catch (e) {
    // 日志不可用不影响服务
  }
}

// 多用户 Basic Auth：数据端口（5984）统一鉴权入口。users.json 非空即要求登录，
// 任何 active 的 admin / peer 账号均可放行（库级 ACL 留待后续）。
function multiUserBasicAuth(userStore) {
  return function (req, res, next) {
    const header = req.headers.authorization || '';
    let valid = false;
    if (header.startsWith('Basic ')) {
      const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      if (idx >= 0) {
        valid = userStore.verify(decoded.slice(0, idx), decoded.slice(idx + 1));
      }
    }
    if (!valid) {
      res.set('WWW-Authenticate', 'Basic realm="pouchdb-server"');
      res.status(401).json({ error: 'unauthorized', reason: 'Authentication required.' });
      return;
    }
    next();
  };
}

// 轻量 JSON body 解析：仅挂到我们自己的路由上，避免与 express-pouchdb 自身的 body 处理冲突。
function jsonBody(req, res, next) {
  if (req.method !== 'POST') return next();
  let data = '';
  req.on('data', function (c) { data += c; });
  req.on('end', function () {
    try { req.body = data ? JSON.parse(data) : {}; } catch (e) { req.body = {}; }
    next();
  });
}

// 关闭所有已打开的库，释放 leveldb 对库目录持有的 LOCK 文件 —— 优雅退出/重启收尾用。
// 注意 pouch__all_dbs__ 是 express-pouchdb 维护的内部库，不会出现在 allDbs() 里，
// 必须显式关闭；漏掉它锁不会释放，之后另一个进程打开同名库会直接报
// "OpenError: IO error: lock xxx/LOCK: Resource temporarily unavailable"，
// 且该库上的请求会一直挂住（levelup 的 deferred 队列不回调），表现为接口无响应。
async function closeAllDbs() {
  let names = [];
  try {
    names = (typeof PouchDB.allDbs === 'function') ? await PouchDB.allDbs() : [];
  } catch (e) { /* 插件未就绪时按空列表处理 */ }
  const all = names.concat(['pouch__all_dbs__']);
  await Promise.all(all.map(function (n) {
    return PouchDB(n).close().catch(function () { /* 已关闭或不存在的库忽略 */ });
  }));
  return all;
}

function createApp(opts, state) {
  const app = express();

  app.use(cors({ origin: opts.corsOrigin || true }));

  // 自有状态端点（先于 express-pouchdb 注册，避免被其路由覆盖）
  app.get('/_status', function (req, res) {
    res.json({
      status: state.running ? 'running' : 'stopped',
      couchdb: COUCHDB_VERSION,
      instance: opts.instance,
      deviceId: opts.deviceId || '',
      port: state.port,
      controlPort: state.controlPort,
      uptimeMs: Date.now() - state.startedAt,
      node: process.version,
      dataDir: opts.dataDir,
      databases: state.store ? state.store.stats() : [],
    });
  });

  // 公开配对端点：免数据端口鉴权，但受「允许配对」开关与限流约束。
  // 必须注册在 multiUserBasicAuth 之前，否则匿名请求会被 401 挡住。
  app.post('/_pair/request', jsonBody, function (req, res) {
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
    res.json({ token: r.token });
  });

  app.get('/_pair/status', function (req, res) {
    const token = req.query.token || '';
    const s = state.userStore.status(token);
    if (s.state === 'notfound') {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    res.json(s);
  });

  // 多用户鉴权：users.json 非空即要求登录（admin / peer 均可）。
  // 取代原先的单账号 basicAuth；旧 --auth-user 已 seed 进 users.json（见 start()）。
  if (state.userStore && state.userStore.hasAnyUser()) {
    app.use(multiUserBasicAuth(state.userStore));
  }

  app.use('/', ExpressPouchDB(PouchDB, {
    mode: 'fullCouchDB',
    configPath: path.join(opts.dataDir, 'config.json'),
    logPath: path.join(opts.dataDir, 'couch.log'),
  }));

  return app;
}

// 控制端口只监听回环地址，且拒绝非本地来源
function createControlApp(opts, state) {
  const app = express();

  app.use(function (req, res, next) {
    const ip = (req.socket && req.socket.remoteAddress) || '';
    const local = ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
    if (!local) {
      res.status(403).json({ error: 'forbidden', reason: 'control port is loopback only' });
      return;
    }
    next();
  });

  app.get('/status', function (req, res) {
    res.json({
      running: state.running,
      port: state.port,
      controlPort: state.controlPort,
      instance: opts.instance,
      uptimeMs: Date.now() - state.startedAt,
      databases: state.store ? state.store.stats() : [],
    });
  });

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

  // 关闭所有库并退出进程。
  // 注意：nodejs-mobile 里 Node 运行在 App 进程内，process.exit 会结束整个 App，
  // 因此 Android 侧只应调用 /flush，不要调用本端点；它用于 CI / 本机测试。
  app.post('/shutdown', async function (req, res) {
    try {
      await closeAllDbs();
      res.json({ ok: true, shuttingDown: true });
      setTimeout(function () { process.exit(0); }, 50);
    } catch (e) {
      res.status(500).json({ error: 'server_error', reason: e.message });
    }
  });

  // ==================== 本机管理账号 ====================
  // 设置/替换 admin 账号（密码以加盐哈希落盘，不存明文）。
  app.post('/admin/set', jsonBody, function (req, res) {
    const user = (req.body && req.body.user) || '';
    const pass = (req.body && req.body.pass) || '';
    if (!user || !pass) {
      res.status(400).json({ error: 'bad_request', reason: 'user and pass required' });
      return;
    }
    state.userStore.setAdmin(user, pass);
    res.json({ ok: true });
  });

  app.get('/admin/status', function (req, res) {
    res.json(state.userStore.getAdminStatus());
  });

  // ==================== 配对开关 ====================
  // 仅本机可切换：开启后数据端口才接受 /_pair/request（默认关闭，防不可信网络被刷）。
  app.post('/pairing/set', jsonBody, function (req, res) {
    state.pairingEnabled = !!(req.body && req.body.enabled);
    writeStateFile(state);
    res.json({ ok: true, pairingEnabled: state.pairingEnabled });
  });

  app.get('/pairing/status', function (req, res) {
    res.json({ pairingEnabled: !!state.pairingEnabled });
  });

  // ==================== 配对待审与审批 ====================
  app.get('/pair/requests', function (req, res) {
    res.json({ requests: state.userStore.getPendingList() });
  });

  app.post('/pair/approve', jsonBody, function (req, res) {
    const token = (req.body && req.body.token) || '';
    const r = state.userStore.approve(token);
    if (!r) {
      res.status(404).json({ error: 'not_found', reason: 'unknown token' });
      return;
    }
    res.json({ ok: true, user: r.user, pass: r.pass });
  });

  app.post('/pair/deny', jsonBody, function (req, res) {
    const token = (req.body && req.body.token) || '';
    const ok = state.userStore.deny(token);
    res.json({ ok: ok });
  });

  // ==================== 同步（replicate）====================
  // 触发本地库与远端（对端）库的同步；复用已加载的 pouchdb-replication。
  app.post('/replicate', jsonBody, function (req, res) {
    const b = req.body || {};
    const db = b.db;
    const remoteHost = b.remoteHost;
    const remotePort = b.remotePort;
    if (!db || !remoteHost || !remotePort) {
      res.status(400).json({ error: 'bad_request', reason: 'db, remoteHost, remotePort required' });
      return;
    }
    const auth = (b.remoteUser && b.remotePass)
      ? encodeURIComponent(b.remoteUser) + ':' + encodeURIComponent(b.remotePass) + '@'
      : '';
    const remote = 'http://' + auth + remoteHost + ':' + remotePort + '/' + encodeURIComponent(db);
    const key = db + '@' + remoteHost + ':' + remotePort;
    if (state.replications[key]) {
      res.json({ ok: true, key: key, note: 'already running' });
      return;
    }
    const local = new PouchDB(db);
    const rep = local.sync(remote, { live: !!b.live, retry: !!b.live });
    const entry = {
      rep: rep, db: db, remote: remote, live: !!b.live,
      status: 'starting', lastSeq: null, error: null,
    };
    rep.on('change', function (info) {
      entry.status = 'active';
      if (info && info.last_seq) entry.lastSeq = info.last_seq;
    });
    rep.on('active', function () { entry.status = 'active'; });
    rep.on('paused', function (err) { entry.status = err ? 'error' : 'paused'; entry.error = err ? String(err) : null; });
    rep.on('denied', function (err) { entry.status = 'denied'; entry.error = err ? String(err) : null; });
    rep.on('error', function (err) { entry.status = 'error'; entry.error = err ? String(err) : null; });
    state.replications[key] = entry;
    console.log('[replicate] 启动 ' + key + ' live=' + !!b.live);
    res.json({ ok: true, key: key });
  });

  app.get('/replications', function (req, res) {
    const list = Object.keys(state.replications).map(function (key) {
      const e = state.replications[key];
      return {
        key: key, db: e.db, remote: e.remote, live: e.live,
        status: e.status, lastSeq: e.lastSeq, error: e.error,
      };
    });
    res.json({ replications: list });
  });

  app.post('/replicate/stop', jsonBody, function (req, res) {
    const key = (req.body && req.body.key) || '';
    const e = state.replications[key];
    if (!e) {
      res.status(404).json({ error: 'not_found', reason: 'unknown replication key' });
      return;
    }
    try { e.rep.cancel(); } catch (e2) { /* ignore */ }
    delete state.replications[key];
    res.json({ ok: true, key: key });
  });

  return app;
}

function listen(server, port, host) {
  return new Promise(function (resolve, reject) {
    server.once('error', reject);
    server.listen(port, host, function () {
      server.removeListener('error', reject);
      resolve(server.address().port);
    });
  });
}

function startListening(state, cb) {
  if (state.running) return cb(null);
  listen(state.server, state.requestedPort, '0.0.0.0')
    .then(function (port) {
      state.port = port;
      // 端口为 0（系统分配）时固定住实际端口，保证 stop 后 start 仍用同一端口
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

function writeStateFile(state) {
  try {
    fs.writeFileSync(state.stateFile, JSON.stringify({
      pid: process.pid,
      running: state.running,
      port: state.port,
      controlPort: state.controlPort,
      instance: state.instance,
      deviceId: state.deviceId || '',
      pairingEnabled: !!state.pairingEnabled,
      startedAt: state.startedAt,
      dataDir: state.dataDir,
      updatedAt: Date.now(),
    }));
  } catch (e) { /* ignore */ }
}

// Android 侧通过 argv 传参（JNI 只传命令行，无法设置环境变量）
function parseArgs(argv) {
  const map = {
    'data-dir': 'dataDir',
    port: 'port',
    'control-port': 'controlPort',
    instance: 'instance',
    'auth-user': 'authUser',
    'auth-pass': 'authPass',
    'cors-origin': 'corsOrigin',
    'snapshot-ms': 'snapshotIntervalMs',
    'device-id': 'deviceId',
    // Android 侧额外传入：Node 的 stdout 在安卓上不可见，日志写到这个文件后
    // 界面才能读到；同时它也是 App 判断「服务是否还活着」的依据之一。
    'log-file': 'logFile',
  };
  const numeric = { port: 1, 'control-port': 1, 'snapshot-ms': 1 };
  const out = {};
  (argv || []).forEach(function (arg) {
    if (!arg || arg.indexOf('--') !== 0) return;
    const eq = arg.indexOf('=');
    const key = eq < 0 ? arg.slice(2) : arg.slice(2, eq);
    const val = eq < 0 ? 'true' : arg.slice(eq + 1);
    if (map[key]) out[map[key]] = numeric[key] ? Number(val) : val;
  });
  return out;
}

function defaults() {
  const dataDir = process.env.POUCH_DATA_DIR || path.join(process.cwd(), 'data');
  return {
    dataDir,
    port: Number(process.env.POUCH_PORT || DEFAULT_PORT),
    controlPort: Number(process.env.POUCH_CONTROL_PORT || DEFAULT_CONTROL_PORT),
    instance: process.env.POUCH_INSTANCE || 'pouchdb-server',
    authUser: process.env.POUCH_AUTH_USER || '',
    authPass: process.env.POUCH_AUTH_PASS || '',
    corsOrigin: process.env.POUCH_CORS_ORIGIN || true,
    snapshotIntervalMs: Number(process.env.POUCH_SNAPSHOT_MS || 5000),
    // 日志文件：默认落在数据目录，Android 会显式传 --log-file 指向界面可读的位置
    logFile: process.env.POUCH_LOG_FILE || '',
  };
}

// express-pouchdb 会在启动时异步安装 pouchdb-all-dbs，
// 只有在它安装之后创建的 db 才会出现在 allDbs() 里（HTTP 才能看到）。
// 因此快照恢复必须等插件就绪后再做。
async function waitForAllDbs(timeoutMs) {
  const end = Date.now() + (timeoutMs || 5000);
  while (Date.now() < end) {
    if (typeof PouchDB.allDbs === 'function') return true;
    await new Promise(function (r) { setTimeout(r, 20); });
  }
  return false;
}

async function start(opts) {
  // 优先级：调用参数 > 命令行参数 > 环境变量 > 默认值
  opts = Object.assign(defaults(), parseArgs(process.argv.slice(2)), opts || {});
  fs.mkdirSync(opts.dataDir, { recursive: true });
  // leveldb 适配器把每个库写在 cwd 下的 <name>.leveldb；
  // 切到数据目录，保证落盘位置可控且可写（files/data 是 App 私有可写目录）。
  process.chdir(opts.dataDir);
  setupLogger(opts.logFile || path.join(opts.dataDir, 'server.log'));
  bootLog('start(): dataDir=' + opts.dataDir + ' port=' + opts.port
    + ' controlPort=' + opts.controlPort + ' instance=' + opts.instance);

  const store = new LevelStore(opts.dataDir);

  // 账户与配对存储：旧 --auth-user/--auth-pass 在 users.json 为空时 seed 为 admin（启动兼容）
  const userStore = new UserStore(opts.dataDir);
  userStore.seedAdminIfEmpty(opts.authUser, opts.authPass);

  const state = {
    dataDir: opts.dataDir,
    instance: opts.instance,
    deviceId: opts.deviceId || '',
    requestedPort: opts.port,
    port: opts.port,
    controlPort: opts.controlPort,
    running: false,
    startedAt: Date.now(),
    store,
    userStore,
    replications: {},
    pairingEnabled: false,
    stateFile: path.join(opts.dataDir, 'server.json'),
  };

  state.app = createApp(opts, state);
  state.server = http.createServer(state.app);

  // 等 pouchdb-all-dbs 就绪后再迁移旧快照，否则迁移出来的库 HTTP 层看不到
  const allDbsReady = await waitForAllDbs(5000);
  if (!allDbsReady) {
    console.error('[server] 警告: PouchDB.allDbs 未就绪，迁移的旧库可能无法被 HTTP 层发现');
  }
  const restored = await store.restoreAll(PouchDB);
  if (restored.length) {
    console.log('[server] 已迁移旧快照到 leveldb: ' + JSON.stringify(restored));
  }

  state.controlApp = createControlApp(opts, state);
  state.controlServer = http.createServer(state.controlApp);

  // 控制端口先起来：它是唯一常驻通道
  state.controlPort = await listen(state.controlServer, opts.controlPort, '127.0.0.1');
  console.log('[server] 控制端口 127.0.0.1:' + state.controlPort);

  await new Promise(function (resolve, reject) {
    startListening(state, function (err) { (err ? reject(err) : resolve()); });
  });
  // 明确的「就绪」标记：Android 侧据此判断页面可以打开（此前只有控制端口日志可判断）。
  bootLog('HTTP 已就绪: 数据端口=' + state.port + ' 控制端口=' + state.controlPort);

  // leveldb 适配器自动持久化到磁盘，无需定时快照/落盘，仅保留心跳定时器。

  // 心跳：定期把时间戳写入 heartbeat 文件。Android 侧据此判断 Node 是否还活着
  // （Node 线程静默退出/卡死时该文件会停止更新，Watchdog 便能发现并自愈重启）。
  const writeHeartbeat = function () {
    try {
      fs.writeFileSync(path.join(opts.dataDir, 'heartbeat'), String(Date.now()));
    } catch (e) { /* 心跳写失败不影响服务 */ }
  };
  writeHeartbeat();
  state.heartbeatTimer = setInterval(writeHeartbeat, 10000);

  const shutdown = async function (signal) {
    console.log('[server] 收到 ' + signal + '，正在关闭数据库（leveldb 自动落盘）...');
    try {
      clearInterval(state.heartbeatTimer);
      // 优雅退出：关闭所有已打开的库，确保 leveldb memtable 落盘并释放文件锁。
      const closed = await closeAllDbs();
      console.log('[server] 数据库已关闭 (' + closed.length + ' 个)');
    } catch (e) {
      console.error('[server] 退出关闭异常: ' + e.message);
    }
    writeStateFile(state);
    process.exit(0);
  };
  process.on('SIGTERM', function () { shutdown('SIGTERM'); });
  process.on('SIGINT', function () { shutdown('SIGINT'); });

  console.log('[server] 已启动 instance=' + opts.instance +
    ' port=' + state.port + ' controlPort=' + state.controlPort +
    ' dataDir=' + opts.dataDir);
  return {
    app: state.app, server: state.server, controlServer: state.controlServer,
    store, state, PouchDB, closeAllDbs,
  };
}

module.exports = { start, createApp, defaults, COUCHDB_VERSION };

if (require.main === module) {
  start().catch(function (e) {
    console.error('[server] 启动失败: ' + (e && e.stack ? e.stack : e));
    process.exit(1);
  });
}
