# Badcase 模块设计

## 功能设计说明书

海思知识库架构设计 - Badcase 管理模块（多来源工单归集、来源-问题类型联动筛选、差异化详情渲染、状态处理）

### 修订记录

| 日期         | 修订版本 | 修改描述 | 作者 |
|------------|---------|---------|------|
| 2026-10-20 | 0.1 | 初稿：多来源 Badcase 统一工单模型、来源-问题类型联动、差异化详情区块 | AI 协作 |
| 2026-10-20 | 0.2 | 状态改为四态（待处理/处理中/已处理/不予处理），合并为统一处理接口（传目标状态+备注），详情接口返回处理备注 | AI 协作 |

### Keywords 关键词

> Badcase、工单、来源联动、问题类型、问答对话、冲突片段、切片片段、差异化渲染、枚举映射

### Abstract 摘要

> 统一管理来自 4 个来源的 Badcase 工单：**页面问答、MCP 调用问答、冲突检测、知识切片**。支持列表页按来源 / 问题类型 / 状态 / 知识库 / 关键词多维筛选，其中**来源与问题类型下拉联动**（切换来源自动重置并加载该来源对应的类型候选）；详情弹窗根据工单来源**差异化渲染内容区块**（问答对话 / 冲突片段 / 切片片段 三选一），其余公共区块（问题描述、标签栏、知识库、涉及文档、提交信息、处理状态、处理备注）全来源通用。
>
> **关键边界**：本模块是**纯 CRUD + 查询管理模块**，不涉及任何 LLM 调用、异步任务、消息回调。工单数据来源有两种：
> 1. **外部系统直接写入**（页面问答 / MCP 问答 / 知识切片的 Badcase 提报）—— 本期先由前端通过创建接口提交，后续由各业务系统对接；
> 2. **冲突检测模块自动写入**（复用现有 `conflict_badcase_tickets` 落库逻辑，迁移到统一 `badcase_tickets` 表）。
>
> **本期落地范围**：列表查询、详情查询、来源-问题类型字典接口、工单创建、工单状态处理（四态：待处理 / 处理中 / 已处理 / 不予处理 + 处理备注）、详情区块差异化渲染的后端数据支撑。

---

## 1 背景与需求映射

### 1.1 需求来源

`docs/Badcase模块需求文档.md`：Badcase 管理列表页（来源 / 问题类型 / 状态 / 知识库 / 搜索筛选）、Badcase 详情弹窗（按来源差异化区块渲染）、来源-问题类型联动规则。

### 1.2 需求 → 后端能力映射

| 需求点 | 后端能力 |
|--------|---------|
| 列表页多维筛选（来源、问题类型、状态、知识库、关键词） | `badcase_tickets` 分页查询（CQRS QueryService） |
| 来源下拉与问题类型下拉联动 | 枚举字典接口：返回「来源 → 问题类型集合」映射表，前端据此联动；后端查询时校验组合合法性 |
| 详情弹窗按来源差异化渲染区块 | 统一工单表 + `source_payload` JSONB 存差异化数据；详情接口按来源返回对应区块字段 |
| 冲突检测提报的 Badcase 自动入列表 | 现有 `conflict_badcase_tickets` 迁移到 `badcase_tickets`，统一来源 = `CONFLICT_DETECTION` |
| 处理状态流转（四态：待处理/处理中/已处理/不予处理 + 处理备注） | 统一处理接口（传目标状态 + 备注）+ 处理记录历史表 |

### 1.3 三个必须先讲清的现状约束

| # | 现状（代码事实） | 对设计的影响 |
|---|------------------|--------------|
| 1 | 现有 `conflict_badcase_tickets` 表仅服务于冲突检测模块的 Badcase 提报，字段固定为「冲突快照 + 描述 + 提交人」，**不支持问答 / 切片来源的差异化数据** | 本期新建统一 `badcase_tickets` 表，`conflict_badcase_tickets` 数据迁移到新表（来源 = CONFLICT_DETECTION），旧表保留只读或废弃（见 §3.5） |
| 2 | 全仓**无 Badcase 管理列表页 / 详情页接口**，现有 `ConflictResource` 仅有冲突详情内嵌 badcase 信息 | 本期新建独立 `BadcaseResource`，前缀 `/kb/badcases`，与冲突检测模块解耦 |
| 3 | 页面问答 / MCP 问答 / 知识切片的 Badcase 提报**当前无外部系统对接** | 本期先提供创建接口（前端可直接调用），后续由各业务系统对接写入；`source_payload` JSONB 预留差异化字段 |

