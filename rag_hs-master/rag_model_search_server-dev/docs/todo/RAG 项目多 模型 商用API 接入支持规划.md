# RAG 项目多 模型 商用API 接入支持规划

> 版本：v1.0  
> 日期：2026-10-14  
> 适用项目：`haisi_rag_models_server`

---

## 1. 背景与目标

### 1.1 背景
当前项目 4 类模型（LLM / Embedding / Reranker / VLM）的商用 API 接入面较窄，主要集中在阿里云 DashScope，存在单点供应商风险：
- 配额、限流、价格策略受制于单一平台；
- 模型迭代、降级、故障时无法快速切换；
- 不同任务（路由 / 表格解析 / 通用对话）无法择优选用各平台最强模型。

### 1.2 目标
- **广接入**：4 类模型各自支持 ≥ 5 家主流商用 API，覆盖国内 + 国际主流平台；
- **丝滑切换**：切换模型只改 `model_config.yaml`，不改业务代码；
- **协议收敛**：能用 OpenAI 兼容协议的绝不写适配代码，降低维护成本；
- **统一抽象**：4 类模型采用对齐的 Worker 注册表模式，新增 provider 有固定套路可循。

### 1.3 非目标
- 不做模型聚合网关 / 多模型并发竞速；
- 不做按成本/延迟的动态路由（留作二期）；
- 不替换本地部署模型支持（`MODEL_PATH` 机制保留）。

---

## 2. 现状分析

### 2.1 当前支持的模型

| 类别 | 配置键 | 已支持模型 | 接入方式 |
|------|--------|-----------|----------|
| LLM | `ONLINE_LLM_MODEL` | deepseek-v4-pro、qwen-flash | `AsyncOpenAI` / langchain `ChatOpenAI` |
| Embedding | `EMBEDDING_API_CONFIG` | text-embedding-v3 | `AliyunOpenAIEmbedding` worker |
| Reranker | `RERANKER_API_CONFIG` | qwen3.7-text-rerank、coreb-code-reranker-api | `Qwen3Reranker` / `CorebCodeReranker` worker |
| VLM | `VLM_API_CONFIG` | qwen3.7-plus | `VLMClient`（httpx 直连） |

### 2.2 现有抽象层（可复用）

