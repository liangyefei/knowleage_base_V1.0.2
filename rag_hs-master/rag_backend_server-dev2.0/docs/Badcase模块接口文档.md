# Badcase 模块接口文档

> 前缀：`/hisilicon-knowledge/kb/badcases`
> 认证：所有接口需 JWT（Header `Authorization: Bearer <token>`）
> 统一返回外壳：`{ "code": "0", "data": {...}, "message": "操作成功" }`
> 分页返回结构：`{ "pageNumber": 1, "pageSize": 20, "totalPage": 1, "totalRow": 4, "records": [...] }`

---

## 枚举字典速查

### 来源 `source`

| code | label |
|---|---|
| `PAGE_QA` | 页面问答 |
| `MCP_QA` | MCP 调用问答 |
| `CONFLICT_DETECTION` | 冲突检测 |
| `KNOWLEDGE_SLICE` | 知识切片 |

### 来源 → 问题类型映射

| source | problemType 候选 |
|---|---|
| `PAGE_QA` | `WRONG_ANSWER`(回答错误) / `CODE_ERROR`(代码错误) / `IRRELEVANT_ANSWER`(答非所问) / `OUTDATED_INFO`(信息过时) / `IRRELEVANT_RESULT`(结果不相关) / `SENSITIVE_NOT_FLAGGED`(涉敏未标记) / `NOT_SENSITIVE_FLAGGED`(未涉敏标记) |
| `MCP_QA` | 同 `PAGE_QA` 的 7 种 |
| `CONFLICT_DETECTION` | `FACT_CONFLICT`(事实冲突) / `SEMANTIC_CONFLICT`(语义冲突) / `VERSION_CONFLICT`(版本冲突) / `CROSS_MODAL_CONFLICT`(跨模态冲突) / `NEAR_DUPLICATE_CONFLICT`(近重复冲突) |
| `KNOWLEDGE_SLICE` | `INACCURATE_SLICE`(切片不准确) |

> 后端会校验 `source + problemType` 组合合法性，非法组合返回 `A0451`。

### 状态 `status`

| code | label |
|---|---|
| `PENDING` | 待处理（初始态） |
| `PROCESSING` | 处理中 |
| `RESOLVED` | 已处理 |
| `REJECTED` | 不予处理 |

---

## 1. 获取字典（来源 / 问题类型映射 / 状态）

**GET** `/kb/badcases/dict`

进页面时调用一次，前端据此渲染「来源下拉」「问题类型下拉联动」「状态下拉」。

### 示例

```
GET http://localhost:8080/hisilicon-knowledge/kb/badcases/dict
Authorization: Bearer <token>
Accept-Language: zh-CN
```

