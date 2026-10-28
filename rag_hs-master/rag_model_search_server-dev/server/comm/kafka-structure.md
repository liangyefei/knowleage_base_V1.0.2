# Kafka 消息接口说明

> 本文档定义后端与模型端之间通过 Kafka 传递的消息结构契约，供双方对接联调使用。

---

## 1. Topic 约定

| 方向 | Topic | 分区数 | Message Key | 用途 |
|------|-------|--------|-------------|------|
| 后端 → 模型端 | `kb-document-processing` | 16 | `kbId` | 后端投递业务事件，触发模型端处理 |
| 模型端 → 后端 | `kb-document-processed` | 8 | `documentId`，无 `documentId` 时用 `kbId` | 模型端投递处理结果回调 |

约定：
- 消息体为 UTF-8 编码的 JSON
- 消费者组固定为 `model-service`（由模型端配置，后端无需关心）
- 后端投递的消息，模型端处理成功或业务失败均会提交位点（不会无限重投）；仅当模型端向回调 topic 投递失败时才会重投，此时后端可能收到重复消息，需做幂等处理

---

## 2. 输入消息（后端 → 模型端）

投递到 `kb-document-processing`。

### 2.1 消息信封（Envelope）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageId` | string (UUID) | 是 | 全局唯一消息 ID，用于追踪 |
| `eventType` | string | 是 | 事件类型，取值见 [2.2](#22-事件类型目录) |
| `kbId` | string (UUID) | 是 | 知识库 ID |
| `documentId` | string (UUID) \| null | 文档级事件必填，知识库级事件为 `null` | 文档 ID |
| `timestamp` | string (ISO-8601) | 否 | 后端投递时间 |
| `payload` | object | 视事件类型 | 附加数据，见 [2.3](#23-payload-字段定义) |

示例：

```json
{
  "messageId": "94ddf79d-b397-460b-a7d9-89dac7133846",
  "eventType": "DOCUMENT_PUBLISHED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": "d3c1f0a2-8b7e-4a39-9f10-2e1d6c3b7a90",
  "timestamp": "2026-08-14T06:55:01.244866Z",
  "payload": {
    "filePath": "kb/9f0e3467/d3c1f0a2/需求文档v3.xlsx",
    "file_version_label": "v1.0",
    "tags": ["GPIO", "UART"]
  }
}
```

### 2.2 事件类型目录

| eventType | 业务语义 | kbId | documentId | payload | 是否回调 |
|-----------|----------|------|------------|---------|----------|
| `KB_CREATED` | 新建知识库 | 必填 | `null` | 无 | 是 |
| `KB_UPDATED` | 知识库元信息更新 | 必填 | `null` | 无 | 否 |
| `KB_DELETED` | 删除知识库 | 必填 | `null` | 无 | 是 |
| `DOCUMENT_PUBLISHED` | 文档发布上线（向量化入库） | 必填 | 必填 | `filePath` 必填 | 是 |
| `DOCUMENT_ARCHIVED` | 文档归档下线 | 必填 | 必填 | 无 | 否 |
| `DOCUMENT_DELETED` | 删除文档（含向量） | 必填 | 必填 | `filePath` 可选 | 是 |
| `DOCUMENT_RETRY_PROCESS` | 重试文档处理（覆盖重建向量） | 必填 | 必填 | `filePath` 必填 | 是 |

说明：
- `KB_UPDATED`、`DOCUMENT_ARCHIVED`：模型端无对应副作用，**不会投递回调**
- `KB_DELETED` 为幂等操作：若知识库在模型端不存在，仍按成功处理并回调 `PROCESSING_COMPLETED`
- `DOCUMENT_DELETED`：`filePath` 缺失时模型端按成功处理（no-op）并回调 `PROCESSING_COMPLETED`

### 2.3 payload 字段定义

#### 文档类事件（`DOCUMENT_PUBLISHED` / `DOCUMENT_RETRY_PROCESS` / `DOCUMENT_DELETED`）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | string | `DOCUMENT_PUBLISHED` / `DOCUMENT_RETRY_PROCESS` 必填；`DOCUMENT_DELETED` 可选 | SFS 共享存储下的相对路径 |
| `file_version_label` | string | 否 | 后端约定的版本标签（展示用，如 `v1.0` / `rev-202610`）；空则模型端用内部 INT 版本号。仅 `DOCUMENT_PUBLISHED` / `DOCUMENT_RETRY_PROCESS` 生效，`DOCUMENT_DELETED` 忽略 |
| `tags` | array<string> | 否 | 文件标签列表（如 `["GPIO","UART"]`），OR 语义过滤。仅 `DOCUMENT_PUBLISHED` / `DOCUMENT_RETRY_PROCESS` 生效，`DOCUMENT_DELETED` 忽略 |

> **多版本管理接入说明**：`file_version_label` / `tags` 为多版本管理扩展字段。模型端 Kafka 消费链路（`task_factory._UploadDocumentTask`）已从 payload 读取并透传给 `upload_docs_use_path`；当前 `upload_docs_use_path` 仅接收并记录日志，向下落库（`file_version_history` 表、Milvus metadata、MemPalace）与问答 tags 过滤逻辑见多版本管理实施计划 Task 1-15。后端可先行投递，模型端实施完成后自动生效，向后兼容（字段缺失走默认空值）。

`filePath` 约束：
- 必须为**相对路径**，不能以 `/` 开头，也不能以 Windows 盘符（如 `C:`、`D:`）开头
- 不得包含 `..` 路径段（防目录穿越）
- 路径分隔符建议使用 `/`

示例：

```json
"payload": {
  "filePath": "kb/9f0e3467/d3c1f0a2/需求文档v3.xlsx",
  "file_version_label": "v1.0",
  "tags": ["GPIO", "UART"]
}
```

#### 知识库类事件（`KB_CREATED` / `KB_UPDATED` / `KB_DELETED`）

`payload` 可省略或为空对象 `{}`，模型端不读取。

---

## 3. 输出消息（模型端 → 后端）

投递到 `kb-document-processed`。

### 3.1 消息信封（Envelope）

| 字段 | 类型 | 说明 |
|------|------|------|
| `messageId` | string (UUID) | 模型端新生成，**非原消息 ID** |
| `eventType` | string | `PROCESSING_COMPLETED` 或 `PROCESSING_FAILED` |
| `kbId` | string | 透传输入消息的 `kbId` |
| `documentId` | string (UUID) \| null | 透传输入消息的 `documentId` |
| `timestamp` | string (ISO-8601 UTC) | 模型端投递时间 |
| `payload` | object | 见 [3.2](#32-payload-字段定义) |

示例：

```json
{
  "messageId": "a1b2c3d4-e5f6-4789-9abc-def012345678",
  "eventType": "PROCESSING_COMPLETED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": "d3c1f0a2-8b7e-4a39-9f10-2e1d6c3b7a90",
  "timestamp": "2026-08-14T06:55:01.366676Z",
  "payload": {
    "processedAt": "2026-08-14T06:55:01.366676Z",
    "chunkCount": 12
  }
}
```

### 3.2 payload 字段定义

#### PROCESSING_COMPLETED（处理成功）

| 字段 | 类型 | 说明 |
|------|------|------|
| `processedAt` | string (ISO-8601 UTC) | 处理完成时间 |
| `chunkCount` | int | 文档切片数量；知识库级事件为 `0` |

示例：

```json
"payload": {
  "processedAt": "2026-08-14T06:55:01.366676Z",
  "chunkCount": 12
}
```

#### PROCESSING_FAILED（处理失败）

| 字段 | 类型 | 说明 |
|------|------|------|
| `failedAt` | string (ISO-8601 UTC) | 失败时间 |
| `errorCode` | string | 错误码字符串，如 `"404"`、`"500"` |
| `error` | string | 错误描述 |

示例：

```json
"payload": {
  "failedAt": "2026-08-14T06:55:01.366676Z",
  "errorCode": "500",
  "error": "上传文档时出现意外： ..."
}
```

### 3.3 回调触发规则

| 输入 eventType | 是否回调 | 可能的回调 eventType |
|----------------|----------|---------------------|
| `KB_CREATED` | 是 | `PROCESSING_COMPLETED` / `PROCESSING_FAILED` |
| `KB_UPDATED` | 否 | — |
| `KB_DELETED` | 是 | `PROCESSING_COMPLETED` / `PROCESSING_FAILED` |
| `DOCUMENT_PUBLISHED` | 是 | `PROCESSING_COMPLETED` / `PROCESSING_FAILED` |
| `DOCUMENT_ARCHIVED` | 否 | — |
| `DOCUMENT_DELETED` | 是 | `PROCESSING_COMPLETED` / `PROCESSING_FAILED` |
| `DOCUMENT_RETRY_PROCESS` | 是 | `PROCESSING_COMPLETED` / `PROCESSING_FAILED` |

---

## 4. 典型场景完整示例

### 4.1 新建知识库

后端投递（→ `kb-document-processing`）：

```json
{
  "messageId": "11111111_-_aaaa_bbbb_cccc_222222222222",
  "eventType": "KB_CREATED",
  "kbId": "kb_9f0e3467_2eb0_434e_9452_0b824bd8ed52",
  "documentId": null,
  "timestamp": "2026-08-14T06:55:01.000000Z",
  "payload": {}
}
```

模型端回调（→ `kb-document-processed`）：

```json
{
  "messageId": "33333333-cccc-dddd-eeee-444444444444",
  "eventType": "PROCESSING_COMPLETED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": null,
  "timestamp": "2026-08-14T06:55:02.000000Z",
  "payload": { "processedAt": "2026-08-14T06:55:02.000000Z", "chunkCount": 0 }
}
```

### 4.2 删除知识库

后端投递：

```json
{
  "messageId": "55555555-eeee-ffff-0000-666666666666",
  "eventType": "KB_DELETED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": null,
  "timestamp": "2026-08-14T07:00:00.000000Z",
  "payload": {}
}
```

模型端回调（幂等：即使 KB 不存在也返回成功）：

```json
{
  "messageId": "77777777-1111-2222-3333-888888888888",
  "eventType": "PROCESSING_COMPLETED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": null,
  "timestamp": "2026-08-14T07:00:01.000000Z",
  "payload": { "processedAt": "2026-08-14T07:00:01.000000Z", "chunkCount": 0 }
}
```

### 4.3 发布文档

后端投递：

```json
{
  "messageId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "eventType": "DOCUMENT_PUBLISHED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": "d3c1f0a2-8b7e-4a39-9f10-2e1d6c3b7a90",
  "timestamp": "2026-08-14T07:05:00.000000Z",
  "payload": {
    "filePath": "kb/9f0e3467/d3c1f0a2/需求文档v3.xlsx",
    "file_version_label": "v1.0",
    "tags": ["GPIO", "UART"]
  }
}
```

模型端回调（成功，带切片数）：

```json
{
  "messageId": "ffffffff-0000-1111-2222-333333333333",
  "eventType": "PROCESSING_COMPLETED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": "d3c1f0a2-8b7e-4a39-9f10-2e1d6c3b7a90",
  "timestamp": "2026-08-14T07:05:15.000000Z",
  "payload": { "processedAt": "2026-08-14T07:05:15.000000Z", "chunkCount": 24 }
}
```

### 4.4 处理失败回调示例

```json
{
  "messageId": "1a2b3c4d-5e6f-4789-9abc-def012345678",
  "eventType": "PROCESSING_FAILED",
  "kbId": "9f0e3467-2eb0-434e-9452-0b824bd8ed52",
  "documentId": "d3c1f0a2-8b7e-4a39-9f10-2e1d6c3b7a90",
  "timestamp": "2026-08-14T07:05:16.000000Z",
  "payload": {
    "failedAt": "2026-08-14T07:05:16.000000Z",
    "errorCode": "500",
    "error": "文件解析失败：文件损坏"
  }
}
```

---

## 5. 数据类型与格式约定

| 类型 | 格式 | 示例 |
|------|------|------|
| UUID | 标准 36 位 | `94ddf79d-b397-460b-a7d9-89dac7133846` |
| ISO-8601 时间 | UTC，带 `Z` 或 `+00:00` | `2026-08-14T06:55:01.244866Z` |
| JSON 字段名 | 统一 camelCase | `messageId`、`eventType`、`kbId` |

字段默认值：
- `documentId` 在知识库级事件（`KB_CREATED` / `KB_UPDATED` / `KB_DELETED`）中为 `null`
- `payload` 无附加数据时可传 `{}` 或省略
- `timestamp` 缺失时模型端不报错，但建议后端始终填写以便追踪