---

## 2 方案概述与不做什么

### 2.1 职责划分

```mermaid
flowchart LR
    FE["前端<br/>(Badcase 列表页 / 详情弹窗)"]

    subgraph KB["knowledge 模块（本仓库）"]
        Res["BadcaseResource<br/>(Facade)"]
        Cmd["BadcaseCommandService"]
        Q["BadcaseQueryService<br/>(CQRS 查询侧)"]
        Dict["BadcaseDictService<br/>(来源-类型映射)"]
    end

    subgraph DB[("PostgreSQL")]
        Tickets[("badcase_tickets")]
        Payloads[("badcase_ticket_payloads<br/>(差异化区块, JSONB)")]
        Handlings[("badcase_handlings<br/>(处理记录)")]
    end

    FE -->|"REST"| Res
    Res --> Cmd
    Res --> Q
    Res --> Dict
    Cmd --> Tickets
    Cmd --> Handlings
    Q --> Tickets
    Q --> Payloads
```

### 2.2 本期明确不做（YAGNI）

- **不做工单的在线流转 / 指派 / 多人协作**（仅状态标记 + 备注，需求未要求）。
- **不做 Badcase 与知识库文档的双向跳转联动**（详情只展示快照数据，不实时关联文档）。
- **不做外部系统对接写入**（页面问答 / MCP / 切片的 Badcase 提报来源本期仅通过创建接口手动提交，后续各业务系统自行对接）。
- **不做 Badcase 的自动检测 / 自动生成**（所有工单均为人工提报）。
- **不删除旧 `conflict_badcase_tickets` 表**（数据迁移后旧表保留只读，冲突检测模块的提报入口改为写新表）。

---

## 3 核心设计决策

### 3.1 统一工单模型：一张主表 + JSONB 差异化载荷

4 种来源的工单公共字段（问题描述、来源、问题类型、状态、知识库、涉及文档、提交人、提交时间）统一落在 `badcase_tickets` 主表；差异化区块数据（问答对话 / 冲突片段 / 切片片段）以 `source_payload` JSONB 列存储在同一行，按来源不同结构不同：

| 来源 | `source_payload` 结构 |
|------|----------------------|
| `PAGE_QA`（页面问答） | `{ "question": "...", "answer": "..." }` |
| `MCP_QA`（MCP 调用问答） | `{ "question": "...", "answer": "...", "mcpTool": "..." }` |
| `CONFLICT_DETECTION`（冲突检测） | `{ "conflictTopic": "...", "evidence": [{...}] }`（兼容现有冲突快照） |
| `KNOWLEDGE_SLICE`（知识切片） | `{ "sliceContent": "...", "documentId": "...", "chunkIndex": 0 }` |

> JSONB 而非独立子表：①差异化区块字段不固定、各来源结构差异大，用子表会导致 4 张子表 + 大量 nullable 列；②详情查询时一次取出，无需 join；③数据量可控（Badcase 是低频人工工单，不会海量写入）。

### 3.2 来源-问题类型映射：枚举常量，非数据库表

映射关系为**业务硬编码枚举**，不做数据库配置表（需求中 4 种来源的类型集合是固定的，运营无动态调整需求）：

| 来源枚举 | 来源码 | 问题类型码集合 |
|---------|--------|---------------|
| 页面问答 | `PAGE_QA` | `WRONG_ANSWER`(回答错误) / `CODE_ERROR`(代码错误) / `IRRELEVANT_ANSWER`(答非所问) / `OUTDATED_INFO`(信息过时) / `IRRELEVANT_RESULT`(结果不相关) / `SENSITIVE_NOT_FLAGGED`(涉敏未标记) / `NOT_SENSITIVE_FLAGGED`(未涉敏标记) |
| MCP 调用问答 | `MCP_QA` | 同 PAGE_QA 的 7 种类型 |
| 冲突检测 | `CONFLICT_DETECTION` | `FACT_CONFLICT`(事实冲突) / `SEMANTIC_CONFLICT`(语义冲突) / `VERSION_CONFLICT`(版本冲突) / `CROSS_MODAL_CONFLICT`(跨模态冲突) / `NEAR_DUPLICATE_CONFLICT`(近重复冲突) |
| 知识切片 | `KNOWLEDGE_SLICE` | `INACCURATE_SLICE`(切片不准确) |

