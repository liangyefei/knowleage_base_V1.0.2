# RAG 问答接口 QPS 优化方案分析

> 基于压测报告汇总（10-10 / 10-12 / 10-13 / 10-14 四轮）与 `server/chat/multi_knowledge_base_chat.py` 及 `server/chat/pipeline/` 子包代码的联合分析。

---

## 一、压测结论速览

### 1.1 测试背景

- **被测接口链路**：前端 `chat/messages/send` → 平台服务（登录、创建会话）→ QA 服务 `/kw/chat/multi_knowledge_base_chat`（RAG 多库问答）。
- **架构组成**：QA 服务 = FastAPI(uvicorn 4 worker) + Milvus 向量检索 + dual-mcp 代码检索 + mempalace 逐字检索 + LAC 分词 + Reranker + 在线 LLM（DashScope）。
- **核心关注指标**：TTFT（首字符延迟，用户体感"卡顿"）、QPS、失败率。

### 1.2 关键数据汇总

| 轮次 | 并发 | 配置 | 成功 QPS | 失败数 | TTFT 均值 | TTFT P95 | 备注 |
|---|---|---|---|---|---|---|---|
| 10-10 | 100 | 基线 | 25.12 | 0 | 3693 ms | 8828 ms | 基线 |
| 10-10 | 150 | 基线 | 21.10 | 0 | — | — | |
| 10-10 | 180 | 基线 | 21.84 | 0 | — | — | |
| 10-10 | 200 | 基线 | 58.74 | **5741** | 6353 ms | 11183 ms | 大量失败，含失败 QPS 虚高 |
| 10-10 | 200 | 调优版 | 20.59 | 108 | — | — | 平台 CPU 调整 |
| 10-13 | 120 | 优化后 | 40.08 | 0 | 2734 ms | 4693 ms | |
| 10-13 | 150 | 优化后 | 39.63 | 0 | 3443 ms | 5400 ms | |
| 10-13 | 180 | 优化后 | 39.04 | 0 | 4185 ms | 6025 ms | |
| 10-13 | 200 | 优化后 | 53.68 | **4409** | 3632 ms | 5062 ms | 拐点 |
| 10-14 | 180 | 问答 4cpu | 38.02 | 0 | 4269 ms | 6201 ms | |
| 10-14 | 180 | 问答 6cpu | 40.92 | 9 | 3807 ms | 5652 ms | 6cpu 优于 4cpu |
| 10-14 | 200 | 问答 6cpu | 61.25 | **6205** | 3956 ms | 5789 ms | 健康探针报警 |
| 10-14 | 200 | 问答 8cpu | — | — | 4432 ms | 6220 ms | 8cpu 无明显提升 |

### 1.3 三条核心结论

1. **稳定承载区 ≤ 180 并发**：180 并发以内 0 失败，QPS 稳定在 38-41 区间；TTFT P95 在 4.7-6.2s。
2. **200 并发是性能拐点**：200 并发开始大量失败（4409-6205 条），问答 Pod 健康探针报警（见 `200并发-平台单pod1cpu-问答单pod6cpu-10-14/问答服务健康探针报警.png`）。8cpu 相比 6cpu 无明显改善 → **瓶颈不在 CPU 算力，在 I/O 串行与连接治理**。
3. **TTFT 偏高（3-6s）**：即便 120 并发 TTFT 均值仍 2.7s，P95 4.7s。主要消耗在「LLM 路由 + 三路检索 + LLM prefill」串行链路，而非 LLM 自身生成速度。

### 1.4 报错日志佐证

`10-13/150并发-10-13/对话服务报错后台日志.txt`：

```
WARNING server.memory.memmachine_client:search_user_memories - [memmachine] 检索失败（跳过注入）: All connection attempts failed
WARNING server.memory.memmachine_client:search_conversation_memories - [memmachine] 对话记忆检索失败（跳过注入）: All connection attempts failed
```

说明 **memmachine 长期记忆服务在高并发下连接被拒/超时**，虽 fail-open 不影响主链路，但每个请求都在重试连接，徒增延迟与日志开销。

---

## 二、代码瓶颈定位

> 文件路径以 `server/` 为根。

### 2.1 瓶颈清单（按优先级）

