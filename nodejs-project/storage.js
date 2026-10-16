'use strict';

// leveldb 持久化：PouchDB 使用 pouchdb-adapter-leveldb（leveldown），
// 每个数据库自动落盘到 cwd 下的 <name>.leveldb 目录（main.js 已 chdir 到 dataDir）。
// 不再需要 JSON 快照：数据以 leveldb 的紧凑格式存储，内存占用与磁盘占用都大幅下降。

const fs = require('fs');
const path = require('path');

// 递归计算目录大小（字节）
function du(dir) {
  let stat;
  try { stat = fs.statSync(dir); } catch (e) { return 0; }
  if (!stat.isDirectory()) return stat.size;
  let entries = [];
  try { entries = fs.readdirSync(dir); } catch (e) { return stat.size; }
  let total = stat.size;
  for (const e of entries) total += du(path.join(dir, e));
  return total;
}

class LevelStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
  }

  // 一次性迁移：把旧版 JSON 快照导入 leveldb（仅当对应 leveldb 目录尚不存在时）。
  // 迁移成功后删除快照文件以回收空间。返回已迁移的库名列表。
  async restoreAll(PouchDB) {
    const migrated = [];
    let files = [];
    try {
      files = fs.readdirSync(this.dataDir).filter((f) => f.endsWith('.snapshot.json'));
    } catch (e) { return migrated; }

    for (const file of files) {
      const name = file.replace(/\.snapshot\.json$/, '');
      const levelDir = path.join(this.dataDir, name + '.leveldb');
      if (fs.existsSync(levelDir)) {
        // 已迁移：旧快照可直接删除
        try { fs.unlinkSync(path.join(this.dataDir, file)); } catch (e) { /* ignore */ }
        continue;
      }
      let payload;
      try {
        payload = JSON.parse(fs.readFileSync(path.join(this.dataDir, file), 'utf8'));
      } catch (e) {
        console.error('[storage] 快照解析失败，跳过: ' + file + ' ' + e.message);
        continue;
      }
      const docs = payload && Array.isArray(payload.docs) ? payload.docs : [];
      // leveldb 适配器以库名直接作为目录名（如 dataDir/mydb），已存在说明迁移过/已有数据
      const dbDir = path.join(this.dataDir, name);
      if (fs.existsSync(dbDir)) {
        try { fs.unlinkSync(path.join(this.dataDir, file)); } catch (e) { /* ignore */ }
        continue;
      }
      const db = new PouchDB(name);
      try {
        if (docs.length) {
          await db.bulkDocs(docs, { new_edits: false });
        }
        try { fs.unlinkSync(path.join(this.dataDir, file)); } catch (e) { /* ignore */ }
        migrated.push({ name, docs: docs.length });
        console.log('[storage] 已迁移快照到 leveldb: ' + name + ' (' + docs.length + ' docs)');
      } catch (e) {
        console.error('[storage] 快照迁移失败: ' + name + ' ' + e.message);
      }
    }
    return migrated;
  }

  // leveldb 自动持久化，无需手动同步/落盘
  async syncRegistry() { return; }
  async flushAll() { return []; }

  stats() {
    let entries = [];
    try { entries = fs.readdirSync(this.dataDir); } catch (e) { return []; }
    const dbs = [];
    for (const e of entries) {
      const full = path.join(this.dataDir, e);
      let st;
      try { st = fs.statSync(full); } catch (err) { continue; }
      if (!st.isDirectory()) continue;
      // 跳过 PouchDB/leveldb 内部目录（如 _pouch__all_dbs）；用户库名不允许以 _ 开头
      if (e.charAt(0) === '_') continue;
      // leveldb 数据目录特征：含 CURRENT 标记文件（库名即目录名，无固定后缀）
      if (!fs.existsSync(path.join(full, 'CURRENT'))) continue;
      let size = -1;
      try { size = du(full); } catch (err) { /* ignore */ }
      dbs.push({ name: e, size, dirty: false, lastSave: 0 });
    }
    return dbs;
  }
}

module.exports = { LevelStore, du };
