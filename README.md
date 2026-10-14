# pouchdb-server-apk

在 Android 手机上跑一个 **CouchDB 兼容的数据库服务器**：存储与协议语义用 PouchDB，
HTTP 层用 express-pouchdb，运行时用 nodejs-mobile 打进 APK。

手机因此可以直接作为 PouchDB / CouchDB 的同步对端，并通过 mDNS 在局域网内被发现。

## 架构

```
┌──────────────────────── APK (Android) ────────────────────────┐
│                                                               │
│  MainActivity (管理界面)                                       │
│        │ 启停 / 状态                                           │
│        ▼                                                      │
│  NodeService (前台服务，保活)                                   │
│        │ JNI: startNodeWithArguments()                         │
│        ├──────────────► native-lib.cpp ──► libnode.so         │
│        │                        │                             │
│        │                        ▼                             │
│        │            nodejs-project/main.js                    │
│        │              ├─ express-pouchdb  (CouchDB REST API)  │
│        │              ├─ PouchDB (memory adapter)             │
│        │              └─ storage.js (快照持久化)               │
│        │                                                      │
│        └──► MdnsAdvertiser: 注册 _couchdb._tcp                 │
└───────────────────────────────────────────────────────────────┘
```

两个端口：

| 端口 | 监听 | 用途 |
|------|------|------|
| **5984** | `0.0.0.0` | CouchDB 兼容 API（`/db/_changes`、`_bulk_docs`、`_revs_diff`、`_bulk_get`、附件、`_find` 等），供局域网客户端同步 |
| **5985** | `127.0.0.1` | 控制端口，仅本机可访问：`GET /status`、`POST /start`、`POST /stop`、`POST /flush` |

控制端口必须常驻：数据端口一旦关闭就无法再用 HTTP 自我唤醒。

## 目录结构

```
app/                        Android 工程（Groovy DSL + Java + CMake）
├── src/main/cpp/native-lib.cpp        JNI 桥接，调用 libnode 的 node::Start()
├── src/main/java/.../NodeService.java 前台服务、Node 生命周期、状态轮询
├── src/main/java/.../MdnsAdvertiser.java  mDNS 宣告（_couchdb._tcp）
└── src/main/assets/nodejs-project/    构建时生成（含 node_modules，不入库）

nodejs-project/             Node 工程（可单独在本机/CI 运行自测）
├── main.js                 express-pouchdb 服务 + 控制端口
├── storage.js              快照持久化（changes 导出 / bulkDocs 恢复）
└── selftest.js             CouchDB 端点与持久化自测

scripts/
├── fetch-nodejs-mobile.sh  下载官方预编译 libnode.so → jniLibs
└── prepare-node-project.sh npm 依赖 → assets
```

## 构建

CI（push 到 `main` 自动触发，见 `.github/workflows/build-release.yml`）：

1. `npm run selftest` —— 先验证 Node 侧协议与持久化
2. 生成 assets、下载 libnode.so
3. 安装 NDK/CMake，用 Gradle 7.5 构建 APK
4. 上传到 SFTP（保留最近 5 个版本）+ 上传 Actions Artifact

本地构建：

```bash
./scripts/fetch-nodejs-mobile.sh     # 需要网络，下载约 57MB
./scripts/prepare-node-project.sh
gradle assembleDebug                 # 或用 Android Studio 打开
```

> 仓库不含 Gradle Wrapper（避免提交二进制 jar），CI 使用全局 gradle 7.5。

## 使用

1. 安装 APK，授予通知权限（前台服务需要）
2. 打开 App → 启动服务；状态栏常驻通知显示访问地址
3. 局域网内访问 `http://<手机IP>:5984/`，或用 mDNS 发现 `_couchdb._tcp`
4. 客户端同步示例：

```js
const db = new PouchDB('mydb')
db.sync('http://192.168.1.20:5984/mydb', { live: true, retry: true })
```

启用 Basic Auth（可选）：在 Node 启动参数里加 `--auth-user=xxx --auth-pass=yyy`，
或在 `Prefs` 中配置后由服务传入（当前默认关闭，仅限可信局域网使用）。

## 持久化机制与局限

PouchDB 的 `memory` adapter 不落盘，本项目用**快照**持久化（见 `nodejs-project/storage.js`）：

- 每 5 秒（可配 `--snapshot-ms`）把脏库的 `changes(since=0, include_docs=true)` 导出为 JSON
- 用 `new_edits=false` 恢复，保留原始 `_rev` 与删除标记，避免同步对端出现修订分叉
- **真正落盘**：写入先 `fsync` 文件（数据落盘）再 `rename`，再 `fsync` 目录（rename 元数据落盘），掉电/强杀也不丢已保存的快照
- **兜底**：若内存库的 live-changes 订阅意外中断，会标记脏并自动重连；另有一个 30s 周期**强制落盘**，保证每个库最多 30s 内被持久化一次（脏库或超期库均写）
- 停止服务（`/stop`）、收到 `/flush`、进程退出前都强制全量落盘

数据丢失窗口：正常情况受 `--snapshot-ms` 控制（默认 5s），最坏不超过 30s；进程被系统强杀时未落盘的那部分内存数据会丢失（前台服务 + 周期落盘已把窗口压到最小，彻底零丢失需改为原生 on-disk adapter）。

局限：数据全量在内存并整体序列化，**适合中小数据量**；若数据量较大，
可把 `storage.js` 换成基于 `leveldown` 的实现（需为 nodejs-mobile 交叉编译原生模块）。

## 已知限制

- `minSdk 24`：nodejs-mobile 的构建要求
- APK 体积较大：实测 debug APK 约 100MB
  - 构成：`libnode.so`（arm64-v8a + armeabi-v7a，native 库在 APK 内不压缩，约 60MB）+ node_modules（约 30MB）
  - 可选瘦身：`abiFilters` 只保留 `arm64-v8a`（省约 25MB）；或 manifest 设 `android:extractNativeLibs="false"`（APK 再降约 30MB，安装后占用不变）
- Android 15+ 的 16KB page size 设备上，`libnode.so v18.20.4` 未做 16KB 对齐可能无法加载
- 默认无认证，请勿在不可信网络暴露（控制端口已限制为本机回环）
