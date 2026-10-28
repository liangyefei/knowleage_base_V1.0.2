# WebUI 检索流程 Agent 化方案 —— 直接调用开源 Agent

> 范围：在现有 WebUI（Vue3 前端）检索/问答链路中增加 Agent 能力。
> 核心约束：**不自研 Agent 循环**（规划、工具调用、反思、终止判断均由开源 Agent 运行时提供），只做工具注册 + 接入适配。
>
> 现状链路：Vue 前端 `POST /chat/messages/send`(SSE) → Quarkus 透传（鉴权/敏感词/落库/docs改写） → Python 模型服务 `multi_knowledge_base_chat` 固定管线（分诊路由 → 三路并发检索 Milvus‖dual-mcp‖MemPalace → RRF → 去重 → rerank → 预算拼接 → 流式生成）。
>
> 日期：2026-10-20

---

## 1. 方案选型：调用开源 Agent 的两种形态

| 形态 | 代表开源项目 | 集成方式 |
|---|---|---|
| **A. 平台服务型** | Dify（Agent 节点）、FastGPT、RAGFlow-Agent | 独立部署一套 Agent 平台，前端/后端调它的 chat API；检索能力用平台的 HTTP 插件或 MCP 重新接入 |
| **B. 库嵌入型** | **Qwen-Agent**（Apache 2.0）、LangGraph `create_react_agent`（MIT） | 在现有 Python 模型服务进程内 `import` 开源 Agent 运行时，注册工具 + system prompt，Agent 循环全部由框架执行 |

### 1.1 选型结论：形态 B，首选 Qwen-Agent `Assistant`

理由（B 与现有流程匹配度更高）：B 在 3 跳链路内进程插入，前端/Quarkus/SSE 协议/检索函数全部原位复用，异常可 fail-open 回退固定管线；A 需迁移会话体系、重建治理层、转换 SSE 双协议，且成为入口单点，其优势（可视化编排界面、插件生态）属于平台化运营价值，与"在现有 WebUI 检索流程内加 Agent"的目标不符。具体到框架选 Qwen-Agent：

1. **模型栈同源**：现有 LLM 全部走 DashScope compatible-mode（qwen-flash 路由 / deepseek-v4-pro 生成），Qwen-Agent 对 Qwen 系 function-calling 调优最好；
2. **许可证干净**：Apache 2.0，无 Dify 式附加条款；
3. **MCP 前瞻**：Qwen-Agent 原生支持 MCP client，未来 `code_search`/`kg_lookup` 可直接挂载现有 `rag_mcp_server` 的 `:9800/mcp` 工具，省掉自包装。

备选：若后续升级 LangChain 主版本，`langgraph.prebuilt.create_react_agent` 是同等生态位选择，接入代码结构一致。

与 ZCode、Hermes Agent、Pi、OpenCode 等开源 Agent 及库类框架的详细对比，见配套文档《Agent框架选型-为什么选Qwen-Agent.md》。

---

## 2. 增加 Agent 后的检索流程

### 2.1 全链路总览

```
Vue 前端（ChatPage，新增 agentMode: auto|on|off，其余不动）
  │ POST /chat/messages/send  (SSE，协议向后兼容)
  ▼
Quarkus（完全不动：鉴权、敏感词、落库、docs改写、图片签名URL、SSE透传）
  │ POST /kw/chat/multi_knowledge_base_chat (+ agent_mode)
  ▼
Python 模型服务
  │
  ├─ ① 问候闸门（现有 _is_greeting，不动）──► 纯 LLM 直答
  │
  ├─ ② 分诊（现有 _route_and_rewrite 升级输出 complexity 字段，qwen-flash）
  │     ├─ simple / agent_mode=off ──► 【现有固定管线原样跑】
  │     │     三路并发(Milvus‖dual-mcp‖MemPalace)→RRF→去重→rerank→拼接→生成
  │     │     （存量 90% 流量走这里，P95 延迟零变化）
  │     │
  │     └─ complex / agent_mode=on ──► ③
  │
  ├─ ③ 调用开源 Agent（Qwen-Agent Assistant，进程内，非独立服务）
  │     注册工具（全部是现有函数的薄包装）：
  │       • kb_vector_search(query, kb_ids)          → 现有 _search_milvus_sync（Milvus 向量路）
  │       • kb_hybrid_search(query, kb_ids, keywords) → MemPalace /search（向量+BM25+章节水合）
  │       • code_search(query, project)               → dual-mcp /query（代码图谱 BM25）
  │       • kg_lookup(entity, direction)              → MemPalace /kg（实体关联扩展）
  │       • doc_fetch(filename)                       → get_doc_by_filename（定向精读全文）
  │
  │     ┌────────── Agent 内部循环（框架负责，不自研）──────────┐
  │     │  LLM(deepseek-v4-pro, bind tools)                     │
  │     │    → 决定调哪个工具、传什么参数（可一轮并发多个）        │
  │     │    → 框架执行工具，结果作为 observation 回填对话        │
  │     │    → LLM 判断：证据够了吗？不够→换关键词/换工具再查      │
  │     │      （例：向量查不到寄存器名 → 改走 kb_hybrid_search   │
  │     │        的 BM25；查到符号缺上下文 → doc_fetch 精读）      │
  │     │    → 够了 → 流式输出最终答案                            │
  │     │  护栏：max_iterations（框架参数，设 4~6）、              │
  │     │        单工具 asyncio.wait_for 超时、总 deadline 60s    │
  │     └────────────────────────────────────────────────────────┘
  │     期间框架流式吐事件：function_call / function_result / answer token
  │
  ├─ ④ 证据后处理（唯一新增的"检索侧"代码，复用现有函数）
  │     Agent 各轮工具返回的 chunks 统一收集 → _dedupe_docs 去重
  │     → qwen3-rerank 对【原始 query】精排 → 取 top_n
  │     → 生成现有格式的 docs 事件（file/page/relevance_score/doc_type）
  │     ※ 保证：即使 agent 查了 6 轮，前端引用面板拿到的仍是
  │       和今天完全一样的结构化 docs，引用 UI 零改动
  │
  └─ ⑤ SSE 事件映射层（框架事件 → 现有协议）
        function_call   → {type:"tool_call",   tool, args_digest}     [新增]
        function_result → {type:"tool_result", tool, count, elapsed}  [新增]
        中间思考文本     → {type:"route_reasoning"}                   [复用现有]
        最终答案 token   → {type:"answer"}                            [复用现有]
        证据后处理完成   → {docs:[...]}                               [复用现有]
        流结束          → event: done                                [复用现有]
```

