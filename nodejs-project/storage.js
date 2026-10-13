'use strict';

// 快照持久化：PouchDB memory adapter 本身不落盘，
// 这里把每个 db 的全量变更导出为 JSON 快照，启动时恢复。
// 后续若要换成原生 leveldown，只要替换本文件的 restore/flush 即可。

const fs = require('fs');
const path = require('path');

function snapshotPath(dataDir, name) {
  // 防止 db 名里出现路径分隔符
  const safe = String(name).replace(/[^a-zA-Z0-9_.$()+-]/g, '_');
  return path.join(dataDir, safe + '.snapshot.json');
}

class SnapshotStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.registry = new Map(); // name -> { db, dirty, lastSave }
    fs.mkdirSync(dataDir, { recursive: true });
  }

  register(name, db) {
    if (this.registry.has(name)) return;
    const entry = { db, dirty: false, lastSave: 0 };
    this.registry.set(name, entry);
    try {
      db.changes({ since: 'now', live: true })
        .on('change', () => { entry.dirty = true; })
        .on('error', () => { /* 忽略，flush 时会重新尝试 */ });
    } catch (e) {
      // 某些 adapter 不支持 live changes：退化为定期全量保存
      entry.dirty = true;
    }
  }

  // 发现 express-pouchdb 新创建的数据库并纳入持久化
  async syncRegistry(PouchDB) {
    try {
      const names = await PouchDB.allDbs();
      for (const name of names) {
        if (!this.registry.has(name)) {
          this.register(name, new PouchDB(name));
        }
      }
    } catch (e) { /* allDbs 不可用时跳过 */ }
  }

  async restoreAll(PouchDB) {
    const restored = [];
    let files = [];
    try {
      files = fs.readdirSync(this.dataDir).filter((f) => f.endsWith('.snapshot.json'));
    } catch (e) { return restored; }

    for (const file of files) {
      const name = file.replace(/\.snapshot\.json$/, '');
      let payload;
      try {
        payload = JSON.parse(fs.readFileSync(path.join(this.dataDir, file), 'utf8'));
      } catch (e) {
        console.error('[storage] 快照解析失败，跳过: ' + file + ' ' + e.message);
        continue;
      }
      const docs = payload && Array.isArray(payload.docs) ? payload.docs : [];
      if (!docs.length) { this.register(name, new PouchDB(name)); continue; }

      const db = new PouchDB(name);
      try {
        // new_edits=false 保留原有 _rev 与删除标记，避免同步对端出现修订分叉
        await db.bulkDocs(docs, { new_edits: false });
        restored.push({ name, docs: docs.length });
      } catch (e) {
        console.error('[storage] 恢复失败: ' + name + ' ' + e.message);
      }
      this.register(name, db);
    }
    return restored;
  }

  // 用 changes(since 0) 导出，才能带上 deleted 文档（allDocs 不含）
  async exportDocs(db) {
    const res = await db.changes({ since: 0, include_docs: true, limit: Infinity });
    return (res.results || [])
      .map((row) => row.doc)
      .filter((doc) => doc && doc._id);
  }

  async flushOne(name) {
    const entry = this.registry.get(name);
    if (!entry || !entry.dirty) return false;
    const target = snapshotPath(this.dataDir, name);
    const tmp = target + '.tmp';
    const docs = await this.exportDocs(entry.db);
    const payload = JSON.stringify({ name, updatedAt: Date.now(), docs });
    fs.writeFileSync(tmp, payload);
    fs.renameSync(tmp, target); // 原子替换，避免写到一半崩溃导致快照损坏
    entry.dirty = false;
    entry.lastSave = Date.now();
    return true;
  }

  async flushAll(force) {
    const saved = [];
    for (const name of Array.from(this.registry.keys())) {
      if (force) {
        const entry = this.registry.get(name);
        entry.dirty = true;
      }
      try {
        if (await this.flushOne(name)) saved.push(name);
      } catch (e) {
        console.error('[storage] 保存失败: ' + name + ' ' + e.message);
      }
    }
    return saved;
  }

  stats() {
    return Array.from(this.registry.entries()).map(([name, entry]) => ({
      name,
      dirty: entry.dirty,
      lastSave: entry.lastSave,
    }));
  }
}

module.exports = { SnapshotStore, snapshotPath };
