# RAG联网搜索功能 - 模型端接口变更说明

## 架构说明

```
前端 → Java后端 → Python模型端（本服务） → Java后端转发 → 前端展示
```

本文档说明**Python模型端接口**的变更，供Java后端对接参考。

---

## 功能概述

RAG多知识库问答接口新增 **联网搜索融合能力**：
1. **自动兜底模式**：知识库未命中搜索结果时，自动触发联网搜索获取最新信息补充回答
2. **强制启用模式**：调用方明确要求联网搜索时，总是将联网搜索结果和知识库结果融合一起回答

---

## 接口信息

| 项 | 说明 |
|----|------|
| **接口路径** | `POST /kw/chat/multi_knowledge_base_chat` |
| **请求类型** | JSON |
| **响应类型** | 非流式：JSON<br>流式：`text/event-stream` (SSE) |

---

## 请求参数 - 完整列表

完整参数列表，**✨ 标记为本次新增**：

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `query` | `string` | ✅ 是 | - | 用户输入的问题 |
| `knowledge_base_names` | `string[]` | ✅ 是 | - | 知识库名称列表，支持同时查询多个库 |
| `top_k` | `int` | ❌ 否 | `10` | 最终返回给 LLM 的文档数量（Reranker 精排后） |
| `score_threshold` | `float` | ❌ 否 | `0.5` | 知识库匹配严格度 `[0, 1]` |
| `conversation_id` | `string` | ❌ 否 | `""` | 对话 ID，用于关联会话历史 |
| `user_id` | `string` | ✅ 是 | - | 用户 ID，UUID 格式，用于多租户隔离 |
| `history` | `array` | ❌ 否 | `[]` | 历史对话 `[{"role": "user", "content": "..."}, ...]` |
| `stream` | `boolean` | ❌ 否 | `false` | 是否流式输出（SSE） |
| `model_name` | `string` | ❌ 否 | 配置默认 | LLM 模型名称 |
| `temperature` | `float` | ❌ 否 | `0.7` | LLM 采样温度 `[0, 1]` |
| `max_tokens` | `int \| null` | ❌ 否 | `null` | 限制 LLM 生成 Token 数量 |
| `prompt_name` | `string` | ❌ 否 | `"default"` | prompt 模板名称 |
| ✨ **`enable_internet_search`** | **`boolean \| null`** | ❌ 否 | **`null`** | **联网搜索控制**<br>- `true`: 强制启用，总是搜索并融合结果<br>- `false`: 强制禁用，不搜索<br>- `null`: 自动模式，知识库未命中且全局配置开启时才搜索 |
| `deep_think` | `boolean` | ❌ 否 | `false` | 是否开启深度思考模式 |
| `tags` | `string[] \| null` | ❌ 否 | `null` | 文件标签过滤（OR 语义） |
| `version` | `string \| null` | ❌ 否 | `null` | 指定版本标签检索 |

---

## 请求示例

```json
{
  "query": "今天杭州天气",
  "knowledge_base_names": ["default"],
  "user_id": "a1b2c3d4-5678-90ef-ghij-klmnopqrstuv",
  "enable_internet_search": true,
  "stream": true
}
```

---

## 输出说明

### 非流式输出（`stream=false`）

返回 JSON 格式：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "answer": "模型生成的回答内容",
    "docs": [
      {
        "file": "知识库文件名",
        "relevance_score": [0.968],
        "page": [""],
        "used": true,
        "doc_type": ["pdf"],
        "file_version_label": "v1.0.0",
        "tags": ["硬件", "HDMI"],
        "chunks": [...]
      },
      {
        "file": "https://page.sm.cn/blm/...",
        "relevance_score": [0.968],
        "page": [""],
        "used": true,
        "doc_type": ["internet_search"],
        "file_version_label": "",
        "tags": [],
        "chunks": [
          {
            "content": "标题: 杭州天气...\n来源: https://...\n摘要内容...",
            "relevance_score": 0.968,
            "page": "",
            "chunk_index": 0,
            "doc_type": "internet_search",
            "file_version_label": "",
            "tags": [],
            "used": true
          }
        ]
      }
    ],
    "message_id": "uuid-of-this-message"
  }
}
```

**联网搜索结果说明**：
- 搜索结果自动添加到 `docs` 数组末尾
- 结构和知识库文档**完全一致**
- 通过 `doc_type = ["internet_search"]` 标记，便于区分

### 流式输出（`stream=true`）

SSE 流式输出，每个事件是一行 JSON：

#### 1. 路由推理/检索状态

```json
{
  "type": "route_reasoning",
  "stream": false,
  "content": "正在为您检索知识库相关内容...",
  "message_id": "message-id"
}
```

**如果触发联网搜索，会多推送一个**：

```json
{
  "type": "route_reasoning",
  "stream": false,
  "content": "正在联网获取最新信息...",
  "message_id": "message-id"
}
```

**说明**：
- `type` 保持 `route_reasoning`，和知识库检索格式一致
- Java后端只需要透传即可，前端现有处理逻辑可以直接复用

#### 2. 回答内容逐字推送

```json
{
  "type": "answer",
  "token": "当前这一段文字",
  "finished": false
}
```

最后一块：

```json
{
 "type": "answer",
 "token": "",
 "finished": true
}
```

#### 3. 最终完成推送文档信息

```json
{
  "type": "finished",
  "docs": [
    // 结构同非流式输出的 docs 数组，包含联网搜索结果
  ],
  "message_id": "message-id"
}
```

---

## Java后端需要做的适配

| 适配项 | 是否必须 | 说明 |
|--------|----------|------|
| 1. DTO新增 `enable_internet_search` 字段 | ✅ 必须 | 从前端透传参数到模型端 |
| 2. 透传流式 `route_reasoning` 事件 | ✅ 必须 | 模型端已经推送状态事件，直接透传给前端即可 |
| 3. 处理 `docs` 中的 `internet_search` 类型 | 可选 | 如果需要前端特殊样式展示，可以区分，否则原样透传即可 |

---

## 测试建议

| 测试场景 | 预期结果 |
|----------|----------|
| 不传 `enable_internet_search` (默认 `null`)，知识库有结果 | 不触发联网搜索，正常返回 |
| 不传 `enable_internet_search`，知识库无结果，全局配置开启 | 触发联网搜索，返回搜索结果在 `docs` 中 |
| `enable_internet_search = true` | 总是触发联网搜索，融合知识库+搜索结果 |
| `enable_internet_search = false` | 永远不搜索，即使知识库无结果也不搜索 |

---

## 全局配置（模型端运维关注）

在模型端 `params/kb_config.yaml` 中已配置：

```yaml
INTERNET_SEARCH:
  enable: true
  api_url: "https://cloud-iqs.aliyuncs.com/search/unified"
  api_token: "your-api-token"
  engine_type: "CNLiteBasic"
  num_results: 5
  time_range: "NoLimit"
  max_concurrent: 10
  max_retries: 2
```

---

## 变更记录

| 日期 | 版本 | 变更 | 作者 |
|------|------|------|------|
| 2026-10-21 | v1.0 | 新增联网搜索功能，新增参数 `enable_internet_search` | hetao |

---

## 相关代码（模型端）

- 新增模块：`server/internet_search/aliyun_search.py`
- 主流程修改：`server/chat/multi_knowledge_base_chat.py`
- 配置：所有环境 `params/**/kb_config.yaml`