- 后端提供 `GET /kb/badcases/dict` 接口下发完整映射表（含中文 label），前端不硬编码。
- 查询时后端校验「来源 + 问题类型」组合合法性：若指定了 `source` 但 `problemType` 不在该来源的集合内 → `A0451`（非法组合）。

### 3.3 详情区块显隐：由 `source` 决定，前端按字典渲染

后端**不做区块显隐逻辑**，只保证详情接口按来源返回对应的 `source_payload` 字段。前端根据来源字典表自行决定显示 / 隐藏哪个区块：

| 来源 | 前端展示区块 | 前端隐藏区块 |
|------|------------|------------|
| PAGE_QA / MCP_QA | 问答对话（question + answer） | 冲突片段、切片片段 |
| CONFLICT_DETECTION | 冲突片段（evidence 列表） | 问答对话、切片片段 |
| KNOWLEDGE_SLICE | 切片片段（sliceContent） | 问答对话、冲突片段 |

> 后端职责边界：只返回数据，不控制 UI 显隐。这与需求「详情弹窗根据来源自动渲染对应区块」一致——前端拿到 `source` 字段 + `source_payload`，自行决定渲染哪些区块。

### 3.4 工单状态机

四态流转，任意状态可改为任意目标状态（运营场景下可能需要"误标已处理后改回待处理"）：

```
PENDING(待处理) ──┬──> PROCESSING(处理中) ──┬──> RESOLVED(已处理)
                 │                          └──> REJECTED(不予处理)
                 └─────────────────────────┴──> REJECTED(不予处理)
```

- 初始状态：`PENDING`。
- 处理动作为**统一接口**（`POST /kb/badcases/{id}/handle`），入参为 `targetStatus`（四值之一）+ `remark`（备注）。不再拆分 resolve / ignore 两个端点。
- 目标状态四值：`PENDING`（重新打开为待处理）、`PROCESSING`（处理中）、`RESOLVED`（已处理）、`REJECTED`（不予处理）。
- 处理时必须填写 `remark`（1~1000 字），同时记录处理人、处理时间、目标状态，写入 `badcase_handlings` 表（每次处理留一条历史记录）。
- 不做复杂流转守卫：任何状态都允许改为任意目标状态，状态合法性由前端下拉约束 + 后端枚举校验保证。

### 3.5 旧 `conflict_badcase_tickets` 表迁移

- 现有 `conflict_badcase_tickets` 数据迁移到 `badcase_tickets`：
  - `source` = `CONFLICT_DETECTION`
  - `problem_type` = `type_snapshot`（复用现有冲突类型码，映射到 Badcase 问题类型码，见 §3.2）
  - `source_payload` = `{ "conflictTopic": topic_snapshot, "evidence": [] }`
  - `description` = 原有 `description`
  - `submitted_by` / `created_at` 不变
- 冲突检测模块的 `ConflictRecordCommandService.submitBadcase` 改为写 `badcase_tickets`（而非旧表），旧 `conflict_badcase_tickets` 表保留只读用于历史数据查询，后续版本废弃。

---

## 4 数据模型

> 遵循 `quarkus规范.md`：PO 在 `infrastructure.po`（贫血 JPA Entity）；仓储接口在 `domain.repository` 返回 `Uni`；领域对象纯 POJO；MapStruct 转换；ID 用值对象包装。迁移脚本 `docs/sql/migration_020_badcase_module.sql`，并同步 `docs/sql/init.sql`。

