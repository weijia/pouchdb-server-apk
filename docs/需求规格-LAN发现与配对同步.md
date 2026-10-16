# pouchdb-server-apk 局域网发现与配对同步 需求文档

> 文档目的：对齐「做什么」与「做到什么程度」。
> 适用范围：在现有 pouchdb-server-apk（Android 上跑 CouchDB 兼容 PouchDB 服务）基础上，新增局域网内其他服务器实例的自动发现、本机管理账号设置、设备间配对授权，以及按需双向同步。
> 状态：需求已与干系人确认（v3）。

## 1. 背景与目标

- **背景**：当前 pouchdb-server-apk 仅通过 mDNS（`_couchdb._tcp`）**宣告自身**，可被局域网客户端发现，但无法发现局域网内*其他*实例、也不知道对方有哪些数据库、更没有安全的授权与同步机制。用户希望在 App 内直接看到同网其他节点、安全地建立信任、并把对方指定数据库同步到本机。
- **目标**：
  1. App 自动发现局域网内其他 pouchdb-server 实例，列出其地址与数据库清单。
  2. 用户可在 App 内设置本服务器的管理账号（替代原先仅靠命令行/配置传入的单账号）。
  3. 设备间通过「请求 → 本机审批」的配对流程互相生成专属账号，全程无需人工输入对方密码。
  4. 配对完成后，用户手动勾选对方数据库，按需（单向或双向）同步到本机。

## 2. 范围

- **包含（In Scope）**：
  - 设备稳定身份（deviceId + 可改名 name）。
  - 本机管理账号的设置、修改与持久化。
  - 基于 Android NSD 的局域网服务发现（仅发现，不复用宣告逻辑）。
  - 互为配对的请求/审批/凭据下发流程（双向生成 peer 账号）。
  - 多用户鉴权模型（users.json，非空即要求登录）。
  - 手动勾选触发的数据库同步（replicate 端点 + 控制端口指令）。
  - 待审请求的暴露面控制（配对开关默认关 + pending 限流）。
  - 上述能力的 App UI 与结点侧（Node）端点实现，以及 selftest 自测。
- **不包含（Out of Scope）**：
  - 自动（无审批）同步或按白名单全自动 live 同步（本期为 manual 模式）。
  - 跨网/公网发现与同步（仅限局域网 mDNS）。
  - 端到端加密隧道、设备级证书体系（依赖局域网可信前提）。
  - 账号的细粒度库级权限（peer 账号默认可读写其同步所需库，库级 ACL 留待后续）。
  - 多设备冲突解决策略的定制（沿用 PouchDB/CouchDB 既有冲突模型）。

## 3. 干系人与角色

| 角色 | 职责 |
|------|------|
| 本机用户（管理员） | 设置本机管理账号、开启/关闭配对、审批他人配对请求、勾选并触发同步、查看同步状态。 |
| 对端用户 | 收到本机发起的配对请求并审批；反之亦然。 |
| 本机 Node 服务 | 提供数据端口（5984，CouchDB 兼容）与控制端口（5985，loopback）；承载多用户鉴权、配对、同步执行。 |
| 局域网其他 pouchdb-server 实例 | 作为被发现方与配对/同步的对端。 |

## 4. 功能需求

