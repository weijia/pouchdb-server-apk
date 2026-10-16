'use strict';

// PouchDB Server (nodejs-mobile)
// 存储/语义层：PouchDB（leveldb adapter，leveldown 原生持久化）
// 协议层：express-pouchdb（CouchDB 兼容 REST API）
//
// 两个监听端口：
//   1) 数据端口（默认 5984，0.0.0.0）— CouchDB 兼容 API，供局域网客户端同步
//   2) 控制端口（默认 5985，仅 127.0.0.1）— 供 Android 侧启停数据端口，
//      因为数据端口关闭后就再也无法用 HTTP 自我唤醒，必须有一个常驻通道。

// 最早落盘的启动诊断：只用内置模块，不依赖任何第三方库。这样即使后面某个 require
// 失败、或按 nodejs-mobile 单例语义 node::Start 根本没把脚本跑起来，也能在 node.log
// 里区分「脚本压根没执行」与「脚本执行了但启动报错」——否则只能对着空日志猜。
(function earlyBootMarker() {
  try {
    const logArg = process.argv.slice(2).find((a) => a.indexOf('--log-file=') === 0);
    if (!logArg) return;
    const logFile = logArg.slice('--log-file='.length);
    const fsMod = require('fs');
    // 目录可能还没建（start() 里的 mkdirSync 在这之后才执行），先补上再写。
    fsMod.mkdirSync(require('path').dirname(logFile), { recursive: true });
    fsMod.appendFileSync(
      logFile,
      new Date().toISOString() + ' [boot] main.js 已加载 argv=' + JSON.stringify(process.argv) + '\n'
    );
  } catch (e) { /* 诊断失败绝不能影响启动 */ }
})();

const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const cors = require('cors');
const ExpressPouchDB = require('express-pouchdb');

const PouchDB = require('pouchdb-core')
  .plugin(require('pouchdb-adapter-leveldb'))
  .plugin(require('pouchdb-mapreduce'))
  .plugin(require('pouchdb-replication'))
  .plugin(require('pouchdb-find'));

const { LevelStore } = require('./storage');

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

function basicAuth(user, pass) {
  return function (req, res, next) {
    const header = req.headers.authorization || '';
    let valid = false;
    if (header.startsWith('Basic ')) {
      const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      valid = idx >= 0 && decoded.slice(0, idx) === user && decoded.slice(idx + 1) === pass;
    }
    if (!valid) {
      res.set('WWW-Authenticate', 'Basic realm="pouchdb-server"');
      res.status(401).json({ error: 'unauthorized', reason: 'Authentication required.' });
      return;
    }
    next();
  };
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
      port: state.port,
      controlPort: state.controlPort,
      uptimeMs: Date.now() - state.startedAt,
      node: process.version,
      dataDir: opts.dataDir,
      databases: state.store ? state.store.stats() : [],
    });
  });

  if (opts.authUser) {
    app.use(basicAuth(opts.authUser, opts.authPass));
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
      const names = (typeof PouchDB.allDbs === 'function') ? await PouchDB.allDbs() : [];
      await Promise.all(names.map((n) => PouchDB(n).close().catch(function () {})));
      res.json({ ok: true, shuttingDown: true });
      setTimeout(function () { process.exit(0); }, 50);
    } catch (e) {
      res.status(500).json({ error: 'server_error', reason: e.message });
    }
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

  const store = new LevelStore(opts.dataDir);

  const state = {
    dataDir: opts.dataDir,
    instance: opts.instance,
    requestedPort: opts.port,
    port: opts.port,
    controlPort: opts.controlPort,
    running: false,
    startedAt: Date.now(),
    store,
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
      // 优雅退出：关闭所有已打开的库，确保 leveldb memtable 落盘。
      const names = (typeof PouchDB.allDbs === 'function') ? await PouchDB.allDbs() : [];
      await Promise.all(names.map((n) => PouchDB(n).close().catch(function () {})));
      console.log('[server] 数据库已关闭 (' + names.length + ' 个)');
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
  return { app: state.app, server: state.server, controlServer: state.controlServer, store, state, PouchDB };
}

module.exports = { start, createApp, defaults, COUCHDB_VERSION };

if (require.main === module) {
  start().catch(function (e) {
    console.error('[server] 启动失败: ' + (e && e.stack ? e.stack : e));
    process.exit(1);
  });
}