### 响应

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
      "MCP_QA": [
        { "code": "WRONG_ANSWER", "label": "回答错误" }
      ],
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
  },
  "message": "操作成功"
}
```

> **联动逻辑**：前端切换来源时，问题类型下拉重置为「全部类型」，候选值取 `sourceProblemTypes[新来源]`。

---

## 2. 分页查询 Badcase 列表

**GET** `/kb/badcases`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| source | string | 否 | 来源码；指定时 problemType 必须属于该来源 |
| problemType | string | 否 | 问题类型码 |
| status | string | 否 | 状态码（PENDING / PROCESSING / RESOLVED / REJECTED） |
| kbId | string | 否 | 按知识库过滤 |
| keyword | string | 否 | 问题描述模糊搜索 |
| page | int | 否 | 页码，默认 1 |
| pageSize | int | 否 | 每页条数，默认 20，最大 100 |

### 示例

```
GET http://localhost:8080/hisilicon-knowledge/kb/badcases?source=PAGE_QA&status=PENDING&page=1&pageSize=20
Authorization: Bearer <token>
Accept-Language: zh-CN
```

### 响应

```json
{
  "code": "0",
  "data": {
    "pageNumber": 1,
    "pageSize": 20,
    "totalPage": 1,
    "totalRow": 4,
    "records": [
      {
        "id": "bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b",
        "ticketNo": "BC-20261020-0915",
        "source": "PAGE_QA",
        "sourceLabel": "页面问答",
        "problemType": "WRONG_ANSWER",
        "problemTypeLabel": "回答错误",
        "description": "回答中 UART 波特率上限参数错误，用户手册写 921600bps，回答却说 3Mbps。",
        "status": "PENDING",
        "statusLabel": "待处理",
        "kbId": "kb-diting",
        "kbName": "谛听知识库",
        "submittedByName": "张三",
        "submittedAt": "2026-10-20 09:15:00",
        "latestHandlingRemark": null,
        "latestHandledByName": null,
        "latestHandledAt": null
      },
      {
        "id": "bc2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
        "ticketNo": "BC-20261020-1000",
        "source": "MCP_QA",
        "sourceLabel": "MCP 调用问答",
        "problemType": "IRRELEVANT_ANSWER",
        "problemTypeLabel": "答非所问",
        "description": "调用 MCP 工具返回的结果与用户提问完全无关，答非所问。",
        "status": "PROCESSING",
        "statusLabel": "处理中",
        "kbId": "kb-diting",
        "kbName": "谛听知识库",
        "submittedByName": "王五",
        "submittedAt": "2026-10-20 10:00:00",
        "latestHandlingRemark": "已定位为召回策略问题，正在调整权重。",
        "latestHandledByName": "李四",
        "latestHandledAt": "2026-10-20 10:30:00"
      }
    ]
  },
  "message": "操作成功"
}
```

### 可能的错误码

| code | 说明 |
|---|---|
| A0451 | 来源与问题类型组合不匹配（source 指定时 problemType 不在该来源候选内） |

---

## 3. 查 Badcase 详情

**GET** `/kb/badcases/{id}`

### 示例

```
GET http://localhost:8080/hisilicon-knowledge/kb/badcases/bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b
Authorization: Bearer <token>
Accept-Language: zh-CN
```

### 响应（PAGE_QA 来源示例）

```json
{
  "code": "0",
  "data": {
    "id": "bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b",
    "ticketNo": "BC-20261020-0915",
    "source": "PAGE_QA",
    "sourceLabel": "页面问答",
    "problemType": "WRONG_ANSWER",
    "problemTypeLabel": "回答错误",
    "description": "回答中 UART 波特率上限参数错误，用户手册写 921600bps，回答却说 3Mbps。",
    "status": "PENDING",
    "statusLabel": "待处理",
    "kbId": "kb-diting",
    "kbName": "谛听知识库",
    "relatedDocs": [
      { "docId": "doc1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c", "docName": "谛听用户手册 v2.1.pdf" }
    ],
    "submittedByName": "张三",
    "submittedAt": "2026-10-20 09:15:00",
    "sourcePayload": {
      "question": "UART 最高波特率是多少？",
      "answer": "UART 最高支持 3Mbps"
    },
    "handling": null
  },
  "message": "操作成功"
}
```

### 响应（CONFLICT_DETECTION 来源示例）

```json
{
  "code": "0",
  "data": {
    "id": "bc1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c",
    "ticketNo": "BC-20261020-1100",
    "source": "CONFLICT_DETECTION",
    "sourceLabel": "冲突检测",
    "problemType": "FACT_CONFLICT",
    "problemTypeLabel": "事实冲突",
    "description": "冲突判定与实际情况不符，3Mbps 为早期工程样片参数，非现售规格。",
    "status": "RESOLVED",
    "statusLabel": "已处理",
    "kbId": "kb-diting",
    "kbName": "谛听知识库",
    "relatedDocs": [
      { "docId": "doc1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c", "docName": "谛听用户手册 v2.1.pdf" },
      { "docId": "doc2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d", "docName": "谛听数据手册 v1.3.pdf" }
    ],
    "submittedByName": "张三",
    "submittedAt": "2026-10-20 11:00:00",
    "sourcePayload": {
      "conflictTopic": "UART 波特率上限参数不一致",
      "evidence": [
        { "documentId": "doc1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c", "excerpt": "支持波特率 9600 ~ 921600 bps", "isBaseline": true },
        { "documentId": "doc2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d", "excerpt": "UART 最高支持 3Mbps", "isBaseline": false }
      ]
    },
    "handling": {
      "action": "RESOLVED",
      "actionLabel": "已处理",
      "remark": "以用户手册为准，数据手册中 3Mbps 为早期工程样片参数，已在数据手册中补充样片说明。",
      "handledByName": "李四",
      "handledAt": "2026-10-20 14:20:00"
    }
  },
  "message": "操作成功"
}
```

### 响应（KNOWLEDGE_SLICE 来源示例）

```json
{
  "code": "0",
  "data": {
    "id": "bc5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a",
    "ticketNo": "BC-20261020-1500",
    "source": "KNOWLEDGE_SLICE",
    "sourceLabel": "知识切片",
    "problemType": "INACCURATE_SLICE",
    "problemTypeLabel": "切片不准确",
    "description": "切片切分位置不合理，把一个完整的寄存器说明切成了两段。",
    "status": "REJECTED",
    "statusLabel": "不予处理",
    "kbId": "kb-diting",
    "kbName": "谛听知识库",
    "relatedDocs": [
      { "docId": "doc3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e", "docName": "谛听寄存器参考手册 v1.0.pdf" }
    ],
    "submittedByName": "张三",
    "submittedAt": "2026-10-20 15:00:00",
    "sourcePayload": {
      "sliceContent": "UART_IER 寄存器：偏移 0x04，复位值 0x00... 该寄存器用于使能接收中断，当 RXNE 置位时触发",
      "documentId": "doc3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e",
      "chunkIndex": 45
    },
    "handling": {
      "action": "REJECTED",
      "actionLabel": "不予处理",
      "remark": "经确认该切片为历史版本遗留，当前 v2.0 已重新切分，无需处理。",
      "handledByName": "王五",
      "handledAt": "2026-10-20 16:00:00"
    }
  },
  "message": "操作成功"
}
```

### sourcePayload 结构说明（前端按 source 渲染对应区块）

| source | 前端展示区块 | sourcePayload 字段 |
|---|---|---|
| `PAGE_QA` | 问答对话 | `question` / `answer` |
| `MCP_QA` | 问答对话 | `question` / `answer` / `mcpTool`（可选） |
| `CONFLICT_DETECTION` | 冲突片段 | `conflictTopic` / `evidence[]`（每条含 `documentId` / `excerpt` / `isBaseline`） |
| `KNOWLEDGE_SLICE` | 切片片段 | `sliceContent` / `documentId` / `chunkIndex` |

> 后端只返回数据，**区块显隐由前端根据 `source` 自行控制**（隐藏无关区块）。

### handling 字段说明

- `null`：从未处理过（工单初始态 PENDING）
- 非 `null`：最新一条处理记录，详情弹窗的「处理状态」「处理备注」直接读此字段

### 可能的错误码

| code | 说明 |
|---|---|
| A0453 | Badcase 工单不存在 |

---

## 4. 创建 Badcase 工单

**POST** `/kb/badcases`

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| source | string | 是 | 来源码（4 选 1） |
| problemType | string | 是 | 问题类型码（须与 source 组合合法） |
| description | string | 是 | 问题描述，1~1000 字 |
| kbId | string | 否 | 关联知识库 ID |
| kbName | string | 否 | 知识库名称（快照，前端传） |
| relatedDocIds | string[] | 否 | 涉及文档 ID 数组 |
| relatedDocNames | string[] | 否 | 涉及文档名称数组（须与 docIds 一一对应） |
| sourcePayload | object | 否 | 差异化区块 JSON（结构按来源，见 §3） |

### 示例（页面问答）

```
POST http://localhost:8080/hisilicon-knowledge/kb/badcases
Authorization: Bearer <token>
Content-Type: application/json
Accept-Language: zh-CN