### 4.1 badcase_tickets（Badcase 工单主表）

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | varchar PK | 工单号（值对象 `BadcaseTicketId`），格式 `BC-yyyyMMdd-xxxx` |
| `source` | varchar(30) | 来源：`PAGE_QA` / `MCP_QA` / `CONFLICT_DETECTION` / `KNOWLEDGE_SLICE` |
| `problem_type` | varchar(30) | 问题类型码（见 §3.2 映射表） |
| `description` | varchar(1000) | 问题描述（必填，1~1000 字） |
| `status` | varchar(20) | 状态：`PENDING`(待处理) / `PROCESSING`(处理中) / `RESOLVED`(已处理) / `REJECTED`(不予处理)，默认 `PENDING` |
| `latest_handling_remark` | varchar(1000) NULL | 最新处理备注冗余（列表 / 详情直接读，免 join handlings 表取最新一条） |
| `latest_handled_by` | varchar(50) NULL | 最新处理人 ID 冗余 |
| `latest_handled_by_name` | varchar(50) NULL | 最新处理人显示名冗余 |
| `latest_handled_at` | timestamp NULL | 最新处理时间冗余 |
| `kb_id` | varchar(36) NULL | 关联知识库 ID（可空，问答类工单可能未关联特定知识库） |
| `kb_name_snapshot` | varchar(200) NULL | 知识库名称快照（避免 join） |
| `related_doc_ids` | json NULL | 涉及文档 ID 数组 `["uuid-1", "uuid-2"]` |
| `related_doc_names` | json NULL | 涉及文档名称快照数组 |
| `source_payload` | jsonb | 差异化区块数据（结构按来源不同，见 §3.1） |
| `submitted_by` | varchar(50) | 提交人 |
| `submitted_by_name` | varchar(50) NULL | 提交人显示名快照 |
| `submitted_at` | timestamp | 提交时间 |
| `created_at` / `updated_at` | timestamp | |

索引：`idx_tickets_source_status(source, status)`、`idx_tickets_status(status)`、`idx_tickets_kb(kb_id)`、`idx_tickets_type(problem_type)`、`idx_tickets_submitted_at(submitted_at DESC)`；keyword 搜索对 `description` 做 `ILIKE '%kw%'`。

### 4.2 badcase_handlings（处理记录）

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | varchar PK | |
| `ticket_id` | varchar | 所属工单 |
| `action` | varchar(20) | 本次操作的目标状态：`PENDING` / `PROCESSING` / `RESOLVED` / `REJECTED` |
| `remark` | varchar(1000) | 处理备注（必填，1~1000） |
| `handled_by` | varchar(50) | 处理人 |
| `handled_by_name` | varchar(50) NULL | 处理人显示名 |
| `handled_at` | timestamp | |

索引：`idx_handlings_ticket(ticket_id, handled_at)`。

> 一条工单可能有多条处理记录（先忽略后处理等），详情页展示最新一条即可。

### 4.3 领域对象与枚举

- 聚合根 `BadcaseTicket`（`domain.entity`，纯 POJO）：封装状态变更方法 `changeStatus(targetStatus, remark)`、来源-类型合法性校验。
- 值对象 `BadcaseTicketId`。
- 枚举：`BadcaseSource`（4 种来源）、`BadcaseProblemType`（所有问题类型码）、`BadcaseStatus`（PENDING / PROCESSING / RESOLVED / REJECTED 四态）。
- 领域服务 `BadcaseDictService`：提供「来源 → 问题类型集合」映射、枚举 label 解析。

---

## 5 分层与包结构（对齐 quarkus规范.md）

沿用 knowledge 模块现有扁平包组织（类名以 `Badcase` 前缀隔离）：

| 层 | 包 | 主要类 |
|----|----|--------|
| Facade | `knowledge.facade` | `BadcaseResource`（工单列表 / 详情 / 创建 / 处理 / 字典 REST） |
| Command | `knowledge.command` | `BadcaseCommandService`（创建、状态流转）；record：`CreateBadcaseCommand`、`HandleBadcaseCommand` |
| Domain 实体 | `knowledge.domain.entity` | `BadcaseTicket`、枚举与 ID 值对象 |
| Domain 仓储 | `knowledge.domain.repository` | `BadcaseTicketRepository`、`BadcaseHandlingRepository`（均返回 `Uni`） |
| Domain 服务 | `knowledge.domain.service` | `BadcaseDictService`（来源-类型映射） |
| Infrastructure PO | `knowledge.infrastructure.po` | `BadcaseTicketPO`、`BadcaseHandlingPO` |
| Infrastructure 仓储 | `knowledge.infrastructure.repository` | 各 `*RepositoryImpl` + `*PanacheRepository` |
| Infrastructure Mapper | `knowledge.infrastructure.mapper` | `BadcaseTicketMapper`（MapStruct） |
| Query（CQRS） | `knowledge.query` | `BadcaseQueryService`（列表 / 详情 / 字典）；DTO：`BadcaseListItem`、`BadcaseDetailDTO`、`BadcaseDictDTO` |

事务约定（同冲突检测模块）：

