# MemMachine 入门与参考手册

> MemMachine 是 AI Agent 的开源**长期记忆层**（long-term memory layer），让无状态 chatbot 变成具备跨会话记忆、个性化、上下文感知的助手。
>
> - 许可证：Apache 2.0
> - 运行时：Python 3.12+
> - 主语言：Python（服务端 / 客户端 SDK），并附带 TypeScript 客户端
> - 官网：<https://memmachine.ai>，文档站：<https://docs.memmachine.ai>

本文件是 `MemMachine` 仓库的统一入门与参考手册，覆盖项目简介、核心能力、架构、目录结构、REST API、Python SDK、MCP、TypeScript 与集成、部署方式、配置文件、使用示例、环境变量、常用命令与健康检查。

---

## 1. 项目简介

MemMachine 是面向 AI Agent 与 LLM 应用的开源**长期记忆层**。它让 AI 能够**学习、存储、召回**过去会话中的信息，把无状态 chatbot 转变为个性化、上下文感知的助手。

- **开源许可**：Apache 2.0。
- **Python 版本**：3.12+（见根目录 `pyproject.toml` 与 `packages/server/pyproject.toml` 的 `requires-python = ">= 3.12"`）。
- **LLM 无关**：支持 OpenAI、Anthropic、AWS Bedrock、Ollama，以及任何 OpenAI 兼容 endpoint。
- **多语言接入**：REST API v2、Python SDK（`memmachine-client`）、TypeScript SDK、原生 MCP Server。
- **自托管 / 云托管**：可本地运行、Docker 部署、Kubernetes（Helm）部署，也可使用托管服务。

---

## 2. 核心能力（四种记忆）

| 记忆类型 | 存储 | 说明 |
|---------|------|------|
| **Episodic Memory（情景记忆）** | 图数据库（Neo4j） | 对话上下文与交互记录，跨会话持久化；含 **short-term**（近期上下文）与 **long-term**（已总结）两层 |
| **Semantic Memory（语义记忆）** | PostgreSQL + pgvector | 用户事实、偏好、知识，用向量嵌入做语义搜索 |
| **Working Memory（工作记忆）** | 内存 / 短期存储 | 当前会话的短期上下文 |
| **Profile Memory** | SQL（PostgreSQL） | 长期用户事实与偏好 |

其他关键特性：

- **Agent 记忆持久化**：记忆跨重启、跨会话、甚至跨模型变更依然存活。
- **LLM 无关**：支持 OpenAI / Anthropic / Bedrock / Ollama / 任何 OpenAI 兼容 endpoint。
- **灵活存储**：Episodic → 图数据库（Neo4j）；Profile/Semantic → SQL（PostgreSQL + pgvector）。

---

## 3. 架构（三层）

MemMachine 采用三层架构：

1. **API 层**：用户通过 Agent 接入，方式 = REST API / Python SDK / MCP Server。
2. **MemMachine 服务层**：处理交互，写入 Episodic Memory（对话上下文）与 Profile/Semantic Memory（长期用户事实）。
3. **存储层**：Episodic → 图数据库（Neo4j）；Profile/Semantic → SQL（PostgreSQL + pgvector）。

服务实现细节：

- 基于 **FastAPI + uvicorn**。
- 路由统一前缀 `/api/v2`（见 `packages/server/src/memmachine_server/server/api_v2/router.py` 的 `app.include_router(router, prefix="/api/v2")`）。
- MCP 挂载在 `/mcp`（见 `packages/server/src/memmachine_server/server/app.py` 的 `self.mount("/mcp", mcp_app)`）。
- 入口文件：`packages/server/src/memmachine_server/server/app.py`。
- CLI 入口命令：`memmachine-server`（见 `packages/server/pyproject.toml` 的 `[project.scripts]`）。

---

## 4. 仓库结构

### 4.1 `packages/`（Python 工作区，uv workspace）

| 目录 | 发布包名 | 作用 |
|------|---------|------|
| `packages/server/` | `memmachine-server` | 服务端 Python 包（记忆系统、REST API、MCP、资源管理器、迁移）。CLI = `memmachine-server` |
| `packages/client/` | `memmachine-client` | Python 客户端 SDK。CLI = `memmachine` / `mem-cli` |
| `packages/common/` | `memmachine-common` | 共享 API spec / DTO（Pydantic 模型） |
| `packages/meta/` | `memmachine` | meta 包（同时含 `memmachine-client` 与 `memmachine-server` 依赖），一键安装全套 |
| `packages/ts-client/` | — | TypeScript 客户端 |

