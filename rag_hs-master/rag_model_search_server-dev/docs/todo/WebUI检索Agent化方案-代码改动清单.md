# WebUI 检索 Agent 化 · 代码改动清单

> 配套主文档：《WebUI检索Agent化方案-调用开源Agent.md》§3 各层改动清单的代码级明细。
> 日期：2026-10-20

---

## 1. Python 模型服务（rag_model_search_server）

### 1.1 新增依赖

- `qwen-agent`（独立包，不触碰 vendored langchain-ChatGLM fork）

### 1.2 新增模块 `server/chat/agent/`

| 文件 | 内容 |
|---|---|
| `tools.py` | 5 个工具，均为 `qwen_agent.tools.base.BaseTool` 子类薄包装（每个 ~20 行），内部转调现有函数： |
| | • `kb_vector_search(query, kb_ids)` → `_search_milvus_sync`（Milvus 向量路，含 SCORE_THRESHOLD 过滤） |
| | • `kb_hybrid_search(query, kb_ids, keywords)` → MemPalace client `GET :8777/search`（向量+BM25+章节水合） |
| | • `code_search(query, project)` → dual-mcp client `POST :9789/query`（含 `_resolve_project` 三级解析） |
| | • `kg_lookup(entity, direction)` → MemPalace `GET /kg`（实体关联扩展） |
| | • `doc_fetch(filename)` → `get_doc_by_filename`（定向精读全文） |
| `assistant.py` | Qwen-Agent `Assistant` 实例化：模型配置指向 DashScope compatible-mode（deepseek-v4-pro），`max_iterations=4~6`，单工具 `asyncio.wait_for` 超时，总 deadline 60s |
| `prompt.py` | system prompt：工具选择策略（精确术语→hybrid_search；需全文→doc_fetch；代码问题必调 code_search）、终止条件、引用格式约定 |
| `sse_bridge.py` | 框架流式事件 → 现有 SSE 协议映射（见 §1.5） |
| `postprocess.py` | 跨轮工具返回 chunks 汇总 → `_dedupe_docs` 去重 → `qwen3-rerank` 对原始 query 精排取 top_n → 生成现有格式 docs 事件（file/page/relevance_score/doc_type/used） |

### 1.3 修改 `server/chat/multi_knowledge_base_chat.py`

- `_route_and_rewrite` 输出 schema 增加 `complexity: simple|complex` 字段（同一次 qwen-flash 调用，无新增 LLM 请求）；
- 恢复启用被注释禁用的 `sub_queries`（L966-968），作为 Agent 首轮规划参考输入（而非直接检索）；
- complex 分支调用 `agent/assistant.py`；simple / `agent_mode=off` 分支走现有固定管线，代码路径不变。

### 1.4 配置 `params/*/kb_config.yaml`

```yaml
AGENT_ENABLED: true          # 总开关（可按 KB/用户灰度）
AGENT_MAX_ITERATIONS: 6      # 迭代上限
AGENT_DEADLINE_S: 60         # 总时长上限
AGENT_TOOL_TIMEOUT_S: 10     # 单工具超时
```

### 1.5 SSE 协议增量扩展（老事件全保留）

```json
{"type":"tool_call",    "tool":"kb_hybrid_search", "args_digest":"kb=X, q=DDR_PHY_CTL", "iteration":2}
{"type":"tool_result",  "tool":"kb_hybrid_search", "count":12, "elapsed_ms":340}
{"type":"agent_done",   "iterations":3, "tool_calls":6, "degraded":false}
```

事件映射关系：

| Qwen-Agent 流式事件 | 现有 SSE 协议 |
|---|---|
| function_call | `{type:"tool_call"}`（新增） |
| function_result | `{type:"tool_result"}`（新增） |
| 中间思考文本 | `{type:"route_reasoning"}`（复用） |
| 最终答案 token | `{type:"answer"}`（复用） |
| 证据后处理完成 | `{docs:[...]}`（复用） |
| 流结束 | `event: done`（复用） |

---

## 2. Quarkus 后端（rag_backend_server）

- `ModelKnowledgeBaseChatRequest`：增加 `agent_mode` 字段，`SendMessageCommandService.callModel()` 透传给 `/kw/chat/multi_knowledge_base_chat`；
- SSE 中继管线（`SendMessageCommandService.stream()`）：**无白名单，默认透传已核实**——`ChatDocsEventTransformer` 仅拦截含 `docs` 数组的载荷、`ChatAnswerImageRewriter` 仅处理 `type=answer`、`SensitiveAnswerScanner` 仅提取 answer 值，其余 chunk 经 `sse.newEventBuilder().data(chunk)` 原样包发。`tool_call`/`tool_result`/`agent_done` 预期零改动自动透传，仅需回归验证；
- 唯一新增代码：在 `stream()` 管线中识别 `agent_done` 载荷并提取 `iterations`/`tool_calls`/`degraded`，传入 `complete()` 落库（作为 JSON 附在 ASSISTANT 消息实体上），供 `GET /chat/messages` 历史回放与前端 `hydrateStoredAssistantContent` 使用；
- 落库语义确认：新事件原文会随 `buffer::append` 进入 ASSISTANT 原文（历史回放时轨迹可一并还原，与前端按原始 SSE chunk 回放的机制自洽）；若不希望入库，需在 buffer 之前按事件类型过滤（反而要新增识别代码）——默认选择入库。

---

## 3. Vue 前端（rag_front_server）

- `src/services/chat.service.ts`：
  - `chatSendMessage` 请求体增加 `agentMode: 'auto'|'on'|'off'`；
  - `ChatStreamEvent` 联合类型增加 `tool_call` / `tool_result` / `agent_done` 分支，`applyChatStreamEvent()` 对应处理；
- `ChatPage.vue` / `QAPage.vue` / `FloatingChatBot.vue`：设置区新增 Agent 模式开关（UI 对齐现有 `deepThinking` 开关）；
- 检索轨迹展示（二选一）：
  - **最省**：`tool_call`/`tool_result` 映射进现有 route_reasoning 面板渲染，零新组件；
  - **推荐**：新增 `src/components/frontend/AgentTraceTimeline.vue`（可折叠时间线：工具名 + 参数摘要 + 命中数 + 耗时，按 iteration 分组逐条追加）；
- 不动：引用面板（DocumentPreviewDrawer / chat-docs）、会话管理、`bgStreams` 后台续流 Map；
- 重构建议：把三处复制的聊天流逻辑抽为 `src/composables/useChatStream.ts`，Agent 事件只在该 composable 内处理一次。