{
  "source": "PAGE_QA",
  "problemType": "WRONG_ANSWER",
  "description": "回答中波特率参数错误",
  "kbId": "kb-diting",
  "kbName": "谛听知识库",
  "relatedDocIds": ["doc1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c"],
  "relatedDocNames": ["谛听用户手册 v2.1.pdf"],
  "sourcePayload": {
    "question": "UART 最高波特率是多少？",
    "answer": "最高支持 3Mbps"
  }
}
```

### 响应

```json
{
  "code": "0",
  "data": {
    "ticketId": "bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b"
  },
  "message": "操作成功"
}
```

### 可能的错误码

| code | 说明 |
|---|---|
| A0451 | 来源与问题类型组合不匹配 |

---

## 5. 处理 Badcase（修改状态 + 填写备注）

**POST** `/kb/badcases/{id}/handle`

统一处理接口：传目标状态 + 备注，后端同事务写主表冗余字段 + 追加一条 handling 历史。

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| targetStatus | string | 是 | 目标状态码（PENDING / PROCESSING / RESOLVED / REJECTED 四选 1） |
| remark | string | 是 | 处理备注，1~1000 字 |

> 工单 ID 从 URL 路径传入，body 里不需要再传 ticketId。

> **状态流转**：任意当前状态可改为任意目标状态（含已处理 → 改回待处理），由前端下拉约束可选范围。

### 示例（标记为已处理）

```
POST http://localhost:8080/hisilicon-knowledge/kb/badcases/bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b/handle
Authorization: Bearer <token>
Content-Type: application/json
Accept-Language: zh-CN

{
  "targetStatus": "RESOLVED",
  "remark": "已确认数据手册参数，补充了样片说明。"
}
```

### 响应

```json
{
  "code": "0",
  "data": {
    "ticketId": "bc3f8a2b1c4d5e6f7a8b9c0d1e2f3a4b"
  },
  "message": "操作成功"
}
```

### 可能的错误码

| code | 说明 |
|---|---|
| A0453 | Badcase 工单不存在 |
| A0454 | 非法的工单状态（targetStatus 不在四值枚举内） |

---

## Postman 测试顺序建议

1. 先跑示例数据 SQL（`migration_020_badcase_module_sample_data.sql`），让库里有 4 条不同来源 / 状态的工单
2. **GET** `/dict` → 确认来源 / 问题类型映射 / 状态字典
3. **GET** `/kb/badcases` → 查列表（可分别试 `source=PAGE_QA` / `status=PENDING` / `keyword=波特率`）
4. **GET** `/kb/badcases/{id}` → 查详情（分别看 4 种来源的 `sourcePayload` 结构）
5. **POST** `/kb/badcases` → 创建一条新工单（试非法组合：`source=PAGE_QA` + `problemType=FACT_CONFLICT` → 应返回 A0451）
6. **POST** `/kb/badcases/{id}/handle` → 处理工单（试非法 targetStatus → 应返回 A0454；试合法状态 → 再 GET 详情确认 `handling` 字段已更新）

---

## 错误码总表

| code | 含义 | 触发接口 |
|---|---|---|
| A0451 | 来源与问题类型组合不匹配，请重新选择 | 创建、列表查询 |
| A0453 | Badcase 工单不存在 | 详情、处理 |
| A0454 | 非法的工单状态，请选择有效的状态 | 处理 |