根 `pyproject.toml` 中 uv workspace 成员：`packages/client`、`packages/common`、`packages/server`。

### 4.2 顶层目录

| 目录 | 作用 |
|------|------|
| `integrations/` | 第三方框架集成（crewai、dify、n8n、langchain、langgraph、llamaindex、aws_strands_agent_sdk、fastgpt、openclaw 等） |
| `examples/` | 示例 Agent 与 demo：`openai_agent/`、`qwen_agent/`、`simple_chatbot/`、`ts_rest_client_demo/`、`MemMachine_V2_Complete_Guide.ipynb`、`v1/`（含 crm / financial_analyst / health_assistant / writing_assistant / frontend）等 |
| `deployments/helm/` | Kubernetes Helm Chart（`Chart.yaml`、`values.yaml`、`README.md`、`templates/`） |
| `docs/` | Mintlify 文档站源（`docs.json`、`api_reference/`、`getting_started/`、`examples/`、`open_source/` 等） |
| `evaluation/` | 评估脚本与数据（`data/locomo10.json`、`episodic_memory/`、`retrieval_agent/`、`utils/`） |
| `tools/` | 辅助工具（`simple_doc_gen.py` 等） |
| `maintainers/` | 维护者指南（`branch-management.md`、`releases.md`、`pull-requests.md` 等） |
| `sample_configs/` | 配置文件模板（`episodic_memory_config.cpu.sample`、`.gpu.sample`、`.nebula.sample`、`server_config.sample`、`env.dockercompose`） |

### 4.3 根目录关键文件

| 文件 | 作用 |
|------|------|
| `Dockerfile` | 容器镜像构建（支持 CPU/GPU 构建参数） |
| `docker-compose.yml` | 编排 postgres + neo4j + memmachine（+ 可选 docs） |
| `memmachine-compose.sh` | 交互式部署脚本（生成 `.env` 与 `configuration.yml`，启动并等待健康检查） |
| `build-docker.sh` | 交互式镜像构建脚本 |
| `pyproject.toml` | uv workspace 根配置、ruff/pytest/ty 配置 |
| `uv.lock` | uv 锁文件 |
| `alembic.ini` | 数据库迁移（Alembic）配置 |
| `USAGE.md` | 面向 AI 编码助手的集成指南 |
| `AGENTS.md` / `CLAUDE.md` | 面向 AI 助手的仓库导航说明 |
| `LICENSE` | Apache 2.0 许可证 |

---

## 5. REST API v2

### 5.1 总则

- **Base URL**：`http://localhost:8080/api/v2`
- 所有写操作用 **POST + JSON body**；GET 仅用于 health / metrics。
- **项目模型**：所有操作 scope 到 `org_id` + `project_id`。
- 交互式 API 文档：<http://localhost:8080/docs>
- 错误码：`200` / `201` / `204` / `400` / `404` / `409` / `422` / `500` / `503`。

### 5.2 项目（Projects）端点

| Method | Path | 说明 | 必填 / 可选字段 |
|--------|------|------|----------------|
| POST | `/api/v2/projects` | 创建项目（201） | `org_id`, `project_id`, `description?`, `config?{backend?, embedder?, reranker?, vector_graph_store?, vector_store?, segment_store?, properties_schema?}` |
| POST | `/api/v2/projects/get` | 获取项目 | `org_id`, `project_id` |
| POST | `/api/v2/projects/list` | 列出全部项目 | `{}` |
| POST | `/api/v2/projects/delete` | 删除项目（204） | `org_id`, `project_id` |
| POST | `/api/v2/projects/episode_count/get` | 获取 episode 数量 | `org_id`, `project_id` |

### 5.3 记忆（Memories）端点

| Method | Path | 说明 | 必填 / 可选字段 |
|--------|------|------|----------------|
| POST | `/api/v2/memories` | 添加记忆 | `org_id`, `project_id`, `messages[]{content, producer?, produced_for?, role?, timestamp?, metadata?}`, `types?` |
| POST | `/api/v2/memories/search` | 搜索记忆 | `org_id`, `project_id`, `query`, `top_k?=10`, `filter?`, `types?`, `agent_mode?=false` |
| POST | `/api/v2/memories/list` | 列出记忆 | `org_id`, `project_id`, `type?`, `page_size?=100`, `page_num?=0`, `filter?`, `set_metadata?` |
| POST | `/api/v2/memories/episodic/delete` | 删除 episodic 记忆（204） | `org_id`, `project_id`, `episodic_id` 或 `episodic_ids[]` |
| POST | `/api/v2/memories/semantic/delete` | 删除 semantic 记忆（204） | `org_id`, `project_id`, `semantic_id` 或 `semantic_ids[]` |