- 写库在应用服务方法标事务；查询侧 `@WithSession`；领域层零事务 / 零 JPA 注解。
- Command record 加 `@RegisterForReflection`；JSON 注入 Quarkus 托管 `ObjectMapper`，**禁止 `new ObjectMapper()`**。

---

## 6 接口设计（BadcaseResource，前缀 `/kb/badcases`）

统一返回 `ResponseResult` / `PageResult`（响应式 `Uni<...>`）；`@Authenticated`；入参非空 / 长度在 Resource 层 bean validation 完成。

### 6.0 需求页面动作 → 接口映射

| 需求页面动作 | 方法 | 路径 | 详述 |
|------------|------|------|------|
| 进页面加载来源 / 问题类型字典（联动用） | GET | `/kb/badcases/dict` | 见 §6.1 |
| 列表页筛选（来源、问题类型、状态、知识库、关键词） | GET | `/kb/badcases` | 分页列表，见 §6.2 |
| 列表点击查看 → 详情弹窗 | GET | `/kb/badcases/{id}` | 见 §6.3 |
| 创建 Badcase 工单（手动提报） | POST | `/kb/badcases` | 见 §6.4 |
| 详情弹窗处理：修改状态（四态）+ 填写处理备注 | POST | `/kb/badcases/{id}/handle` | 统一处理接口，见 §6.5 |

### 6.1 字典接口（GET `/kb/badcases/dict`）

返回来源列表 + 每个来源对应的问题类型集合，前端据此渲染联动下拉框。

```json
{
  "code": "0",
  "data": {
    "sources": [
      { "code": "PAGE_QA", "label": "页面问答" },
      { "code": "MCP_QA", "label": "MCP 调用问答" },
      { "code": "CONFLICT_DETECTION", "label": "冲突检测" },
      { "code": "KNOWLEDGE_SLICE", "label": "知识切片" }
    ],
    "sourceProblemTypes": {
      "PAGE_QA": [
        { "code": "WRONG_ANSWER", "label": "回答错误" },
        { "code": "CODE_ERROR", "label": "代码错误" }
      ],
      "MCP_QA": [ /* 同 PAGE_QA */ ],
      "CONFLICT_DETECTION": [
        { "code": "FACT_CONFLICT", "label": "事实冲突" }
      ],
      "KNOWLEDGE_SLICE": [
        { "code": "INACCURATE_SLICE", "label": "切片不准确" }
      ]
    },
    "statuses": [
      { "code": "PENDING", "label": "待处理" },
      { "code": "PROCESSING", "label": "处理中" },
      { "code": "RESOLVED", "label": "已处理" },
      { "code": "REJECTED", "label": "不予处理" }
    ]
  }
}
```

### 6.2 分页列表（GET `/kb/badcases`）

Query 参数：`pageNo` / `pageSize` / `source`（可选）/ `problemType`（可选）/ `status`（可选）/ `kbId`（可选）/ `keyword`（可选）。

- `source` 指定时，若同时传了 `problemType` 但不在该来源的类型集合内 → `A0451`。
- 不传 `source` 时，`problemType` 可为任意类型（跨来源筛选）。

列表项 `BadcaseListItem`：

| 字段 | 说明 |
|------|------|
| `id` / `ticketNo` | 工单号（`BC-yyyyMMdd-xxxx`） |
| `source` / `sourceLabel` | 来源码 + 中文标签 |
| `problemType` / `problemTypeLabel` | 问题类型码 + 中文标签 |
| `description` | 问题描述（列表截断展示） |
| `status` / `statusLabel` | 处理状态码 + 中文标签（待处理 / 处理中 / 已处理 / 不予处理） |
| `kbName` | 知识库名称（快照，可空） |
| `submittedByName` | 提交人显示名 |
| `submittedAt` | 提交时间 |
| `latestHandlingRemark` | 最新处理备注（冗余自主表，可空） |
| `latestHandledByName` | 最新处理人显示名（冗余自主表，可空） |
| `latestHandledAt` | 最新处理时间（冗余自主表，可空） |

### 6.3 详情（GET `/kb/badcases/{id}`）