- **Embedding Worker 注册表**：[server/embedding_workers/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/__init__.py) — `register_embedding_worker` 装饰器 + `get_embedding_worker` 查询，**已是对齐范式，可作为其他三类的模板**。
- **Reranker Worker 注册表**：[server/reranker_workers/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker_workers/__init__.py) — 同构。
- **Worker 缓存指纹**：[server/embeddings_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embeddings_api.py#L24) `_config_fingerprint` — 配置变更自动重建 worker，无需重启。

### 2.3 痛点

| 痛点 | 位置 | 影响 |
|------|------|------|
| LLM 无独立 Worker 抽象层 | 散落在 `llm_api.py` / `openai_clients.py` / `chat.py` | 新增非 OpenAI 协议 LLM 无统一入口 |
| 路由模型名硬编码 | [constants.py:5](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/constants.py#L5) `_ROUTER_MODEL_NAME = "qwen-flash"` | 切换路由模型需改代码 |
| 深度思考耦合模型名 | [llm_api.py:75](file:///d:/Code/haisi/haisi_rag_models_server/server/llm_api.py#L75) `if "deepseek" in model_name.lower()` | 新模型需改判断 |
| VLM 单文件实现 | [server/vlm_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/vlm_api.py) | 无注册表，新增 VLM 平台需改主类 |
| provider 字段未利用 | 配置中 `provider` 仅作展示 | 无法做按 provider 的能力探测 |

---

## 3. 商用 API 支持矩阵

### 3.1 LLM 对话模型

| 平台 | 代表模型 | 协议 | 接入路径 | 优先级 |
|------|----------|------|----------|--------|
| 阿里云 DashScope | deepseek-v4-pro、qwen-flash、qwen-plus | OpenAI 兼容 | 路径 A（仅配置） | P0 已接入 |
| 火山引擎方舟 | doubao-1.5-pro-32k、doubao-seed-1.6 | OpenAI 兼容 | 路径 A | P0 |
| 智谱 AI | glm-4-plus、glm-4.5、glm-4-air | OpenAI 兼容 | 路径 A | P0 |
| DeepSeek 官方 | deepseek-chat、deepseek-reasoner | OpenAI 兼容 | 路径 A | P0 |
| Moonshot | moonshot-v1-8k/32k、kimi-thinking-preview | OpenAI 兼容 | 路径 A | P1 |
| MiniMax | abab6.5s-chat、MiniMax-M1 | OpenAI 兼容 | 路径 A | P1 |
| 百度千帆 | ernie-4.0、ernie-3.5、deepseek-v3 | OpenAI 兼容 | 路径 A | P1 |
| OpenAI 官方 | gpt-4o、gpt-4o-mini、o1系列 | OpenAI 标准 | 路径 A | P2 |
| Azure OpenAI | gpt-4o 等部署 | OpenAI 兼容 | 路径 A | P2 |
| Google Gemini | gemini-2.0-flash、gemini-2.5-pro | OpenAI 兼容端点 | 路径 A | P2 |
| 讯飞星火 | spark-v4、spark-u1 | 自有（WebSocket/SSE） | 路径 B（adapter） | P2 |
| 腾讯混元 | hunyuan-pro、hunyuan-turbo | 自有（HTTP） | 路径 B（adapter） | P2 |
| Anthropic Claude | claude-sonnet-4.5、claude-opus-4 | 自有（Messages API） | 路径 B（adapter） | P3 |

### 3.2 Embedding 向量化模型

| 平台 | 代表模型 | 协议 | 接入路径 | 优先级 |
|------|----------|------|----------|--------|
| 阿里云 DashScope | text-embedding-v3、text-embedding-v4 | OpenAI 兼容 | 复用 `AliyunOpenAIEmbedding` | P0 已接入 |
| 火山引擎 | doubao-embedding-text | OpenAI 兼容 | 复用通用 worker | P0 |
| 智谱 AI | embedding-3、embedding-4 | OpenAI 兼容 | 复用通用 worker | P0 |
| OpenAI | text-embedding-3-small/large | OpenAI 标准 | 复用通用 worker | P1 |
| Jina AI | jina-embeddings-v3 | OpenAI 兼容 | 复用通用 worker | P1 |
| 百度千帆 | Embedding-V1、bge-large | OpenAI 兼容 | 复用通用 worker | P1 |
| MiniMax | embo-01 | 自有 | 路径 B | P2 |
| Cohere | embed-v3 / embed-multilingual-v3 | 自有 | 路径 B | P2 |
| Voyage AI | voyage-3、voyage-code-3 | 自有 | 路径 B | P3 |

> **关键决策**：Embedding 维度（dim）必须随模型记录，切换平台时若维度不一致需重建向量库。建议各模型在配置中显式声明 `embedding_dim`，切换时校验与 Milvus collection 维度一致。

### 3.3 Reranker 重排模型

> Reranker 是协议最碎片的一类，多数平台为自有 HTTP，需逐家写 adapter。

| 平台 | 代表模型 | 协议端点 | 接入路径 | 优先级 |
|------|----------|----------|----------|--------|
| 阿里云 DashScope | qwen3.7-text-rerank | DashScope SDK | 已接入 `Qwen3Reranker` | P0 |
| 本地 Coreb | coreb-code-reranker | 自有 `/score` | 已接入 `CorebCodeReranker` | P0 |
| Jina AI | jina-reranker-v2-base | HTTP POST `/rerank` | 路径 B | P0 |
| Cohere | rerank-v3.5 | HTTP POST `/v2/rerank` | 路径 B | P0 |
| 智谱 AI | rerank | 自有 HTTP | 路径 B | P1 |
| 硅基流动 | BAAI/bge-reranker-v2-m3 | OpenAI 风格 `/rerank` | 路径 B | P1 |
| Voyage AI | rerank-2、rerank-2-lite | 自有 HTTP | 路径 B | P2 |

### 3.4 VLM 视觉模型

| 平台 | 代表模型 | 协议 | 接入路径 | 优先级 |
|------|----------|------|----------|--------|
| 阿里云 DashScope | qwen3.7-plus、qwen-vl-max | OpenAI 兼容 | 通用 worker | P0 已接入 |
| 火山引擎 | doubao-vision-pro | OpenAI 兼容 | 通用 worker | P0 |
| 智谱 AI | glm-4v-plus、glm-4v-flash | OpenAI 兼容 | 通用 worker | P0 |
| OpenAI | gpt-4o、gpt-4o-mini | OpenAI 标准 | 通用 worker | P1 |
| Google Gemini | gemini-2.0-flash | OpenAI 兼容端点 | 通用 worker | P1 |
| Anthropic Claude | claude-sonnet-4.5 | 自有 Messages API | 路径 B | P2 |

---

## 4. 调用格式规范（核心决策）

### 4.1 两条接入路径

| 路径 | 适用 | 工作量 | 说明 |
|------|------|--------|------|
| **路径 A：仅加配置** | OpenAI 兼容协议平台 | 改 yaml，0 行代码 | 90% 的 LLM/Embedding/VLM 平台走此路 |
| **路径 B：写 Worker adapter** | 非 OpenAI 协议平台 | 1 个 worker 类 + 注册 | 讯飞/Claude/Cohere/Jina 等自有协议 |

**优先级原则：能用路径 A 绝不写路径 B。** 写 adapter 前，先查目标平台是否有 OpenAI 兼容端点。

### 4.2 LLM 调用格式规范

**决策：统一用 OpenAI Python SDK 的 `chat.completions.create`，流式 + 非流式都要支持。**

| 模式 | 使用场景 | 接口 |
|------|----------|------|
| 流式（stream=True） | WebUI 对话、思考过程推送 | `AsyncOpenAI(...).chat.completions.create(stream=True)` |
| 非流式 | 表格解析、next_question、路由判定、结构化抽取 | `stream=False` |

**统一输出契约**（所有 adapter 必须产出）：
```python
# 流式：yield ChatDelta
class ChatDelta:
    content: str | None          # 正文增量
    reasoning_content: str | None  # 深度思考增量（deepseek/qwen/glm/o1 通用字段）
    usage: dict | None           # 最终 token 用量（仅末包）

# 非流式：返回 ChatCompletion
class ChatCompletion:
    content: str
    reasoning_content: str | None
    usage: dict
```

**深度思考开关**：不再靠 `model_name` 字符串判断，改为配置项 `supports_reasoning: bool` + 运行参数 `deep_think: bool`，见 §5.3。

### 4.3 Embedding 调用格式规范

- **统一接口**：`embed_texts_with_prompt(texts, prompt_name)` + async 版本
- **prompt_name 语义**：`query` / `document`，OpenAI 兼容平台忽略此参数（仅记录），自有协议平台按各自规范映射
- **维度声明**：配置中 `embedding_dim` 必填，切换平台时与向量库对齐校验
- **批量并发**：复用基类 `_aembed_batch` 的分片 + 信号量逻辑

### 4.4 Reranker 调用格式规范

- **统一接口**：`compress_documents(query, documents) -> List[Document]`（继承 langchain `BaseDocumentCompressor`）
- **内部统一**：各家 adapter 把响应归一为 `[(index, score), ...]`，基类按 score 重排
- **top_n 上限**：沿用 [constants.py:11](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/constants.py#L11) `_RERANK_TOP_N_MAX = 30`，防止成本无界

### 4.5 VLM 调用格式规范

- **统一接口**：`parse_image(image_path/base64, system_prompt, user_prompt) -> str`
- **输入**：统一接受 base64 + MIME 类型（参考 [vlm_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/vlm_api.py) 现有实现）
- **流式可选**：VLM 文本输出量大时建议支持流式，但 PDF 解析场景用非流式即可

---

## 5. 目录结构与代码组织

### 5.1 目标目录结构

对齐现有 `embedding_workers` / `reranker_workers` 范式，为 LLM 和 VLM 补齐同构目录：

```
server/
├── llm_workers/                  # 【新增】LLM 抽象层
│   ├── __init__.py               # 注册表：register_llm_worker / get_llm_worker
│   ├── base.py                   # BaseLLM 抽象基类（统一流式/非流式契约）
│   ├── openai_compatible.py      # 【通用】覆盖阿里云/火山/智谱/DeepSeek/Moonshot/...
│   ├── claude_adapter.py         # Claude Messages API 适配（路径 B）
│   ├── spark_adapter.py          # 讯飞星火适配（路径 B）
│   └── hunyuan_adapter.py        # 腾讯混元适配（路径 B）
│
├── embedding_workers/            # 【已存在】补充通用 worker
│   ├── __init__.py
│   ├── base.py
│   ├── qwen3_embedding.py
│   ├── aliyun_openai_embedding.py
│   ├── openai_compatible.py      # 【新增通用】覆盖所有 OpenAI 兼容 embedding 平台
│   ├── cohere_embedding.py       # 【新增】Cohere 自有协议
│   └── voyage_embedding.py       # 【新增】Voyage 自有协议
│
├── reranker_workers/             # 【已存在】补充各家 adapter
│   ├── __init__.py
│   ├── base.py
│   ├── qwen3_reranker.py
│   ├── coreb_reranker.py
│   ├── jina_reranker.py          # 【新增】Jina /rerank
│   ├── cohere_reranker.py        # 【新增】Cohere /v2/rerank
│   ├── zhipu_reranker.py         # 【新增】智谱 rerank
│   └── siliconflow_reranker.py  # 【新增】硅基流动
│
├── vlm_workers/                  # 【新增】VLM 抽象层（从 vlm_api.py 重构）
│   ├── __init__.py               # 注册表
│   ├── base.py                   # BaseVLM 抽象基类
│   ├── openai_compatible.py      # 【通用】覆盖阿里云/火山/智谱/OpenAI/Gemini
│   └── claude_vlm_adapter.py     # 【新增】Claude vision 适配
│
├── llm_api.py                    # 改造：调用 llm_workers 注册表
├── embeddings_api.py             # 已对接注册表，无需改
├── vlm_api.py                    # 改造为 vlm_workers 的薄封装层
└── chat/pipeline/
    ├── openai_clients.py         # 改造：从 llm_workers 取 client
    └── constants.py              # 改造：_ROUTER_MODEL_NAME 改读配置
```

### 5.2 命名约定

- **通用 worker**：`openai_compatible.py`，类名 `OpenAICompatibleChat` / `OpenAICompatibleEmbedding` / `OpenAICompatibleVLM`，注册名为 `default-llm` / `default-embedding` / `default-vlm`
- **平台 adapter**：`<provider>_<model_type>.py`，如 `claude_adapter.py`、`jina_reranker.py`
- **注册名**：与配置中 model_name 完全一致（这是查询 key）

### 5.3 抽象基类设计

以 `BaseLLM` 为例（其他三类同构）：

```python
# server/llm_workers/base.py
from abc import ABC, abstractmethod
from typing import AsyncIterator, Optional

class ChatDelta:
    def __init__(self, content=None, reasoning_content=None, usage=None):
        self.content = content
        self.reasoning_content = reasoning_content
        self.usage = usage

class BaseLLM(ABC):
    """LLM 抽象基类。所有 adapter 必须实现流式 + 非流式两种调用。"""

    def __init__(self, api_key, api_base_url, model_id, timeout=60,
                 max_retries=3, supports_reasoning=False, **kwargs):
        self.api_key = api_key
        self.api_base_url = api_base_url
        self.model_id = model_id
        self.timeout = timeout
        self.max_retries = max_retries
        self.supports_reasoning = supports_reasoning  # 是否支持深度思考

    @abstractmethod
    async def astream(self, messages, temperature=0.3, max_tokens=None,
                      deep_think=False) -> AsyncIterator[ChatDelta]:
        """流式调用。"""

    @abstractmethod
    async def ainvoke(self, messages, temperature=0.3, max_tokens=None,
                      deep_think=False) -> dict:
        """非流式调用，返回 {'content', 'reasoning_content', 'usage'}。"""
```

### 5.4 配置扩展（`params/model_config.yaml`）

新增能力声明字段，让调用方按能力选择而非按模型名硬编码：

```yaml
ONLINE_LLM_MODEL:
  doubao-1.5-pro-32k:
    api_key: "sk-xxx"
    api_base_url: "https://ark.cn-beijing.volces.com/api/v3"
    model_id: "doubao-1.5-pro-32k-250116"
    provider: "火山引擎"
    supports_reasoning: false       # 【新增】是否支持深度思考
    supports_stream: true           # 【新增】是否支持流式
    max_tokens_limit: 32000         # 【新增】模型最大输出
    description: "豆包 1.5 pro 32k"
    # worker_class 字段不再使用，由注册表按 model_name 查询
```

---

## 6. 实施路径（分阶段）

### 阶段一：抽象层对齐（1 周）
- [ ] 新建 `server/llm_workers/` 目录，编写 `base.py` + `openai_compatible.py`
- [ ] 重构 `server/vlm_api.py` → `server/vlm_workers/`，保持对外 API 兼容
- [ ] 改造 `chat/pipeline/openai_clients.py` 从 `llm_workers` 取 client
- [ ] 改造 `constants.py`：`_ROUTER_MODEL_NAME` 改读配置（`ROUTER_MODEL` 配置项）
- [ ] 改造 `llm_api.py:75` 深度思考判断：用 `supports_reasoning` 替代字符串匹配

**验收**：现有 deepseek-v4-pro / qwen-flash 走新抽象层，全量回归通过。

### 阶段二：OpenAI 兼容平台批量接入（3 天）
纯配置，零代码。新增以下平台到 `ONLINE_LLM_MODEL` / `EMBEDDING_API_CONFIG` / `VLM_API_CONFIG`：
- [ ] 火山引擎方舟（LLM + VLM）
- [ ] 智谱 GLM（LLM + Embedding + VLM）
- [ ] DeepSeek 官方（LLM）
- [ ] 百度千帆（LLM + Embedding）
- [ ] Moonshot / MiniMax（LLM）

**验收**：每个平台至少跑通一次对话 + 一次 embedding，配置切换不改代码。

### 阶段三：Reranker 多平台 adapter（1 周）
Reranker 协议最碎片，逐家写：
- [ ] `jina_reranker.py`（HTTP POST `/rerank`）
- [ ] `cohere_reranker.py`（HTTP POST `/v2/rerank`）
- [ ] `zhipu_reranker.py`
- [ ] `siliconflow_reranker.py`

**验收**：每家 reranker 在知识库问答链路（`server/chat/pipeline/`）跑通 BadCase 对比。

### 阶段四：非 OpenAI 协议 LLM/VLM adapter（按需，1-2 周）
- [ ] `claude_adapter.py`（Anthropic Messages API）
- [ ] `spark_adapter.py`（讯飞星火，SSE）
- [ ] `hunyuan_adapter.py`（腾讯混元）

**验收**：adapter 产出符合 `ChatDelta` / `BaseVLM` 契约，流式思考过程正常推送。

### 阶段五：切平台演练 + 文档（3 天）
- [ ] 编写「模型切换 Runbook」：每家平台的 api_key 申请、base_url、model_id 速查
- [ ] 在 devp/test/prod 三套配置中演练切换
- [ ] 维度对齐校验脚本：切换 embedding 时校验 Milvus collection dim

---

## 7. 核心要点与风险

### 7.1 核心要点

1. **OpenAI 兼容优先**：路径 A 覆盖 80%+ 平台，adapter 只用于自有协议。查平台先查兼容端点。
2. **流式 + 非流式双支持**：LLM/VLM 两类必须同时支持，业务方按场景选。
3. **统一输出契约**：`ChatDelta`（含 `reasoning_content`）是所有 LLM adapter 的输出标准，非 OpenAI 协议在 adapter 内转换。
4. **能力声明驱动**：用 `supports_reasoning` / `supports_stream` / `embedding_dim` 等配置字段声明能力，调用方按能力选模型，不按模型名硬编码。
5. **注册表模式统一**：4 类模型都用 `register_xxx_worker` + `get_xxx_worker`，新增 provider 有固定套路。
6. **配置指纹缓存**：复用 [embeddings_api.py:24](file:///d:/Code/haisi/haisi_rag_models_server/server/embeddings_api.py#L24) 指纹机制，改 api_key/endpoint 自动重建 worker，无需重启服务。
7. **多环境配置同步**：`params/{devp,local,prod,release,test}/model_config.yaml` 五套需同步改，否则部署覆盖。

### 7.2 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| Embedding 维度不一致 | 切平台后向量无法检索 | 配置强制声明 `embedding_dim`，切换时校验 Milvus collection 维度，不一致需重建向量库 |
| 深度思考字段差异 | qwen 用 `reasoning_content`，deepseek 用 `reasoning`，Claude 用 `thinking` | adapter 内统一归一到 `reasoning_content`，配置 `reasoning_field_map` |
| Reranker 返回结构差异 | Jina 返回 `results[].relevance_score`，Cohere 返回 `results[].relevance_score`，智谱不同 | 基类只要求 `[(index, score)]`，adapter 内转换 |
| 流式 SSE 格式差异 | 讯飞/Claude 的 SSE 事件结构与 OpenAI 不同 | adapter 内解析各自 SSE，产出统一 `ChatDelta` |
| Token 计费差异 | 各平台计价单位不同 | `usage` 字段统一记录 prompt/completion/total_tokens，成本统计按平台单价表二次计算 |
| API key 泄露 | 配置文件入库 | 沿用现有 gitignore，敏感配置走环境变量注入（`ONLINE_LLM_MODEL__<name>__api_key`） |
| 限流 / 配额 | 切换后新平台限流 | 每家配置 `max_workers` / `batch_size`，沿用 [base.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/base.py) 信号量限流 |
| provider 字段语义 | 当前 `provider` 仅展示，易误用为加载逻辑 | 明确：`provider` 仅元数据，加载逻辑只看 `model_name` → 注册表 |

### 7.3 不做事项（明确边界）
- 不引入 LiteLLM 等第三方聚合库（增加依赖、调试黑盒，自建 adapter 更可控）；
- 不做模型质量评测平台（评测走独立 benchmark 工程）；
- 不做按成本/延迟的动态路由（二期，需接监控数据）；
- 不破坏现有本地模型支持（`MODEL_PATH.llm_model` 机制保留）。

---

## 8. 验收标准

### 8.1 功能验收
- [ ] 4 类模型各自注册表可用 `list_xxx_workers()` 列举已注册项
- [ ] 任一 OpenAI 兼容平台，改 yaml 即可切换，业务链路无感
- [ ] 非 OpenAI 平台至少接入 1 家（推荐 Claude 或 讯飞），流式思考正常推送
- [ ] Embedding 切换时维度校验生效，不一致时 fail-fast 报错而非静默写脏数据

### 8.2 代码质量验收
- [ ] 新增 worker 类均有类型注解 + docstring（中英双语，沿用 [qwen3_embedding.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/qwen3_embedding.py) 风格）
- [ ] 每家 adapter 有单元测试（mock HTTP 响应，断言 `ChatDelta`/向量/score 契约）
- [ ] `constants.py` / `llm_api.py` 无模型名字符串硬编码
- [ ] 五套环境配置（devp/local/prod/release/test）同步更新

### 8.3 文档验收
- [ ] 本规划文档更新为实施后版本，标注实际接入清单
- [ ] `docs/api/models_api.html` 同步更新支持的平台清单
- [ ] Runbook：每家平台的 api_key 申请流程、base_url、model_id、限流、计价

---

## 附录 A：现有文件清单（改造前必读）

| 文件 | 作用 | 改造点 |
|------|------|--------|
| [configs/model_loader.py](file:///d:/Code/haisi/haisi_rag_models_server/configs/model_loader.py) | 加载 model_config.yaml | 新增 `supports_reasoning` 等字段导出 |
| [server/llm_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/llm_api.py) | LLM API 端点 | `get_ChatOpenAI` 改走 `llm_workers` |
| [server/chat/pipeline/openai_clients.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/openai_clients.py) | 预创建 AsyncOpenAI | 从 `llm_workers` 取 client |
| [server/chat/pipeline/constants.py](file:///d:/Code/haisi/haisi_rag_models_server/server/chat/pipeline/constants.py) | 路由模型名硬编码 | `_ROUTER_MODEL_NAME` 改读配置 |
| [server/embeddings_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embeddings_api.py) | Embedding API + 缓存 | 复用指纹机制，无需大改 |
| [server/embedding_workers/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/server/embedding_workers/__init__.py) | Embedding 注册表 | 新增通用 worker 注册 |
| [server/reranker_workers/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/server/reranker_workers/__init__.py) | Reranker 注册表 | 新增各家 adapter 注册 |
| [server/vlm_api.py](file:///d:/Code/haisi/haisi_rag_models_server/server/vlm_api.py) | VLM 单文件实现 | 重构为 `vlm_workers/` 注册表 |
| [params/model_config.yaml](file:///d:/Code/haisi/haisi_rag_models_server/params/model_config.yaml) | 默认配置 | 新增平台配置块 + 能力字段 |

## 附录 B：OpenAI 兼容平台 base_url 速查

| 平台 | base_url |
|------|----------|
| 阿里云 DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 火山引擎方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| 智谱 AI | `https://open.bigmodel.cn/api/paas/v4/` |
| DeepSeek 官方 | `https://api.deepseek.com/v1` |
| Moonshot | `https://api.moonshot.cn/v1` |
| MiniMax | `https://api.minimax.chat/v1` |
| 百度千帆 | `https://qianfan.baidubce.com/v2` |
| OpenAI | `https://api.openai.com/v1` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` |

> 注：base_url 可能随平台调整，接入时以平台最新文档为准。