### 5.4 语义记忆 features / sets / categories / tags 端点

| Path | 说明 |
|------|------|
| `POST /api/v2/memories/semantic/feature` | 添加 semantic feature |
| `POST /api/v2/memories/semantic/feature/get` | 获取 feature（404 表示不存在） |
| `POST /api/v2/memories/semantic/feature/update` | 更新 feature |
| `POST /api/v2/memories/semantic/set_type` | 创建 semantic set type |
| `POST /api/v2/memories/semantic/set_type/list` | 列出全部 set type |
| `POST /api/v2/memories/semantic/set_type/delete` | 删除 set type |
| `POST /api/v2/memories/semantic/set_id/get` | 获取/创建 set ID |
| `POST /api/v2/memories/semantic/set_id/list` | 列出全部 set |
| `POST /api/v2/memories/semantic/set/configure` | 配置 set（embedder/llm 覆盖） |
| `POST /api/v2/memories/semantic/category` | 添加 category 到 set |
| `POST /api/v2/memories/semantic/category/get` | 获取 category（404 表示不存在） |
| `POST /api/v2/memories/semantic/category/template` | 添加 category 模板到 set type |
| `POST /api/v2/memories/semantic/category/template/list` | 列出 set type 的 category 模板 |
| `POST /api/v2/memories/semantic/category/disable` | 对某 set 禁用 category |
| `POST /api/v2/memories/semantic/category/set_ids/get` | 获取使用某 category 的 set IDs |
| `POST /api/v2/memories/semantic/category/delete` | 删除 category |
| `POST /api/v2/memories/semantic/category/tag` | 添加 tag 到 category |
| `POST /api/v2/memories/semantic/category/tag/delete` | 删除 tag |

### 5.5 情景记忆配置端点

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v2/memory/episodic/config/get` | 获取 episodic memory 配置 |
| POST | `/api/v2/memory/episodic/config` | 配置 episodic memory（总开关 / long-term / short-term 启停） |
| POST | `/api/v2/memory/episodic/short_term/config/get` | 获取 short-term 配置 |
| POST | `/api/v2/memory/episodic/short_term/config` | 更新 short-term 配置 |
| POST | `/api/v2/memory/episodic/long_term/config/get` | 获取 long-term 配置 |
| POST | `/api/v2/memory/episodic/long_term/config` | 更新 long-term 配置 |

### 5.6 系统端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v2/health` | 健康检查，返回 `{status, service, version}` |
| GET | `/api/v2/metrics` | Prometheus 指标（text/plain） |

### 5.7 curl 示例

创建项目：

```bash
curl -X POST "http://localhost:8080/api/v2/projects" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "description": "My project description",
    "config": {
      "embedder": "my-openai-embedder",
      "reranker": "my-rrf-reranker"
    }
  }'
```

添加记忆：

```bash
curl -X POST "http://localhost:8080/api/v2/memories" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "messages": [
      {
        "content": "I like pizza",
        "producer": "user123",
        "produced_for": "agent456",
        "role": "user",
        "timestamp": "2024-01-15T10:00:00Z",
        "metadata": {"type": "preference", "topic": "food"}
      }
    ]
  }'
```

搜索记忆（带 `filter` 变体）：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/search" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "query": "user preferences",
    "top_k": 10,
    "filter": "metadata.user_id='\''user123'\''",
    "types": ["semantic"]
  }'
```

搜索记忆（带 `agent_mode` 变体，启用 retrieval-agent 编排）：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/search" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "query": "user preferences",
    "top_k": 10,
    "types": ["episodic", "semantic"],
    "agent_mode": true
  }'
```

> `agent_mode=true` 时使用 retrieval-agent 搜索，且不支持 score-threshold 过滤。

---

## 6. Python SDK（memmachine-client）

### 6.1 安装

```bash
pip install memmachine-client
```

### 6.2 调用链

```
MemMachineClient(base_url, timeout=30, max_retries=3)
  └─ .get_project(org_id, project_id)        → Project
     ├─ .create_project(...)                  → Project
     └─ .get_or_create_project(...)           → Project
        └─ project.memory(group_id, agent_id, user_id, session_id) → Memory
           ├─ .add(...)
           ├─ .search(...)
           ├─ .delete_episodic(...)
           ├─ .delete_semantic(...)
           └─ .get_context()
```

