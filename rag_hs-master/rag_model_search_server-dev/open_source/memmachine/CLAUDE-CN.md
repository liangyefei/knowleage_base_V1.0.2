# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码仓库中处理代码时提供指导。

## 仓库结构

这是一个 Python 工作区（uv monorepo），包含以下包：

- `packages/server/` - 核心服务器，实现情景记忆、语义记忆与画像记忆层
- `packages/client/` - Python 客户端 SDK
- `packages/common/` - 共享的 REST API 定义
- `packages/meta/` - 同时安装服务器与客户端的元包
- `packages/ts-client/` - TypeScript REST 客户端
- `packages/skills/` - 面向 AI 代理的技能

其他目录：
- `integrations/` - 框架集成（LangChain、LangGraph、CrewAI 等）
- `examples/` - 示例代理与演示
- `evaluation/` - 基准测试与评估工具
- `tools/` - 实用脚本（迁移、chatgpt2memmachine 等）

## 环境配置

```bash
uv sync                    # Install all dependencies
uv sync --all-extras       # With optional dev dependencies
```

Python 版本：3.12+

## 构建、测试与 Lint 命令

### Python（在仓库根目录运行）

```bash
# Linting and formatting
ruff check                 # Check for issues
ruff format                # Format code

# Type checking
ty check packages          # Type check all packages

# Testing
pytest                     # Run all tests (excludes integration by default)
pytest -k "test_name"      # Run tests matching keyword
pytest path/to/test.py     # Run specific test file
pytest path/to/test.py::test_function  # Run specific test
pytest -m integration      # Run integration tests only
pytest -m slow             # Run slow tests only
```

### TypeScript（在 `packages/ts-client/` 中运行）

```bash
npm install                # Install dependencies
npm run build              # Build the package
npm run lint               # Lint (ESLint)
npm run lint:fix           # Fix linting issues
npm run format             # Format (Prettier)
npm run test               # Run Jest tests
```

### Docker

```bash
./build-docker.sh          # Build container images (interactive)
```

## 架构概览

### 记忆系统

服务器实现了三种记忆类型：

1. **情景记忆**（`episodic_memory/`）- 基于图的会话上下文
   - 存储于 Neo4j
   - 管理会话、对话与片段
   - 组件：`episodic_memory.py`、`episodic_memory_manager.py`、`event_memory/`、`long_term_memory/`

2. **语义记忆**（`semantic_memory/`）- 基于向量的检索
   - 使用向量数据库（Qdrant、pgvector、usearch、sqlite-vec）
   - 管理嵌入与聚类以实现高效检索
   - 组件：`semantic_memory.py`、`semantic_ingestion.py`、`cluster_store/`、`storage/`

3. **画像记忆** - 基于 SQL 的用户事实与偏好
   - 存储于 PostgreSQL 或 SQLite
   - 管理用户画像与声明性事实

### 关键组件

- `server/` - FastAPI REST 服务器与 MCP 服务器实现
- `retrieval_agent/` - 用于智能记忆检索的代理系统
- `common/` - 共享的工具、配置与辅助函数
- `installation/` - 数据库迁移与安装脚本

### 服务层

`service_locator.py` 文件为以下内容提供依赖注入：
- 记忆管理器（情景、语义）
- 数据库连接（Neo4j、PostgreSQL）
- 嵌入提供方（OpenAI、Bedrock 等）
- LLM 提供方（用于检索代理）

## 代码风格

- **Python**：使用 Ruff 进行格式化与 lint，使用 `ty` 进行类型检查
- **TypeScript**：ESLint + Prettier
- 在 Python 中全面使用类型注解
- 编辑代码时遵循既有模式
- 优先编写职责清晰的小型聚焦函数

## 测试说明

- 测试使用 pytest，支持异步
- 集成测试需要运行中的数据库（Neo4j、PostgreSQL）
- 使用 `@pytest.mark.integration` 与 `@pytest.mark.slow` 标记特殊测试
- 测试文件位于各包内的 `*_tests/` 目录中

## 示例与演示

`examples/` 目录包含可运行的示例与验证脚本：

### 武侠知识图谱（武侠小说知识图谱）

演示在 MemMachine 之上构建特定领域的知识图谱：

- `wuxia_minimal_verify.py` - 基于本体进行实体/关系抽取的最小验证脚本
- `wuxia_graph_api.py` - 用于图谱可视化的 FastAPI Web 服务
- `wuxia_graph_viewer.html` - 具备交互式图谱可视化的 Web 界面
- `wuxia_README.md` - 使用文档
- `sample_wuxia_text.txt` - 用于测试的小说示例文本

运行方式：
```bash
cd examples
./run_wuxia_verify.sh    # Run extraction verification
./run_graph_web.sh       # Start web visualization server
```

### 其他示例

- `memmachine_client_demo.py` - Python 客户端 SDK 演示
- `MemMachine_V2_Complete_Guide.ipynb` - 交互式 Jupyter 笔记本指南
- `simple_chatbot/`、`qwen_agent/`、`openai_agent/` - 代理框架示例

## MCP 服务器

服务器包含 MCP（Model Context Protocol）支持：
- `memmachine-mcp-stdio` - 用于 Claude Desktop（stdio 模式）
- `memmachine-mcp-http` - 用于 Web 客户端（HTTP 模式）

## 重要事项：Docker 部署

切勿自动停止或移除正在运行的 Docker 容器。如发生冲突，请告知用户手动解决。