```json
{
  "code": "0",
  "data": {
    "id": "uuid",
    "ticketNo": "BC-20261015-0001",
    "source": "PAGE_QA",
    "sourceLabel": "页面问答",
    "problemType": "WRONG_ANSWER",
    "problemTypeLabel": "回答错误",
    "description": "回答中波特率参数错误...",
    "status": "PENDING",
    "statusLabel": "待处理",
    "kbId": "kb-diting",
    "kbName": "谛听知识库",
    "relatedDocs": [
      { "docId": "uuid-1", "docName": "谛听用户手册 v2.1.pdf" }
    ],
    "submittedByName": "admin",
    "submittedAt": "2026-10-15T10:00:00",
    "sourcePayload": {
      "question": "UART 最高波特率是多少？",
      "answer": "最高支持 3Mbps"
    },
    "handling": {
      "action": "RESOLVED",
      "actionLabel": "已处理",
      "remark": "已确认数据手册 3Mbps 为早期工程样片参数，已补充说明。",
      "handledByName": "产品经理",
      "handledAt": "2026-10-16T10:24:00"
    }
  }
}
```

- `sourcePayload` 结构按来源不同（见 §3.1），前端根据 `source` 字段决定渲染哪个区块。
- `handling` = 最新一条处理记录（无则 null），包含：目标状态（`action`）、处理备注（`remark`）、处理人、处理时间。详情弹窗的「处理状态」「处理备注」区块直接读此字段。
- 列表 / 详情接口的处理人 / 备注 / 时间均冗余在 `badcase_tickets` 主表上（`latest_handled_by_name` / `latest_handling_remark` / `latest_handled_at`），查询无需 join handlings 表；handlings 表仅用于历史追溯，本期不提供历史列表接口。
- 工单不存在 → `A0453`。

### 6.4 创建工单（POST `/kb/badcases`）

| 项 | 内容 |
|----|------|
| 权限 | `kb:badcase:create` |
| 请求体 | `CreateBadcaseCommand`：`source`（必填）、`problemType`（必填）、`description`（必填，1~1000）、`kbId`（可空）、`relatedDocIds`（可空数组）、`sourcePayload`（可空 JSON，结构按来源校验） |
| 成功响应 | `ResponseResult<BadcaseSubmitResult{ticketId, ticketNo}>` |

```json
// 请求（页面问答来源）
{
  "source": "PAGE_QA",
  "problemType": "WRONG_ANSWER",
  "description": "回答中波特率参数错误",
  "kbId": "kb-diting",
  "relatedDocIds": ["uuid-1"],
  "sourcePayload": {
    "question": "UART 最高波特率是多少？",
    "answer": "最高支持 3Mbps"
  }
}
```

- 后端校验 `source + problemType` 组合合法性（不在映射表内 → `A0451`）。
- 冲突检测模块的 `ConflictRecordCommandService.submitBadcase` 内部调用此创建逻辑（或复用同一领域服务），保证数据统一。

### 6.5 统一处理接口（修改状态 + 备注）

| 项 | 内容 |
|----|------|
| 方法 / 路径 | `POST /kb/badcases/{id}/handle` |
| 权限 | `kb:badcase:handle` |
| 请求体 | `HandleBadcaseCommand`：`targetStatus`（必填，四值枚举之一：PENDING / PROCESSING / RESOLVED / REJECTED）、`remark`（必填，1~1000 字） |
| 成功响应 | `ResponseResult<BadcaseHandleResult{ticketId, status, statusLabel, handledAt}>` |

```json
// 请求
{ "targetStatus": "RESOLVED", "remark": "已确认数据手册参数，补充了样片说明。" }
// 响应
{ "code": "0", "data": { "ticketId": "uuid", "status": "RESOLVED", "statusLabel": "已处理", "handledAt": "2026-10-16T10:24:00" } }
```

- `targetStatus` 不在四值枚举内 → `A0454`（状态非法）。
- 处理逻辑（同事务）：
  1. 更新 `badcase_tickets.status` = `targetStatus`，冗余 `latest_handling_remark` = `remark`；
  2. 写 `badcase_handlings` 一条记录（action = targetStatus、remark、处理人、时间）。
- 不做状态流转守卫：任意状态可改为任意目标状态（含从已处理改回待处理），由前端下拉选项约束可选范围。

---

## 7 安全与权限

- 所有端点经现有 JWT Filter（`commons-web`）+ `@Authenticated`。
- 新增权限码：
  - `kb:badcase:read`：查看 Badcase 列表 / 详情 / 字典。
  - `kb:badcase:create`：创建 Badcase 工单。
  - `kb:badcase:handle`：处理工单（修改状态 + 填写备注）。