构造函数：

```python
MemMachineClient(base_url="http://localhost:8080", timeout=30, max_retries=3)
```

- `base_url` 必填，也可通过环境变量 `MEMORY_BACKEND_URL` 提供。
- `api_key` 可选，用于鉴权（环境变量 `MEMORY_API_KEY`）。
- 内置 `requests.Session` + 重试策略（重试 `429/500/502/503/504`）。
- 支持 `with` 上下文管理器（`__enter__` / `__exit__` → `close()`）。

### 6.3 `Memory` 方法

| 方法 | 关键参数 | 说明 |
|------|---------|------|
| `add(content, role="user", producer?, produced_for?, episode_type="text", metadata={})` | `content` 必填 | 返回 `list[AddMemoryResult]`（每项含 `uid`） |
| `search(query, limit=10, expand_context=0, score_threshold=None, agent_mode=False, filter_dict=None)` | `query` 必填 | 返回 `SearchResult` Pydantic 模型，字段 `content.episodic_memory` / `content.semantic_memory`；episodic 结果内含 `episode_summary` |
| `delete_episodic(episodic_id=...)` / `delete_episodic(episodic_ids=[...])` | — | 删除 episodic 记忆 |
| `delete_semantic(semantic_id=...)` / `delete_semantic(semantic_ids=[...])` | — | 删除 semantic 记忆 |
| `get_context()` | — | 返回 `{org_id, project_id, user_id, agent_id, session_id, group_id}` |

其他客户端方法：

- `MemMachineClient.health_check()` → `GET /api/v2/health`。
- `MemMachineClient.get_metrics()` → `GET /api/v2/metrics`。
- `MemMachineClient.list_projects()` → `POST /api/v2/projects/list`。

环境变量：`MEMORY_BACKEND_URL`（默认 `http://localhost:8080`）、`MEMORY_API_KEY`。

### 6.4 快速上手（与 README 一致）

```python
from memmachine_client import MemMachineClient

# Initialize the client
client = MemMachineClient(base_url="http://localhost:8080")

# Get or create a project
project = client.get_or_create_project(org_id="my_org", project_id="my_project")

# Create a memory instance for a user session
memory = project.memory(
    group_id="default",
    agent_id="travel_agent",
    user_id="alice",
    session_id="session_001",
)

# Add a memory
memory.add("I prefer aisle seats on flights", metadata={"category": "travel"})
# => [AddMemoryResult(uid='...')]

# Search memories
results = memory.search("What are my flight preferences?")
print(results.content.episodic_memory.long_term_memory.episodes[0].content)
# => "I prefer aisle seats on flights"
```

---

## 7. MCP Server

MemMachine 内置原生 **Model Context Protocol (MCP)** Server，可无缝接入 Claude Desktop、Cursor 等 MCP 兼容客户端。

### 7.1 启动命令

来自 `packages/server/pyproject.toml` 的 `[project.scripts]`：

| 命令 | 作用 |
|------|------|
| `memmachine-mcp-stdio` | stdio 模式（Claude Desktop / Cursor） |
| `memmachine-mcp-http` | HTTP 模式 |

也可通过 `memmachine-server --stdio` 以 stdio 模式启动。HTTP 模式下，MCP 端点挂载在 `/mcp`。

### 7.2 三个 MCP 工具

来自 `docs/api_reference/mcp.mdx`：

| 工具 | 参数 | 说明 |
|------|------|------|
| `add_memory` | `content`（必填）, `user_id?`, `proj_id?`, `org_id?` | 存入情景 + 长期语义记忆。默认 `user_id=user-{mac}`、`proj_id=mcp-{user_id}`、`org_id=mcp-universal` |
| `search_memory` | `query`（必填）, `top_k?=20`, `user_id?`, `proj_id?`, `org_id?` | 搜索 profile + episodic 记忆 |
| `delete_memory` | `episodic_memory_uids=[]`, `semantic_memory_uids=[]`, `user_id?`, `proj_id?`, `org_id?` | 按 ID 删除（episodic 与 semantic 是不同 ID 空间） |

返回码：`200` 成功；`422` 内容无法嵌入；`500` MemMachine 未初始化或内部错误。

### 7.3 Claude Desktop / Cursor 配置示例（stdio）

```json
{
  "mcpServers": {
    "memmachine": {
      "command": "memmachine-mcp-stdio"
    }
  }
}
```

---

## 8. TypeScript SDK 与集成

