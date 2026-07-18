## Beilin Data Portability（北约建筑数据可携带导出系统）

Beilin Data Portability 是 Beilin Entry Control 的 Fabric 服务端附属模组，面向 **北约成员服**。本模组用于记录玩家在服务器中的建筑归属，并在玩家通过 beiyue.us 提出请求后，将建筑导出为 Litematica 包交付给用户。

该模组旨在满足用户对建筑的“数据可携带权”需求，而非通用备份工具，无法用于恢复完整存档。

---

### 功能概览

- **建筑归属索引**\
  玩家放置或破坏方块时，模组会为建筑组件记录归属信息。

- **自动构建导出包**\
  管理员审核通过后，模组会自动生成 `.litematic` 文件，并上传至 beiyue.us 交付链路。

- **低性能负担**\
  模组仅索引数据导出所需的最小必要信息，随世界目录一并备份和回档。

- **行为可观测性**\
  管理员可登录 beiyue.us 了解导出请求、组件明细、风险标记、交付物大小和失败原因。

---

### 前置条件

在安装和使用本模组之前，请确保满足以下条件：

- **北约成员服务器身份**\
  本模组仅对北约成员服务器开放使用权限，非成员服务器暂不提供支持。

- **Minecraft 服务端环境**\
  服务器需运行 **Fabric 服务端**，并已正确安装 **Fabric API**，以确保模组能够正常加载和运行。

- **已安装 Beilin Entry Control**
  - 本模组是 Beilin Entry Control 的附属模组；
  - 需要同一服务器上已安装并正确配置 Beilin Entry Control。

---

### 安装步骤

1. **安装 Beilin Entry Control**
   - 按照 `README.md` 完成 Beilin Entry Control 的安装与配置；
   - 确认 `config/beilin-entry-control.json` 中的 `apiKey` 已正确配置。

2. **放入模组文件**
   - 将本模组的 `.jar` 文件放入服务器根目录下的 `mods` 文件夹。
   - Beilin Entry Control 与 Beilin Data Portability 需要同时存在。

3. **首次启动服务器**
   - 启动一次服务器，使模组完成初始化；
   - 模组会自动生成：
     - `config/beilin-data-portability.json`
     - `world/beilin-data-portability/index.db`

4. **完成**
   - 大多数情况下无需修改配置文件；
   - 若要自定义配置，可编辑 config/beilin-data-portability.json；
   - 启动日志中应能看到 Beilin Data Portability 成功打开索引数据库并注册导出监听。

---

### 配置说明

配置文件路径：`config/beilin-data-portability.json`

示例内容（仅示例，实际以生成文件为准）：

```json
{
  "exportProcessingEnabled": true,
  "recordingEnabled": true,
  "recordWorldEditBulkPlacements": true,
  "recordEffortlessBulkPlacements": true,
  "structureAuditEnabled": true,
  "discardLinearBulkPlacements": true,
  "artifactDirectory": "beilin-data-portability-exports",
  "maxExportVolumeBlocks": 4000000,
  "scanChunksPerTick": 12
}
```

- **`exportProcessingEnabled`**
  - 是否处理队列中的导出任务；
  - 默认值为 `true`。

- **`recordingEnabled`**
  - 是否为玩家建筑建立索引；
  - 默认值为 `true`。

- **`recordWorldEditBulkPlacements`**
  - 是否记录玩家通过 WorldEdit 产生的结构变更；
  - 默认值为 `true`。

- **`recordEffortlessBulkPlacements`**
  - 是否记录玩家通过 Effortless Structure 产生的结构变更；
  - 默认值为 `true`。

- **`structureAuditEnabled`**
  - 是否上报由 WorldEdit、Effortless Structure 产生结构变更；
  - beiyue.us 上的“结更审计”功能依赖此项运行；
  - 默认值为 `true`。

- **`discardLinearBulkPlacements`**
  - 是否忽略长条状线性结构（如铁路线路）的新增索引；
  - 默认值为 `true`。

- **`artifactDirectory`**
  - 本地临时导出包目录；
  - 相对路径会解析到 `config` 目录下；
  - 默认值为 `"beilin-data-portability-exports"`。

- **`maxExportVolumeBlocks`**
  - 单个导出区域允许的最大体积；
  - 默认值为 `4000000`。

- **`scanChunksPerTick`**
  - 导出扫描的批处理预算；
  - 默认值为 `12`。

修改完成后，保存配置文件，然后重新启动服务器。

---

### 导出流程

1. 玩家在 beiyue.us 玩家面板提交建筑导出申请。
2. 管理员在 beiyue.us 管理后台审核该申请。
3. 审核通过后，beiyue.us 将导出任务送入队列。
4. Beilin Data Portability 领取任务，自动构建导出包。
5. 导出包分片上传至 beiyue.us，由其处理接下来的交付链路。

---

### 索引与存储说明

- 索引数据库路径：`<world>/beilin-data-portability/index.db`
- SQLite WAL 文件路径：同目录下的 `index.db-wal` 与 `index.db-shm`
- 本地临时导出包目录：由 `artifactDirectory` 配置控制

索引数据库会随世界目录一起备份和回档。当世界数据回退时，portability 的建筑索引也会一并回退，避免索引与世界状态错位。

当前索引模型会保存：

- 建筑区域的维度与包围盒；
- 用户名归属与作者比例；
- 最近触达时间、风险标记和扫描缓存信息。

通过原版 `/fill`、WorldEdit 或 Effortless Structure 产生结构变更时，模组会以执行玩家作为归属记录。原版 `/fill` 的 `replace`、`keep`、`outline`、`hollow` 与 `destroy` 模式均按实际成功写入的方块更新索引；由控制台或命令方块执行时，则沿用系统方块变更的处理方式，不会虚构玩家归属。

---

### 升级与移除

- **升级模组**
  - 停止服务器；
  - 替换 `mods` 目录中的旧版本 `.jar` 为新版本；
  - 保留原有的 `config/beilin-data-portability.json`，通常无需更改；
  - 重新启动服务器即可。

- **移除模组**
  - 停止服务器；
  - 从 `mods` 目录中删除本模组 `.jar` 文件；
  - 保留 Beilin Entry Control 时，服务器入服控制不受影响；
  - 重新启动服务器后，将不再建立建筑索引，也不会处理数据导出任务。

> 提示：如不再使用本模组，可根据需要手动删除 `config/beilin-data-portability.json`、本地临时导出包目录，以及世界目录下的 `beilin-data-portability` 索引目录。这不会影响其他配置或存档。

---

### 安全性与透明度说明

- 本模组仅为建筑数据可携带导出记录必要索引，详见 `索引与存储说明`。
- 不会修改其他模组或 Minecraft 的配置文件，也不会改动原版世界存档结构。
- 所有与 beiyue.us 的通信仅围绕导出任务领取、导出包上传、状态回报和结构变更事件进行。
- 本模组复用 Beilin Entry Control 的 API 凭据和导出任务推送，不替代入服控制逻辑。

如有任何其他疑问，请联系 Narek。