- **KB 可见性过滤**：列表 / 详情按用户可访问的知识库集合过滤；`kb_id` 为 NULL 的工单（如未关联知识库的问答类）对所有登录用户可见。
- 输入约束：`description` / `remark` 长度 1~1000；`keyword` 长度上限与通用搜索一致。
- 审计：创建、处理操作纳入审计日志。

---

## 8 失败处理与一致性

| 场景 | 处理 |
|------|------|
| 工单不存在 | `A0453` |
| 来源 + 问题类型组合非法 | `A0451` |
| 处理接口 targetStatus 不在四值枚举内 | `A0454` |
| 创建时 sourcePayload 结构与来源不匹配 | 记录 warn，不阻断创建（JSONB 灵活性优先，前端负责传对） |

- 旧 `conflict_badcase_tickets` 迁移为一次性脚本，迁移后冲突检测模块的提报入口改写新表（§3.5）。

---

## 9 待确认事项

| # | 事项 | 倾向 |
|---|------|------|
| 1 | 页面问答 / MCP 问答的 Badcase 由谁提报：前端手动创建 vs 各业务系统后端写入 | 本期先支持前端手动创建，后续业务系统自行对接创建接口 |
| 2 | 旧 `conflict_badcase_tickets` 表是否需要保留 | 迁移后保留只读一个版本，下版本删除 |
| 3 | 列表默认排序：按提交时间倒序 | 是，`submitted_at DESC` |
| 4 | 处理记录是否需要展示历史多条 | 详情页展示最新一条；历史记录暂不提供接口（需求未要求） |

---

## 10 关键决策记录

| # | 权衡点 | 决策 | 理由 |
|---|--------|------|------|
| 1 | 差异化区块用子表 vs JSONB | **JSONB** | 4 种来源结构差异大、无固定查询需求；JSONB 一次取出无需 join，低频工单数据量可控 |
| 2 | 来源-类型映射放数据库 vs 枚举常量 | **枚举常量** | 映射关系固定、无运营动态调整需求；枚举常量 + 字典接口下发，避免过度设计 |
| 3 | 区块显隐由后端控制 vs 前端控制 | **前端控制** | 后端只返回 `source` + `sourcePayload`；前端根据来源字典自行渲染，后端职责更纯 |
| 4 | 新建独立表 vs 复用旧表 | **新建 `badcase_tickets`** | 旧表字段固定为冲突快照，无法扩展到问答 / 切片来源；新表统一模型，旧表数据迁移 |
| 5 | 详情区块数据是否实时关联文档 | **快照** | 与冲突检测模块证据快照策略一致，文档变更不影响工单展示 |

---

## 11 交付物清单（实现拆分建议）

1. `docs/sql/migration_020_badcase_module.sql`（2 张表 / 索引）+ 同步 `init.sql` + 旧表数据迁移脚本。
2. `knowledge`：枚举 `BadcaseSource` / `BadcaseProblemType` / `BadcaseStatus` + 值对象 `BadcaseTicketId`。
3. `knowledge`：PO / Mapper / Repository / 领域模型 `BadcaseTicket`。
4. `knowledge`：`BadcaseDictService`（来源-类型映射 + label 解析）。
5. `knowledge`：`BadcaseCommandService`（创建、状态流转）。
6. `knowledge`：`BadcaseQueryService`（列表 / 详情 / 字典 DTO）。
7. `knowledge`：`BadcaseResource`（6 个端点）+ 权限码 / i18n 文案。
8. `knowledge`：冲突检测模块 `ConflictRecordCommandService.submitBadcase` 改为写 `badcase_tickets`（§3.5）。
9. 错误码（`ErrorCodeEnum` 增补，见下）。
10. 单测（来源-类型校验、状态机守卫、列表查询过滤）。

### 11.1 建议新增错误码（A 段，顺延知识业务自定义区间）

| 枚举 | code | 含义 |
|------|------|------|
| `BADCASE_INVALID_SOURCE_TYPE` | A0451 | 来源与问题类型组合不匹配，请重新选择 |
| `BADCASE_NOT_FOUND` | A0453 | Badcase 工单不存在 |
| `BADCASE_INVALID_STATUS` | A0454 | 非法的工单状态，请选择有效的状态 |

> 越权复用 `A0301`；参数校验失败复用通用参数错误码。
