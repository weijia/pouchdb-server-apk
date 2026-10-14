'use strict';

// PouchDB Server (nodejs-mobile)
// 存储/语义层：PouchDB（memory adapter + 快照持久化）
// 协议层：express-pouchdb（CouchDB 兼容 REST API）
//
// 两个监听端口：
//   1) 数据端口（默认 5984，0.0.0.0）— CouchDB 兼容 API，供局域网客户端同步
//   2) 控制端口（默认 5985，仅 127.0.0.1）— 供 Android 侧启停数据端口，
//      因为数据端口关闭后就再也无法用 HTTP 自我唤醒，必须有一个常驻通道。

const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const cors = require('cors');
const ExpressPouchDB = require('express-pouchdb');

const PouchDB = require('pouchdb-core')
  .plugin(require('pouchdb-adapter-memory'))
  .plugin(require('pouchdb-mapreduce'))
  .plugin(require('pouchdb-replication'))
  .plugin(require('pouchdb-find'));

const { SnapshotStore } = require('./storage');

const DEFAULT_PORT = 5984;
const DEFAULT_CONTROL_PORT = 5985;
// 对外伪装成 CouchDB 3.3.3，部分客户端会检查该版本号
const COUCHDB_VERSION = '3.3.3';

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

  app.post('/flush', async function (req, res) {
    try {
      await state.store.syncRegistry(PouchDB);
      const saved = await state.store.flushAll(true);
      res.json({ ok: true, saved });
    } catch (e) {
      res.status(500).json({ error: 'server_error', reason: e.message });
    }
  });

  // 落盘后退出进程。
  // 注意：nodejs-mobile 里 Node 运行在 App 进程内，process.exit 会结束整个 App，
  // 因此 Android 侧只应调用 /flush，不要调用本端点；它用于 CI / 本机测试。
  app.post('/shutdown', async function (req, res) {
    try {
      await state.store.syncRegistry(PouchDB);
      await state.store.flushAll(true);
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
  setupLogger(path.join(opts.dataDir, 'server.log'));

  const store = new SnapshotStore(opts.dataDir);

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

  // 等 pouchdb-all-dbs 就绪后恢复快照，否则恢复出来的库 HTTP 层看不到
  const allDbsReady = await waitForAllDbs(5000);
  if (!allDbsReady) {
    console.error('[server] 警告: PouchDB.allDbs 未就绪，快照可能无法被 HTTP 层发现');
  }
  const restored = await store.restoreAll(PouchDB);
  if (restored.length) {
    console.log('[server] 已恢复快照: ' + JSON.stringify(restored));
  }

  state.controlApp = createControlApp(opts, state);
  state.controlServer = http.createServer(state.controlApp);

  // 控制端口先起来：它是唯一常驻通道
  state.controlPort = await listen(state.controlServer, opts.controlPort, '127.0.0.1');
  console.log('[server] 控制端口 127.0.0.1:' + state.controlPort);

  await new Promise(function (resolve, reject) {
    startListening(state, function (err) { (err ? reject(err) : resolve()); });
  });

  // 定期发现新建的数据库并落盘（脏库才写，轻量）
  state.timer = setInterval(async function () {
    try {
      await store.syncRegistry(PouchDB);
      const saved = await store.flushAll(false);
      if (saved.length) console.log('[storage] 已保存快照: ' + saved.join(', '));
    } catch (e) {
      console.error('[storage] 定时保存异常: ' + e.message);
    }
  }, opts.snapshotIntervalMs);

  // 周期强制落盘：即便 live changes 订阅意外中断、漏标脏，
  // 也保证每个库最多 30s（或 6 个快照周期）内被持久化一次。
  const FORCE_FLUSH_MS = Math.max(opts.snapshotIntervalMs * 6, 30000);
  state.forceTimer = setInterval(async function () {
    try {
      await store.syncRegistry(PouchDB);
      const saved = await store.flushAll(false, FORCE_FLUSH_MS);
      if (saved.length) console.log('[storage] 周期强制落盘: ' + saved.join(', '));
    } catch (e) {
      console.error('[storage] 周期强制落盘异常: ' + e.message);
    }
  }, FORCE_FLUSH_MS);

  const shutdown = async function (signal) {
    console.log('[server] 收到 ' + signal + '，正在保存快照...');
    try {
      clearInterval(state.timer);
      clearInterval(state.forceTimer);
      await store.syncRegistry(PouchDB);
      await store.flushAll(true);
      console.log('[server] 快照保存完成');
    } catch (e) {
      console.error('[server] 退出保存失败: ' + e.message);
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