### 2.2 分步说明

| 步骤 | 归属 | 说明 | 改动量 |
|---|---|---|---|
| ① 问候闸门 | 现有代码 | `_is_greeting` 命中直接走纯 LLM，不进 Agent | 不动 |
| ② 分诊 | 现有代码升级 | `_route_and_rewrite`（qwen-flash）输出 schema 增加 `complexity: simple\|complex`；可恢复启用目前被注释禁用的 `sub_queries`（multi_knowledge_base_chat.py L966-968）作为 Agent 首轮参考 | prompt + 1 字段 |
| ③ Agent 循环 | **Qwen-Agent 框架** | 工具选择、多轮补查、终止判断全部由框架内 LLM function-calling 驱动 | 工具注册 + prompt |
| ④ 证据后处理 | 新增胶水 | 跨轮 chunks 汇总 → `_dedupe_docs` → `qwen3-rerank` → docs 事件；引用体验与现状完全一致 | ~100 行 |
| ⑤ SSE 映射 | 新增胶水 | Qwen-Agent 流式事件翻译为现有 SSE 协议 + 2 个新事件类型，老前端不受影响（新事件可忽略） | ~100 行 |

### 2.3 具体示例（与现有固定管线的差别）

问题：**"B 芯片 DDR 初始化失败可能和哪些寄存器配置有关？对应驱动代码在哪？"**

**现有固定管线**（单趟，原始 query 检索一次）：向量检索对"寄存器配置"这类精确 token 召回弱，代码路和文档路各自命中零散结果，rerank 后拼进上下文，答案常常泛泛而谈。

**Agent 流程**（框架自主循环，典型 3 轮）：

1. **第 1 轮**：Agent 并发调 `kb_vector_search("B芯片 DDR 初始化流程")` + `code_search("DDR init driver")` → 拿到初始化流程文档 + 驱动符号列表；
2. **第 2 轮**：Agent 从文档中发现关键寄存器名（如 `DDR_PHY_CTL`），判断需要精确匹配 → 调 `kb_hybrid_search("DDR_PHY_CTL 配置")`（BM25 对寄存器名强）+ `doc_fetch(命中的芯片手册文件名)` 精读该章节；
3. **第 3 轮**：调 `kg_lookup("DDR初始化函数")` 确认调用链上下游 → 证据充分 → 流式生成"寄存器配置原因 + 对应代码位置"的结构化答案；
4. **④ 后处理**：三轮共 ~40 个 chunks 去重、rerank，top10 作为 docs 事件下发，前端引用面板照常展示。

第 2、3 轮的补查决策全部来自 Qwen-Agent 框架内 LLM 的 function-calling 循环；自研部分只提供了工具和一段 system prompt（写明"向量查不到精确术语时改用 hybrid_search；需要全文时 doc_fetch；代码问题必须调 code_search"等策略约定）。

### 2.4 流量分层（延迟保护）