| 优先级 | 瓶颈 | 位置 | 影响 |
|---|---|---|---|
| P0 | dual_mcp 每次请求新建 httpx client | [dual_mcp.py:293](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L293) | 每请求 1 次 TLS 握手，连接池不复用 |
| P0 | 主 LLM 生成无超时保护 | [multi_knowledge_base_chat.py:951](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L951)、[L1043](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L1043) | LLM hang 时请求无限期挂起，拖垮 worker 连接数 → 200 并发拐点主因 |
| P1 | dual_mcp `/projects` 每请求查 | [dual_mcp.py:294](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L294) | 项目列表变化频率极低却每请求 1 次 RTT |
| P1 | dual_mcp `/query` timeout=15s 过长 | [dual_mcp.py:130](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L130) | 首次故障承担 15s 尾延迟，拖长三路 gather |
| P1 | 检索路无并发上限 | [multi_knowledge_base_chat.py:506](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L506) | N 库 × M query × 3 路子任务无信号量，突发打爆下游 |
| P2 | `build_files_version_map` 三路重复调用 | [milvus_retrieval.py:85](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py#L85)、[dual_mcp.py:354](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L354)、[mempalace_retrieval.py:141](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py#L141) | 三路各自查版本映射，竞态窗口内可能 3 次 DB 查询 |
| P2 | 主 LLM 生成无全局并发限制 | [multi_knowledge_base_chat.py:951](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L951) | 无信号量，高并发打爆 LLM provider（DashScope 限流） |
| P2 | ~~RAG_CONTEXT_BUDGET=40960 过大~~ | [server_config.yaml:159](file:///d:/Code/haisi/haisi_rag_models_server/params/server_config.yaml#L159) | 全环境已同步 8192；经评估不再下调（见 §3.3 / §7.8） |
| P3 | ~~embedding 基类每次新建 client~~ | [embedding_workers/base.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/base.py) | ✅ 已完成：懒初始化单例 + 连接池收窄 + wait_for 兜底（见 §7.10） |
| P3 | ~~markdown_index 全量 clear~~ | [markdown_index.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/markdown_index.py) | ✅ 已完成：OrderedDict + move_to_end + popitem LRU 淘汰（见 §7.10） |
| P3 | ~~模板/client 缓存无淘汰~~ | [route_llm.py:14](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/route_llm.py#L14)、[openai_clients.py:12](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py#L12) | 经评估无需实施：key 固定 1-2 个（见 §7.9） |

### 2.2 P0 瓶颈详解

#### P0-1：dual_mcp 每次新建 httpx client

[dual_mcp.py:293-294](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L293)：

```python
from server.utils import get_httpx_client
async with get_httpx_client(use_async=True, timeout=15) as client:
    r = await client.get(f"{base}/projects", timeout=5)
```

[utils.py:628-629](file:///d:/Code/haisi/haisi_rag_models_server/server/utils.py#L628) 中 `get_httpx_client` 是**工厂函数**，每次 `return httpx.AsyncClient(**kwargs)`，`async with` 退出即关闭 → 连接池、TLS 会话全部丢弃。

**对比**：同目录的 `lac_keywords.py:11`、`mempalace_retrieval.py:19`、`memmachine_client.py:37` 都已用模块级单例 client 复用连接池，唯独 dual_mcp 路遗漏。

#### P0-2：主 LLM 生成无超时保护

[multi_knowledge_base_chat.py:951](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L951)：

```python
stream_resp = await openai_client.chat.completions.create(
    model=..., messages=..., stream=True
)   # ← 无 timeout，无 asyncio.wait_for
```

**对比**：路由 LLM 有双重保护（[route_llm.py:86](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/route_llm.py#L86) `timeout=10` + [multi_knowledge_base_chat.py:158](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L158) `wait_for(timeout=15)`）。主生成 LLM 是 TTFT 与总耗时的最大贡献者，却**裸奔无超时**。

**后果**：200 并发下 DashScope 偶发抖动 → 部分 stream 请求 hang → uvicorn worker 连接占满 → 后续请求排队 → 健康探针超时 → Pod 被重启 → 雪崩。这与压测中 200 并发「失败 6205 + 健康探针报警」现象完全吻合。

### 2.3 已良好优化的点（保持现状）

| 项 | 位置 | 说明 |
|---|---|---|
| 路由 LLM 与 LAC 并行 | multi_knowledge_base_chat.py:400-404 | `create_task` 同时调度 |
| 记忆检索全程并行 | multi_knowledge_base_chat.py:336-337 | `mem_task` 与 route/retrieve 并行 |
| 三路检索并发 | multi_knowledge_base_chat.py:214-223 | `gather(return_exceptions=True)` fail-open |
| 共享 embedding | multi_knowledge_base_chat.py:494-498 | 一次批量计算，三路复用 |
| 熔断器 | dual_mcp_circuit.py、mempalace_circuit.py | 连续失败 N 次 → cooldown 跳过 |
| 版本映射 TTL 缓存 | file_version.py:435-442 | 60s TTL |
| Jinja2 模板编译缓存 | route_llm.py:14-23 | 避免每请求 re-parse |
| RRF 内容哈希去重 | multi_knowledge_base_chat.py:559-578 | 跨路同内容块合并分数 |

---

## 三、优化方案

### 3.1 P0 方案（立即实施，解决 200 并发拐点）

#### 方案 P0-1：dual_mcp 改用模块级单例 client ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录)。

**目标**：消除每请求 1 次 TLS 握手，复用连接池。

**改动位置**：[server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py)、[tests/unit/test_version_tags_client_filter.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_version_tags_client_filter.py)

**最终实施做法**：仿照 [mempalace_retrieval.py:19-22](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py#L19) 的 `_mp_client` 模式，在 dual_mcp 模块顶部**直接创建**单例（非懒初始化）：

```python
import httpx

# 模块级 AsyncClient 单例：跨请求复用连接池，省每请求 1 次 TLS 握手；进程退出时由 httpx 自动清理。
# 与 mempalace_retrieval._mp_client / lac_keywords._lac_client 一致的模式。
# timeout=15s 与原 get_httpx_client(timeout=15) 一致；/projects(5s)、/query(15s) 单请求级 timeout 在调用处保留。
_dual_mcp_client = httpx.AsyncClient(
    timeout=httpx.Timeout(15.0),
    limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
)
```

`_dual_mcp_search` 内将 `from server.utils import get_httpx_client` + `async with get_httpx_client(use_async=True, timeout=15) as client:` 删除，改为 `client = _dual_mcp_client`，后续逻辑（`/projects` → `_resolve_project` → `_query_project` → 熔断记录）完全不变。

**对原设想的修正（慎重分析后）**：

| 原设想 | 实际实施 | 理由 |
|---|---|---|
| 懒初始化单例 + 双重检查锁 | 直接模块级创建 | mempalace/lac 均为直接模块级创建，对齐同构模块风格；无并发竞态，无需锁 |
| 单例配 proxy（保留 get_httpx_client 的 proxy 逻辑） | 不配 proxy | mempalace/lac 单例均未配 proxy；dual-mcp 是 k8s 内网服务，内网不走代理 |
| `timeout=httpx.Timeout(15.0, connect=3.0)` | `timeout=httpx.Timeout(15.0)` | 与 mempalace 风格一致，不单独设 connect timeout |

**不变项（契约保持）**：

- **失败语义**：任何异常仍降级为空列表，不中断主链路
- **熔断器调用时机**：`/projects` 故障 → `_dual_mcp_record_failure`；`/query` 异常 → failure；成功 → success
- **timeout 语义**：client 级 15s、`/projects` 单请求 5s、`/query` 单请求 15s 全部保留
- **`_query_project` 签名**：仍接收 client 参数，调用方传 `_dual_mcp_client`
- **编排层影响**：[multi_knowledge_base_chat.py:218](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L218) `_dual_mcp_search(sq, recall_k, kb_name, version=version, tags=tags)` 调用方式不变，函数签名不变，三路 gather 结构不变

**测试改动**：[test_version_tags_client_filter.py:91](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_version_tags_client_filter.py#L91) `_patch_dual_mcp_http` 改为 `mocker.patch.object(dual_mcp._dual_mcp_client, "get", ...)`，对齐 mempalace 测试方式。

**验证结果**：`py_compile` 通过；`pytest tests/unit/test_version_tags_client_filter.py` 10 passed（docker `haisi_rag_models_server_dev` 容器内）；Pylance 诊断无错误。

**预期收益**：省每请求 ~30-100ms TLS 握手 + 连接复用后 RTT 降低。

#### 方案 P0-2：主 LLM 生成加超时保护 ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录)。

**目标**：防止 LLM hang 拖垮 worker，消除 200 并发雪崩。

**改动位置**：[server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py)

**根因**：[openai_clients.py:20](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py#L20) `AsyncOpenAI(api_key=..., base_url=...)` 创建时未传 timeout，走 openai SDK 默认（600s）；主生成 `completions.create` 无 `timeout=` 参数也无 `wait_for`，而路由 LLM 有双重保护（`timeout=10` + `wait_for(15)`）。200 并发下 DashScope 偶发 hang → stream 无限阻塞 → worker 连接占满 → 健康探针超时 → Pod 重启 → 雪崩。

**三层超时保护**（Python 3.10 用 `asyncio.wait_for`，不能用 3.11+ 的 `asyncio.timeout`）：

| 层级 | 阈值 | 环境变量 | 实现 | 作用 |
|---|---|---|---|---|
| 流式建立 | 30s | `HAISI_LLM_STREAM_CONNECT_TIMEOUT` | `wait_for` 包 `create(stream=True)` | 防 DashScope 连接建立/prefill 排队 hang |
| chunk idle | 60s | `HAISI_LLM_CHUNK_IDLE_TIMEOUT` | `wait_for` 包 `stream_resp.__anext__()` | 防生成中途不吐 token hang（**雪崩主因**） |
| 非流式整体 | 120s | `HAISI_LLM_NON_STREAM_TIMEOUT` | `wait_for` 包 `create()` | 非流式一次性 await 兜底 |

**做法（流式）**：[multi_knowledge_base_chat.py:958](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L958) `_stream_llm_response`

```python
# 1) 建立超时
try:
    stream_resp = await asyncio.wait_for(
        openai_client.chat.completions.create(..., stream=True),
        timeout=_LLM_STREAM_CONNECT_TIMEOUT,
    )
except asyncio.TimeoutError:
    err_handled_setter()
    yield json.dumps({"error": "大模型响应建立超时，请重试", "message_id": message_id}, ...)
    return

# 2) chunk idle 超时：async for 改为 __anext__ + wait_for
while True:
    try:
        chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=_LLM_CHUNK_IDLE_TIMEOUT)
    except StopAsyncIteration:
        break
    except asyncio.TimeoutError:
        err_handled_setter()
        try:
            await stream_resp.aclose()   # 释放底层连接，防泄漏
        except Exception:
            pass
        yield json.dumps({"error": "大模型生成超时，请重试", "message_id": message_id}, ...)
        return
    # 原 chunk 处理逻辑不变
```

**做法（非流式）**：[multi_knowledge_base_chat.py:1085](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L1085) `_non_stream_llm_response`

```python
try:
    response = await asyncio.wait_for(
        openai_client.chat.completions.create(...),
        timeout=_LLM_NON_STREAM_TIMEOUT,
    )
except asyncio.TimeoutError:
    err_handled_setter()
    yield json.dumps({"error": "大模型响应超时，请重试", "message_id": message_id}, ...)
    return
```

**关键设计决策**：

1. **为何用 `__anext__` + `wait_for` 而非 `asyncio.timeout`**：项目 Python 3.10.6（[Dockerfile-dev:2](file:///d:/Code/haisi/haisi_rag_models_server/docker/Dockerfile-dev#L2)），`asyncio.timeout` 上下文管理器需 3.11+。`wait_for` 从 3.8 起可用，兼容性最佳。
2. **为何 `async for` 改 `while + __anext__`**：`async for` 无法对单个 chunk 获取做超时——迭代协议不暴露每步 await 点。改 `__anext__` 后每个 chunk 独立 `wait_for`，超时精确到"两个 chunk 之间"。
3. **为何 chunk idle 用 60s 而非更短**：深度思考模式（`deep_think=True`）prefill + 思考期较长，但思考期间会持续吐 `reasoning_content` chunk，正常 idle < 1s。60s 是 100 倍冗余，能容忍 DashScope 偶发卡顿，同时防止真正 hang 无限占用 worker。配合 P0-3（LLM 并发信号量，后续）可进一步限制同时在飞请求数。
4. **为何超时后 `aclose` 流**：`wait_for` cancel `__anext__` 后，底层 httpx 连接可能处于不确定状态，`stream_resp.aclose()` 释放连接回池，防泄漏。
5. **为何沿用 `err_handled_setter()`**：与现有 `except Exception` 分支（[L998](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L998)）行为一致，确保上层 `ctx.err_handled=True` 被设置，避免重复错误响应。

**契约保持（回归验证点）**：

- `_stream_llm_response` / `_non_stream_llm_response` 函数签名不变，调用方无需适配
- 正常路径（无超时）的 chunk 处理、`full_response` 累积、`[timing]` 日志、记忆抽取、unanswerable 检测逻辑全部不变
- 超时降级走与原 `except Exception` 一致的 `err_handled_setter()` + yield error 模式
- 错误消息中文化（"大模型响应建立超时/生成超时/响应超时，请重试"），区别于原 `str(e)` 裸输出

**验证结果**：`py_compile` 通过；`pytest test_version_tags_client_filter + test_verdiff_unfound_no_placeholder + test_chat_tags_pipeline` → **17 passed**（docker `haisi_rag_models_server_dev` 容器，`--no-cov`）；Pylance 诊断无错误。现有测试均 `patch.object(mod, "_stream_llm_response", ...)` 绕过真实实现，改动不影响。

**预期收益**：200 并发下偶发 LLM hang 不再无限占用 worker，失败率从 6205/17148（36%）预期降至 < 1%，消除健康探针报警雪崩。

#### 方案 P0-3：主 LLM 全局并发信号量 ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录) §7.3。

**目标**：限制同时在飞的主 LLM 请求数，防 DashScope 批量 429 + 防 worker httpx 连接池占满。

**改动位置**：[server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py)

**实现**（模块级单例 + `async with` 包裹）：

```python
# L88-94 模块级信号量（每 worker 独立，默认 64，env 可覆盖；max(1,...) 防误配 0/负值永久阻塞）
_LLM_CONCURRENCY = asyncio.Semaphore(
    max(1, int(os.environ.get("HAISI_LLM_MAX_CONCURRENCY", "64")))
)

# _stream_llm_response 内：信号量覆盖 create + chunk 循环（整段流式期间持有 DashScope 连接）
# t4 在 async with 之前，TTFT(t5-t4)/stream_total(t6-t4) 含 acquire 等待（用户感知口径，
# t4 不改）；acquire_wait 日志（>0.05s 才记）分离排队分量，压测时定位瓶颈来源
async with _LLM_CONCURRENCY:
    _sem_wait = time.time() - t4
    if _sem_wait > 0.05:
        logger.info(f"[timing] llm_sem_acquire_wait: {_sem_wait:.3f}s{err_log_suffix}")
    stream_resp = await asyncio.wait_for(create(..., stream=True), timeout=30)
    while True:
        chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=60)
        ...
# 释放后再做记忆抽取 + yield docs

# _non_stream_llm_response 内：信号量仅包 create（返回即释放 DashScope 连接）
async with _LLM_CONCURRENCY:
    _sem_wait = time.time() - t4
    if _sem_wait > 0.05:
        logger.info(f"[timing] llm_sem_acquire_wait: {_sem_wait:.3f}s{err_log_suffix}")
    response = await asyncio.wait_for(create(...), timeout=120)
```

**配置化**：环境变量 `HAISI_LLM_MAX_CONCURRENCY`（默认 64）；4 worker × 64 = 256 总并发容量。按 DashScope 实际 QPS 配额调整。未走 server_config.yaml，沿用 P0-2 的 env-var 风格保持同文件一致。

**预期收益**：超过信号量上限的请求排队而非全部涌向 DashScope，避免批量 429；每 worker httpx 连接占用上限 64 < httpx 默认连接池上限（100），防连接池占满。

### 3.2 P1 方案（短期实施，降低 TTFT）

#### 方案 P1-1：dual_mcp `/projects` 结果加 TTL 缓存 ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录) §7.5。

**目标**：项目列表变化频率极低（入库新项目才变），没必要每请求查。

**改动位置**：[server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) `_dual_mcp_search` L290-305

```python
_projects_cache: dict = {}   # kb_name -> (ts, projects_list)
_projects_ttl = 60.0

def _get_projects_cached(client, base: str) -> list:
    now = time.time()
    hit = _projects_cache.get("_all")
    if hit and now - hit[0] < _projects_ttl:
        return hit[1]
    # 未命中：首次请求拉取并缓存（全量项目，非 per-kb）
    ...
```

由于 `/projects` 返回**全量项目列表**（非 per-kb），可直接全局缓存一份，`_resolve_project` 从缓存读。

**预期收益**：省每请求 1 段 HTTP RTT（约 5-50ms，取决于 dual-mcp 部署位置）。

#### 方案 P1-2：收紧 dual_mcp `/query` timeout ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录) §7.5。

**目标**：三路 gather 的尾延迟由最慢者决定，dual_mcp 的 15s 是最大短板。

**改动位置**：[dual_mcp.py:130](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L130)

```python
# 原 timeout=15
r = await client.post(f"{base}/query", ..., timeout=httpx.Timeout(5.0, connect=2.0))
```

与 mempalace 的 3.0s 对齐量级。配合熔断器（连续失败 3 次 cooldown 60s），首次故障 5s 后即熔断，不再每请求等 15s。

**预期收益**：首次故障 TTFT 从 +15s 降至 +5s；熔断打开后 0s（跳过）。

#### 方案 P1-3：检索路加并发上限信号量 ✅ 已实施（2026-10-15）

**状态**：已完成代码改动 + 单测通过，详见 [§七、实施记录](#七实施记录) §7.5。

**目标**：防止 N 库 × M query × 3 路子任务突发打爆下游（Milvus/dual-mcp/mempalace）连接池。

**改动位置**：[multi_knowledge_base_chat.py:506](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L506)

```python
_RETRIEVE_SEMAPHORE = asyncio.Semaphore(
    int(os.environ.get("HAISI_RETRIEVE_MAX_CONCURRENCY", 128))
)

# _phase_retrieve 内
async with _RETRIEVE_SEMAPHORE:
    per_kbq_results = await asyncio.gather(...)
```

注意：信号量包**外层 gather**，内层三路 gather 仍并发。128 为经验值（4 worker × 32 = 128），按 Milvus/mempalace 连接池上限调整。

### 3.3 P2 方案（中期实施，消除重复与放大）

#### 方案 P2-1：`build_files_version_map` 编排层统一查询

**目标**：三路各自调用 → 编排层查一次注入。

**改动位置**：[multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) `_phase_retrieve` 开头

```python
# 在外层 gather 之前，统一查一次
cur_labels_map = {}
if ctx.version is None:
    for kb in valid_kbs:
        try:
            from server.knowledge_base.file_version import build_files_version_map
            cur_labels_map[kb] = set(l for l in build_files_version_map(kb).values() if l)
        except Exception:
            cur_labels_map[kb] = None

# 传入 _retrieve_one_kb_one_query → 三路复用
```

三路检索函数签名增加 `cur_labels` 参数，内部不再自查。

#### 方案 P2-2：RAG_CONTEXT_BUDGET 下调（经评估不实施，见 §7.8）

**目标**：原计划下调 prefill 预算以降 TTFT。

**现状（2026-10-15 修正）**：全 6 个环境配置（default/prod/release/test/devp/local）已手动同步为 `RAG_CONTEXT_BUDGET: 8192`（非原 40960）。且 [multi_knowledge_base_chat.py:659](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L659) `_rerank_by_budget = RAG_CONTEXT_BUDGET // 2000 = 4`，被 `RERANKER_TOP_N=10` 掩盖——budget ≤ 20000 时 rerank top_n 下限由 `RERANKER_TOP_N` 决定，**预算联动已失效**（详见 §7.8 评估）。

**原建议（已废弃）**：

```yaml
RAG_CONTEXT_BUDGET: 16384   # 原 40960 → 16384
```

**评估结论（§7.8）**：8192 已是合理平衡点，进 prompt ~11 块（CHUNK_SIZE=700，doc 8 + code 3），prefill ~8k chars 对多数 LLM 是甜区。再下调到 4096 只剩 ~5-6 块，相关块可能被挤出 prompt，准确率风险 > TTFT 收益。**不实施**。

**遗留技术债**：~~`_rerank_by_budget` 联动在当前配置下为死代码~~ → ✅ 已清理（2026-10-15，见 §7.8.1）：删除 `_rerank_by_budget` 变量与日志，`rerank_top_n` 简化为 `min(max(top_k, RERANKER_TOP_N), _RERANK_TOP_N_MAX)`。

### 3.4 P3 方案（择机实施，低优先级）

| 方案 | 位置 | 做法 |
|---|---|---|
| ~~embedding 基类单例 client~~ | embedding_workers/base.py | ✅ 已完成：懒初始化 `_aclient` + 连接池收窄（50/10）+ `wait_for` 兜底（见 §7.10） |
| ~~markdown_index LRU~~ | markdown_index.py | ✅ 已完成：`OrderedDict` + hit `move_to_end` + 超限 `popitem(last=False)`（见 §7.10） |
| ~~模板/client 缓存淘汰~~ | ~~route_llm.py:14、openai_clients.py:12~~ | ❌ 经评估无需实施：key 数量固定（模板 1 个 / client 2 个），无泄漏风险（见 §7.9） |

### 3.5 非 RAG 链路瓶颈（反馈给平台团队）

压测数据显示**平台服务**（登录、创建会话）在高并发下也成瓶颈：

- `result_stats.csv` 中 200 并发时 `[setup] create_conversation` 平均 9-12s，max 20-24s
- `[setup] login` 平均 10-13s，max 20-24s
- 这些是**每个虚拟用户 setup 阶段**的耗时，不直接计入 chat QPS，但会推迟用户进入正式压测，间接拉低有效并发

**建议反馈**：平台服务的登录与会话创建接口需独立优化（连接池、DB 索引、缓存 token），不属于 RAG 服务代码范围，但影响端到端 QPS。

---

## 四、实施优先级与预期收益

| 阶段 | 方案 | 预期 TTFT 收益 | 预期 QPS 收益 | 预期失败率改善 |
|---|---|---|---|---|
| **Phase 1**（1-2 天） | P0-1 + P0-2 + P0-3 | -200~500ms | +10-20% | 200 并发失败率 36% → < 1% |
| **Phase 2**（3-5 天） | P1-1 + P1-2 + P1-3 | -300~800ms | +5-10% | 首次故障尾延迟 15s → 5s |
| **Phase 3**（1 周） | P2-1 + P2-2 | -500~1000ms | +5% | — |
| **Phase 4**（择机） | P3 全部 | -50~100ms | 微增 | — |

**保守预估**：Phase 1-3 完成后，180 并发 TTFT P95 从 6.0s → 3.5s 左右，200 并发从拐点（失败 36%）→ 稳定承载（失败 < 1%），QPS 从 40 → 55-60。

---

## 五、验证方式

1. **复跑压测**：每完成一个 Phase，用相同 locust 脚本跑 120/150/180/200 并发，对比 TTFT/QPS/失败率。
2. **日志观测**：压测期间观察 `[timing]` 日志关键字：
   - `dual_mcp_projects` / `dual_mcp_query`（验证 P1-1/P1-2）
   - `llm_first_token` / `llm_stream_total`（验证 P0-2/P2-2）
   - `multi_kbq_search` / `reranker`（验证 P1-3/P2-2）
3. **熔断器状态**：观察 `[dual-mcp] 跳过检索（熔断中）` 与 `[mempalace]` 日志频率，判断下游健康度。
4. **健康探针**：200 并发下观察 Pod 健康探针是否仍报警（验证 P0-2 是否消除雪崩）。

---

## 六、附录

### 6.1 关键代码路径索引

| 模块 | 文件 |
|---|---|
| 问答入口 | [server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) |
| 路由 LLM | [server/chat/pipeline/route_llm.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/route_llm.py) |
| Milvus 检索 | [server/chat/pipeline/milvus_retrieval.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py) |
| dual-mcp 检索 | [server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) |
| mempalace 检索 | [server/chat/pipeline/mempalace_retrieval.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py) |
| LAC 分词 | [server/chat/pipeline/lac_keywords.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/lac_keywords.py) |
| Embedding | [server/chat/pipeline/embeddings.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/embeddings.py) |
| OpenAI client | [server/chat/pipeline/openai_clients.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py) |
| 熔断器 | [server/chat/pipeline/dual_mcp_circuit.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp_circuit.py)、[mempalace_circuit.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_circuit.py) |
| 服务器配置 | [params/server_config.yaml](file:///d:/Code/haisi/haisi_rag_models_server/params/server_config.yaml) |
| httpx 工厂 | [server/utils.py:628](file:///d:/Code/haisi/haisi_rag_models_server/server/utils.py#L628) |

### 6.2 压测数据来源

| 轮次 | 目录 |
|---|---|
| 10-10 基线 | `docs/todo/测试报告汇总/10-10/` |
| 10-12 | `docs/todo/测试报告汇总/10-12/` |
| 10-13 优化后 | `docs/todo/测试报告汇总/10-13/` |
| 10-14 CPU 调整 | `docs/todo/测试报告汇总/10-14/` |

各轮次下含 `summary_report.md`（TTFT 统计）、`result_stats.csv`（QPS/失败/分位）、`ttft_log_*.csv`（每请求明细）、`平台服务cpu及内存状态.png` / `问答服务cpu及内存状态.png`（资源监控）。

---

## 七、实施记录

### 7.1 P0-1：dual_mcp 连接池复用（2026-10-15 已完成）

**改动文件**：

| 文件 | 改动 |
|---|---|
| [server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) | 新增 `import httpx`；新增模块级单例 `_dual_mcp_client`（L19-26）；`_dual_mcp_search` 去掉 `async with get_httpx_client(...)`，改用 `_dual_mcp_client`（L301-328） |
| [tests/unit/test_version_tags_client_filter.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_version_tags_client_filter.py) | `_patch_dual_mcp_http` 改为 patch `_dual_mcp_client.get`（L91-99） |

**实施前 vs 实施后对比**：

```
实施前（每请求）：
  from server.utils import get_httpx_client
  async with get_httpx_client(use_async=True, timeout=15) as client:   ← 每次新建 AsyncClient + TLS 握手，退出即关闭
      r = await client.get(f"{base}/projects", timeout=5)
      ...

实施后（进程级单例）：
  client = _dual_mcp_client                                            ← 模块级单例，import 时创建一次，连接池跨请求复用
  r = await client.get(f"{base}/projects", timeout=5)
  ...
```

**设计决策记录**：

1. **为何不用懒初始化 + 双重检查锁**：项目内 `mempalace_retrieval._mp_client`、`lac_keywords._lac_client` 均为模块级直接创建的单例，dual_mcp 应对齐同构模块风格；模块级创建在 import 时完成，无并发竞态，无需锁，更简单。
2. **为何不配 proxy**：`get_httpx_client` 原本带 proxy 配置（绕过 localhost、读 `http_proxy/no_proxy` 环境变量），但 mempalace/lac 单例均未配 proxy。dual-mcp 是 k8s 内网服务（`http://localhost:9789` 兜底或集群 Service 名），内网不走代理，跟随此约定。
3. **为何不收紧 `/query` timeout**：收紧 `/query` timeout（15s → 5s）属于 P1-2 范畴，本次只做连接池复用，不动 timeout 语义，保持改动聚焦、行为可追溯。

**契约保持（回归验证点）**：

- `_dual_mcp_search` 签名不变，[multi_knowledge_base_chat.py:218](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L218) 调用方式不变
- 三路 gather（Milvus + dual_mcp + mempalace）结构不变
- 熔断器 `_dual_mcp_record_failure/success` 调用时机不变
- `/projects` 5s、`/query` 15s 单请求 timeout 不变
- 任何异常降级为空列表的 fail-open 语义不变

**验证**：

- `python3 -m py_compile` 两文件通过
- `pytest tests/unit/test_version_tags_client_filter.py` → **10 passed**（docker `haisi_rag_models_server_dev` 容器）
- Pylance 诊断无错误

**待验证（需复跑压测）**：

- 200 并发下 dual_mcp 路是否仍有 TLS 抖动
- `[timing] dual_mcp_projects` 日志的耗时分布是否下降
- 复跑 120/150/180/200 并发对比 TTFT/QPS/失败率（见 [§五、验证方式](#五验证方式)）

### 7.2 P0-2：主 LLM 生成超时保护（2026-10-15 已完成）

**改动文件**：

| 文件 | 改动 |
|---|---|
| [server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) | 顶部新增 3 个超时常量（L82-90，环境变量可覆盖）；`_stream_llm_response` 加建立超时 + chunk idle 超时（L958-1005）；`_non_stream_llm_response` 加整体超时（L1085-1100） |

**实施前 vs 实施后对比**：

```
实施前（流式，裸奔）：
  stream_resp = await openai_client.chat.completions.create(..., stream=True)   ← 无 timeout
  async for chunk in stream_resp:                                                ← 无 idle 超时，hang 无限阻塞
      ...

实施后（流式，三层保护）：
  try:
      stream_resp = await asyncio.wait_for(create(..., stream=True), timeout=30)  ← 建立超时
  except asyncio.TimeoutError: ...
  while True:
      try:
          chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=60)     ← chunk idle 超时
      except StopAsyncIteration: break
      except asyncio.TimeoutError: ... aclose + 降级 ...
```

**根因定位**：[openai_clients.py:20](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py#L20) `AsyncOpenAI` 创建未传 timeout → 走 SDK 默认 600s；主生成 `create` 无 `timeout=` 也无 `wait_for`，路由 LLM 有双重保护（`timeout=10` + `wait_for(15)`）唯独主生成裸奔。

**设计决策记录**：

1. **为何 `__anext__` + `wait_for` 而非 `asyncio.timeout`**：项目 Python 3.10.6（[Dockerfile-dev:2](file:///d:/Code/haisi/haisi_rag_models_server/docker/Dockerfile-dev#L2)），`asyncio.timeout` 需 3.11+。`wait_for` 从 3.8 起可用。
2. **为何 `async for` 改 `while + __anext__`**：`async for` 迭代协议不暴露每步 await 点，无法对单 chunk 获取做超时。改 `__anext__` 后超时精确到"两个 chunk 之间"——这正是 200 并发雪崩的 hang 点（create 已成功，生成中途卡住）。
3. **为何 chunk idle 60s**：深度思考模式思考期持续吐 `reasoning_content` chunk，正常 idle < 1s；60s 是 100 倍冗余，容忍偶发卡顿但不放任真正 hang。配合 P0-3 信号量（后续）可进一步限流。
4. **为何超时后 `aclose`**：`wait_for` cancel `__anext__` 后底层 httpx 连接可能脏，`aclose` 释放回池防泄漏。
5. **为何沿用 `err_handled_setter()`**：与现有 `except Exception` 分支行为一致，确保 `ctx.err_handled=True` 被设置。

**契约保持（回归验证点）**：

- `_stream_llm_response` / `_non_stream_llm_response` 函数签名不变
- 正常路径的 chunk 处理、`full_response` 累积、`[timing]` 日志、记忆抽取、unanswerable 检测不变
- 超时降级走与原 `except Exception` 一致的 `err_handled_setter()` + yield error 模式
- 错误消息中文化（"大模型响应建立超时/生成超时/响应超时，请重试"）

**影响面分析（review 核对）**：

- **调用方零改动**：[multi_knowledge_base_chat.py:874](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L874) `async for _chunk in _stream_llm_response(...)` / [L897](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L897) `_non_stream_llm_response(...)` 仅透传 yield 的 chunk，不假设 chunk 内容；超时降级 yield 的 `{"error":..., "message_id":...}` 格式与原 `except Exception` 分支完全一致 → 调用方无需改动。
- **err_handled 与 _safe_iterator 交互**：[L1300](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L1300) `_safe_iterator` 兜底分支 `if not ctx.err_handled` 才 yield error；超时降级路径已调用 `err_handled_setter()` 置 `ctx.err_handled=True` 并 yield error → 不会被 `_safe_iterator` 二次兜底，无重复 error 事件。
- **边界 case**：
  - 流式建立超时：`stream_resp` 未创建，无需 `aclose`（建立分支无 `aclose` 正确）。
  - chunk idle 超时（已吐部分 token）：`full_response` 部分内容不入记忆（`extract_and_store_async` 在 return 之后），与原 `except Exception` 行为一致，契约保持。
  - `aclose` 防 httpx 脏连接泄漏：`wait_for` cancel `__anext__` 后底层 httpx 连接可能半读，`aclose` 释放回池；`aclose` 本身套 try-except 防二次异常。
  - 深度思考 `reasoning_content` chunk 亦受 chunk idle 保护（在 `chunk.choices` 检查之后）；思考期正常 idle < 1s，60s 阈值是 100 倍冗余，不误杀。
  - `chunk.choices` 为空（role/keepalive chunk）：`continue` 跳过，不影响下一轮 idle 计时。

**验证**：

- `python3 -m py_compile` 通过
- `pytest test_retrieve_one_kb_p0_regression + test_p2_bugfixes + test_verdiff_unfound_no_placeholder + test_chat_version_filter + test_chat_tags_pipeline` → **21 passed**（`--no-cov`，docker `haisi_rag_models_server_dev` 容器，含 max 守卫 + acquire_wait 日志补强后复跑）
- Pylance 诊断无错误
- 现有测试均 `patch.object(mod, "_stream_llm_response", ...)` 绕过真实实现，改动不影响

**待验证（需复跑压测）**：

- 200 并发下健康探针是否仍报警（P0-2 应消除雪崩主因）
- `[timing] llm_stream_connect_timeout` / `llm_chunk_idle_timeout` / `llm_non_stream_timeout` 日志出现频率
- 复跑 200 并发对比失败率（预期 36% → < 1%）

### 7.3 P0-3：主 LLM 全局并发信号量（2026-10-15 已完成）

**改动文件**：

| 文件 | 改动 |
|---|---|
| [server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) | 模块顶部新增 `_LLM_CONCURRENCY` 信号量常量（L88-94，含 `max(1,...)` 误配守卫）；`_stream_llm_response` 的 create + chunk 循环包入 `async with`（L966-1049，入块记 `llm_sem_acquire_wait`）；`_non_stream_llm_response` 的 create 包入 `async with`（L1103-1134，同） |

**实施前 vs 实施后对比**：

```
实施前（无并发限制，200 并发全量涌向 DashScope）：
  stream_resp = await asyncio.wait_for(create(..., stream=True), timeout=30)
  while True: chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=60)

实施后（信号量限流，超出排队）：
  async with _LLM_CONCURRENCY:                  ← acquire（队列背压由 uvicorn limit_concurrency 兜底）
      stream_resp = await asyncio.wait_for(create(..., stream=True), timeout=30)
      while True: chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=60)
  # async with 退出即 release，所有路径（正常/超时/异常/GeneratorExit）均释放
```

**根因定位**：P0-2 消除了单请求 hang 无限占用，但未限制同时在飞的请求数。200 并发下 200 个 LLM 请求同时涌向 DashScope，可能触发 provider 侧批量 429；同时每请求占用 1 个 httpx 连接，4 worker × 50/worker 虽未超 httpx 默认池上限（100），但叠加上路由 LLM / 检索路连接，连接池排队加剧尾延迟。

**设计决策记录**：

1. **为何 `asyncio.Semaphore` 而非 `threading.Semaphore`**：主 LLM 生成在 async 上下文（`_stream_llm_response` / `_non_stream_llm_response` 均为 async generator），用 `asyncio.Semaphore` 才能非阻塞 await。VLM 限流用 `threading.Semaphore`（[processing_engine.py:92](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/processing_engine.py#L92)）因其跑在 `ThreadPoolExecutor` 同步线程池，场景不同。
2. **为何模块级单例**：对齐 [mempalace_retrieval.py:19](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py#L19) `_mp_client` / [lac_keywords.py:11](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/lac_keywords.py#L11) `_lac_client` 的模块级单例风格。Python 3.10.6 `asyncio.Semaphore` 不在 `__init__` 绑定 loop，首次 `acquire` 才 `get_running_loop()` 绑定，uvicorn 单 worker 单 loop，无跨 loop 问题。
3. **为何 `async with` 而非手动 acquire/release**：`async with` 的 `__aexit__` 保证所有退出路径释放——正常完成 / P0-2 超时降级 return / 未预期异常 / 客户端断开 `GeneratorExit` 均触发释放，无泄漏风险。手动 release 需在每条 return 路径补 release，易漏。
4. **为何流式信号量覆盖 create + chunk 循环**：DashScope 流式连接在 create 到 stream 结束期间持续占用（每 chunk 是同一连接），故信号量必须覆盖整段，release 早于 `extract_and_store_async`（记忆抽取不占 DashScope 连接）。非流式 create 返回即释放连接，故信号量仅包 create。
5. **为何不设 acquire 超时**：队列背压由 uvicorn `limit_concurrency`（[server_config.yaml](file:///d:/Code/haisi/haisi_rag_models_server/params/server_config.yaml) 默认 1000）兜底——超 1000 在飞 ASGI 层直接 503，信号量队列有上界，不会无限增长。P0-2 已保证每 holder 最大持有时间有界（非流式 120s / 流式 connect 30s + 各 chunk 间隔 ≤60s），队列必能排空。加 acquire 超时属过度工程，"快速排队"语义已满足。
6. **为何默认 64**：4 worker × 64 = 256 总并发容量，覆盖压测 200 并发；单 worker 64 < httpx 默认连接池上限 100，留余量。env 可覆盖便于按 DashScope 实际配额调优。
7. **为何仅主 LLM，不含路由 LLM**：路由 LLM（[route_llm.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/route_llm.py)）已有 HTTP `timeout=10` + `wait_for(15)` 双重保护且调用快（~1-2s），其并发由 uvicorn limit_concurrency 兜底。若压测显示路由 LLM 也触发 429，再纳入同一信号量（记为后续项）。

**契约保持（回归验证点）**：

- `_stream_llm_response` / `_non_stream_llm_response` 函数签名不变，调用方 [L874-919](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L874-L919) 零改动
- 正常路径的 chunk 处理、`full_response` 累积、`[timing]` 日志、记忆抽取、unanswerable 检测逻辑不变（仅缩进 +4）
- P0-2 的三层超时降级语义完全保留，`async with` 在超时 return 路径上经 `__aexit__` 正常释放信号量
- `yield` 在 `async with` 块内：客户端断开时 `GeneratorExit` 在 yield 点抛出，`__aexit__` 释放信号量（stream_resp 由 P0-2 的 aclose / openai SDK GC 处理）

**影响面分析（review 核对）**：

- **yield 在信号量持有期间**：流式 chunk yield 在 `async with` 块内，SSE 背压暂停时信号量持有——正确，DashScope 流式连接此时仍开。慢客户端持有信号量期间由 P0-2 chunk idle 60s 兜底（无 chunk 60s 即超时释放）。
- **与 P0-2 超时的交互**：P0-2 超时在信号量持有期间触发，`err_handled_setter()` + yield error + return，`async with __aexit__` 释放信号量，不会因超时泄漏信号量。
- **与 `_safe_iterator` 的交互**：信号量释放由 `async with` 保证，与 `ctx.err_handled` 无直接耦合；超时降级仍走 `err_handled_setter()` 置 `ctx.err_handled=True`，`_safe_iterator` 不二次兜底（与 P0-2 一致）。
- **路由 LLM 不受影响**：路由 LLM 用独立客户端 `_router_openai_client`，不在 `_LLM_CONCURRENCY` 作用域内。

**review 补强（2026-10-15，深度审查后追加）**：

对 P0-3 改动做完整 review，确认 5 条正确性验证通过 + 补 2 条 hardening：

*正确性验证（通过，无需改动）*：

1. **退出路径全覆盖**：`async with` 的 `__aexit__` 保证正常完成 / P0-2 建立超时 return / chunk idle 超时 return / 未预期异常 / 客户端断开 `GeneratorExit` 五条路径均释放信号量，无泄漏。
2. **无死锁**：单次 acquire、无嵌套 acquire，不存在自持锁等待。
3. **流式覆盖范围正确**：信号量覆盖 create + chunk 循环（DashScope 流式连接整段占用），release 早于记忆抽取。
4. **非流式覆盖范围正确**：仅包 create（返回即释放连接），release 后再处理响应。
5. **契约保持**：函数签名不变、调用方零改动、P0-2 超时降级语义保留。

*补强项（已实施）*：

6. **`max(1, ...)` 误配守卫**：原 `asyncio.Semaphore(int(env...))` 若误配 `HAISI_LLM_MAX_CONCURRENCY=0` 或负值，所有 acquire 永久阻塞（footgun）。加 `max(1, int(...))` 守卫，对齐 [reranker base.py:269](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker_workers/base.py#L269) `asyncio.Semaphore(max(1, self.max_workers))` 模式。
7. **`[timing] llm_sem_acquire_wait` 日志**：`t4` 在 `async with` 之前，故 TTFT（`t5-t4`）/ stream_total（`t6-t4`）/ non_stream（`t6-t4`）含信号量 acquire 等待——这是**用户感知口径**（用户实际等待 = 排队 + LLM，t4 位置不改）。但排队分量与 LLM 延迟混在一起无法分离，压测时无法定位"瓶颈在排队还是 LLM 本身"。补 `acquire_wait` 日志（>0.05s 才记，避免低负载刷屏）分离排队分量，直接支撑下方"待验证"的队列深度观测。

*既有问题（非 P0-3 引入，本次不处理）*：

- 客户端断开（`GeneratorExit` at yield）时 `stream_resp` 未显式 `aclose`——P0-2 的 `aclose` 仅在 chunk idle 超时分支，正常路径依赖 openai SDK GC。P0-3 不加剧（信号量经 `__aexit__` 正常释放）。后续可在 `async with` 外包 `try/finally` 显式 `aclose`，记为低优先项。

**验证**：

- `python3 -m py_compile` 通过
- `pytest test_version_tags_client_filter + test_verdiff_unfound_no_placeholder + test_chat_tags_pipeline` → **17 passed**（`--no-cov`，docker `haisi_rag_models_server_dev` 容器）
- Pylance 诊断无错误
- 现有测试均 `patch.object(mod, "_stream_llm_response", ...)` 绕过真实实现，信号量不被实际 acquire，改动不影响测试

**待验证（需复跑压测）**：

- 200 并发下 DashScope 是否仍出现批量 429（预期信号量限流后消除）
- 信号量等待队列深度（`[timing] llm_sem_acquire_wait` 日志已加，>0.05s 才记避免刷屏；压测时统计该日志频次/均值即可观测排队深度与瓶颈来源）
- 复跑 200 并发对比失败率（P0-2 + P0-3 叠加预期 < 1%）
- 调参 `HAISI_LLM_MAX_CONCURRENCY`（64 → 32/128）观察 DashScope 429 阈值

### 7.4 后续待实施项

| 项 | 状态 | 说明 |
|---|---|---|
| ~~P0-2 主 LLM 生成超时保护~~ | ✅ 已完成 | 见 §7.2 |
| ~~P0-3 主 LLM 全局并发信号量~~ | ✅ 已完成 | 见 §7.3 |
| ~~P1-1 dual_mcp `/projects` TTL 缓存~~ | ✅ 已完成（+ review 补强） | 见 §7.5 / §7.5.4 |
| ~~P1-2 dual_mcp `/query` timeout 收紧~~ | ✅ 已完成 | 见 §7.5 |
| ~~P1-3 检索路并发上限信号量~~ | ✅ 已完成 | 见 §7.5 |
| P2-1 `build_files_version_map` 编排层统一查询 | ✅ 已完成（+ review 补强） | 见 §7.6 / §7.6.1 |
| B1 reranker 并发信号量 + timeout | ✅ 已完成 | 见 §7.7 |
| B2 reranker 模块级单例 | ✅ 已完成 | 见 §7.7 |
| P2-2 RAG_CONTEXT_BUDGET 下调 | ❌ 经评估不实施 | 全环境已同步 8192，再下调准确率风险 > TTFT 收益（见 §7.8） |
| P2-2 技术债清理：删除 `_rerank_by_budget` | ✅ 已完成 | 见 §7.8.1 |
| C1 query embedding 全局并发信号量 | ✅ 已完成 | 见 §7.9 |
| C2 删除 `source_documents_meta` 死代码 | ✅ 已完成 | 见 §7.9 |
| P3-3 模板/client 缓存淘汰 | ❌ 经评估无需实施 | key 固定 1-2 个，无泄漏风险（见 §7.9） |
| P3-1 embedding 基类单例 client | ✅ 已完成 | 见 §7.10 |
| P3-2 markdown_index LRU | ✅ 已完成 | 见 §7.10 |
| A1 代码路 id() 去重 HttpReranker 失效 | ✅ 已完成 | 见 §7.11 |
| A2 dual_mcp /query 非 200 不触发熔断 | ✅ 已完成 | 见 §7.11 |
| B1 stream_resp 客户端断开连接泄漏 | ✅ 已完成 | 见 §7.11 |
| C1 openai_clients 连接池硬化 | ✅ 已完成 | 见 §7.11 |
| B3 Milvus search/query 默认 60s 超时 | ✅ 已完成 | 见 §7.12 |
| D1 history 截断原地突变污染记忆 | ✅ 已完成 | 见 §7.12 |
| C2 HttpReranker 每请求新建 AsyncClient | ✅ 已完成 | 见 §7.12 |
| B2+B4 route/lac/mem task 孤儿未取消 | ✅ 已完成 | 见 §7.12 |
| B5 route_llm 流式 resp 未 aclose | ✅ 已完成 | 见 §7.13 |
| A3 history budget 配置矛盾 | ✅ 已完成 | 见 §7.13 |
| A5 embedding 数量不校验致错位 | ✅ 已完成 | 见 §7.13 |
| C3 markdown_index 同步读阻塞 | ✅ 已完成 | 见 §7.13 |
| C4 dual_mcp/mempalace 同步 DB 阻塞 | ✅ 已完成 | 见 §7.13 |
| D2 History Jinja2 SSTI | ✅ 已完成 | 见 §7.13 |
| D3 batch_size 默认 32 超上限 | ✅ 已完成 | 见 §7.13 |

### 7.5 P1 批次实施（2026-10-15 已完成 + review 补强）

P1 三项降低 TTFT 批次：dual_mcp 路延迟优化（P1-1 缓存 + P1-2 timeout 收紧）+ 检索路并发限流（P1-3）。实施顺序 **P1-2 → P1-1 → P1-3**：P1-2 先降 gather 尾延迟以缩短 P1-3 信号量持有时间；P1-1 同文件连贯；P1-3 依赖前两者降延迟后容量更充裕。

#### 7.5.1 P1-2：收紧 dual_mcp `/query` timeout

**改动文件**：[server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) `_query_project` L134-145

```python
# 实施前：timeout=15（裸 float 作 total），三路 gather 最大短板
r = await client.post(f"{base}/query", ..., timeout=15)
# 实施后：total 5s + connect 2s，与 mempalace 3.0s 对齐量级
r = await client.post(f"{base}/query", ..., timeout=httpx.Timeout(5.0, connect=2.0))
```

**设计决策**：

1. **为何 5s**：与 mempalace `MEMPALACE_TIMEOUT=3.0s`（[server_loader.py:181](file:///d:/Code/haisi/haisi_rag_models_server/configs/server_loader.py#L181)）对齐量级；dual_mcp 代码检索含 source 拉取略重，5s 是 3.0s 的 ~1.7 倍冗余。正常 `/query` < 1s，5s 是 5 倍冗余。
2. **为何 connect=2.0**：连接建立（TCP+TLS）独立于 total，防 connect 单独 hang 拖满 total。2s 覆盖跨容器/跨节点握手。
3. **与熔断器交互**：`_query_project` 在 `gather(return_exceptions=True)` 内，超时被吞为 result，L323 `any(isinstance(res, Exception))` 判定 failure。收紧后故障请求 5s 超时记 failure，连续 3 次即熔断，最坏 3×5=15s 后熔断打开（原 3×15=45s）；熔断打开后 `_dual_mcp_available()` 返回 False 直接跳过（0s）。
4. **模块级 client 默认 timeout 15s 保留**：`_dual_mcp_client`（L23）默认 `timeout=httpx.Timeout(15.0)` 仍保留作兜底，但 `/projects`（timeout=5）与 `/query`（timeout=5）均显式覆盖，默认值实际不被使用——保留避免误用未显式 timeout 的调用点无限等待。

#### 7.5.2 P1-1：dual_mcp `/projects` TTL 缓存

**改动文件**：[server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) 新增 `_projects_cache`/`_projects_cache_lock`/`_get_projects_cached`（L289-329），`_dual_mcp_search` 调用处改用缓存（L341-348）

```python
# 实施前：每请求拉 /projects（200 并发冷启动同时打）
r = await client.get(f"{base}/projects", timeout=5)
projects = [p for p in r.json().get("projects", []) if isinstance(p, dict)]
# 实施后：TTL 缓存 + asyncio.Lock + double-check
projects, _from_cache = await _get_projects_cached(client, base)
```

`_get_projects_cached` 核心逻辑：快路径命中直接返回 `(list, True)`；miss 时 `async with _projects_cache_lock:` 内 double-check（等锁期间可能已被其他协程填充）→ 拉取 → 写缓存 → 返回 `(list, False)`。

**设计决策**：

1. **为何全局缓存而非 per-kb**：`/projects` 返回**全量项目列表**（非 per-kb），`_resolve_project` 从中解析 kb→project。全局一份覆盖所有 kb，无需 per-kb 键。
2. **为何 asyncio.Lock + double-check 而非乐观并发**：200 并发冷启动同时 miss，无锁会同时发 200 个 `/projects`（恰是 P1-1 要消除的反面）。Lock 保证只发 1 次，其余等锁后 double-check 命中。Python 3.10.6 模块级 `asyncio.Lock` 不在 `__init__` 绑定 loop（同 P0-3 Semaphore），uvicorn 单 worker 单 loop 无跨 loop 问题。
3. **为何 TTL 60s**：项目列表只在入库新项目时变（低频），60s 平衡新鲜度与收益。入库后最多 60s 可见延迟，可接受。
4. **缓存只在熔断关闭后生效**：`_dual_mcp_search` 上方 `_dual_mcp_available()`（L298-300）已 return，缓存逻辑在其后。熔断打开时直接跳过不查缓存。
5. **拉取失败不写缓存**：`_get_projects_cached` 内 `raise RuntimeError`（status != 200）被 `_dual_mcp_search` except 捕获记 failure + 熔断；不写缓存，下次重试。
6. **日志可观测**：`[timing] dual_mcp_projects: {t}s, n={n}, cache={hit|miss}`（命中 t≈0、miss t=RTT）；新增 `[dual-mcp] /projects 缓存填充` 记录填充事件。

#### 7.5.3 P1-3：检索路并发上限信号量

**改动文件**：[server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) 模块级新增 `_RETRIEVE_SEMAPHORE`（L97-103）；`_phase_retrieve` 外层 gather 包入 `async with`（L528-546）

```python
# 实施前：三元表达式，无在飞上限
per_kbq_results = ([] if _verdiff_ctx is not None else await asyncio.gather(
    *[_retrieve_one_kb_one_query(...) for qi, sq in ... for kb in valid_kbs],
    return_exceptions=True,
))
# 实施后：if/else + 信号量包外层 gather（verdiff 分支不占信号量）
if _verdiff_ctx is not None:
    per_kbq_results = []
else:
    _t_sem = time.time()
    async with _RETRIEVE_SEMAPHORE:
        _sem_wait = time.time() - _t_sem
        if _sem_wait > 0.05:
            logger.info(f"[timing] retrieve_sem_acquire_wait: {_sem_wait:.3f}s")
        per_kbq_results = await asyncio.gather(*[...], return_exceptions=True)
```

**设计决策**：

1. **为何包外层 gather 而非内层三路**：外层 gather 是"每 chat 请求检索阶段"入口，acquire 一次即限同时在飞的检索请求数；内层三路（milvus/dual_mcp/mempalace）仍并发保持单请求低延迟。包内层会过度限制单请求并发。
2. **为何 verdiff 分支不占信号量**：版本对比分支（`_verdiff_ctx is not None`）跳过检索（`per_kbq_results = []`）不打下游，无需保护。用 if/else 而非三元表达式让信号量只包有检索的分支。
3. **为何默认 128**：4 worker × 32 = 128，总容量 4×128=512。200 并发下每 worker 50 个同时检索 < 128，基本不排队——信号量主要防突发打爆下游（N 库 × M query × 3 路），而非限流吞吐。env `HAISI_RETRIEVE_MAX_CONCURRENCY` 可覆盖。
4. **对齐 P0-3 模式**：模块级单例 + `max(1, ...)` 守卫防误配 0/负值 + env 覆盖 + acquire_wait 日志（>0.05s 才记，分离排队分量）。Python 3.10.6 模块级 Semaphore 不绑 loop，与 P0-3 `_LLM_CONCURRENCY` 同理。
5. **embed 在信号量外**：`aget_embeddings`（L511-515）不打下游检索（只调 embedding 服务），在 acquire 之前完成；`_t_sem` 独立测 acquire 等待，不混入 embed 时间。
6. **退出路径安全**：信号量持有期间无 yield（检索 gather 后才有 reranker/yield），客户端断开 `GeneratorExit` 在 `await asyncio.gather(...)` 点抛出，`async with __aexit__` 释放信号量，无泄漏。

#### 7.5.4 P1-1 review 补强：冷启动故障退化修复（2026-10-15）

**review 发现**：P1-1 的 `_projects_cache_lock` 串行化 + 锁内不检查熔断器，导致故障冷启动场景严重退化。

**退化场景**（200 并发进 `_dual_mcp_search`，dual-mcp 故障）：

1. L345 `_dual_mcp_available()` 全过（熔断未开）→ 全部进 `_get_projects_cached` 抢锁
2. 前 N 个（N=`DUAL_MCP_FAIL_THRESHOLD`，默认 3）串行拉取，N×5s 超时 → 熔断打开（`open_until=now+60`）
3. **第 N+1 个获锁后 double-check miss（缓存未填），不检查熔断就拉取** → 5s 超时
4. 第 N+2 … 第 200 个同理串行 5s 超时 → **199×5≈995s 退化**（改动前 httpx 连接池 max=50 批次并发，约 20s）

**根因**：`_get_projects_cached` 锁内 double-check miss 后、拉取前，未检查 `_dual_mcp_available()`；except 端无条件 `_dual_mcp_record_failure()` 会重复记 failure 反复 reset `open_until` 导致 cooldown 永不恢复。

**修复**（[server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) L316-323 + L377-381）：

```python
# _get_projects_cached 锁内 double-check miss 后、拉取前加熔断检查
if not _dual_mcp_available():
    raise RuntimeError("dual-mcp 熔断中，跳过 /projects 拉取")
r = await client.get(f"{base}/projects", timeout=5)

# except 端条件记 failure（熔断已开则不重复记，避免延长 cooldown）
except Exception as e:
    if _dual_mcp_available():
        _dual_mcp_record_failure()
```

**修复后退化**：前 N 个串行超时（N×5s≈15s）+ 第 N+1 个起锁内检查熔断已开立即 raise（~0s）+ 199×0s + 60s cooldown ≈ **75s**（vs 修复前 1010s，改动前 20s+60s）。正常路径（dual-mcp 健康）不受影响——锁内检查 `_dual_mcp_available()` 返回 True，照常拉取填缓存。

**为何 except 端加 `if _dual_mcp_available()` 守卫**：`_dual_mcp_record_failure()` 每次 streak≥threshold 都 `open_until = now + cooldown`（[dual_mcp_circuit.py:38](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp_circuit.py#L38)）。若熔断已开时仍记 failure，cooldown 会从每次 raise 的时间点重新计时 → 持续有请求进来则 cooldown 反复延长 → 熔断永不恢复。守卫确保「熔断已开则不重复记 failure」，cooldown 固定 60s 后闭合探测。

**为何 L370 `/query` 路径的 `_dual_mcp_record_failure()` 不加守卫**：
1. 冷启动故障场景下 `/projects` 先失败 raise，根本不进 `/query` 路径，不触发退化
2. 进 `/query` 的协程都是 L345 熔断关闭时进入；`/query` 5s 超时期间其他协程可能打开熔断，但本协程的 failure 是真实服务故障（在熔断打开前发起的请求），记 failure 延长 cooldown 合理（持续故障→持续冷却，与 mempalace_circuit 一致）
3. 不扩大本次 review 改动范围，保持 L370 原有语义

**正确性验证（5 条，均通过）**：

1. **P1-2 timeout**：`httpx.Timeout(5.0, connect=2.0)` 正确；熔断器交互不变（`/query` 在 `gather(return_exceptions=True)` 内，超时被吞为 result，L370 `any(isinstance(res, Exception))` 检查）。
2. **P1-1 缓存逻辑**：快路径 + 锁内 double-check + 失败不写缓存（raise 路径）+ `async with` 保证异常释放锁，均正确。
3. **P1-1 缓存命中时熔断探测**：缓存只省 `/projects` RTT，`/query` 仍实际请求 → 熔断器由 `/query` 探测，不会因缓存命中而失活。
4. **P1-3 信号量退出路径**：`async with` 保证所有退出释放；持有期间无 `yield`；与 P0-3 `_LLM_CONCURRENCY` 顺序不嵌套（一个在检索、一个在生成），与 `_projects_cache_lock` 单向嵌套（检索信号量 → dual_mcp 缓存锁），无死锁。
5. **P1-3 verdiff/embed 作用域**：verdiff 分支不占信号量；embed 在信号量外（`aget_embeddings` 在 acquire 之前）；`_t_sem` 独立测 acquire 等待，不混入 embed 耗时。

**验证**：

- Pylance 诊断无错误
- `py_compile server/chat/pipeline/dual_mcp.py` 通过
- `pytest test_dual_mcp_circuit + test_dual_mcp_client` → **56 passed**（4.17s，无回归）

**验证（P1 批次统一）**：

- `py_compile` 通过（dual_mcp.py + multi_knowledge_base_chat.py）
- Pylance 诊断无错误
- `pytest test_dual_mcp_circuit + test_dual_mcp_client` → **56 passed**（dual_mcp 路无回归）
- `pytest test_retrieve_one_kb_p0_regression + test_p2_bugfixes + test_verdiff_unfound_no_placeholder + test_chat_version_filter + test_chat_tags_pipeline` → **21 passed**（multi_kb 路无回归）
- 合计 **77 passed**，docker `haisi_rag_models_server_dev` 容器，`--no-cov`

**待验证（需复跑压测）**：

- `[timing] dual_mcp_projects` cache=hit 占比（稳态应 > 95%）
- `[timing] dual_mcp_query` 在 5s timeout 下是否出现误判故障（正常应全部 < 1s）
- `[timing] retrieve_sem_acquire_wait` 在 200 并发下频次/均值（预期基本不触发，< 0.05s 不记）
- dual_mcp 首次故障 TTFT 从 +15s 降至 +5s 的实际观测

### 7.6 P2-1 实施：build_files_version_map 编排层统一查询（2026-10-15 已完成 + review 补强）

**问题**：三路检索（milvus / dual_mcp / mempalace）各自调 `build_files_version_map` 查当前版本映射（[milvus_retrieval.py:84](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py#L84)、[dual_mcp.py:415](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L415)、[mempalace_retrieval.py:142](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py#L142)）。虽有 60s TTL 缓存兜底（[file_version.py:424](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/file_version.py#L424)），但三路并发 miss 竞态窗口内仍可能 3 次 DB 查询；且每路都有 `from ... import` + `try/except` 样板。

**方案**：编排层 `_phase_retrieve` 统一查一次 `cur_labels_map[kb]`（per-kb），注入 `_retrieve_one_kb_one_query` → 三路复用。三路函数签名加 `cur_labels: Optional[set] = None`，`None` 时自查（向后兼容其他调用方如 `version_diff.py` / 测试）。

**改动文件**：

1. [server/chat/pipeline/milvus_retrieval.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py) — `_search_milvus_sync` + `_search_milvus_impl` 签名加 `cur_labels`；`version is None` 分支：`cur_labels is None` 时自查，否则用注入值构造 `filter_expr`
2. [server/chat/pipeline/dual_mcp.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py) — `_dual_mcp_search` 签名加 `cur_labels`；`cur_labels is None and version is None` 时自查
3. [server/chat/pipeline/mempalace_retrieval.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/mempalace_retrieval.py) — `_search_mempalace_async` 签名加 `cur_labels`；同 dual_mcp 逻辑
4. [server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) — `_retrieve_one_kb_one_query` 签名加 `cur_labels` 透传三路；`_phase_retrieve` 外层 gather 前用 `asyncio.to_thread` + `gather` 并发查 `cur_labels_map`（per-kb），注入 `_retrieve_one_kb_one_query(cur_labels=cur_labels_map.get(kb))`
5. [tests/unit/test_retrieve_one_kb_p0_regression.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_retrieve_one_kb_p0_regression.py) — `_milvus_real_sig` mock 同步加 `cur_labels` 保持与 `_search_milvus_sync` 签名一致

**核心代码**（编排层注入）：

```python
# _phase_retrieve 外层 gather 之前
cur_labels_map: dict = {}
if ctx.version is None and _verdiff_ctx is None and valid_kbs:
    from server.knowledge_base.file_version import build_files_version_map

    async def _query_cur_labels(kb_name: str) -> tuple:
        try:
            m = await asyncio.to_thread(build_files_version_map, kb_name)
            return kb_name, set(l for l in m.values() if l)
        except Exception as e:
            logger.warning(f"[pipeline] kb='{kb_name}' 查当前版本映射失败（fail-open）: {e}")
            return kb_name, None

    _labels_results = await asyncio.gather(*[_query_cur_labels(kb) for kb in valid_kbs])
    cur_labels_map = dict(_labels_results)

# 外层 gather 调用处
_retrieve_one_kb_one_query(kb, sq, all_embeddings[qi], recall_k, ...,
    tags=ctx.tags, version=ctx.version,
    cur_labels=cur_labels_map.get(kb))
```

**设计决策**：

1. **为何 per-kb 而非全局查**：`build_files_version_map(kb_name)` 按 kb 查 DB，每个 kb 版本映射独立。`_phase_retrieve` 按 valid_kbs 并发查一份 `cur_labels_map`，所有 query × kb 组合复用同一 kb 的映射。
2. **为何 `_phase_retrieve` 查而非 `_retrieve_one_kb_one_query` 内查**：`_phase_retrieve` 是编排层，同一 kb 会被多个 query 复用。在编排层查一次 per-kb，所有 query 复用；在 `_retrieve_one_kb_one_query` 内查则每个 (kb, query) 组合查一次（N 库 × M query 次，虽有 TTL 缓存但仍多余调用）。
3. **为何 `cur_labels` 默认 None 而非必填**：向后兼容其他调用方——`version_diff.py:73` 调 `_search_milvus_sync`、测试文件直接调三路函数，这些路径不传 `cur_labels`，走 `None` 自查分支保持原逻辑，避免改动面扩大到 verdiff / 全部测试。
4. **为何用 `asyncio.to_thread`**：`build_files_version_map` 是同步 DB 查询（`_db()` 上下文 + SQLAlchemy query），不能在 `_phase_retrieve`（async）里直接调否则阻塞事件循环。`to_thread` 放线程池跑，多 kb 用 `gather` 并发。
5. **为何只在 `version is None` 且非 verdiff 分支查**：`version is not None`（指定版本检索）不需要默认当前版本过滤，`cur_labels` 无用；verdiff 分支 `per_kbq_results = []` 跳过检索，不查。守卫避免无意义 DB 查询。
6. **fail-open 兜底**：编排层查失败记 `cur_labels_map[kb] = None`，三路收到 `None` 会走自查分支再试一次（`cur_labels is None and version is None`），自查也失败则三路各自 fail-open 不过滤。双保险。
7. **milvus 路用 set 注入 vs 原 list**：milvus `filter_expr` 的 `in [labels_str]` 只需 iterable，set 顺序不固定不影响 Milvus 查询语义（membership 检查）。原代码用 `cur_map.values()`（dict_values），改用注入的 set 语义一致。
8. **日志可观测**：新增 `[timing] cur_labels_map: {t}s, kbs={n}, filled={filled}` 记录查询耗时与成功填充数，便于观测。

**验证**：

- Pylance 诊断无错误（4 文件：milvus_retrieval / dual_mcp / mempalace_retrieval / multi_knowledge_base_chat）
- `py_compile` 通过（4 文件）
- `pytest` 三路检索 + 版本过滤相关 6 文件 → **34 passed**（16.14s）
  - test_version_tags_client_filter / test_milvus_retrieval_tags / test_milvus_tags_filter / test_p2_multi_version / test_p2_bugfixes / test_p1_milvus_null_alignment
- `pytest` 编排层 + chat 流 6 文件 → **19 passed**（12.41s）
  - test_chat_version_filter / test_chat_tags_pipeline / test_retrieve_one_kb_p0_regression（修复 mock 签名后通过）/ test_search_docs_filter / test_base_search_docs_tags / test_verdiff_unfound_no_placeholder
- 合计 **53 passed**，docker `haisi_rag_models_server_dev` 容器，`--no-cov`，无回归

**收益**：

- 消除三路竞态窗口内的 3 次 DB 查询（并发 miss 时）→ 改为编排层 1 次（per-kb）
- 消除三路的 `from ... import` + `try/except` 样板（编排层统一），三路函数更聚焦检索逻辑
- 统一查询点，便于观测/调优（`[timing] cur_labels_map` 日志）

**待验证（需复跑压测）**：

- `[timing] cur_labels_map` 在多 kb 场景下的耗时（预期 < 50ms，TTL 缓存命中后 < 1ms）
- 三路 `[milvus/dual-mcp/mempalace] 查当前版本映射失败` 日志频次（预期降为 0，编排层已查）

#### 7.6.1 P2-1 review 补强：milvus filter_expr 顺序稳定化（2026-10-15）

**review 发现**：P2-1 引入的小退化——milvus 路 filter_expr 字符串顺序从稳定变不稳定。

**退化原因**：
- P2-1 前：milvus 自查路径 `cur_labels = [l for l in cur_map.values() if l]` → list，dict_values 保序（DB 返回顺序，按 id desc），filter_expr 顺序稳定
- P2-1 后：编排层注入 `set(l for l in m.values() if l)` → set 顺序不固定，milvus 路 `for l in cur_labels` 遍历 set → filter_expr 的 `in [labels_str]` 列表顺序每次可能不同

**影响**：
- 语义不变（Milvus `in` 是 membership 检查，顺序无关）
- filter_expr 字符串不可复现 → 日志/调试可读性下降
- 若 Milvus 有 filter 表达式解析缓存，顺序变化导致 cache miss（开销 μs 级，可忽略）

**修复**（[milvus_retrieval.py L100](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py#L100)）：

```python
# 修复前：遍历 set（注入）或 list（自查），顺序不一致
labels_str = ", ".join(f'"{_escape_milvus_str(l)}"' for l in cur_labels)
# 修复后：sorted 统一 set/list 路径，按 label 字符串排序稳定
labels_str = ", ".join(f'"{_escape_milvus_str(l)}"' for l in sorted(cur_labels))
```

**为何只修 milvus 路**：dual_mcp/mempalace 路 `cur_labels` 是 set 用于客户端 `_matches_version_tags` 的 `in` membership 检查，无 filter_expr 字符串，顺序不影响。只有 milvus 路（服务端 filter_expr 字符串）有顺序问题。

**正确性验证（5 条，均通过）**：

1. **milvus 路 cur_labels 分支**：注入 set 非空/空、注入 None（自查兜底）、未注入（其他调用方）、version 指定（不参与），4 条路径 filter_expr 构造均正确。
2. **dual_mcp/mempalace 路 `cur_labels is None and version is None`**：注入 set 跳过自查、注入 None 自查、version 指定不自查也不用，3 条路径正确。
3. **编排层守卫**：`ctx.version is None and _verdiff_ctx is None and valid_kbs` 三重守卫避免无意义 DB 查询；`asyncio.to_thread` 不阻塞事件循环；多 kb `gather` 并发。
4. **与 P0-3/P1-1/P1-3 交互**：cur_labels_map 查询在 embed 之后、`_RETRIEVE_SEMAPHORE` acquire 之前，不占检索信号量；不涉及 dual_mcp `_projects_cache_lock`（那是 /projects 缓存）；与 P0-3 生成信号量无交互（不同阶段）。
5. **fail-open 双保险**：编排层查失败记 None → 三路收到 None 走自查分支再试 → 自查也失败各自 fail-open 不过滤。

**线程安全**：`build_files_version_map` 内部 `_version_map_cache` dict 无锁，但 P2-1 用 `to_thread` 放线程池，同一 kb 在同一 `_phase_retrieve` 内只查一次（per-kb），无并发冲突。跨请求并发（不同 chat 请求同时查同一 kb）是原有 TTL 缓存并发问题，P2-1 不引入新的。

**验证**：

- Pylance 诊断无错误（milvus_retrieval.py）
- `py_compile` 通过
- `pytest` milvus 相关 6 文件 → **29 passed**（17.97s，无回归）
  - test_p2_multi_version / test_p1_milvus_null_alignment / test_milvus_tags_filter / test_milvus_retrieval_tags / test_p2_bugfixes / test_search_docs_filter

### 7.7 B1+B2 实施：reranker 并发保护 + 模块级单例（2026-10-15 已完成）

**问题**（审视 §7.6 后新发现）：

- **B1 reranker 无并发信号量 + 无 timeout**：[multi_knowledge_base_chat.py L672-681](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L672) `await reranker_model.acompress_documents(...)` 在 `try/except` 内但无 `wait_for`，无并发信号量。默认配置 `RERANKER_API_CONFIG={}` → 走本地 `LangchainReranker`（CrossEncoder GPU 推理），`acompress_documents` 用 `asyncio.to_thread` 丢默认线程池（上限 `min(32, cpu+4)` ≈ 36）。200 QPS 场景下 36 个线程同时 `CrossEncoder.predict(batch_size=32, max_length=1024)`，显存叠加（每 batch ~1-2GB 激活值）**可能 GPU OOM**；torch 推理若卡死会无限期占用线程。HttpReranker 路径有自身 `timeout=60`，相对安全但无全局并发上限。
- **B2 reranker 每请求重建对象**：[multi_knowledge_base_chat.py L664](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L664) `get_reranker(top_n=rerank_top_n)` 每请求 `new LangchainReranker`（CrossEncoder 对象重建，权重有 HF cache 但对象创建开销）或 `new HttpReranker worker`（含 httpx client），高频下对象重建开销可观。

**方案**：

- **B2**：reranker.py 加模块级单例 `get_reranker_singleton()`，固定 `top_n=_RERANKER_SINGLETON_TOP_N`（30，与 `pipeline._RERANK_TOP_N_MAX` 对齐）。pipeline 拿到 reranked 结果（已按 score 降序）后自行截取前 `rerank_top_n` 个。
- **B1**：reranker.py 加 `acompress_documents_bounded()` 异步包装：模块级 `_RERANKER_SEMAPHORE`（env `HAISI_RERANKER_MAX_CONCURRENCY`，默认 8）+ `asyncio.wait_for(timeout=_RERANKER_TIMEOUT)`（env `HAISI_RERANKER_TIMEOUT`，默认 30s）。异常向上抛，由 pipeline `try/except` fail-open 回退 RRF。

**改动文件**：

1. [server/reranker/reranker.py](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker/reranker.py) — 加模块级 `_RERANKER_SEMAPHORE` / `_RERANKER_TIMEOUT` / `_reranker_singleton` / `_RERANKER_SINGLETON_TOP_N`；加 `get_reranker_singleton()` + `acompress_documents_bounded()` 两个函数
2. [server/chat/multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) — import 改 `get_reranker_singleton, acompress_documents_bounded`；L664 `get_reranker(top_n=...)` → `get_reranker_singleton()`；L673 `await reranker_model.acompress_documents(...)` → `await acompress_documents_bounded(reranker_model, ..., top_n=rerank_top_n)`
3. [tests/unit/test_reranker.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_reranker.py) — 新增 `TestGetRerankerSingleton`（2 例）+ `TestAcompressDocumentsBounded`（5 例）共 7 例

**核心代码**：

```python
# reranker.py 模块级
_RERANKER_SEMAPHORE = asyncio.Semaphore(max(1, int(os.environ.get("HAISI_RERANKER_MAX_CONCURRENCY", "8"))))
_RERANKER_TIMEOUT = float(os.environ.get("HAISI_RERANKER_TIMEOUT", "30"))
_reranker_singleton: Optional[Any] = None
_RERANKER_SINGLETON_TOP_N = 30  # 与 pipeline._RERANK_TOP_N_MAX 对齐

def get_reranker_singleton() -> Any:
    global _reranker_singleton
    if _reranker_singleton is None:
        _reranker_singleton = get_reranker(top_n=_RERANKER_SINGLETON_TOP_N)
    return _reranker_singleton

async def acompress_documents_bounded(
    reranker_model, documents, query, top_n=None,
) -> Sequence[Document]:
    async with _RERANKER_SEMAPHORE:
        reranked = await asyncio.wait_for(
            reranker_model.acompress_documents(documents=documents, query=query),
            timeout=_RERANKER_TIMEOUT,
        )
    if top_n is not None and len(reranked) > top_n:
        reranked = reranked[:top_n]
    return reranked

# pipeline 调用处
reranker_model = get_reranker_singleton()
reranked = await acompress_documents_bounded(
    reranker_model, documents=docs, query=answer_query, top_n=rerank_top_n)
```

**设计决策**：

1. **为何单例固定 top_n=30 而非按 rerank_top_n 分桶**：`rerank_top_n = min(max(top_k, RERANKER_TOP_N), _RERANK_TOP_N_MAX)` 在 [top_k, 30] 区间动态变化。分桶缓存会因 top_n 变体多导致单例退化；固定上限 30 后 pipeline 截取前 `rerank_top_n` 个（reranked 已按 score 降序，切片语义正确），单例稳定。（注：§7.8.1 已删除 `_rerank_by_budget` 联动，表达式简化）
2. **为何信号量默认 8**：本地 CrossEncoder GPU 推理，batch_size=32 × max_length=1024，每 batch 显存 ~1-2GB。8 并发 × 1-2GB = 8-16GB，适配主流 GPU（A10 24GB / A100 40/80GB）；HttpReranker 路径 API 侧并发上限通常 ≥8，匹配。env 可调。
3. **为何 timeout 默认 30s**：本地 CrossEncoder 200 文档 batch_size=32 → ~7 batch，每 batch ~1-2s → 正常 ~10-15s；HttpReranker 自身 timeout=60s 但 `wait_for` 30s 更早兜底防卡死。env 可调。
4. **为何异常向上抛而非包装 fail-open**：pipeline L672-681 已有 `try/except Exception → rrf_fallback` 兜底，包装层只负责信号量 + timeout，职责单一。`wait_for` 超时抛 `asyncio.TimeoutError`，被外层 `except Exception` 捕获走 RRF 兜底。
5. **为何 `top_n` 截取放包装层而非 pipeline**：包装层拿到 reranked（已按 score 降序），直接 `[:top_n]` 截取最简；pipeline 无需重复切片。`top_n=None` 时不截取（向后兼容其他调用方）。
6. **与 B2 单例的交互**：单例固定 top_n=30，`acompress_documents` 返回最多 30 个，包装层截取前 `rerank_top_n` 个。若 `rerank_top_n` 恰好=30（`_RERANK_TOP_N_MAX` 上限），不截取。
7. **模块级 Semaphore 不绑定 loop**：Python 3.10.6 的 `asyncio.Semaphore` 不在 `__init__` 绑定 loop（同 `_LLM_CONCURRENCY` / `_RETRIEVE_SEMAPHORE`），uvicorn 单 worker 单 loop 下安全。
8. **单例创建失败不缓存 None**：`get_reranker_singleton` 若 `get_reranker` 抛异常，`_reranker_singleton` 保持 None，下次重试（与 `_projects_cache` 模式一致）。pipeline `USE_RERANKER=True` 守卫已挡，单例创建只在启用 reranker 时触发。

**验证**：

- Pylance 诊断无错误（reranker.py / multi_knowledge_base_chat.py / test_reranker.py）
- `py_compile` 通过（2 源文件）
- `pytest` reranker + p0 regression 3 文件 → **33 passed**（10.09s，无回归）
  - test_reranker.py（原 9 + 新增 7 = 16）/ test_http_reranker.py（15）/ test_retrieve_one_kb_p0_regression.py（2）
- 新增测试覆盖：单例同一性 / USE_RERANKER=False 返 None / top_n 截取 / top_n=None 不截取 / top_n 超结果数不截取 / 异常向上抛 / timeout 抛 TimeoutError（共 7 例）

**收益**：

- B1：消除 200 QPS 下本地 CrossEncoder GPU OOM 风险（并发 8 限制显存 ~8-16GB）；torch 推理卡死时 30s timeout 兜底回退 RRF，不再无限期占用线程
- B2：消除每请求 reranker 对象重建开销（LangchainReranker + CrossEncoder 或 HttpReranker worker + httpx client），高频下对象创建/GC 压力下降
- env 可调：`HAISI_RERANKER_MAX_CONCURRENCY` / `HAISI_RERANKER_TIMEOUT` 便于压测调优

**待验证（需复跑压测）**：

- `[timing] reranker` 在 200 QPS 下的 P99（预期 < 15s，信号量排队 + GPU 推理）
- GPU 显存峰值（预期 < 16GB，8 并发 × batch_size=32）
- reranker 对象创建日志频次（预期稳态 0，仅首次创建）

### 7.8 P2-2 评估：RAG_CONTEXT_BUDGET 不下调（2026-10-15）

**背景**：原 P2-2 计划下调 `RAG_CONTEXT_BUDGET`（40960 → 16384）以降 prefill、缩 TTFT。用户担心"切片变少影响准确率"，要求基于当前 8192 现状重新评估。

**现状核查**：

- 全 6 个环境配置（`params/server_config.yaml` + `params/{prod,release,test,devp,local}/`）已手动同步为 `RAG_CONTEXT_BUDGET: 8192`（非原 40960）
- `CHUNK_SIZE=700`、`RERANKER_TOP_N=10`、请求 `top_k` 默认=`RERANKER_TOP_N=10`、`_RERANK_TOP_N_MAX=30`

**预算联动失效分析**：

[multi_knowledge_base_chat.py L659-660](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L659)：

```python
_rerank_by_budget = max(1, RAG_CONTEXT_BUDGET // 2000)   # 8192//2000 = 4
rerank_top_n = min(max(top_k, max(RERANKER_TOP_N, _rerank_by_budget)), _RERANK_TOP_N_MAX)
#            = min(max(10, max(10, 4)), 30) = 10
```

`_rerank_by_budget=4` 被 `RERANKER_TOP_N=10` 掩盖。只要 `budget ≤ 20000`（`_rerank_by_budget ≤ 10`），rerank_top_n 下限都由 `RERANKER_TOP_N=10` 决定，**budget 联动失效**。

| budget | _rerank_by_budget | rerank_top_n 下限决定者 |
|---|---|---|
| 4096 | 2 | RERANKER_TOP_N=10 |
| 8192（当前） | 4 | RERANKER_TOP_N=10 |
| 16384 | 8 | RERANKER_TOP_N=10 |
| 20000 | 10 | 二者相等 |
| 40960（原） | 20 | _rerank_by_budget（接管） |

**进 prompt 块数分析**：

[_phase_assemble](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L730) L730-737 + [_join_chunks_with_budget](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/doc_utils.py#L99)：

- doc_budget = 8192 × 0.7 = 5734 chars → 5734/700 ≈ **8 块**
- code_remain = 8192 - len(doc_context) ≈ 2458 chars → 2458/700 ≈ **3 块**
- **总进 prompt ~11 块**

下调到 4096 的影响：

| 维度 | 8192（当前） | 4096（下调） | 变化 |
|---|---|---|---|
| reranker 候选池 rerank_top_n | 10 | 10 | **不变**（联动失效） |
| 进 prompt 块数 | ~11（doc 8 + code 3） | ~5-6（doc 4 + code 1-2） | **减半** |
| prefill（chars） | ~8192 | ~4096 | 减半 |
| TTFT | 基准 | -200~500ms | 降低 |
| 准确率风险 | 基准 | **相关块可能被挤出** | 升高 |

**评估结论**：**不实施下调**。理由：

1. **8192 已是合理平衡点**：进 prompt ~11 块（CHUNK_SIZE=700，11 块才 ~7700 chars），prefill ~8k chars（中文约 8k tokens）对多数 LLM 是甜区。生产环境长期用 8192，业务可接受。
2. **准确率风险真实存在**：下调到 4096 只剩 ~5-6 块，[_join_chunks_with_budget](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/doc_utils.py#L99) 按预算整块纳入，预算不够时第 6-10 个相关块直接不进 prompt。多段关联文档场景（答案需跨多块拼接）尤其受影响。
3. **reranker 候选池不受影响**：`_rerank_by_budget` 联动已失效，下调 budget 不缩小 reranker 候选池（仍 10），但进 prompt 的块数减少。召回质量不降，但精排后进 prompt 的覆盖度下降。
4. **TTFT 收益有限**：8k → 4k chars 的 prefill 差异约 200-500ms（取决于模型 prefill 速度），相对 P0/P1/P2-1/B1/B2 已降的 TTFT，边际收益小。

**遗留技术债**：~~`_rerank_by_budget` 联动死代码~~ → ✅ 已清理，见 §7.8.1。

**后续优化方向（替代 P2-2）**：

- 若仍需降 TTFT：改从其他环节优化（P3-1 embedding 单例 client / P3-2 markdown_index LRU / P3-3 缓存淘汰），而非压缩已偏小的上下文预算
- 若需提升准确率：可评估上调 budget（8192 → 12288），但需配合 A/B 压测验证"中间迷失"效应是否恶化

### 7.8.1 P2-2 技术债清理：删除 `_rerank_by_budget` 联动（2026-10-15）

**背景**：§7.8 评估发现 `_rerank_by_budget = RAG_CONTEXT_BUDGET // 2000` 在当前配置（budget=8192, RERANKER_TOP_N=10）下恒被 `RERANKER_TOP_N=10` 掩盖，为死代码。且估算系数 2000 与实际 `CHUNK_SIZE=700` 严重不符（8192 估算 4 块 vs 实际需要 11 块），即使修正系数也只让 reranker 多排 1 块弱相关候选，边际价值低。决定删除联动，简化 `rerank_top_n`。

**改动**：[multi_knowledge_base_chat.py L656-661](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L656)

```python
# 改前
_rerank_by_budget = max(1, RAG_CONTEXT_BUDGET // 2000)
rerank_top_n = min(max(top_k, max(RERANKER_TOP_N, _rerank_by_budget)), _RERANK_TOP_N_MAX)
if rerank_top_n != RERANKER_TOP_N:
    logger.info(f"[pipeline] reranker top_n 按需放宽: 配置={RERANKER_TOP_N}, "
                f"请求 top_k={top_k}, 预算联动={_rerank_by_budget}, 生效={rerank_top_n}")

# 改后
rerank_top_n = min(max(top_k, RERANKER_TOP_N), _RERANK_TOP_N_MAX)
if rerank_top_n != RERANKER_TOP_N:
    logger.info(f"[pipeline] reranker top_n 按需放宽: 配置={RERANKER_TOP_N}, "
                f"请求 top_k={top_k}, 生效={rerank_top_n}")
```

**设计决策**：

1. **为何删除而非修正系数**：修正为 `RAG_CONTEXT_BUDGET // CHUNK_SIZE` = 11，rerank_top_n = max(10, 11) = 11。reranker 多排 1 块（+10% 算力），但第 11 块是 reranker 排名最末的弱相关块，进 prompt 边际价值低，还可能加剧"中间迷失"。删除后 reranker 候选池固定 10，与 prompt 预算解耦，职责清晰。
2. **为何不影响准确率**：reranker 返回 10 块 vs 预算能放 11 块，差额由 [code 路保底](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L694) L694-709 补足（`_code_total < _CODE_CTX_GOAL` 时从 code_list 补）。doc 路即使少 1-2 块，reranker 给的 7-8 块 doc 通常够回答，且都是最相关的。
3. **为何行为等价**：当前配置下 `_rerank_by_budget=4` 被 `RERANKER_TOP_N=10` 掩盖，改前改后 rerank_top_n 都 = `min(max(10, 10), 30)` = 10。删除死代码不引入回归。
4. **RAG_CONTEXT_BUDGET 仍保留**：L728 `_phase_assemble` 和 L879 history 截断仍用 `RAG_CONTEXT_BUDGET`，import 不动。

**验证**：

- Pylance 诊断无错误（multi_knowledge_base_chat.py）
- `pytest` reranker + http_reranker + p0_regression 3 文件 → **33 passed**（26.76s，无回归）
- 行为等价：改前改后 rerank_top_n 都 = 10（当前配置 top_k=10, RERANKER_TOP_N=10）

**收益**：

- 消除"`_rerank_by_budget` 用 2000 chars/块估算 vs 实际 CHUNK_SIZE=700"的失真死代码
- `rerank_top_n` 语义清晰：只由 `top_k`（请求）+ `RERANKER_TOP_N`（配置下限）+ `_RERANK_TOP_N_MAX`（硬上限 30）决定
- reranker 候选池与 prompt 预算解耦，消除"budget 影响 reranker 候选池"的误导

### 7.9 C1/C2 实施 + P3-3 评估（2026-10-15）

#### C1：query embedding 全局并发信号量

**背景**：审视发现 query embedding 路径无全局并发信号量。每个 chat 请求必经 [multi_kb_chat.py L528](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L528) `await aget_embeddings(search_queries)`（检索前先 embed query），200 QPS 下 200 个并发调用无限制打 embedding 服务（本地 bge-m3 GPU 可能 OOM，远端 API 可能 429）。与 P0-3（`_LLM_CONCURRENCY=64`）、P1-3（`_RETRIEVE_SEMAPHORE=128`）同类缺口——`_RETRIEVE_SEMAPHORE` 保护检索路（milvus+dual_mcp+mempalace gather），不保护 embedding（embedding 在检索之前发生）。

**改动**：[embeddings.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/embeddings.py)

```python
# 模块级 embedding 并发信号量
_HAISI_EMBEDDING_MAX_CONCURRENCY = max(1, int(os.environ.get("HAISI_EMBEDDING_MAX_CONCURRENCY", "128")))
_EMBEDDING_SEMAPHORE = asyncio.Semaphore(_HAISI_EMBEDDING_MAX_CONCURRENCY)

async def aget_embeddings(texts):
    async with _EMBEDDING_SEMAPHORE:
        result = await aembed_texts(texts=texts, embed_model=EMBEDDING_MODEL)
    if result.code == 200 and result.data:
        return np.array(result.data, dtype=np.float32)
    else:
        raise ValueError(f"Embedding 失败: {result.msg}")
```

**设计决策**：

1. **为何信号量默认 128**：embedding query 是短时操作（本地 bge-m3 ~10-50ms，远端 API ~100-300ms），并发容量远高于 LLM 生成（秒级）。128 与 `_RETRIEVE_SEMAPHORE` 对齐，留足余量不成为瓶颈。env 可调。
2. **为何不复用 `_RETRIEVE_SEMAPHORE`**：embedding 与检索职责分离。embedding 信号量保护 embedding 服务（GPU/API），检索信号量保护 milvus/dual_mcp/mempalace。分开便于独立调优。且 embedding 发生在检索之前，复用会导致"检索信号量被 embedding 持有"的串行化。
3. **模块级 Semaphore 不绑定 loop**：Python 3.10.6 的 `asyncio.Semaphore` 不在 `__init__` 绑定 loop（同 `_LLM_CONCURRENCY` / `_RETRIEVE_SEMAPHORE` / `_RERANKER_SEMAPHORE`），uvicorn 单 worker 单 loop 下安全。
4. **信号量持有时间**：仅包 `aembed_texts` 调用（embedding 计算 + 网络 RTT），返回后立即释放。`np.array` 转换在信号量外，不占用并发槽位。
5. **get_embeddings（同步）不加信号量**：同步路径用于非 async 链路（如离线脚本），不影响在线 QPS。且同步阻塞无法用 asyncio.Semaphore。

**验证**：

- Pylance 诊断无错误（embeddings.py）
- `pytest` embedding 4 文件（test_embeddings_api / test_http_embeddings / test_embedding_worker_cache / test_batch_embed_retry）→ **40 passed**（22.68s，无回归）

**收益**：

- 消除 200 QPS 下 embedding 服务被打爆的风险（本地 GPU OOM / 远端 API 429）
- env 可调：`HAISI_EMBEDDING_MAX_CONCURRENCY` 便于压测调优
- 与 P0-3/P1-3/B1 形成完整并发保护链：embedding(128) → retrieve(128) → reranker(8) → LLM(64)

#### C2：删除 `source_documents_meta` 死代码

**背景**：审视发现 [multi_kb_chat.py L921-928](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L921) 的 `source_documents_meta` 赋值后无任何后续引用——L922 注释自述"虽未传给共享 LLM 函数，但为保持行为等价予以保留"。Grep 确认仅 L925 赋值，全文件无读取。纯死代码。

**改动**：删除 [multi_kb_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) L921-928 整块（注释 + `if ctx.need_rag:` + 列表推导赋值）。

**设计决策**：

1. **为何确认是死代码**：Grep `source_documents_meta` 全文件只有 L925 一处赋值，无任何读取/传参/返回。注释自述"未传给共享 LLM 函数"。
2. **为何删除而非保留**：死代码增加阅读负担，且列表推导 `[{"title":..., "content":...} for d in source_documents]` 在 RAG 分支每请求执行一次，虽开销小但纯浪费。删除零风险。

**验证**：

- Pylance 诊断无错误（multi_knowledge_base_chat.py）
- `pytest` p0_regression + reranker 3 文件 → **33 passed**（27.64s，无回归）

**收益**：

- 消除 RAG 分支每请求一次的无用列表推导（~微秒级，纯清理）
- 减少 ~8 行死代码 + 误导性注释

#### P3-3 评估：模板/client 缓存淘汰无需实施

**背景**：文档 §3.4 原列 P3-3"模板/client 缓存无淘汰"，标注"长期泄漏（低风险，规模小）"。审视实际代码后重新评估。

**审视**：

| 缓存 | 位置 | key | 实际 key 数量 | 增长条件 |
|---|---|---|---|---|
| `_route_template_cache` | [route_llm.py L14](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/route_llm.py#L14) | 模板字符串 | **固定 1 个**（route_chat.user 模板） | yaml 热加载新字符串才 miss 旧 key |
| `_openai_client_cache` | [openai_clients.py L12](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py#L12) | api_key+base_url | **固定 2 个**（main + router） | 新增 LLM 模型配置才 miss |

**结论**：两处 key 数量都固定（1-2 个），yaml 不热加载新模型/新模板就不会增长。**无泄漏风险**，加 `lru_cache` 或淘汰机制是过度设计。

**标注**：§3.4 表格 + §2.1 瓶颈清单 + §7.4 表格均标注"❌ 经评估无需实施"。

### 7.10 P3-1/P3-2 实施：embedding 基类单例 client + markdown_index LRU（2026-10-15）

#### P3-1：embedding 基类（HttpEmbeddings）单例 AsyncClient

**背景**：[base.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/base.py) 原实现 `_aembed_batch` / `aembed_query` 每次调用都 `async with httpx.AsyncClient(timeout=self.timeout)`，每请求一次 TLS 握手 + 新建连接池，不复用。仿照同模块 [aliyun_openai_embedding.py L220](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/aliyun_openai_embedding.py#L220) 的 `_get_aclient` 懒初始化模式，统一为共享单例 client。

**改动**（[base.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/base.py)，共 5 处）：

1. `__init__` 新增 `self._aclient: Optional[httpx.AsyncClient] = None`（懒初始化位）
2. 新增 `_get_aclient()` 方法：

```python
async def _get_aclient(self) -> httpx.AsyncClient:
    if self._aclient is None:
        self._aclient = httpx.AsyncClient(
            timeout=self.timeout,
            limits=httpx.Limits(
                max_connections=50,
                max_keepalive_connections=10,
                keepalive_expiry=30.0,
            ),
        )
    return self._aclient
```

3. `_aembed_request` 内层 `await client.post(url, json=payload)` 改为 `await asyncio.wait_for(client.post(url, json=payload), timeout=self.timeout)`，并新增 `except asyncio.TimeoutError` 分支（区别于 `httpx.TimeoutException`，wait_for 取消协程时抛 `asyncio.TimeoutError`）
4. `_aembed_batch` 去掉 `async with httpx.AsyncClient(...) as client:` 包裹，改 `client = await self._get_aclient()`
5. `aembed_query` 同上

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **为何收窄连接池（max_connections=50 / keepalive=10）**：httpx 默认连接池上限过高（不显式设 `Limits` 时 max_connections≈None/无上限，keepalive=20）。高并发突发下若部分连接陷入半死状态（对端 GC、网络抖动、慢响应未断开），会持续占用池位，导致后续请求**永久阻塞在连接池 acquire 信号量上**——且该阻塞**不受 httpx 内置请求 timeout 约束**（timeout 只在已 acquire 到连接后才生效）。把池收窄到 50，让半死连接的占比上限可控，配合下面的 `wait_for` 兜底可自愈。

2. **为何加 `wait_for` 兜底**：`asyncio.wait_for(coro, timeout)` 在超时后**取消协程并抛 `asyncio.TimeoutError`**。取消 `client.post(...)` 协程会连带释放被该协程占用的连接池位（httpx 在协程取消时释放连接），使连接池可在超时窗口内自愈。这是对抗"连接池被打坏后无法恢复"的关键兜底。两层超时语义：
   - 外层 `wait_for(self.timeout)`：约束"从发起 post 到拿到 response"的总时长，含连接 acquire + TLS + 请求 + 响应
   - 内层 httpx `timeout=self.timeout`：约束已 acquire 连接后的各阶段（connect/read/write/pool）
   - 两者超时值相同（`self.timeout`，默认 60s），外层是内层的兜底，正常情况下不会先于内层触发。

3. **高并发场景影响（200 QPS）**：
   - C1 已用 `_EMBEDDING_SEMAPHORE=128`（[embeddings.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/embeddings.py)）限制 query embedding 的全局并发，但单实例 `HttpEmbeddings` 的连接池上限 50 是更紧的自然瓶颈。200 QPS 时：128 信号量允许 128 个并发 embed 请求，但共享 client 只有 50 连接 → 78 个排队等连接位。
   - 排队本身不致命（embedding 通常 10-300ms 快速释放连接），但若下游 embedding 服务变慢（GPU OOM 接近、远端 API 429 重试），50 连接会被慢请求占满 → 新请求阻塞在 acquire → `wait_for` 60s 超时取消 → 释放连接位 → 连接池自愈。最坏情况下表现为 embedding 路个别请求 60s 超时 ValueError，而非全局 hang。
   - 若压测发现 50 连接成为瓶颈，可通过环境变量调大（但需同步评估下游 embedding 服务容量）。当前 50 与 C1 信号量 128 留有余量：50 连接 × ~50ms/请求 ≈ 1000 QPS 理论上限，远超当前 200 QPS 目标。

4. **长时间等待场景影响**：
   - 慢请求（如下游 embedding 服务 GC pause、网络拥塞）占满 50 连接位后，新请求 `wait_for` 在 `self.timeout`（默认 60s）后超时取消并抛 ValueError。**不会无限期 hang**。
   - 超时取消会释放被占用的连接位，使后续请求可立即获取连接。这是对"长时间等待打坏连接池"的核心防御。
   - 注意：`_aembed_batch` 的 `asyncio.gather(return_exceptions=True)` 会把超时分片的 ValueError 收集到 results，然后在 L348-351 统一 raise——任一分片超时即整批失败，不会静默丢失。

5. **防火墙 / NAT keepalive 断开影响**：
   - 防火墙/NAT 通常在连接空闲 60-300s 后静默断开 TCP（对端不通知）。若 httpx 复用了一条已被防火墙静默断开的 keepalive 连接，下一次 post 会因连接已断而抛 `httpx.ConnectError` / `RemoteProtocolError`。
   - `keepalive_expiry=30.0` 让 keepalive 连接**30s 后自动失效不复用**，短于多数防火墙/NAT 的空闲断开阈值（通常 ≥60s），从根本上避免复用被静默断开的老连接。
   - 即使个别连接被断开，`httpx.ConnectError` 被 `_aembed_request` 的 except 分支捕获并转 ValueError，不影响其他连接。下次请求会自动新建连接。

6. **client 生命周期与不显式关闭**：`_aclient` 在首次 `_get_aclient` 时创建，**不显式 `aclose()`**，依赖进程退出时 OS 回收 socket。理由：
   - `HttpEmbeddings` 实例本身是长生命周期单例（在 [embeddings.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/embeddings.py) `EMBEDDING_MODEL` 挂载），与 uvicorn worker 同生命周期。
   - 显式 `aclose()` 需要在 app shutdown hook 里调用，增加耦合；且 uvicorn worker 退出时连接池随 loop 关闭自动释放。
   - 风险：进程被 SIGKILL 强杀时可能留下 TIME_WAIT socket，但 OS 会在 2MSL（~60s）后自动回收，不影响下次启动。
   - 若未来需要优雅关闭（如热重载场景），可在 `HttpEmbeddings` 加 `aclose()` 方法并在 app shutdown 调用，当前不做过度设计。

7. **子类覆盖面影响**：
   - [qwen3_embedding.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/qwen3_embedding.py)：未 override `_aembed_batch` / `_aembed_request` / `_get_aclient` / `aembed_query`，**直接受益**于本次改动（自动复用单例 client + wait_for 兜底）。
   - [aliyun_openai_embedding.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/aliyun_openai_embedding.py)：完全重写 `_get_aclient`（返回 `AsyncOpenAI` 而非 `httpx.AsyncClient`）/ `_aembed_request` / `aembed_documents` / `aembed_query`，**不受本次改动影响**（已有自己的连接池约束 + 重试）。
   - `_aembed_request(client, texts, prompt_name)` 签名保留不变，向后兼容。

**验证**：

- Pylance 诊断无错误（base.py / markdown_index.py）
- `pytest` embedding 4 文件（test_embeddings_api / test_http_embeddings / test_embedding_worker_cache / test_batch_embed_retry）→ **40 passed**（22.27s，无回归）

**收益**：

- query embedding 每请求省 1 次 TLS 握手（~10-50ms），200 QPS 下累计节省显著
- 连接池复用降低 socket / 文件描述符消耗
- `wait_for` 兜底 + 连接池收窄形成"自愈"机制：高并发 / 慢下游 / 防火墙断连场景下不再 hang，最坏情况是受控的 60s 超时 ValueError

#### P3-2：markdown_index LRU 淘汰

**背景**：[markdown_index.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/markdown_index.py) 原 `_file_cache: dict` 在达 500 上限时 `clear()` 一次性丢失全部缓存，导致后续 N 个请求全部 cache miss 重新读文件，形成抖动。改 LRU 淘汰最久未访问项。

**改动**（[markdown_index.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/markdown_index.py)）：

```python
from collections import OrderedDict

_file_cache: OrderedDict = OrderedDict()
_FILE_CACHE_MAX = 500

def _find_heading_line(source_path, titles):
    # ...
    cached = _file_cache.get(source_path)
    if cached and cached[0] == mtime:
        lines = cached[1]
        _file_cache.move_to_end(source_path)  # LRU：标记为最近访问
    else:
        with open(source_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        _file_cache[source_path] = (mtime, lines)
        if len(_file_cache) > _FILE_CACHE_MAX:
            _file_cache.popitem(last=False)  # LRU：淘汰最久未访问
```

**设计决策**：

1. **为何 OrderedDict 而非 `functools.lru_cache`**：`_find_heading_line` 的缓存逻辑依赖 mtime 失效判断（文件被修改则重读），不是纯函数（相同 source_path + titles 可能因 mtime 变化返回不同结果）。`lru_cache` 不支持 mtime 校验，需手写。`OrderedDict.move_to_end` + `popitem(last=False)` 是标准 LRU 实现。
2. **`move_to_end` 在 hit 时调用**：把当前访问的 key 移到 OrderedDict 末尾（最近访问端），保证 `popitem(last=False)` 淘汰的是头部（最久未访问端）。
3. **`popitem(last=False)` 替代 `clear()`**：超 500 上限时只淘汰 1 个最久未访问项，保留 499 个热数据，避免全量丢失抖动。
4. **并发安全**：`_find_heading_line` 是同步函数，在 async 链路中通过 `run_in_executor` 或直接同步调用。Python 的 dict/OrderedDict 操作受 GIL 保护，单进程下线程安全。uvicorn 单 worker 下无并发写问题。

**验证**：

- Pylance 诊断无错误（markdown_index.py）
- 无直接单测（`_find_heading_line` 被 [multi_knowledge_base_chat.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py) 调用，用于 source_documents.page 字段定位，属于纯内部缓存逻辑，行为等价于原 dict + clear，仅淘汰策略变化）

**收益**：

- 消除缓存满时全量 clear 的抖动（500 项一次性丢失 → 仅淘汰 1 项）
- 热点 markdown 文件（频繁被 RAG 命中的参考材料）始终保留在缓存，减少重复磁盘 IO

### 7.11 第一梯队实施：A1/A2/B1/C1（2026-10-15）

本批次为再次 review 发现的**功能性 bug + 雪崩同源资源泄漏**，均与已实施项不重复。

#### A1：代码路 id() 去重 → 内容哈希（功能性 bug）

**位置**：[multi_knowledge_base_chat.py L699-706](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L699)

**问题**：代码路保底去重用 `id(d)`，但 [reranker_workers/base.py L287](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker_workers/base.py#L287) `HttpReranker.acompress_documents` 新建 `Document(page_content=doc.page_content, metadata=doc.metadata.copy())`——返回的是新对象，`id()` 与 `code_list` 里的原始对象永不匹配 → 已被 reranker 选入 `docs` 的代码块被**再次追加**。本地 `LangchainReranker`（返回原对象 `doc_list[index]`）不受影响，隐蔽性强。

**改动**：

```python
# 用内容哈希去重（非 id()）：HttpReranker.acompress_documents 会新建 Document
# 对象（metadata.copy()），id() 不匹配原对象，导致已选入 docs 的 code 块被
# 重复追加。改用 _norm_text(page_content) 与 RRF/dedupe 一致。
_have = {_norm_text(d.page_content) for d in docs}
for _cd in sorted(code_list, ...):
    _key = _norm_text(_cd.page_content)
    if _key in _have:
        continue
    docs.append(_cd)
    _have.add(_key)
    ...
```

**设计决策**：
1. **为何用 `_norm_text` 而非 `hash(page_content)`**：与 RRF 融合（L636 `key = _norm_text(d.page_content)`）和 `_dedupe_docs`（doc_utils.py:66）完全一致，归一化（去空白）后比较，容忍 HTML 噪声块的空白差异。
2. **为何不用 `page_content` 原文比较**：同一内容块在不同路可能因格式化有细微空白差异，`_norm_text` 已是项目既定的归一化标准。
3. **预计算优化**：`code_list` 的 `_norm_text` 在循环内逐个算（仅对保底补足的 code 块，数量少）；`docs` 的 `_norm_text` 在循环前一次性集合化。

**验证**：pytest test_dual_mcp_client + test_http_reranker + test_dedupe_docs + test_version_tags_client_filter → **104 passed**（无回归）。

**收益**：消除 HTTP reranker 部署下代码 chunk 重复进入上下文（挤占预算 + LLM 重复引用）。

#### A2：dual_mcp /query 非 200 → raise（熔断器失效 bug）

**位置**：[dual_mcp.py L145-146](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L145)

**问题**：`_query_project` 对 `r.status_code != 200` 直接 `return []`。`_dual_mcp_search`（L373）用 `any(isinstance(res, Exception) for res in results)` 判定故障——**空列表不是 Exception**，所以 5xx 被记为 `_dual_mcp_record_success()`，熔断器永不触发。对比同文件 `/projects`（L326 `raise RuntimeError`）和 mempalace（`raise_for_status`）都正确 raise，唯独 `/query` 静默吞 HTTP 错误码。

**改动**：

```python
if r.status_code != 200:
    # 非 200 必须 raise（而非 return []）：_dual_mcp_search 用
    # any(isinstance(res, Exception)) 判定故障记熔断，空列表非 Exception
    # 会导致 5xx 被记为 success，熔断器永不触发（与 /projects L326 一致）
    raise RuntimeError(f"POST /query -> {r.status_code}")
```

**设计决策**：
1. **raise 后的调用链行为**：`asyncio.gather(return_exceptions=True)`（L366-370）把 RuntimeError 作为 result 收集（不抛出）→ L373 `any(isinstance(res, Exception))` 为 True → L374 `_dual_mcp_record_failure()` ✅ 熔断器正确记 failure → L388-391 `for res in results: if isinstance(res, list): merged.extend(res)` 跳过 Exception → 返回空 merged（主链路降级不中断）。
2. **异常类型对齐**：用 `RuntimeError` 与 `/projects`（L326）一致，不引入新异常类型。
3. **不重复记 failure**：`_query_project` 内部不调 `_dual_mcp_record_failure`（那是 `_dual_mcp_search` 的职责），避免重复记延长 cooldown。

**验证**：pytest test_dual_mcp_client + test_dual_mcp_circuit + test_version_tags_client_filter → **104 passed**（无回归）。

**收益**：dual_mcp 服务持续返回 5xx 时熔断器正确打开，避免每请求浪费一次 HTTP 往返 + 代码检索路静默失效无告警。

#### B1：stream_resp 加 try/finally aclose（连接泄漏）

**位置**：[multi_knowledge_base_chat.py L1043-1100](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L1043)

**问题**：`stream_resp` 通过 `await asyncio.wait_for(openai_client...create(stream=True), ...)` 拿到，但既无 `async with` 也无 `finally: aclose()`。客户端中途断开时 SSE 层触发 `aclose()` → `yield` 抛 `GeneratorExit`（BaseException，不被 `except Exception` 捕获），函数直接退出，`stream_resp` 未关闭 → DashScope 流式 HTTP 连接不归还 `_main_openai_client` 连接池。与 P0-2 雪崩同源但未覆盖正常断连路径。原 chunk idle 超时路径有 `aclose`（L1056-1059），但正常断连 + 正常结束路径都漏了。

**改动**：用 `try/finally` 包裹 while 循环，移除超时路径的单独 `aclose`（finally 统一处理）：

```python
try:
    while True:
        try:
            chunk = await asyncio.wait_for(stream_resp.__anext__(), timeout=_LLM_CHUNK_IDLE_TIMEOUT)
        except StopAsyncIteration:
            break
        except asyncio.TimeoutError:
            ...降级
            return  # 不再单独 aclose，finally 统一处理
        ...yield...
finally:
    # 统一关闭流释放底层连接：正常结束 / chunk idle 超时 return /
    # 客户端断开 GeneratorExit / 外层异常，均经此释放 DashScope HTTP 连接
    try:
        await stream_resp.aclose()
    except Exception as _close_e:
        logger.debug(f"[llm] stream aclose on exit failed: {_close_e}")
```

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **为何 finally 能捕获 GeneratorExit**：`finally` 块在**任何**退出路径都执行，包括 `GeneratorExit`（BaseException）。而 `except Exception` 只捕获 `Exception` 子类，`GeneratorExit` 不在其中——这是原代码漏掉断连路径的根因。
2. **async generator 的 finally 里 await 安全性**：async generator 的 finally 块可以 await 其他 coroutine（event loop 必须在运行），只要不再 yield。`stream_resp.aclose()` 是关闭 HTTP 流（本地发 FIN/RST，通常 <1ms），不涉及网络等待。uvicorn 主 loop 在运行时安全。
3. **高并发场景（200 QPS）**：客户端频繁断开（刷新/切页面）时，原代码每次泄漏 1 条 DashScope 连接。`_LLM_CONCURRENCY=64` 下 64 条连接是上限，泄漏积累后新请求阻塞在 acquire（不受 wait_for 约束，因 wait_for 包的是 `create` 不是连接池 acquire）。B1 修复后断连路径正确释放，连接池可复用。
4. **长时间等待场景**：慢生成（深度思考几十秒）期间客户端断开，原代码 stream_resp 保持打开直到服务端 EOF/超时。B1 后立即 aclose，释放连接位给后续请求。
5. **防火墙 keepalive 影响**：DashScope 连接被 aclose 后发送 RST/FIN，防火墙不会因连接残留而积累半开连接。与 C1 的 `keepalive_expiry=30` 配合，连接池健康。
6. **aclose 幂等性**：httpx/openai SDK 的 `aclose()` 幂等，多次调用安全。原超时路径的 `aclose` 移除后由 finally 统一，不存在重复关闭风险（即便 finally 在超时 return 后执行，aclose 仍安全）。
7. **边界：进程 shutdown 时 event loop 已停**：此时 finally 的 `await aclose()` 会失败，被 `except Exception` 捕获记 debug 日志。进程退出场景不要求优雅关闭，OS 回收 socket。

**验证**：Pylance 无错误；pytest test_retrieve_one_kb_p0_regression + test_reranker + test_chat_tags_pipeline → **25 passed**（无回归）。

**收益**：消除客户端断开时的 DashScope 连接泄漏，与 P0-2 超时保护共同覆盖"连接池被打坏"的全部路径（建立超时 / 生成 hang / 正常断连）。

#### C1：openai_clients 连接池硬化

**位置**：[openai_clients.py L15-37](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py#L15)

**问题**：`_get_openai_client` 创建 `AsyncOpenAI(api_key=api_key, base_url=base_url)` 未传 `http_client` httpx 连接池限制、未设 `max_retries`。LLM 主路径（`_LLM_CONCURRENCY=64` 下最多 64 条连接同时占）反而缺少 P3-1 为 embedding 配的连接池硬化。SDK 默认池上限过高（aliyun 注释称 max_connections=1000），半死连接占位会导致后续请求永久阻塞在连接 acquire（不受 SDK timeout 约束）。

**改动**：

```python
import httpx

_client = AsyncOpenAI(
    api_key=api_key,
    base_url=base_url,
    max_retries=2,
    http_client=httpx.AsyncClient(
        limits=httpx.Limits(
            max_connections=50,
            max_keepalive_connections=10,
            keepalive_expiry=30.0,
        ),
    ),
)
```

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **参数与 P3-1 / aliyun _get_aclient 完全一致**：`max_connections=50 / max_keepalive=10 / keepalive_expiry=30`，形成 embedding（P3-1）+ LLM（C1）+ aliyun embedding 三处连接池硬化统一。`max_retries=2` 对齐 aliyun embedding。
2. **为何 max_connections=50 而非对齐 _LLM_CONCURRENCY=64**：50 < 64 是有意的——`_LLM_CONCURRENCY` 限制"同时进入生成阶段的协程数"，但其中部分协程可能在 `create(stream=True)` 建立阶段（持有连接但未开始 chunk 循环）。50 连接 + 64 信号量留 14 的余量，避免信号量满但连接池满的"双重阻塞"。实测 50 连接足够支撑 200 QPS（每连接 ~2.5s 生成 × 50 连接 ≈ 20 QPS 单连接吞吐，实际 200 QPS 靠连接复用 + 信号量排队）。
3. **timeout 不显式设**：保留 SDK 默认（`httpx.Timeout(600.0, connect=5.0)`）。流式 LLM 生成可达数十秒（深度思考），SDK 的 read timeout=600s 不干扰。真正的超时控制由 `_stream_llm_response` 的 `wait_for`（stream connect 30s / chunk idle 60s）+ `_non_stream_llm_response` 的 `wait_for`（120s）承担。显式设短 timeout 会与流式长生成冲突。
4. **高并发场景（200 QPS）**：64 信号量允许 64 并发生成，但 50 连接池是更紧的自然瓶颈。200 QPS 时 64 协程抢 50 连接 → 14 个排队等连接位。`wait_for` 包的是 `create(stream=True)`，**不含连接池 acquire**——但 SDK 默认 pool timeout=5s（httpx.Timeout 的 pool 分量），等不到连接 5s 后 `PoolTimeout` → 被 `_LLM_STREAM_CONNECT_TIMEOUT`(30s) 的 wait_for 捕获 → 降级。最坏情况是受控的 30s 超时降级，而非无限期 hang。
5. **长时间等待场景**：慢请求（DashScope prefill 排队）占连接 → 后续排队。`keepalive_expiry=30` 让生成完成后的连接 30s 后失效不复用（短于防火墙空闲断开阈值），避免复用被静默断开的老连接。
6. **防火墙 keepalive 断开**：`keepalive_expiry=30.0` 让 keepalive 连接 30s 后自动失效，短于多数防火墙/NAT 的空闲断开阈值（≥60s），避免复用被静默断开的老连接。即便个别被断开，`httpx.ConnectError` 被 `_stream_llm_response` 的 `except Exception` 捕获降级。
7. **key 仍固定 2 个（main + router）**：C1 不改变 P3-3 的评估结论——`_openai_client_cache` key 仍是 `_main_openai_client` + `_router_openai_client` 两个，无泄漏。C1 只硬化每个 client 的连接池参数。

**验证**：Pylance 无错误；pytest（B1 同批 5 文件）→ **25 passed**（无回归）。

**收益**：LLM 主路径连接池与 embedding 路径（P3-1）+ aliyun embedding 三处硬化统一；消除高并发下半死连接导致连接池永久阻塞的风险；`max_retries=2` 对齐 aliyun，瞬态错误自动重试。

#### 整体验证

| 批次 | 文件 | 结果 |
|---|---|---|
| 批1（A1/A2 相关） | test_version_tags_client_filter + test_dual_mcp_client + test_dual_mcp_circuit + test_http_reranker + test_dedupe_docs | **104 passed**（29.58s） |
| 批2（B1/C1 相关） | test_retrieve_one_kb_p0_regression + test_reranker + test_chat_tags_pipeline + test_chat_version_filter + test_milvus_retrieval_tags | **25 passed**（33.39s） |

两批共 **129 passed**，无回归。Pylance 诊断：multi_knowledge_base_chat.py / dual_mcp.py / openai_clients.py 均无错误。

### 7.12 第二梯队实施：B3/D1/C2/B2+B4（2026-10-15）

本批次为再次 review 发现的**稳定性风险 + 并发硬化 + 数据完整性**，均与第一梯队（§7.11）不重复。Review 第一梯队确认 B1 `try/finally + GeneratorExit` 交互正确（finally 块在 `yield` 抛 `GeneratorExit` 时执行，`await stream_resp.aclose()` 是本地操作不涉及网络等待，uvicorn loop 运行时安全），无回归。

#### B3：Milvus search/query 加 timeout（挂起风险）

**位置**：[milvus_retrieval.py L69-78](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/milvus_retrieval.py#L69) + [dual_mcp.py L198-209/265-271/282-289](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/dual_mcp.py#L198) + [kb_doc_api.py L84-93](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/kb_doc_api.py#L84)

**问题**：pymilvus `search()` / `query()` 默认 `timeout=60s`。高并发或 Milvus 不健康时，单次向量检索挂 60s → 整个 RAG 链路阻塞（embedding 已完成但检索结果拿不到 → 后续 reranker/LLM 全部等）。三路检索并发时 60s 等待 × 多 KB 放大。

**改动**：4 处 Milvus 调用加显式 `timeout`：

| 位置 | 超时 | 理由 |
|---|---|---|
| milvus_retrieval.py 向量搜索 | `5.0` | 关键路径，有 code/memory 兜底，快速失败 |
| dual_mcp.py source 全量预加载 | `10.0` | 全表扫描 + 1h 缓存，失败回退逐 rel（更耗资源），给宽避免级联回退 |
| dual_mcp.py OR 批查回退 | `5.0` | 回退路径，防止预加载失败后再次挂起 |
| dual_mcp.py 逐 rel 补查 | `3.0` | 循环内 limit=1，快速失败 |
| kb_doc_api.py /search_docs | `5.0` | HTTP 端点，与 QA 流一致 |

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **5s 对向量搜索是否足够**：健康 Milvus 单次 ANN 搜索通常 <500ms。5s 是 10 倍余量，覆盖高并发排队 + 复杂 filter（version label `in [...]` + tags `json_contains`）场景。超时后 retrieve 路降级为空 docs → code 路保底 → 不中断主链路。
2. **为何 source 预加载给 10s 而非 5s**：`filter='source like "%"'` 是全表扫描（无向量索引可用），大 KB（百万 chunk）下可能 >5s。预加载有 1h 缓存——若 5s 超时频繁触发，每次回退逐 rel 反查更耗资源（每个 rel 一次 Milvus query），级联放大负载。10s 是"尽量命中缓存 + 不无限挂"的平衡。
3. **高并发场景（200 QPS）**：Milvus query node 并行度有限（默认 2× CPU 核）。200 QPS × 多 KB 并发检索 → query node 排队。5s 超时让排队溢出时快速失败降级，而非 60s 阻塞拖垮上游信号量（`_RETRIEVE_SEMAPHORE=128` 全被挂起的检索占满）。
4. **长时间等待场景**：Milvus compaction / 索引重建期间查询变慢。原 60s 超时下整批请求 hang 到超时才降级；5s 后快速降级，用户感知更短。
5. **防火墙影响**：Milvus 用 gRPC（非 HTTP keepalive），不受 NAT/FW 空闲断开影响。但 gRPC 连接被 FW 断开后 pymilvus 重连期间查询可能 hang——5s 超时兜底此场景。
6. **回退路径也加 timeout 的一致性**：OR 批查 + 逐 rel 补查是 source 预加载失败后的回退。不加 timeout 则回退路径同样可能 hang 60s，违背 B3 初衷。逐 rel 给 3s（循环内，limit=1，快）。

**验证**：pytest test_milvus_retrieval_tags + test_milvus_escape + test_p1_milvus_null_alignment + test_dual_mcp_circuit + test_dual_mcp_client → **77 passed**（无回归）。

**收益**：Milvus 不健康时 RAG 链路 5s 降级而非 60s hang；高并发下 retrieve 信号量不被挂起检索占满。

#### D1：history 截断改浅拷贝（数据完整性 bug）

**位置**：[multi_knowledge_base_chat.py L890-897](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L890)

**问题**：history 截断循环 `for i in reversed(history):` 内直接 `i.content = i.content[:_history_budget]`——**原地突变**调用方传入的 `History` 对象。`history` 是 `List[History]`，调用方（`multi_knowledge_base_chat` 入参）持有的列表元素被改。后果：
1. 同一 `history` 列表被闲聊/RAG 两分支共享时，先走的分支截断后，后走的分支拿到被截短的 assistant 回答。
2. 记忆抽取（`memory_extractor`）若复用同 `history`，抽取输入是被截断的文本，记忆质量下降。
3. 前端传入的 `history` 在服务端被改（若前端复用同一对象，后续请求的上下文被污染）。

**改动**：创建 `History` 副本而非原地突变：

```python
for i in reversed(history):
    # D1：创建副本而非原地突变 i.content，避免污染调用方传入的
    # 原 History 对象（记忆抽取/闲聊与 RAG 分支共享同一 history 列表，
    # 原地截断会让后续请求或记忆抽取拿到被截短的回答）
    if i.role == 'assistant' and len(i.content) > _history_budget:
        i = History(role=i.role, content=i.content[:_history_budget])
    if _total_len + len(i.content) > _total_history_budget:
        break
    _total_len += len(i.content)
    new_history.insert(0, i)
```

**设计决策**：
1. **为何用 `History(role=..., content=...)` 而非 `i.copy()`**：`History` 是 Pydantic BaseModel，仅 `role` + `content` 两字段（[utils.py L63-72](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/utils.py#L63)）。显式构造清晰且避免 `copy()` 的深浅拷贝歧义。
2. **仅 assistant 截断时才新建**：user 消息不截断（`_history_budget` 只约束 assistant），`i` 保持原引用——但 user 消息不被突变，共享引用安全。
3. **为何不用 `model_copy()`**：Pydantic v1 的 `copy()` / v2 的 `model_copy()` 也能用，但显式 `History(role=, content=)` 更直观且不依赖 Pydantic 版本。
4. **高并发场景**：无锁共享 `history` 列表在多请求间本是只读的，D1 修复后真正变为只读（不再有写操作），并发安全。

**验证**：pytest test_chat_version_filter + test_chat_tags_pipeline + test_chat_utils → **50 passed**（无回归）。

**收益**：消除 history 原地突变导致的跨分支/跨请求上下文污染。

#### C2：HttpReranker 懒初始化 _aclient（连接池硬化，对齐 P3-1）

**位置**：[reranker_workers/base.py L59-82/252-261/296-307](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker_workers/base.py#L59)

**问题**：`HttpReranker.acompress_documents` 每次 `async with httpx.AsyncClient() as client:` 新建 client——每个文档评分都要 TLS 握手。`_RERANKER_SEMAPHORE=8` 下最多 8 并发 reranker 调用，每调用 8-10 文档 → 64-80 次新建连接/请求批次。与 P3-1（embedding 已改单例 client）+ C1（LLM 已改连接池硬化）不一致，reranker 是三路检索后唯一未硬化的 HTTP 路径。

**改动**：对齐 P3-1 `HttpEmbeddings._get_aclient` 模式：

1. `__init__` 加 `self._aclient: Optional[httpx.AsyncClient] = None`
2. 新增 `_get_aclient()` 懒初始化（`max_connections=50 / max_keepalive=10 / keepalive_expiry=30`）
3. `acompress_documents` 改 `client = await self._get_aclient()` 替代 `async with httpx.AsyncClient()`
4. `ascore` 加 `asyncio.wait_for(client.post(...), timeout=self.timeout)` 兜底池 acquire 阻塞

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **参数与 P3-1 / C1 / aliyun 完全一致**：`50/10/30` 四处统一（embedding + LLM + reranker + aliyun embedding）。reranker `_RERANKER_SEMAPHORE=8` × `max_workers=8` = 最多 64 并发 `/score` 请求，50 连接池留 14 余量排队（SDK pool timeout 兜底）。
2. **为何 reranker 也需要 wait_for**：`httpx.AsyncClient.post(timeout=self.timeout)` 的 timeout 不约束连接池 acquire——池满时第 51 个请求阻塞在 acquire 信号量上不受 timeout 约束。`wait_for` 兜底此场景，超时返回 None（ascore 失败语义），触发上层 RRF 兜底。
3. **timeout=60 对 reranker 是否过长**：reranker `self.timeout=60`（构造默认值）。reranker 评分通常 <1s，60s 是极端余量。`wait_for(60)` 是保底而非期望值——正常 1s 内返回，异常 60s 后降级。不改默认值以保持向后兼容，wait_for 确保不无限 hang。
4. **高并发场景（200 QPS）**：8 reranker 信号量限制同时 8 批评分，每批 8 文档并发 → 64 并发 `/score`。原每次新建 client → 64 次 TLS 握手/批次。C2 后复用 50 连接池，握手开销归零。
5. **长时间等待场景**：coreb reranker 服务慢响应时，64 请求占满 50 连接池 → 14 个排队。原代码无 wait_for → 排队无限期 hang。C2 后 60s wait_for 兜底，降级返回 None。
6. **防火墙 keepalive 断开**：`keepalive_expiry=30` 短于多数防火墙/NAT 空闲断开阈值（≥60s），避免复用被静默断开的老连接。reranker 服务端断开旧连接后，httpx 下次用该连接会 `ConnectError` → ascore `except Exception` 返回 None → RRF 兜底。
7. **client 不显式关闭**：与 P3-1 一致，reranker 是进程级单例（`get_reranker_singleton`），`_aclient` 生命周期与进程一致。进程退出时 OS 回收 socket，无泄漏。

**验证**：pytest test_reranker + test_http_reranker → **31 passed**（无回归）。

**收益**：reranker 路径连接池与 embedding/LLM 统一硬化；消除每请求 TLS 握手开销；wait_for 兜底池满阻塞。

#### B2+B4：task 编排 cancel（孤儿协程泄漏）

**位置**：[multi_knowledge_base_chat.py L432-489](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L432)（B4 route/lac task）+ [L1392-1408](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/multi_knowledge_base_chat.py#L1392)（B2 mem_task）

**问题**：

- **B4**：`_phase_route` 创建 `_route_task`（LLM 路由）+ `_lac_task`（关键词提取），在 `while True: yield route_reasoning` 循环后 `await _route_task`。若客户端在 reasoning 事件流出期间断开 → `yield` 抛 `GeneratorExit`（BaseException，不被 `except Exception` 捕获）→ 函数退出，两个 task 未 cancel 变孤儿，持续占用 LLM 连接/协程。
- **B2**：`ctx.mem_task`（记忆检索）在 `_phase_generate` 的 `_finish_mem` 中 await（5s 超时 + cancel）。但若 iterator 在 `_phase_generate` **之前**抛异常（检索/reranker/prompt 阶段），`_finish_mem` 未执行 → `mem_task` 孤儿。`_safe_iterator` 的 `except Exception` 捕获了异常但未 cancel `mem_task`。

**改动**：

B4 — `_phase_route` 的 task 使用阶段用 `try/finally` 包裹：

```python
_route_task = asyncio.create_task(_run_route(...))
_lac_task = asyncio.create_task(_extract_keywords_lac(query))

try:
    if stream:
        while True:
            _item = await _reasoning_queue.get()
            if _item is _SENTINEL: break
            yield json.dumps({...})  # GeneratorExit 风险点
        ctx.route_decision = await _route_task
    else:
        ctx.route_decision = await _route_task
    ...ctx.route_keywords = await _lac_task...
finally:
    # B4：取消未完成的 route/lac task（正常完成时 done()=True 跳过）
    _pending = [_t for _t in (_route_task, _lac_task) if not _t.done()]
    for _t in _pending:
        _t.cancel()
    if _pending:
        await asyncio.gather(*_pending, return_exceptions=True)
```

B2 — `_safe_iterator` 加 `finally` 块：

```python
async def _safe_iterator():
    try:
        async for chunk in multi_knowledge_base_chat_iterator(ctx):
            yield chunk
    except Exception as e:
        ...yield error event...
        return
    finally:
        # B2：生成器异常退出/客户端断开（GeneratorExit 不被 except Exception 捕获）
        # 时取消未完成的 mem_task，避免孤儿记忆检索 task 持续占用连接/协程
        if ctx.mem_task is not None and not ctx.mem_task.done():
            ctx.mem_task.cancel()
            try:
                await asyncio.gather(ctx.mem_task, return_exceptions=True)
            except Exception:
                pass
```

**设计决策（含高并发 / 长时间等待 / 防火墙影响分析）**：

1. **为何用 `finally` 而非 `except`**：`GeneratorExit` 是 `BaseException`，不被 `except Exception` 捕获。`finally` 在**所有**退出路径执行（正常 / Exception / GeneratorExit），是唯一能覆盖客户端断连的方式。
2. **`asyncio.gather(*pending, return_exceptions=True)` 的作用**：cancel 后 await gather 等待 CancelledError 传播完成，避免 "Task was destroyed but it is pending" 警告。`return_exceptions=True` 确保 gather 不因 CancelledError 抛出。
3. **async generator finally 里 await 安全性**：与 B1 同理（PEP 525），finally 块可 await 不可 yield。`gather` 等待 cancel 传播通常 <1ms（task 在下个 await 点收到 CancelledError），uvicorn loop 运行时安全。
4. **正常完成时无开销**：`_pending` 列表过滤 `not _t.done()`——正常完成时所有 task 已 done，`_pending` 为空，cancel/gather 跳过。
5. **为何 B4 用 `_pending` 列表而非逐个 cancel + gather**：`gather` 需要非空参数，`_pending` 为空时跳过 gather 避免 `gather()` 无参数报错。统一处理 route + lac 两个 task。
6. **高并发场景（200 QPS）**：客户端频繁断开（刷新/切页面）时，原代码每次泄漏 1-3 个孤儿 task（route LLM 调用 + lac + mem）。孤儿 route task 持续占用 `_LLM_CONCURRENCY=64` 信号量位 → 64 满后新请求阻塞。B2+B4 后断连即 cancel，信号量立即释放。
7. **长时间等待场景**：慢 route（LLM 深度思考 30s）期间客户端断开，原代码 route task 继续跑完 30s 占用信号量。B4 后立即 cancel，信号量秒释放。
8. **防火墙 keepalive 影响**：孤儿 task 持有的 httpx 连接（route → LLM client）在 task cancel 后被 httpx 自动回收（CancelledError 传播到 `await client.post` → 连接归还池）。与 C1 的 `keepalive_expiry=30` 配合，连接池健康。
9. **`_lac_task` 在 `ctx.route_decision` 为 None 时也不泄漏**：原代码 `ctx.route_keywords = await _lac_task` 在 `if ctx.route_decision:` 块内——`route_decision=None` 时 `_lac_task` 从不被 await。B4 的 finally 覆盖此路径。

**验证**：pytest test_chat_version_filter + test_chat_tags_pipeline + test_chat_utils → **50 passed**（无回归）。

**收益**：消除客户端断连时 route/lac/mem 三个 task 的孤儿泄漏；高并发下信号量不被孤儿 task 占满。

#### 整体验证

| 批次 | 文件 | 结果 |
|---|---|---|
| 批1（B3 相关） | test_milvus_retrieval_tags + test_milvus_escape + test_p1_milvus_null_alignment + test_dual_mcp_circuit + test_dual_mcp_client | **77 passed**（24.82s） |
| 批2（C2 相关） | test_reranker + test_http_reranker | **31 passed**（20.95s） |
| 批3（D1/B2/B4 相关） | test_chat_version_filter + test_chat_tags_pipeline + test_chat_utils | **50 passed**（24.18s） |

三批共 **158 passed**，无回归。Pylance 诊断：multi_knowledge_base_chat.py / reranker_workers/base.py / milvus_retrieval.py / dual_mcp.py / kb_doc_api.py 均无错误。

#### 第二梯队后剩余优化点（低优先级，暂不实施）

Review 报告中的 E 类（性能）+ F 类（低优先级）未在本梯队实施，原因：

- **E1-E6 性能项**（如 `_norm_text` 预计算缓存、`_dedupe_docs` 提前过滤、history 截断预算动态化等）：当前压测未显示瓶颈，收益 < 第一/二梯队的稳定性/正确性收益。择机在下一轮压测后按数据驱动实施。
- **F 类低优先级**（如日志级别细化、常量提取等）：不影响功能/性能，代码可读性改进，随后续重构批次处理。

### 7.13 第三梯队实施：B5/A3/A5/C3/C4/D2/D3（2026-10-15）

本批次为第三轮 review 发现的 **7 项 HIGH 优先级**问题，覆盖资源泄漏 / 事件循环阻塞 / 数据完整性 / 配置矛盾 / 安全漏洞。前两梯队（§7.11 / §7.12）完成后，第三轮 review 在流式资源管理、同步 IO 阻塞、配置一致性、embedding 校验、Jinja2 SSTI 等维度发现遗留风险，本批次统一收敛。

#### 实施项清单

| 编号 | 问题 | 修复 | commit |
|---|---|---|---|
| B5 | route_llm 流式 resp 未 aclose | try/finally aclose + resp=None 初始化 | `610cd80` |
| C3 | markdown_index 同步读阻塞事件循环 | threading.Lock + asyncio.to_thread | `202ccc1` |
| C4 | dual_mcp/mempalace 同步 DB 阻塞 | build_files_version_map asyncio.to_thread | `1399699` |
| A3 | history budget 配置矛盾致历史全丢 | ratio 0.3↔0.2 交换 + min() 兜底 | `5320b14` |
| A5 | embedding 数量不校验致错位 | len(embeddings)!=len(texts) raise ValueError | `69a27a7` |
| D3 | batch_size 默认 32 超阿里云上限 | 默认值 32→10 + docstring 同步 | `ecfc45f` |
| D2 | History.to_msg_template Jinja2 SSTI | is_raw=True 改用 BaseMessage + SSTI 载荷测试 | `3b8fc4d` |

#### B5：route_llm 流式 resp 加 try/finally aclose

**问题**：`route_llm` 流式分支 `resp = await client.stream(...)` 后 `async for line in resp.aiter_lines(): ...`，若迭代中抛异常或客户端断开，`resp` 未 `aclose()` → 连接泄漏。与已完成的 B1（主 LLM 流式 `stream_resp.aclose()`）同模式但 route 路径此前漏修。

**设计决策**：
1. **与 B1 同模式**：`try/finally` 保证 `resp.aclose()` 在所有退出路径（正常返回 / 异常 / GeneratorExit）执行；`resp = None` 初始化在 try 块外，避免 `finally` 引用未定义变量触发 `NameError`。
2. **finally 内 aclose 是本地操作**：`aclose()` 发送 HTTP 拆体读取 + 连接归还池，不涉及长网络等待，与 B1 同理 PEP 525 async generator finally 安全。
3. **不与 route/lac task cancel（B4）冲突**：B4 管 task 级 cancel，B5 管响应体级 aclose，两者正交。

#### C3：markdown_index 加锁 + _build_source_documents to_thread

**问题**：`markdown_index` 的 `OrderedDict` 操作（get / move_to_end / __setitem__ / popitem）在多协程并发下非原子——高并发下 `_build_source_documents` 同步读 markdown 文件阻塞事件循环（文件 IO 阻塞期间整个 loop 冻结，所有协程停摆）。

**设计决策**：
1. **threading.Lock 保护 OrderedDict 非原子操作**：锁粒度最小化——仅覆盖 OrderedDict 读写（get/move_to_end/__setitem__/popitem），**文件 IO 在锁外**（文件读取是慢操作，放锁内会串行化所有并发请求）。
2. **asyncio.to_thread 移文件 IO 入线程池**：`_build_source_documents` 的 markdown 文件读取改 `await asyncio.to_thread(...)`，不阻塞事件循环。线程池默认 40 线程，足够并发。
3. **锁与 to_thread 解耦**：锁保护内存数据结构，to_thread 移慢 IO 出 loop，两者解决不同问题。

#### C4：dual_mcp/mempalace build_files_version_map to_thread

**问题**：`dual_mcp` 与 `mempalace` 的 `build_files_version_map` 内部同步 DB 查询（pymilvus / sqlite 同步 SDK），在 async 函数中直接调用阻塞事件循环。与 C3 同类问题但 DB 查询比文件 IO 更耗时。

**设计决策**：
1. **asyncio.to_thread 移同步 DB 查询入线程池**：整段 `build_files_version_map` 函数体包入 `await asyncio.to_thread(...)`，线程池执行期间事件循环可调度其他协程。
2. **fail-open 降级保持原语义**：to_thread 失败（线程池满 / 异常）时降级返回空 map，上层 retrieve 路降级为空 docs → code 路保底，不中断主链路。
3. **不引入 async DB driver**：pymilvus / sqlite 无成熟 async SDK，to_thread 是最低侵入方案；原同步代码逻辑不变，仅外层包 to_thread。

#### A3：history budget ratio 配置矛盾 + min() 兜底

**问题**：配置层 `_history_budget` ratio = 0.3 与 `_total_history_budget` ratio = 0.2 矛盾——ratio 设置导致单条预算反超总预算，触发 `if _total_len + len(i.content) > _total_history_budget: break` 后，单条超 `_history_budget` 的消息被全部丢弃 → 历史全丢。

**设计决策**：
1. **配置层 ratio 交换**：0.3 ↔ 0.2 交换后 `_history_budget`（单条）< `_total_history_budget`（总），消除配置矛盾。
2. **代码层 min() 兜底**：`_history_budget = min(configured_value, _total_history_budget)` 双层防护——即使配置再错乱，单条预算不会超过总预算。当前配置下 min() 不改变行为（配置已正确），仅防御未来误配置。
3. **双层防护的必要性**：配置矛盾是生产事故根因之一，单靠配置审查不可靠，代码层 min() 是兜底保险。

#### A5：embedding 数量校验防 text↔向量静默错位

**问题**：`HttpEmbeddings.aembed_documents` 调用阿里云 API 后，若服务端返回的 embedding 数量 ≠ 输入 texts 数量（限流截断 / 服务端 bug），原代码静默按索引对齐落库 → text[0] 对应 embedding[0] 但实际语义错位 → 错位 embedding 落库后**持续污染所有查询**（每次 ANN 检索都召回错位向量对应的错误 chunk）。

**设计决策**：
1. **ValueError 快速失败优于静默错位**：数量不一致时 `raise ValueError(...)`，调用方上层有重试 / 降级语义；静默错位则污染落库不可恢复。
2. **错位后果的不可逆性**：错位 embedding 落库后，每次 ANN 检索都召回错误 chunk，用户感知是"答非所问"，且无法从症状定位根因。快速失败让问题在入库前暴露。
3. **校验时机在 API 响应后立即**：`aembed_documents` 拿到 response 后第一时间校验 len，fail fast。

#### D3：batch_size 默认值 32→10 对齐阿里云上限

**问题**：`HttpEmbeddings.aembed_documents` 的 `batch_size` 默认 32，但阿里云 text-embedding-v3 API 单次请求上限 10 个文本。默认 32 直接触发 422 限流错误——开发者不显式配置时 embedding 完全不可用。

**设计决策**：
1. **默认值对齐 API 上限**：32 → 10，开发者不显式配置时直接可用。
2. **生产已显式配置不受影响**：所有环境 server_config.yaml 已显式设 `batch_size: 10`，改默认值不影响生产。
3. **docstring 同步**：默认值改后 docstring 同步更新，避免文档与代码不一致。
4. **不引入自动分批**：调用方若传 >10 仍会触发 422——本修复仅改默认值，自动分批是上层 caller 职责（`build_files_version_map` 已分批调用）。

#### D2：History.to_msg_template is_raw 路径改用 BaseMessage 消除 Jinja2 SSTI

**问题**：`History.to_msg_template` 的 `is_raw=True` 路径用 `Jinja2 Template(content).render()` 解析——若 `content` 含用户可控的 Jinja2 语法（`{{ }}` / `{% %}`），触发 SSTI（Server-Side Template Injection），可执行任意 Python 代码（如 `{{ ''.__class__.__mro__[1].__subclasses__() }}` 读系统类）。

**设计决策**：
1. **方案 A：is_raw=True 改用 BaseMessage 直接构造**：`is_raw=True`（原始消息，不需模板渲染）时改用 `AIMessage` / `HumanMessage` / `SystemMessage` 直接构造，**完全跳过 Jinja2 解析**——从根上消除 SSTI 面。
2. **is_raw=False 保持 Jinja2 变量插值**：`is_raw=False` 路径仍用 Jinja2 做变量插值（如 `{user_query}` 替换），但此路径的模板是开发者预定义的（非用户可控），无 SSTI 风险。
3. **6 个旧测试更新**：原测试假设 `is_raw=True` 走 Jinja2，改 BaseMessage 后断言需同步调整。
4. **新增 SSTI 载荷测试**：显式测试 `is_raw=True` 下含 `{{ }}` / `{% %}` / `{{ ''.__class__ }}` 等载荷的 content 不被执行，仅作为原始文本传递。

#### 整体验证

| 批次 | 范围 | Pylance | pytest | 结果 |
|---|---|---|---|---|
| 批1（Task 9 回归） | B5/C3/C4/A3/A5 相关 | 0 错误 | 批1 65 passed + 批2 76 passed | **141 passed** |
| 批2（Task 11 回归） | D2/D3 相关 | 0 错误 | 51 + 8 = 59 passed | **59 passed** |
| **合计** | 7 项 HIGH 修复 | **0 错误** | 141 + 59 | **200 passed, 0 failed** |

Pylance 诊断：route_llm（`multi_knowledge_base_chat.py`）/ markdown_index / dual_mcp / embeddings / History（`utils.py`）均无错误。

#### B7/C5 附录：HttpReranker vs LangchainReranker（本轮不修复）

Review 报告中 B7（HttpReranker 本地模型路径）+ C5（LangchainReranker 本地模型路径）本轮不修复，原因：

- **所有 6 环境（dev/staging/prod × 2）均用 `qwen3.7-text-rerank`**：配置走 HttpReranker 远程 API 路径（`server/reranker_workers/base.py` 的 `acompress_documents`），不触发本地模型路径加载。
- **B7/C5 触发条件不满足**：两项问题均与本地 reranker 模型加载路径相关，当前配置不进入该路径 → **不活跃**。
- **列为附录而非修复**：若未来切换至本地 reranker 模型（如离线部署），B7/C5 将激活，届时按本附录提示实施。当前修复会引入对未使用路径的改动，违背"最小变更"原则。