### 8.1 TypeScript SDK

源码位于 `packages/ts-client/`：

```bash
cd packages/ts-client
npm install
npm run build
npm run test
```

API 参考：`docs/api_reference/ts-rest/`。

### 8.2 集成框架（8 个）

| 框架 | 集成位置 | 说明 |
|------|---------|------|
| LangChain | `integrations/langchain/` | LangChain Agent 的记忆 provider |
| LangGraph | `integrations/langgraph/` | LangGraph 工作流的有状态记忆 |
| CrewAI | `integrations/crewai/tool.py` | CrewAI 多 Agent 系统的持久记忆 |
| LlamaIndex | `integrations/llamaindex/` | LlamaIndex 应用的记忆集成 |
| AWS Strands | `integrations/aws_strands_agent_sdk/` | AWS Strands Agent SDK 的记忆 |
| n8n | `integrations/n8n/` | 无代码工作流自动化集成 |
| Dify | `integrations/dify/` | Dify AI 应用的记忆后端 |
| FastGPT | `integrations/fastgpt/` | FastGPT 平台集成 |

---

## 9. 部署方式

### 9.1 Docker Compose（推荐快速上手）

部署脚本：`./memmachine-compose.sh`（仓库根目录）。

首次运行会交互式询问：

1. CPU / GPU 镜像；
2. provider（OpenAI / Bedrock / Ollama / OpenAI-compatible）；
3. LLM 与 embedding 模型；
4. API key、base_url、reranker。

脚本会自动生成 `.env` 与 `configuration.yml`，启动 **postgres + neo4j + memmachine** 三个容器，并等待健康检查通过。

#### 子命令

| 命令 | 作用 |
|------|------|
| `(无参) / start [<image>:<tag>]` | 启动服务 |
| `stop` | 停止服务 |
| `restart` | 重启服务 |
| `logs` | 查看日志（`-f` 跟随） |
| `clean` | 删除服务与数据卷（`down -v`） |
| `build [<image>:<tag>] [--gpu true/false] [-f|--force]` | 构建自定义镜像 |
| `help` | 帮助 |

#### 4 种 provider 默认模型

| Provider | 默认 LLM | 默认 Embedding | 需要 |
|----------|---------|----------------|------|
| OpenAI | `gpt-4o-mini` | `text-embedding-3-small` | `OPENAI_API_KEY` |
| Bedrock | `openai.gpt-oss-20b-1:0` | `amazon.titan-embed-text-v2:0` | AWS 凭证 + region（默认 `us-west-2`） |
| Ollama | `llama3` | `nomic-embed-text` | 默认 base_url `http://host.docker.internal:11434/v1` |
| OpenAI-compatible | `qwen-flash` | `text-embedding-v4` | API key 与 base_url |

#### `docker-compose.yml` 关键服务

| 服务 | 镜像 | 端口 |
|------|------|------|
| `postgres` | `pgvector/pgvector:pg16` | `${POSTGRES_PORT:-5432}:5432` |
| `neo4j` | `neo4j:5.23-community`（含 APOC + GDS 插件） | `7474` HTTP / `7473` HTTPS / `7687` Bolt |
| `memmachine` | `memmachine/memmachine` | `${MEMORY_SERVER_PORT:-8080}:8080` |
| `docs`（可选） | 本地构建 `docs/` | `${DOCS_PORT:-3000}:3000` |

- 镜像 tag：CPU = `memmachine/memmachine:latest-cpu`，GPU = `memmachine/memmachine:latest-gpu`。
- memmachine healthcheck：`curl --fail --silent http://localhost:8080/api/v2/health`。

### 9.2 pip 安装

```bash
pip install memmachine                # client + server 全套
pip install memmachine-client         # 仅 Python 客户端
pip install memmachine-server         # 仅服务端
pip install "memmachine-server[gpu]"  # 含本地 embedding GPU 依赖（sentence-transformers）
```

启动服务：

```bash
# 配置文件可命名为 cfg.yml 或 configuration.yml
memmachine-server --config cfg.yml
# 或
memmachine-server --config configuration.yml
```

CLI 命令（来自 `[project.scripts]`）：

| 命令 | 作用 |
|------|------|
| `memmachine-server` | HTTP 服务入口 |
| `memmachine-server --stdio` | MCP stdio 模式 |
| `memmachine-server --with-config-api` | 启用配置管理 API |
| `memmachine-server --version` | 显示版本 |
| `memmachine-configure` | 交互式配置向导 |
| `memmachine-nltk-setup` | 下载 NLTK 数据 |
| `memmachine-mcp-stdio` / `memmachine-mcp-http` | 独立 MCP 入口 |