| 问题类型 | 路径 | 延迟特征 |
|---|---|---|
| 问候/闲聊 | ① → 纯 LLM | 现状不变 |
| 简单事实型（存量 ~90%） | ② simple → 现有固定管线 | **P95 零变化**（只多一次分诊结构化输出字段，同一次 qwen-flash 调用） |
| 多跳/对比/汇总型 | ② complex → ③ Agent 循环 | 增加 2~5 次 LLM 决策 + N 次工具调用，总 deadline 60s 内 |
| Agent 异常/超时 | fail-open 降级 | 回退现有固定管线，与现网行为一致，SSE 下发 `degraded:true` |

成本控制：分诊用 qwen-flash（便宜快）；Agent 主 LLM 用 deepseek-v4-pro；工具执行全部复用现有检索客户端，无新增基础设施。

---

## 3. 各层改动清单

> 本节为功能性描述；涉及具体文件、模块与协议字段的代码级改动明细，见配套文档《WebUI检索Agent化方案-代码改动清单.md》。

### 3.1 Python 模型服务（改动最大）

- 引入 Qwen-Agent 开源库作为 Agent 运行时，以独立依赖方式接入，不触碰现有检索代码；
- 新增一个 Agent 模块，承载四类能力：
  1. **工具封装**：把向量检索、混合检索、代码检索、图谱查询、全文精读这五种现有检索能力注册为 Agent 可调用的工具，工具内部只是转调既有检索逻辑，不新写检索客户端；
  2. **Agent 实例配置**：指定决策模型、迭代上限、超时等护栏参数；
  3. **检索策略提示词**：约定工具选择策略（如精确术语优先混合检索、代码问题必查代码路）与终止条件；
  4. **协议适配与证据后处理**：把 Agent 执行过程事件翻译成现有 SSE 事件协议；Agent 多轮检索到的证据统一汇总、去重、按原始问题精排后，生成与现状格式一致的引用数据；
- 分诊路由升级：意图分类结果增加"简单/复杂"分级——简单问题照旧走现有固定管线（存量体验零变化），复杂问题才进入 Agent；
- 配置层新增 Agent 总开关与预算参数（最大迭代轮数、总时长上限等），支持按知识库/用户灰度；
- 对外 SSE 协议做**增量扩展**：新增工具调用、工具结果、执行摘要三类事件，老事件全部保留，未升级的前端可直接忽略新事件。

### 3.2 Quarkus 后端（改动极小）

先明确后端的角色：它是前端和模型服务之间的**中继站**——把前端的问题转发给模型服务，再把模型服务返回的 SSE 流转发给前端，转发途中做敏感词检查、引用链接改写、消息入库这几件治理工作。

增加 Agent 后，后端只有三点变化：

1. **转发开关**：前端新增"Agent 模式"开关，后端把这个开关值原样带给模型服务即可，像捎带一个字段，不做任何判断；
2. **新增消息自动通过，不用改代码**：Agent 运行时，模型服务返回的流里会多出三类新消息（工具调用、工具结果、执行摘要）。后端现有的转发逻辑是"只加工答案和引用这两类消息，其余一律原样放行"——已核实代码确实如此，所以三类新消息会自动到达前端，**转发代码不用改**，上线前回归验证一遍即可；
3. **唯一要新写的处理——给 Agent 记一笔账**：当流里的"执行摘要"消息经过时，把其中几个数字记下来（Agent 跑了几轮、调了几次工具、有没有降级），连同这条助手回复一起存进数据库。用途：用户回看历史会话时能看到这条回答的产生过程，运营侧能统计 Agent 的使用率和降级情况。

**全部不变的**：敏感词扫描、引用链接改写、图片链接改写、消息入库流程——因为 Agent 最终给出的答案和引用数据与现状格式完全相同，这些治理逻辑照常覆盖。

### 3.3 Vue 前端（改动小）

- 聊天设置区新增 Agent 模式开关（自动/开启/关闭），聊天流解析增加三类新事件的识别；
- 新增可折叠的执行轨迹时间线（工具名、参数摘要、命中数、耗时，按轮次分组）；最省做法是复用现有"路由思考"面板渲染，零新组件；
- 引用面板、会话管理、后台续流机制不动。

---

## 4. 实施计划与工作量

| 阶段 | 内容 | 人日 |
|---|---|---|
| P1 | 工具包装 ×5 + Qwen-Agent Assistant 接入 + system prompt 初稿 | 3 |
| P2 | SSE 映射层 + 证据后处理 + 分诊 complexity 升级 | 3 |
| P3 | Quarkus 字段透传/落库 + 前端开关与轨迹面板 | 2 |
| P4 | 护栏/降级联调（故障注入：工具超时、LLM 无 function-calling、deadline 触发）+ 多跳问题评估集验证 | 2 |
| **合计** | | **~10 人日** |

验收要点：
1. `agent_mode=off` 时全链路行为与现网一致（回归）；
2. simple 问题 P95 延迟无回退；
3. 多跳评估集（20~50 题）上 Agent 路径答案完整率相对固定管线的提升；
4. 任一护栏触发时正确降级且不中断 SSE 流。