| 编号 | 需求描述 | 优先级 | 验收标准 |
|------|----------|--------|----------|
| FR-1 | 设备稳定身份 | P0 | 给定首次启动，当 App 写入设备身份时，则生成全局唯一 `deviceId`（UUID）并持久化到 `Prefs` 与 `dataDir/device.json`；给定用户修改 `name`，当重启后，则 `deviceId` 不变、`name` 生效。 |
| FR-2 | 本机管理账号设置 | P0 | 给定用户在 App「本机管理账号」卡片输入用户名与密码并点击保存，当请求成功后，则 `users.json` 中存在 `role:"admin"` 条目且密码以加盐哈希存储；给定访问数据端口，则当 `users.json` 非空时，则要求 Basic Auth 登录。 |
| FR-3 | 管理账号启动兼容 | P1 | 给定旧的 `--auth-user/--auth-pass` 或 `Prefs` 中已有 auth 凭据且 `users.json` 为空，当 Node 启动时，则自动将其 seed 为 admin 条目，不造成无账号可用的锁死。 |
| FR-4 | 局域网服务发现 | P0 | 给定本机服务已启动且网络可用，当进入发现流程时，则通过 `NsdManager.discoverServices("_couchdb._tcp", PROTOCOL_DNS_SD, ...)` 解析出对端的 host/IP、port、TXT（含 `device_id`、`instance`、`version`、`http_port`），并在 UI 列出。 |
| FR-5 | 排除自身 | P0 | 给定本机已宣告且同时运行发现，当解析到一条服务记录时，若其 IP+port 等于本机 `NetUtils.getLanIp()` + 本机数据端口，则不列入对端列表。 |
| FR-6 | 配对开关 | P0 | 给定 `Prefs.pairingEnabled` 默认关闭，当该开关关闭时，则本机数据端口 `/_pair/request` 返回 403；给定用户开启开关，则当收到合法请求时，则进入待审流程。 |
| FR-7 | 发起配对请求 | P0 | 给定用户在发现列表点击某对端的「配对」，当向对端数据端口 `POST /_pair/request {device_id, name}` 时，则对端返回 `{token}`，且对端生成一条 pending 记录（含请求方 id/name、随机密码、token）。 |
| FR-8 | 配对待审与审批 | P0 | 给定对端用户在其 App「待审批区」看到请求，当其点击「允许」调用控制端口 `POST /pair/approve {token}` 时，则 pending 转为 `active` 的 peer 账号；给定点击「拒绝」`POST /pair/deny {token}`，则该 pending 被删除且不生成账号。 |
| FR-9 | 配对状态轮询与凭据获取 | P0 | 给定请求方已拿到 `token`，当其轮询对端数据端口 `GET /_pair/status?token=` 时，则状态为 `pending` 时返回 `{state:"pending"}`，审批通过后返回 `{state:"approved", user, pass}`，拒绝时返回 `{state:"denied"}`；给定 token 不存在，则返回 404。 |
| FR-10 | 双向互为配对 | P0 | 给定 A、B 两台设备各自向对方发起配对且各自审批通过，则当双方都完成后，则 A 的 `Prefs.peer_credentials` 含 B 的凭据、B 的含 A 的凭据，且双方 `users.json` 均含对方的 `role:"peer"` 活跃账号，可双向 `sync`。 |
| FR-11 | 待审限流与上限 | P1 | 给定开启配对，当同 `device_id` 在 60s 内发起超过 5 次 `/_pair/request`，则超出部分被拒绝；给定 pending 总数超过 20，则新的请求被拒绝（返回 429）。 |
| FR-12 | 多用户鉴权 | P0 | 给定 `users.json` 非空，当任意数据端口请求未携带有效 Basic Auth，则返回 401；给定携带有效 admin 或 peer 凭据，则放行对应请求。 |
| FR-13 | 发现并展示对方表 | P0 | 给定本机已与某对端配对，当请求对端数据端口 `GET /_all_dbs`（带该 peer 凭据的 Basic Auth）时，则返回对端数据库名数组；UI 将其与本地库（`/status` 的 `databases`）对比并标记「本地缺失」。 |
| FR-14 | 手动选择同步 | P0 | 给定用户进入某已配对对端的同步区，当其勾选若干数据库并点击「同步选中」，则当指令发出后，则仅对勾选项建立同步；给定未勾选，则不建立任何同步。 |
| FR-15 | 触发同步 | P0 | 给定控制端口收到 `POST /replicate {db, remoteHost, remotePort, remoteUser, remotePass, live}`，则当参数合法时，则使用已加载的 `pouchdb-replication` 对 `PouchDB(db)` 执行 `sync(remoteUrl+'/'+db, {live, retry})`，并将任务记入 `state.replications`（key=`db@host`）。 |
| FR-16 | 同步状态查看 | P1 | 给定存在活跃同步，当调用控制端口 `GET /replications` 时，则返回各任务的 `db`、`remote`、`live`、`active/paused`、`lastSeq`、`ok/err` 状态。 |
| FR-17 | 停止同步 | P1 | 给定存在 key 为 `db@host` 的同步，当调用控制端口 `POST /replicate/stop {key}` 时，则取消该任务并从 `state.replications` 移除。 |
| FR-18 | 凭据本地存储 | P0 | 给定配对成功或管理账号设置成功，则当持久化时，则 peer 凭据以 `device_id` 为 key 存于 `Prefs.peer_credentials`，admin 凭据存于 `Prefs` 供 WebView（Fauxton）自动登录；给定重建，则凭据可从本地恢复。 |
| FR-19 | 自测 | P1 | 给定 `selftest.js` 新增用例，当起两个实例互为配对并各自审批后，则双方 `users.json` 各含对方 peer 账号；给定 A→B 与 B→A 各跑一次 `/replicate`，则双向文档数一致。 |

## 5. 非功能需求

- **性能**：
  - 发现扫描：进入发现后应在数秒内列出可见对端；支持周期兜底重扫（约 60s）以应对换网/休眠。
  - 同步：复用 Node 侧 `pouchdb-replication`，不额外占用 Android 主线程；同步进度可由 `GET /replications` 轮询获取。
