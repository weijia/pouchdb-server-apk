'use strict';

// Node 侧自测：不依赖 Android，直接在 CI/本机跑。
// 覆盖 CouchDB 兼容端点 + 快照持久化，失败即 exit(1)。

const fs = require('fs');
const os = require('os');
const path = require('path');
const { start } = require('./main');

let failures = 0;

function check(name, cond, extra) {
  if (cond) {
    console.log('  PASS  ' + name);
  } else {
    failures++;
    console.log('  FAIL  ' + name + (extra ? ' -> ' + extra : ''));
  }
}

async function req(base, method, url, body) {
  const res = await fetch(base + url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 非 JSON 响应 */ }
  return { status: res.status, json, text };
}

async function main() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pouchdb-server-test-'));
  const first = await start({
    dataDir, port: 0, controlPort: 0, instance: 'selftest', snapshotIntervalMs: 500,
  });
  const port = first.server.address().port;
  const base = 'http://127.0.0.1:' + port;
  const ctl = 'http://127.0.0.1:' + first.controlServer.address().port;
  console.log('数据端口: ' + base + '  控制端口: ' + ctl + '  数据目录: ' + dataDir);

  const root = await req(base, 'GET', '/');
  check('GET / 返回 couchdb welcome', root.status === 200 && /couchdb/i.test(root.text), root.text.slice(0, 120));

  const status = await req(base, 'GET', '/_status');
  check('GET /_status 返回 running', status.status === 200 && status.json && status.json.status === 'running');

  const createDb = await req(base, 'PUT', '/testdb');
  check('PUT /testdb 创建数据库', createDb.status === 201 || createDb.status === 200, JSON.stringify(createDb.json));

  const putDoc = await req(base, 'POST', '/testdb', { _id: 'doc1', name: 'hello', n: 1 });
  check('POST /testdb 写入文档', putDoc.status === 201, JSON.stringify(putDoc.json));

  const getDoc = await req(base, 'GET', '/testdb/doc1');
  check('GET /testdb/doc1 读回文档', getDoc.status === 200 && getDoc.json.name === 'hello');

  const allDocs = await req(base, 'GET', '/testdb/_all_docs?include_docs=true');
  check('GET _all_docs 含 1 条', allDocs.status === 200 && allDocs.json.total_rows === 1);

  const bulk = await req(base, 'POST', '/testdb/_bulk_docs', {
    docs: [{ _id: 'doc2', n: 2 }, { _id: 'doc3', n: 3 }],
  });
  check('POST _bulk_docs 批量写入', bulk.status === 201 && bulk.json.length === 2);

  const changes = await req(base, 'GET', '/testdb/_changes');
  check('GET _changes 返回 3 条变更', changes.status === 200 && changes.json.results.length === 3,
    JSON.stringify(changes.json && changes.json.results && changes.json.results.length));

  const revsDiff = await req(base, 'POST', '/testdb/_revs_diff', { doc1: [] });
  check('POST _revs_diff 正常响应', revsDiff.status === 200 && revsDiff.json && typeof revsDiff.json === 'object');

  const del = await req(base, 'DELETE', '/testdb/doc3?rev=' + encodeURIComponent(bulk.json[1].rev));
  check('DELETE 文档', del.status === 200 && del.json.ok === true);

  const allDbs = await req(base, 'GET', '/_all_dbs');
  check('GET /_all_dbs 含 testdb', allDbs.status === 200 && allDbs.json.indexOf('testdb') >= 0);

  const uuids = await req(base, 'GET', '/_uuids?count=2');
  check('GET /_uuids 返回 uuid', uuids.status === 200 && uuids.json.uuids.length === 2);

  // ---- 控制端口：停止 -> 数据端口不可访问 -> 控制端口仍可唤醒 ----
  const stop = await req(ctl, 'POST', '/stop');
  check('控制端口 /stop 停止数据端口', stop.status === 200 && stop.json.running === false);
  await new Promise((r) => setTimeout(r, 200));
  let stoppedProbe = null;
  try { stoppedProbe = await req(base, 'GET', '/_status'); } catch (e) { stoppedProbe = { error: e.message }; }
  check('停止后数据端口不可访问', !stoppedProbe || !!stoppedProbe.error, JSON.stringify(stoppedProbe));
  const restart = await req(ctl, 'POST', '/start');
  check('控制端口 /start 恢复数据端口', restart.status === 200 && restart.json.running === true);
  const afterRestart = await req(base, 'GET', '/testdb/doc1');
  check('恢复后数据仍可读', afterRestart.status === 200 && afterRestart.json.name === 'hello');
  const flush = await req(ctl, 'POST', '/flush');
  check('控制端口 /flush 落盘', flush.status === 200 && flush.json.ok === true);

  // ---- leveldb 持久化：重启/退出后数据仍在（旧版 JSON 快照断言已移除）----

  // 先彻底停掉本进程内的服务，再启动子进程（否则会共用同一个 server.json）
  if (first.state.heartbeatTimer) clearInterval(first.state.heartbeatTimer);
  await new Promise((resolve) => first.controlServer.close(resolve));
  await new Promise((resolve) => first.server.close(resolve));
  try { fs.unlinkSync(path.join(dataDir, 'server.json')); } catch (e) { /* 可能不存在 */ }

  const childLog = [];
  const { spawn } = require('child_process');
  const child = spawn(process.execPath, [path.join(__dirname, 'main.js')], {
    cwd: __dirname,
    env: Object.assign({}, process.env, {
      POUCH_DATA_DIR: dataDir,
      POUCH_PORT: '0',
      POUCH_CONTROL_PORT: '0',
      POUCH_INSTANCE: 'selftest-2',
      POUCH_SNAPSHOT_MS: '500',
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', (d) => childLog.push(String(d)));
  child.stderr.on('data', (d) => childLog.push(String(d)));

  // 等待子进程写完 server.json（真实重启场景）
  const stateFile = path.join(dataDir, 'server.json');
  const deadline = Date.now() + 20000;
  let childState = null;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 300));
    try {
      const parsed = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
      // 必须匹配 instance，避免读到上一个进程残留的状态
      if (parsed && parsed.running && parsed.port && parsed.instance === 'selftest-2') {
        childState = parsed; break;
      }
    } catch (e) { /* 还没写完整 */ }
  }
  check('子进程启动并写入 server.json', !!childState, childLog.join(''));
  if (!childState) { process.exitCode = 1; return; }

  const base2 = 'http://127.0.0.1:' + childState.port;
  const restored = await req(base2, 'GET', '/testdb/doc1');
  check('重启后文档仍存在', restored.status === 200 && restored.json.name === 'hello',
    JSON.stringify(restored.json) + ' | 子进程日志: ' + childLog.join(''));
  const afterRestore = await req(base2, 'GET', '/testdb/_all_docs');
  check('重启后删除状态保留（doc3 不复活）',
    afterRestore.status === 200 && afterRestore.json.total_rows === 2,
    JSON.stringify(afterRestore.json && afterRestore.json.total_rows));

  // 写入一条新数据后退出，验证优雅退出（/shutdown 会关闭所有库，确保 memtable 落盘）
  // （不用 SIGTERM：Windows 不支持 POSIX 信号，Android 也无法向 Node 线程发信号）
  await req(base2, 'POST', '/testdb', { _id: 'doc4', n: 4 });
  console.log('[dbg] 已写入 doc4，准备 shutdown');
  const shutdownRes = await req('http://127.0.0.1:' + childState.controlPort, 'POST', '/shutdown');
  console.log('[dbg] shutdown 响应: ' + JSON.stringify(shutdownRes.json));
  // 等待子进程落盘并退出（固定等待，避免子进程 exit 事件的平台差异）
  for (let i = 0; i < 40 && child.exitCode === null; i++) {
    await new Promise((r) => setTimeout(r, 100));
  }
  check('/shutdown 优雅退出', child.exitCode === 0, 'exitCode=' + child.exitCode);

  // 第三次启动：验证退出前写入的 doc4 经 leveldb 持久化保留
  const child2 = spawn(process.execPath, [path.join(__dirname, 'main.js')], {
    cwd: __dirname,
    env: Object.assign({}, process.env, {
      POUCH_DATA_DIR: dataDir,
      POUCH_PORT: '0',
      POUCH_CONTROL_PORT: '0',
      POUCH_INSTANCE: 'selftest-3',
      POUCH_SNAPSHOT_MS: '500',
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child2.stdout.on('data', (d) => childLog.push(String(d)));
  child2.stderr.on('data', (d) => childLog.push(String(d)));
  const deadline2 = Date.now() + 20000;
  let child2State = null;
  while (Date.now() < deadline2) {
    await new Promise((r) => setTimeout(r, 300));
    try {
      const parsed = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
      if (parsed && parsed.running && parsed.port && parsed.instance === 'selftest-3') {
        child2State = parsed; break;
      }
    } catch (e) { /* 还没写完整 */ }
  }
  check('第三次启动并写入 server.json', !!child2State, childLog.join(''));
  if (child2State) {
    const base3 = 'http://127.0.0.1:' + child2State.port;
    const doc4 = await req(base3, 'GET', '/testdb/doc4');
    check('退出前写入的 doc4 经 leveldb 持久化保留',
      doc4.status === 200 && doc4.json.n === 4,
      JSON.stringify(doc4.json));
    await req('http://127.0.0.1:' + child2State.controlPort, 'POST', '/shutdown');
    for (let i = 0; i < 40 && child2.exitCode === null; i++) {
      await new Promise((r) => setTimeout(r, 100));
    }
  }

  console.log('');
  if (failures) {
    console.error('SELFTEST FAILED: ' + failures + ' 项失败');
    process.exitCode = 1;
  } else {
    console.log('SELFTEST PASSED');
    process.exitCode = 0;
  }
  // start() 内部注册了心跳定时器，会保持事件循环存活；测试结束必须清理。
  try {
    if (first && first.state && first.state.heartbeatTimer) clearInterval(first.state.heartbeatTimer);
  } catch (e) { /* ignore */ }
  process.exit(process.exitCode === undefined ? 0 : process.exitCode);
}

main().catch(function (e) {
  console.error(e && e.stack ? e.stack : e);
  process.exit(1);
});