### 9.3 源码运行

需要 [uv](https://docs.astral.sh/uv/)（Astral）。

```bash
uv sync                       # 安装依赖
uv sync --all-extras          # 含全部可选 extras
uv run memmachine-server      # 启动服务
uv run ruff check             # lint
uv run ruff format            # 格式化
uv run ty check packages      # 类型检查
uv run pytest                 # 全部测试（默认跳过 integration）
uv run pytest -m integration  # 仅集成测试
uv run pytest -k "create_memory"  # 按关键字筛选
```

- Python 3.12+（见 `pyproject.toml`）。
- 构建镜像：`./build-docker.sh`（交互式）或 `./memmachine-compose.sh build`。

### 9.4 Helm Chart（Kubernetes）

- Chart 路径：`deployments/helm/`。
- Chart version `0.1.0`，app version `v0.3.0`（见 `deployments/helm/README.md`）。
- 部署组件：MemMachine Deployment + Service（NodePort，默认 `31001` → pod `8080`）、可选 in-cluster PostgreSQL（`pgvector/pgvector:pg16`）与 Neo4j（`neo4j:5.23-community`，含 APOC + GDS）。
- 两个 initContainer（`wait-for-postgres`、`wait-for-neo4j`）用 `busybox` + `nc` 探测 TCP。
- PVC：`memmachine-pvc`（始终）、`postgres-pvc`、`neo4j-pvc`（按 `enabled` 开关），默认 `ReadWriteMany`，`storageClass=nfs-client`，`pvcSize=5Gi`。
- Secrets：`postgres-secret`（`POSTGRES_PASSWORD`）、`memmachine-secrets`（`OPENAI_API_KEY`）、`neo4j-secret`（`NEO4J_USER` / `NEO4J_PASSWORD` / `NEO4J_AUTH`）。
- ConfigMaps：`memmachine-config`（挂载 `/app/configuration.yml`）、`memmachine-env-config`（挂载 `/app/.env`）。

基础安装：

```bash
helm upgrade --install memmachine deployments/helm \
  --namespace memmachine --create-namespace
```

OpenAI 后端示例：

```bash
helm upgrade --install memmachine deployments/helm \
  --namespace memmachine --create-namespace \
  --set memmachine.openaiApiKey=sk-... \
  --set memmachine.model.model_path=gpt-4o-mini \
  --set memmachine.embedder.model_path=text-embedding-3-small
```

外部 PostgreSQL：

```bash
helm upgrade --install memmachine deployments/helm \
  --namespace memmachine --create-namespace \
  --set postgres.enabled=false \
  --set postgres.host=my-pg.example.com \
  --set postgres.port=5432
```

外部 Neo4j：

```bash
helm upgrade --install memmachine deployments/helm \
  --namespace memmachine --create-namespace \
  --set neo4j.enabled=false \
  --set neo4j.host=my-neo4j.example.com \
  --set neo4j.port=7687
```

覆盖 NodePort：

```bash
helm upgrade --install memmachine deployments/helm \
  --namespace memmachine --create-namespace \
  --set nodePorts.http8080=30005
```

验证：

```bash
kubectl get pods -n memmachine
curl http://<node-ip>:31001/api/v2/health
```

卸载（PVC 不会自动删）：

```bash
helm uninstall memmachine -n memmachine
kubectl delete pvc -n memmachine neo4j-pvc postgres-pvc memmachine-pvc
```

---

## 10. 配置文件（configuration.yml）

配置文件可命名为 `cfg.yml` 或 `configuration.yml`，关键 section 如下（结构来自 `sample_configs/episodic_memory_config.cpu.sample` 与 `deployments/helm/README.md`）：

```yaml
logging:
  path: mem-machine.log
  level: info                      # info | debug | error

episode_store:
  database: profile_storage        # 引用 resources.databases.*

episodic_memory:
  long_term_memory:
    embedder: openai_embedder       # 引用 resources.embedders.*
    reranker: my_reranker_id        # 引用 resources.rerankers.*
    vector_graph_store: my_storage_id   # 引用 resources.databases.*（Neo4j）
    # backend: declarative          # 或 event（VectorStore + SegmentStore）
    # event 模式下额外字段：vector_store / segment_store / properties_schema
  short_term_memory:
    llm_model: openai_model         # 引用 resources.language_models.*
    message_capacity: 500

semantic_memory:
  llm_model: openai_model
  embedding_model: openai_embedder
  database: profile_storage
  config_database: profile_storage

session_manager:
  database: profile_storage

prompt:
  default_project_categories:       # 可选项如：
    - profile_prompt                #   profile_prompt
    # - crm_prompt                  #   crm_prompt
    # - health_assistant_prompt     #   health_assistant_prompt
    # - financial_analyst_prompt    #   financial_analyst_prompt
    # - writing_assistant_prompt    #   writing_assistant_prompt
    # - coding_style_prompt         #   coding_style_prompt

resources:
  databases:
    profile_storage:                # provider=postgres
      provider: postgres
      config: { host, port, user, password, db_name, pool_size, max_overflow }
    my_storage_id:                  # provider=neo4j
      provider: neo4j
      config: { uri, user, password, max_connection_pool_size, ... }
  embedders:
    # OpenAI / Bedrock(amazon-bedrock) / Ollama / sentence-transformer 等
    openai_embedder:
      provider: openai
      config: { model, api_key, base_url, dimensions, max_input_length }
  language_models:
    # openai-responses / openai-chat-completions / litellm / amazon-bedrock
    openai_model:
      provider: openai-responses
      config: { model, api_key, base_url }
  rerankers:
    my_reranker_id:                 # rrf-hybrid 组合多个 reranker_ids
      provider: rrf-hybrid
      config: { reranker_ids: [id_ranker_id, bm_ranker_id] }
    id_ranker_id: { provider: identity }
    bm_ranker_id:  { provider: bm25 }
    # 其他可选：cross-encoder / cohere / amazon-bedrock / embedder
```

> 顶层资源 ID（如 `default_model`、`default_embedder`、`db_postgres`、`db_neo4j`、`my_reranker_id`）在 `episodic_memory` / `semantic_memory` / `episode_store` / `session_manager` 中被引用，服务启动时统一在 `resources.*` 下解析。

---

## 11. 使用示例

### 11.1 Python SDK 快速上手

见 [6.4](#64-快速上手与-readme-一致)。

### 11.2 curl 调用 REST API

创建项目：

```bash
curl -X POST "http://localhost:8080/api/v2/projects" \
  -H "Content-Type: application/json" \
  -d '{"org_id":"my-org","project_id":"my-project","description":"My project"}'
```

添加记忆：

```bash
curl -X POST "http://localhost:8080/api/v2/memories" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id":"my-org",
    "project_id":"my-project",
    "messages":[{"content":"I like pizza","role":"user"}]
  }'
```

搜索（基础）：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/search" \
  -H "Content-Type: application/json" \
  -d '{"org_id":"my-org","project_id":"my-project","query":"What does the user like?","top_k":5}'
```

搜索（带 filter）：见 [5.7](#57-curl-示例)。

删除 episodic / semantic：

```bash
# 删除单个 episodic
curl -X POST "http://localhost:8080/api/v2/memories/episodic/delete" \
  -H "Content-Type: application/json" \
  -d '{"org_id":"my-org","project_id":"my-project","episodic_id":"episode_123"}'

# 删除多个 semantic
curl -X POST "http://localhost:8080/api/v2/memories/semantic/delete" \
  -H "Content-Type: application/json" \
  -d '{"org_id":"my-org","project_id":"my-project","semantic_ids":["sem1","sem2"]}'
```

健康检查：

```bash
curl http://localhost:8080/api/v2/health
```

> 注意：`USAGE.md` 中出现过 `GET /health` 的写法，但 router 实际挂在 `/api/v2` 前缀下，docker healthcheck 也使用 `/api/v2/health`。本文档统一用 `/api/v2/health`；根路径 `/health` 在部分部署中也可用，但推荐使用 `/api/v2/health`。

### 11.3 MCP 接入

Claude Desktop / Cursor 的 `mcp.json`（stdio 运行 `memmachine-mcp-stdio`）：

```json
{
  "mcpServers": {
    "memmachine": {
      "command": "memmachine-mcp-stdio"
    }
  }
}
```

---

## 12. 环境变量与默认值

### 12.1 `docker-compose.yml` 中 memmachine 服务用到的环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `POSTGRES_HOST` | `postgres` | PostgreSQL 主机 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 端口 |
| `POSTGRES_USER` | `memmachine` | PostgreSQL 用户 |
| `POSTGRES_PASSWORD` | `memmachine_password` | PostgreSQL 密码 |
| `POSTGRES_DB` | `memmachine` | PostgreSQL 库名 |
| `NEO4J_HOST` | `neo4j` | Neo4j 主机 |
| `NEO4J_PORT` | `7687` | Neo4j Bolt 端口 |
| `NEO4J_USER` | `neo4j` | Neo4j 用户 |
| `NEO4J_PASSWORD` | `neo4j_password` | Neo4j 密码 |
| `MEMORY_CONFIG` | `/app/configuration.yml` | 配置文件路径 |
| `MCP_BASE_URL` | `http://memmachine:8080` | MCP 内部回调地址 |
| `GATEWAY_URL` | `http://localhost:8080` | 网关地址 |
| `FAST_MCP_LOG_LEVEL` | `INFO` | FastAPI/MCP 日志级别 |
| `OPENAI_API_KEY` | （空） | OpenAI / OpenAI-compatible API key |
| `MEMMACHINE_WORKERS` | `1` | uvicorn worker 数 |
| `MEMMACHINE_CONFIG_API` | （空） | 启用配置管理 API |
| `LOG_LEVEL` | `INFO` | 应用日志级别 |
| `HOST` | `0.0.0.0` | 监听地址 |

### 12.2 客户端环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MEMORY_BACKEND_URL` | `http://localhost:8080` | 客户端默认 base_url |
| `MEMORY_API_KEY` | （空） | 客户端 API key |

### 12.3 端口环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MEMORY_SERVER_PORT` | `8080` | MemMachine API 端口 |
| `NEO4J_HTTP_PORT` | `7474` | Neo4j HTTP 端口 |
| `NEO4J_HTTPS_PORT` | `7473` | Neo4j HTTPS 端口 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 端口 |
| `DOCS_PORT` | `3000` | 文档站端口 |

### 12.4 默认值速查（来自 `USAGE.md`）

**项目创建**

| 字段 | 默认值 |
|------|--------|
| `description` | `""` |
| `config.embedder` | `""`（用服务端默认 embedder） |
| `config.reranker` | `""`（用服务端默认 reranker） |

**`Memory.add()`（Python SDK）**

| 参数 | 默认值 |
|------|--------|
| `role` | `"user"` |
| `producer` | `user_id`（若设置）或 `"user"` |
| `produced_for` | `agent_id`（若设置）或 `"agent"` |
| `episode_type` | `"text"`（写入 metadata） |
| `metadata` | `{}`（context 字段如 `user_id`/`agent_id`/`session_id` 自动写入） |
| `timestamp` | 自动设为当前时间（非参数） |

**`Memory.search()`（Python SDK）**

| 参数 | 默认值 |
|------|--------|
| `limit` | `10`（作为 API 的 `top_k`） |
| `expand_context` | `0` |
| `score_threshold` | `None`（不过滤） |
| `agent_mode` | `False` |
| `filter_dict` | `None`（context 过滤自动应用） |

**REST API v2 默认值**

| 操作 | 默认值 |
|------|--------|
| Add memories | `producer`=`"user"`，`produced_for`=`""`，`role`=`""`，`timestamp`=当前时间，`metadata`=`{}`，`types`=`[]`（全部） |
| Search memories | `top_k`=`10`，`filter`=`""`，`types`=`[]`（全部），`agent_mode`=`false` |

---

## 13. 常用命令速查

### 13.1 `memmachine-compose.sh` 子命令

| 命令 | 作用 |
|------|------|
| `(无参) / start [<image>:<tag>]` | 启动 |
| `stop` | 停止 |
| `restart` | 重启 |
| `logs` | 查看日志 |
| `clean` | 删除服务与数据卷 |
| `build [<image>:<tag>] [--gpu true/false] [-f]` | 构建自定义镜像 |
| `help` | 帮助 |

### 13.2 Docker Compose 原生命令

```bash
docker compose ps            # 查看容器状态
docker compose logs -f       # 跟随日志
docker compose down          # 停止并删除容器（保留卷）
docker compose down -v       # 停止并删除容器与卷
docker compose restart       # 重启
```

---

## 14. 健康检查与监控

- `GET /api/v2/health` 返回：

```json
{
  "status": "healthy",
  "service": "memmachine",
  "version": "<server_version>"
}
```

- `GET /api/v2/metrics` 返回 Prometheus 文本格式指标。
- docker-compose healthcheck：`curl --fail --silent http://localhost:8080/api/v2/health`。
- Access log 与 Request metrics 中间件位于 `packages/server/src/memmachine_server/server/middleware.py`（`AccessLogMiddleware`、`RequestMetricsMiddleware`）。
