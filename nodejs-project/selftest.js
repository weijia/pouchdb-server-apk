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

// 启动一个独立子进程实例（独立 dataDir，避免与现有测试的 server.json 冲突）。
async function spawnServer(instance, dataDir) {
  fs.mkdirSync(dataDir, { recursive: true });
  const logs = [];
  const child = spawn(process.execPath, [path.join(__dirname, 'main.js')], {
    cwd: __dirname,
    env: Object.assign({}, process.env, {
      POUCH_DATA_DIR: dataDir,
      POUCH_PORT: '0',
      POUCH_CONTROL_PORT: '0',
      POUCH_INSTANCE: instance,
      POUCH_SNAPSHOT_MS: '500',
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', (d) => logs.push(String(d)));
  child.stderr.on('data', (d) => logs.push(String(d)));
  const sf = path.join(dataDir, 'server.json');
  const deadline = Date.now() + 20000;
  let st = null;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 300));
    try {
      const p = JSON.parse(fs.readFileSync(sf, 'utf8'));
      if (p && p.running && p.port && p.instance === instance) { st = p; break; }
    } catch (e) { /* 还没写完整 */ }
  }
  if (!st) throw new Error('server ' + instance + ' 未就绪: ' + logs.join(''));
  return {
    instance, port: st.port, controlPort: st.controlPort,
    base: 'http://127.0.0.1:' + st.port, ctl: 'http://127.0.0.1:' + st.controlPort,
    proc: child,
  };
}

// requester 向 target 发起配对：target 审批后，requester 轮询拿到自己在 target 上的凭据。
async function pair(requester, target, deviceId, name) {
  const r = await req(target.base, 'POST', '/_pair/request', { device_id: deviceId, name });
  const token = r.json.token;
  if (!token) throw new Error('配对请求未返回 token: ' + JSON.stringify(r.json));
  const reqs = await req(target.ctl, 'GET', '/pair/requests');
  const pending = (reqs.json.requests || []).find((x) => x.token === token);
  await req(target.ctl, 'POST', '/pair/approve', { token: pending.token });
  for (let i = 0; i < 25; i++) {
    await new Promise((rr) => setTimeout(rr, 200));
    const s = await req(target.base, 'GET', '/_pair/status?token=' + token);
    if (s.json.state === 'approved') return { user: s.json.user, pass: s.json.pass };
    if (s.json.state === 'denied' || s.json.state === 'notfound') throw new Error('配对被拒绝');
  }
  throw new Error('配对超时未获批');
}

// 从 from 把 db 同步到 to（使用 from 在 to 上的凭据），等待一次性同步完成。
async function replicateOnce(from, to, db, creds) {
  await req(from.ctl, 'POST', '/replicate', {
    db, remoteHost: '127.0.0.1', remotePort: to.port,
    remoteUser: creds.user, remotePass: creds.pass, live: false,
  });
  const key = db + '@127.0.0.1:' + to.port;
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 200));
    const rs = await req(from.ctl, 'GET', '/replications');
    const entry = (rs.json.replications || []).find((e) => e.key === key);
    if (!entry || entry.status === 'paused' || entry.status === 'complete' || entry.status === 'error') break;
  }
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

  // 先彻底停掉本进程内的服务，再启动子进程（否则会共用同一个 server.json）。
  // 关键：leveldb 对库目录持有 LOCK 文件锁，必须以「旧进程已完全退出」的姿态收尾，
  // 即关闭所有库句柄把锁释放掉；否则子进程打开同一个库会报
  // "IO error: lock ... Resource temporarily unavailable"，且该库上的请求会一直挂住。
  if (first.state.heartbeatTimer) clearInterval(first.state.heartbeatTimer);
  await new Promise((resolve) => first.controlServer.close(resolve));
  await new Promise((resolve) => first.server.close(resolve));
  await first.closeAllDbs();
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

  // ==================== 局域网发现 / 配对 / 双向同步（FR-10/FR-19）====================
  // 起两个独立实例 A、B（独立 dataDir），模拟两台手机互为配对并双向同步。
  console.log('--- 配对与双向同步自测 ---');
  const dirA = fs.mkdtempSync(path.join(os.tmpdir(), 'pouchdb-pair-A-'));
  const dirB = fs.mkdtempSync(path.join(os.tmpdir(), 'pouchdb-pair-B-'));
  let A = null, B = null;
  try {
    A = await spawnServer('selftest-A', dirA);
    B = await spawnServer('selftest-B', dirB);

    // 双方开启配对（默认关闭）
    await req(A.ctl, 'POST', '/pairing/set', { enabled: true });
    await req(B.ctl, 'POST', '/pairing/set', { enabled: true });

    // 设置各自管理账号（开启鉴权，验证 peer 凭据可访问）
    await req(A.ctl, 'POST', '/admin/set', { user: 'adminA', pass: 'pwA' });
    await req(B.ctl, 'POST', '/admin/set', { user: 'adminB', pass: 'pwB' });

    // 互为配对：A→B、B→A
    const credsAonB = await pair(A, B, 'dev-A', 'PhoneA');
    check('A 获取在 B 上的 peer 凭据', !!credsAonB && !!credsAonB.user, JSON.stringify(credsAonB));
    const credsBonA = await pair(B, A, 'dev-B', 'PhoneB');
    check('B 获取在 A 上的 peer 凭据', !!credsBonA && !!credsBonA.user, JSON.stringify(credsBonA));

    // 审批后 B 的 users.json 含 peer 账号，A 的同样
    const bUsers = await req(B.ctl, 'GET', '/admin/status');
    check('B 已存在有效账户', bUsers.status === 200);

    // B 写入文档，A 拉取（A 用自己在 B 的凭据）。B 已开鉴权，写文档需带 admin 凭据。
    const putB = await fetch(B.base + '/shared', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Basic ' + Buffer.from('adminB:pwB').toString('base64') },
      body: JSON.stringify({ _id: 'docX', hello: 'fromB' }),
    });
    check('B 写入 docX（带鉴权）', putB.status === 201 || putB.status === 200, 'status=' + putB.status);
    await replicateOnce(A, B, 'shared', credsAonB);
    const pulled = await req(A.base, 'GET', '/shared/docX');
    check('A 从 B 同步到 docX', pulled.status === 200 && pulled.json.hello === 'fromB', JSON.stringify(pulled.json));

    // 反向：A 写入，B 拉取（B 用自己在 A 的凭据）
    const putA = await fetch(A.base + '/shared', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Basic ' + Buffer.from('adminA:pwA').toString('base64') },
      body: JSON.stringify({ _id: 'docY', hello: 'fromA' }),
    });
    check('A 写入 docY（带鉴权）', putA.status === 201 || putA.status === 200, 'status=' + putA.status);
    await replicateOnce(B, A, 'shared', credsBonA);
    const pulled2 = await req(B.base, 'GET', '/shared/docY');
    check('B 从 A 同步到 docY', pulled2.status === 200 && pulled2.json.hello === 'fromA', JSON.stringify(pulled2.json));
  } catch (e) {
    check('配对与双向同步自测未抛异常', false, e && e.message);
  } finally {
    if (A && A.proc) { await req(A.ctl, 'POST', '/shutdown'); }
    if (B && B.proc) { await req(B.ctl, 'POST', '/shutdown'); }
    await new Promise((r) => setTimeout(r, 500));
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