- **安全**：
  - 配对公开端点 `/_pair/request` 仅创建 *pending*，不授予任何权限，直至本机审批（控制端口 loopback 受保护）。
  - 配对开关默认关闭，且 pending 有数量上限与速率限制，防刷。
  - 凭据以 `device_id` 为稳定 key，避免同名串号。
  - 密码以加盐哈希（`crypto.scrypt`）存储于 `users.json`，不存明文；`/admin/status`、`/_pair/status` 不回传密码。
  - 已知局限：admin/peer 凭据在 `Prefs`（SharedPreferences）中以明文形式保存供 WebView 自动登录，依赖局域网可信前提；非可信网络下不应开启配对。
- **兼容性 / 可用性**：
  - 沿用现有 `minSdk 24` 与 Android NSD（`NsdManager`）；发现与宣告共享 `MulticastLock`（各自独立、非引用计数）。
  - 控制端口维持 `127.0.0.1` 且仅本机可访问，配对审批/管理账号设置均在控制端口完成。

## 6. 依赖与约束

- Android 侧：`NsdManager`（API 16+）、`MulticastLock`、`SharedPreferences`（Prefs）。
- Node 侧：现有 `pouchdb-core` + `pouchdb-replication`（已加载）；新增 `crypto`（内置）用于密码哈希；`users.json` 落盘于 `dataDir`。
- 约束：控制端口为 loopback-only，故配对审批、管理账号设置、同步指令等敏感操作必须经本机控制端口，不能由局域网直接触发。
- 约束：mDNS 服务类型固定为 `_couchdb._tcp`，与现有宣告保持一致，保证被标准 CouchDB/PouchDB 客户端也能发现。

## 7. 风险与开放问题

- **风险**：公开 `/_pair/request` 在不可信网络可能被探测/刷 pending；缓解：配对开关默认关 + 限流 + pending 上限。
- **风险**：`Prefs` 明文存凭据；缓解：依赖局域网可信模型，后续可迁移至 Android Keystore。
- **开放问题**：peer 账号是否需库级 ACL（当前默认可读写全部库，仅用于同步）；本期不做，留待后续。
- **开放问题**：管理账号是否支持多管理员；本期单 admin 条目（可经 `/admin/set` 覆盖替换）。

---

## 附录 A：接口契约（概要，供设计追溯）

> 下列端点由 Node 侧 `main.js` 实现；详细设计见后续设计文档。

### A.1 数据端口（5984，CouchDB 兼容，受多用户 Basic Auth 约束）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/_all_dbs` | 需登录 | 列出本机数据库名（已存在，配对后用于被对端拉取表清单）。 |
| POST | `/_pair/request` | 免鉴权（受 FR-6/FR-11 约束） | 入参 `{device_id, name}`；出参 `{token}`；建 pending。 |
| GET | `/_pair/status?token=` | 免鉴权 | 出参 `{state:"pending"\|"approved","user?","pass?"}` 或 404。 |

### A.2 控制端口（5985，loopback-only，不受数据端口鉴权影响）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/set` | 入参 `{user, pass}`；写入/替换 `users.json` 的 `role:"admin"` 条目（加盐哈希）。 |
| GET | `/admin/status` | 出参 `{configured:bool}`，不回传密码。 |
| GET | `/pair/requests` | 出参待审列表（含请求方 `device_id`、`name`、`token`）。 |
| POST | `/pair/approve` | 入参 `{token}`；pending 转 active peer 账号。 |
| POST | `/pair/deny` | 入参 `{token}`；删除 pending。 |
| POST | `/replicate` | 入参 `{db, remoteHost, remotePort, remoteUser, remotePass, live}`；启动同步。 |
| GET | `/replications` | 出参活跃同步任务状态。 |
| POST | `/replicate/stop` | 入参 `{key}`；取消同步。 |

### A.3 数据结构（概要）

- `dataDir/users.json`：`{ "<username>": { deviceId?, passHash, role:"admin"\|"peer", active, createdAt } }`
- `dataDir/device.json`：`{ "deviceId": "<uuid>", "name": "<instance>" }`
- `Prefs.peer_credentials`：`Map<device_id, {user, pass}>`
- `Prefs.pairingEnabled`：`boolean`（默认 false）
- `state.replications`：`Map<"db@host", ReplicationHandle>`

### A.4 配对时序（互为配对，单向示例 A→B）

```
A(App)                  B(Node 数据端口)        B(App/控制端口)
  |--- POST /_pair/request {id,name} ---------->|  建 pending, 返回 token
  |                                            |
  |<---------------- {token} -------------------|
  |                                             |--- 轮询 GET /pair/requests
  |                                             |<-- 展示"允许/拒绝"
  |                                             |--- POST /pair/approve {token}
  |                                             |     pending→active peer 账号
  |--- GET /_pair/status?token= --------------->|
  |<---------------- {state:approved,user,pass}-|
  |  存入 Prefs.peer_credentials[device_id=B]   |
```

反向（B→A）同理；双方都完成后即互为配对，可双向 `/replicate`。
