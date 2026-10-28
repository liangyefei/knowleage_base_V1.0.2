# 数据库表结构说明

> 基于 `server/db/models/` 下 SQLAlchemy 模型梳理。共 **6 张表**，分属 5 个 model 文件（`knowledge_file_model.py` 含 2 个模型）。
> `models/base.py` 里的 `BaseModel` 是混入基类（含 `id`/`create_time`/`update_time`/`create_by`/`update_by`），未被任何模型继承，不对应数据表。

---

## 1. `conversation` — 对话框

> 模型：`ConversationModel` · 文件：`models/conversation_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | String(32) | PK | 对话框 ID |
| `user_id` | String(64) | NOT NULL, index | 用户 ID |
| `name` | String(50) | | 对话框名称 |
| `chat_type` | String(50) | | 聊天类型（chat / agent_chat 等）|
| `create_time` | DateTime | default `now()` | 创建时间 |

---

## 2. `knowledge_base` — 知识库

> 模型：`KnowledgeBaseModel` · 文件：`models/knowledge_base_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | Integer | PK, autoincrement | 知识库 ID |
| `kb_name` | String(50) | | 知识库名称 |
| `kb_info` | String(200) | | 简介（给 Agent 用）|
| `vs_type` | String(50) | | 向量库类型 |
| `embed_model` | String(50) | | 嵌入模型名称 |
| `file_count` | Integer | default 0 | 文件数量 |
| `create_time` | DateTime | default `now()` | 创建时间 |

---

## 3. `knowledge_file` — 知识文件

> 模型：`KnowledgeFileModel` · 文件：`models/knowledge_file_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | Integer | PK, autoincrement | 知识文件 ID |
| `file_name` | String(255) | | 文件名 |
| `file_ext` | String(10) | | 扩展名 |
| `kb_name` | String(50) | | 所属知识库名称 |
| `document_loader_name` | String(50) | | 文档加载器名称 |
| `text_splitter_name` | String(50) | | 文本分割器名称 |
| `file_version` | Integer | default 1 | 文件版本 |
| `file_mtime` | Float | default 0.0 | 文件修改时间 |
| `file_size` | Integer | default 0 | 文件大小 |
| `custom_docs` | Boolean | default False | 是否自定义 docs |
| `docs_count` | Integer | default 0 | 切分文档数量 |
| `create_time` | DateTime | default `now()` | 创建时间 |

---

## 4. `file_doc` — 文件 ↔ 向量库文档

> 模型：`FileDocModel` · 文件：`models/knowledge_file_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | Integer | PK, autoincrement | ID |
| `kb_name` | String(50) | | 知识库名称 |
| `file_name` | String(255) | | 文件名称 |
| `doc_id` | String(50) | | 向量库文档 ID |
| `meta_data` | JSON | default `{}` | 元数据 |

---

## 5. `summary_chunk` — 文档总结片段

> 模型：`SummaryChunkModel` · 文件：`models/knowledge_metadata_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | Integer | PK, autoincrement | ID |
| `kb_name` | String(50) | | 知识库名称 |
| `summary_context` | String(255) | | 总结文本（用于构建向量索引）|
| `summary_id` | String(255) | | 总结向量 ID |
| `doc_ids` | String(1024) | | 关联的向量库 doc_id 列表 |
| `meta_data` | JSON | default `{}` | 元数据 |

**数据来源**：
- 用户上传文件时填写的描述 → 对应 `file_doc.doc_id` 存入 `summary_chunk`
- 程序按 `file_doc.meta_data` 中的页码信息自动切分，用自定义 prompt 生成总结文本，关联页码对应的 `doc_id`

**用途**：对 `summary_context` 建向量索引，召回时先匹配总结，再通过 `doc_ids` 回溯原文档，降低检索噪声。

---

## 6. `message` — 聊天消息

> 模型：`MessageModel` · 文件：`models/message_model.py`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | String(32) | PK | 消息 ID |
| `conversation_id` | String(32) | index | 对话框 ID |
| `user_id` | String(64) | NOT NULL, index | 用户 ID |
| `chat_type` | String(50) | | 聊天类型 |
| `query` | String(4096) | | 用户问题 |
| `response` | String(4096) | | 模型回答 |
| `meta_data` | JSON | default `{}` | 扩展元数据（如知识库 id）|
| `feedback_score` | Integer | default -1 | 用户评分（满分 100，越高越好）|
| `feedback_reason` | String(255) | default `""` | 评分理由 |
| `create_time` | DateTime | default `now()` | 创建时间 |

---

## 表间逻辑关联

> 以下关联为业务逻辑约定，未在模型中声明显式外键（软外键）。

```
┌──────────────┐
│ conversation │ 1
└──────┬───────┘
       │ conversation_id
       ▼ N
┌──────────────┐
│   message    │ N
└──────────────┘
  (user_id 串联两表)

┌───────────────┐
│ knowledge_base│ 1
└───┬───┬───┬───┘
    │   │   │  kb_name（软外键）
    ▼   ▼   ▼ N
┌─────┐ ┌─────────┐ ┌──────────────┐
│file │ │ file_doc│ │summary_chunk │
│ _doc│ │         │ │              │
└──┬──┘ └────┬────┘ └──────┬───────┘
   │         │             │
   └─────────┘             │ summary_chunk.doc_ids
  kb_name+file_name 串联    │ 存 doc_id 列表，回溯 file_doc.doc_id
                            ▼
                    file_doc (N)
```

- **聊天域**：`conversation` 1:N `message`，以 `conversation_id` + `user_id` 串联。
- **知识库域**：`knowledge_base` 1:N `knowledge_file` / `file_doc` / `summary_chunk`，以 `kb_name` 为软外键。
- **文档链路**：`knowledge_file` 1:N `file_doc`，以 `kb_name` + `file_name` 组合串联。
- **总结索引层**：`summary_chunk` 1:N `file_doc`，通过 `summary_chunk.doc_ids`（逗号分隔的 `doc_id` 列表）回溯原文档，用于「先召回总结、再回溯原文」的两阶段检索。

---

## 备注

- 所有 `create_time` 默认 `func.now()`，由数据库填充；`update_time` 仅在 `BaseModel` 中定义但未被各模型使用。
- JSON 字段（`meta_data`）的序列化在 [base.py](base.py) 中配置了 `ensure_ascii=False`，支持中文直接存储。
- 各 repository（`server/db/repository/`）封装了对应表的 CRUD，见同目录 repository 层代码。
