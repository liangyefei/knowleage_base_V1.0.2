# AI 助手指南 - 使用 MemMachine

本指南帮助 AI 编码助手（如 Cursor、Claude Code、Codex 等）理解如何帮助开发者将 MemMachine 集成到其应用中。MemMachine 是一个面向 AI agent 的开源记忆层，使应用能够学习、存储并回忆来自过往会话的数据与偏好。

## 项目概述

MemMachine 提供两种类型的记忆：

- **情节记忆（Episodic Memory）**：将对话情节（消息、交互）存储在图数据库（Neo4j）中。支持短期（近期上下文）和长期（已汇总）存储。
- **语义记忆（Semantic Memory）**：从对话中提取的用户专属事实、偏好与知识。使用向量嵌入进行语义搜索，存储在 PostgreSQL 中。

## 安装

MemMachine **不是托管服务**。用户必须运行自己的 MemMachine 服务端实例。

### 快速安装（Docker - 推荐）

最简单的入门方式是使用 Docker 与提供的 compose 脚本：

```bash
# 下载并运行安装脚本
./memmachine-compose.sh

# 该脚本会：
# - 检查 Docker 安装
# - 如需要则创建 .env 文件
# - 生成 cfg.yml（或 configuration.yml），并选择供应商（OpenAI、Bedrock 或 Ollama）
# - 设置 API 密钥
# - 启动所有服务（MemMachine、Neo4j、PostgreSQL）

# 其他常用命令：
./memmachine-compose.sh stop      # 停止服务
./memmachine-compose.sh restart   # 重启服务
./memmachine-compose.sh logs      # 查看日志
./memmachine-compose.sh clean     # 删除所有数据与卷
```

### Python 包安装

用户可通过 pip 安装 MemMachine：

```bash
# 安装完整套件（客户端 + 服务端）
pip install memmachine

# 或分别安装各组件：
pip install memmachine-client    # 仅安装 Python 客户端库
pip install memmachine-server   # 仅安装服务端

# 如需 GPU 支持（使用本地嵌入模型时）：
pip install "memmachine-server[gpu]"
```

### 运行服务端

安装完成后，用户可运行服务端：

```bash
# 使用 Python 包
# 注意：配置文件可命名为 cfg.yml 或 configuration.yml
memmachine-server --config cfg.yml
# 或
memmachine-server --config configuration.yml

# 若使用 Docker，compose 脚本会自动处理此步骤
```

## API 结构

### REST API v2

v2 API 采用基于项目的模型，所有操作都限定在某个 organization 和 project 范围内：

**Base URL**: `http://localhost:8080/api/v2`

**关键端点**：

- `POST /api/v2/projects` - 创建新项目
- `POST /api/v2/projects/get` - 获取项目信息
- `POST /api/v2/projects/list` - 列出所有项目
- `POST /api/v2/projects/delete` - 删除项目
- `POST /api/v2/memories` - 添加记忆（episodes）
- `POST /api/v2/memories/search` - 搜索记忆
- `POST /api/v2/memories/episodic/delete` - 删除情节记忆
- `POST /api/v2/memories/semantic/delete` - 删除语义记忆
- `GET /api/v2/health` - 健康检查端点
- `GET /api/v2/metrics` - Prometheus 指标端点

## Python SDK 使用

Python SDK 提供了与 MemMachine 交互的便捷接口。以下是用户的典型使用方式：

### 基础设置

```python
from memmachine_client import MemMachineClient

# 初始化客户端
client = MemMachineClient(base_url="http://localhost:8080")

# 创建或获取项目
# 注意：description、embedder 和 reranker 为可选项
# - description 默认为 ""（空字符串）
# - embedder 默认为 ""（使用服务端配置的默认值）
# - reranker 默认为 ""（使用服务端配置的默认值）
project = client.create_project(
    org_id="my-org",
    project_id="my-project",
    description="My project description"  # 可选，默认为 ""
)

# 或获取已有项目
project = client.get_project(org_id="my-org", project_id="my-project")
```

### 使用记忆

```python
# 为特定上下文创建记忆接口
memory = project.memory(
    user_id="user123",        # 用户标识（存储在 metadata 中）
    agent_id="agent456",      # Agent 标识（存储在 metadata 中）
    session_id="session789",  # 会话标识（存储在 metadata 中）
    group_id="group1"         # 可选：群组标识（存储在 metadata 中）
)

# 添加记忆
# 注意：未指定时的默认值：
# - role 默认为 "user"
# - producer 默认为 user_id（若已设置），否则为 "user"
# - produced_for 默认为 agent_id（若已设置），否则为 "agent"
# - episode_type 默认为 "text"（存储在 metadata 中）
# - metadata 默认为 {}（空字典，但 user_id 等上下文字段会自动添加）
# - timestamp 自动设置为当前时间
memory.add(
    content="I prefer Python over JavaScript",
    role="user",              # 可选，默认为 "user"
    metadata={"type": "preference"}  # 可选，默认为 {}
)

memory.add(
    content="I understand you prefer Python",
    role="assistant"  # 可选，默认为 "user"
)

# 搜索记忆
# 注意：未指定时的默认值：
# - limit 默认为 10（用作 top_k）
# - expand_context 默认为 0
# - score_threshold 默认为 None
# - agent_mode 默认为 False
# - filter_dict 默认为 None（但来自 user_id/agent_id/session_id 的上下文过滤会自动应用）
results = memory.search(
    query="What are the user's preferences?",
    limit=10  # 可选，默认为 10
)

# 结果结构：
# {
#     "episodic_memory": [...],      # 情节记忆结果列表
#     "episode_summary": [...],      # 情节摘要
#     "semantic_memory": [...]       # 语义记忆结果列表
# }
```

### 记忆操作

```python
# 删除情节记忆
memory.delete_episodic(episodic_id="episode_123")
# 或删除多条
memory.delete_episodic(episodic_ids=["ep1", "ep2"])

# 删除语义记忆
memory.delete_semantic(semantic_id="semantic_123")
# 或删除多条
memory.delete_semantic(semantic_ids=["sem1", "sem2"])

# 获取当前上下文
context = memory.get_context()
# 返回：{"org_id": "...", "project_id": "...", "user_id": "...", ...}
```

## REST API 使用示例

以下示例演示如何通过 `curl` 命令使用 MemMachine REST API v2。所有示例均假设服务端运行在 `http://localhost:8080`。

### 项目管理

#### 列出项目

列出某个 organization 下的所有项目：

```bash
curl -X POST "http://localhost:8080/api/v2/projects/list" \
  -H "Content-Type: application/json" \
  -d '{}'
```

**响应**：返回所有项目及其配置的列表。

#### 创建项目

创建新项目。必填字段：`org_id`、`project_id`。可选字段：`description`（默认为 `""`）、`config.embedder`（默认为 `""`，使用服务端默认值）、`config.reranker`（默认为 `""`，使用服务端默认值）。

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

**响应**：返回创建的项目及其配置。

#### 获取项目信息

获取已有项目的信息：

```bash
curl -X POST "http://localhost:8080/api/v2/projects/get" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project"
  }'
```

**响应**：返回项目详情，包括 description 和配置。

#### 删除项目

删除项目及其所有关联记忆：

```bash
curl -X POST "http://localhost:8080/api/v2/projects/delete" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project"
  }'
```

**响应**：成功时返回 204 No Content。

### 记忆操作

#### 添加记忆

```bash
# 必填字段：org_id, project_id, messages[].content
# 可选 message 字段：
# - producer（默认为 "user"）
# - produced_for（默认为 ""）
# - role（默认为 ""）
# - timestamp（默认为当前 UTC 时间）
# - metadata（默认为 {}）
# 可选请求字段：
# - types（默认为 []，表示所有记忆类型）
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
        "metadata": {
          "type": "preference",
          "topic": "food"
        }
      }
    ]
  }'
```

#### 搜索记忆

在情节记忆和语义记忆中搜索。必填字段：`org_id`、`project_id`、`query`。可选字段：`top_k`（默认为 `10`）、`filter`（默认为 `""`）、`types`（默认为 `[]`，表示所有记忆类型）、`agent_mode`（默认为 `false`；设为 `true` 时使用检索 agent 搜索。在 agent 模式下，不支持 score-threshold 过滤）。

```bash
curl -X POST "http://localhost:8080/api/v2/memories/search" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "query": "What does the user like?",
    "top_k": 5,
    "types": ["episodic", "semantic"]
  }'
```

**响应**：返回包含 `episodic_memory` 和 `semantic_memory` 数组的搜索结果。

**带 filter 的示例**：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/search" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "query": "user preferences",
    "top_k": 10,
    "filter": "metadata.user_id='user123'",
    "types": ["semantic"]
  }'
```

**使用检索 agent 模式的示例**：

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

#### 删除情节记忆

按 ID 删除指定的情节记忆：

```bash
# 删除单条情节记忆
curl -X POST "http://localhost:8080/api/v2/memories/episodic/delete" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "episodic_id": "episode_123"
  }'
```

**删除多条情节记忆**：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/episodic/delete" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "episodic_ids": ["episode_123", "episode_456", "episode_789"]
  }'
```

**响应**：成功时返回 204 No Content。

#### 删除语义记忆

按 ID 删除指定的语义记忆：

```bash
# 删除单条语义记忆
curl -X POST "http://localhost:8080/api/v2/memories/semantic/delete" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "semantic_id": "semantic_123"
  }'
```

**删除多条语义记忆**：

```bash
curl -X POST "http://localhost:8080/api/v2/memories/semantic/delete" \
  -H "Content-Type: application/json" \
  -d '{
    "org_id": "my-org",
    "project_id": "my-project",
    "semantic_ids": ["semantic_123", "semantic_456"]
  }'
```

**响应**：成功时返回 204 No Content。

### 实用端点

#### 健康检查

检查 MemMachine 服务端是否正在运行且健康：

```bash
curl -X GET "http://localhost:8080/health"
```

**响应**：返回服务端健康状态。

#### 指标

获取 Prometheus 指标（用于监控）：

```bash
curl -X GET "http://localhost:8080/metrics"
```

**响应**：返回 Prometheus 格式的指标。

## 常见集成模式

### 模式一：带记忆的聊天机器人

```python
from memmachine_client import MemMachineClient

client = MemMachineClient(base_url="http://localhost:8080")
project = client.get_project(org_id="my-org", project_id="chatbot-project")

def handle_user_message(user_id: str, session_id: str, message: str):
    # 为该用户/会话创建记忆上下文
    memory = project.memory(user_id=user_id, session_id=session_id)

    # 存储用户消息
    memory.add(content=message, role="user")

    # 搜索相关上下文
    context = memory.search(query=message, limit=5)

    # 用上下文构建提示词
    prompt = build_prompt_with_context(message, context)

    # 获取 AI 响应（使用你的 LLM）
    response = get_llm_response(prompt)

    # 存储助手响应
    memory.add(content=response, role="assistant")

    return response
```

### 模式二：多用户群聊

```python
def handle_group_message(group_id: str, user_id: str, message: str):
    memory = project.memory(
        group_id=group_id,
        user_id=user_id,
        session_id=f"group-{group_id}"
    )

    # 存储消息
    memory.add(content=message, role="user")

    # 搜索群组上下文
    context = memory.search(query=message, limit=10)

    # 用上下文进行处理...
    return process_group_message(message, context)
```

### 模式三：用户偏好学习

```python
def learn_user_preference(user_id: str, preference: str, category: str):
    memory = project.memory(user_id=user_id)

    # 将偏好作为语义记忆存储
    memory.add(
        content=preference,
        role="user",
        episode_type="semantic",  # 存储到语义记忆
        metadata={"category": category, "type": "preference"}
    )

def get_user_preferences(user_id: str, category: str = None):
    memory = project.memory(user_id=user_id)

    query = f"user preferences"
    if category:
        query += f" about {category}"

    results = memory.search(query=query, limit=20)
    return results["semantic_memory"]
```

## 数据类型与结构

### 消息结构

```python
{
    "content": str,              # 必填：消息内容
    "producer": str,             # 可选：创建者（默认为 "user"）
    "produced_for": str,         # 可选：面向对象（默认为 ""）
    "role": str,                 # 可选："user"、"assistant" 或 "system"（默认为 ""）
    "timestamp": str,            # 可选：ISO 8601 时间戳（默认为当前 UTC 时间）
    "metadata": dict             # 可选：附加元数据（默认为 {}）
}
```

### 搜索结果结构

```python
{
    "episodic_memory": [         # 情节记忆结果列表
        {
            "content": str,
            "producer": str,
            "produced_for": str,
            "role": str,
            "timestamp": str,
            "metadata": dict,
            # ... 其他字段
        }
    ],
    "episode_summary": [         # 情节摘要
        {
            "content": str,
            # ... 其他字段
        }
    ],
    "semantic_memory": [         # 语义记忆结果列表
        {
            "content": str,
            "metadata": dict,
            # ... 其他字段
        }
    ]
}
```

## 错误处理

常见 HTTP 状态码：

- **200 OK**：请求成功
- **201 Created**：资源创建成功
- **400 Bad Request**：请求数据无效或缺少必填字段
- **404 Not Found**：资源未找到（例如项目不存在）
- **422 Unprocessable Entity**：校验错误
- **500 Internal Server Error**：服务端错误
- **503 Service Unavailable**：服务未就绪（检查 `/health` 端点）

务必检查响应状态并妥善处理错误：

```python
import requests

try:
    response = requests.post(url, json=data)
    response.raise_for_status()  # 对 4xx/5xx 状态码抛出异常
    return response.json()
except requests.HTTPError as e:
    if e.response.status_code == 404:
        # 处理未找到
        pass
    elif e.response.status_code == 400:
        # 处理错误请求
        pass
    else:
        # 处理其他错误
        pass
```

## 默认值汇总

### 项目创建
- `description`：默认为 `""`（空字符串）
- `config.embedder`：默认为 `""`（使用服务端配置的默认 embedder）
- `config.reranker`：默认为 `""`（使用服务端配置的默认 reranker）

### Memory.add()（Python SDK）
- `role`：默认为 `"user"`
- `producer`：默认为 `user_id`（若 Memory 实例上已设置），否则为 `"user"`
- `produced_for`：默认为 `agent_id`（若 Memory 实例上已设置），否则为 `"agent"`
- `episode_type`：默认为 `"text"`（存储在 metadata 中）
- `metadata`：默认为 `{}`（空字典，但 `user_id`、`agent_id`、`session_id` 等上下文字段会自动添加）
- `timestamp`：自动设置为当前时间（非参数）

### Memory.search()（Python SDK）
- `limit`：默认为 `10`（在 API 中用作 `top_k`）
- `expand_context`：默认为 `0`
- `score_threshold`：默认为 `None`（无阈值过滤）
- `agent_mode`：默认为 `False`
- `filter_dict`：默认为 `None`，但来自 `user_id`、`agent_id` 和 `session_id` 的上下文过滤会自动应用

### REST API v2 默认值
- **添加记忆**：`producer` 默认为 `"user"`，`produced_for` 默认为 `""`，`role` 默认为 `""`，`timestamp` 默认为当前时间，`metadata` 默认为 `{}`，`types` 默认为 `[]`（所有记忆类型）
- **搜索记忆**：`top_k` 默认为 `10`，`filter` 默认为 `""`，`types` 默认为 `[]`（所有记忆类型），`agent_mode` 默认为 `false`

## 最佳实践

1. **始终使用项目**：v2 API 要求 `org_id` 和 `project_id`。使用项目来隔离不同的应用或环境。

2. **设置明确的用户 ID**：始终提供明确的 `user_id` 值，以确保记忆正确隔离。生产环境中切勿依赖默认值。

3. **使用合适的角色**：在 Python SDK 中，`role` 默认为 `"user"`。用户消息使用 `role="user"`，AI 响应使用 `role="assistant"`，系统消息使用 `role="system"`。注意：在 REST API 中，`role` 默认为空字符串，因此建议始终显式指定。

4. **响应前先搜索**：在生成响应前查询记忆以获取相关上下文，从而提供个性化、具备上下文感知的回答。

5. **存储重要信息**：存储用户偏好、事实和重要的对话要点，逐步构建知识库。

6. **有效使用元数据**：为记忆添加相关元数据，以便更好地过滤与组织：
   ```python
   memory.add(
       content="I work at Acme Corp",
       role="user",
       metadata={"type": "fact", "category": "employment", "verified": True}
   )
   ```

7. **监控健康状态**：在发起请求前使用 `/health` 端点检查服务端状态。

8. **处理超时**：为 API 调用设置合适的超时，尤其是可能耗时较长的搜索操作。

## 配置

MemMachine 通过 YAML 文件进行配置。该文件可命名为 `cfg.yml`（文档中推荐）或 `configuration.yml`（Docker 部署中使用）。主要配置段包括：

- **`resources.databases`**：数据库连接（用于情节记忆的 Neo4j、用于语义记忆的 PostgreSQL）
- **`resources.embedders`**：嵌入模型配置（OpenAI、AWS Bedrock、Ollama 等）
- **`resources.language_models`**：用于摘要和抽取的 LLM 配置
- **`episodic_memory`**：情节记忆设置（短期和长期）
- **`semantic_memory`**：语义记忆设置

用户通常在搭建服务端时配置此文件。Docker compose 脚本可帮助生成该文件。

## 示例与资源

- **文档**：[https://docs.memmachine.ai](https://docs.memmachine.ai)
- **API 参考**：[https://docs.memmachine.ai/api_reference](https://docs.memmachine.ai/api_reference)
- **示例目录**：查看仓库中的 `examples/` 目录以获取完整的集成示例：
  - `examples/crm/` - 带记忆的 CRM agent
  - `examples/financial_analyst/` - 金融分析 agent
  - `examples/health_assistant/` - 医疗健康助手
  - `examples/writing_assistant/` - 写作助手
  - `examples/frontend/` - Web 前端集成

## 总结

帮助用户集成 MemMachine 时：

1. **安装**：引导用户通过 `pip install memmachine` 安装，或使用 Docker 运行 `./memmachine-compose.sh`
2. **服务端设置**：确保 MemMachine 服务端正在运行（默认：`http://localhost:8080`）
3. **项目创建**：帮助用户使用 `client.create_project(org_id, project_id)` 创建项目
4. **记忆操作**：展示如何使用 `memory.add()` 存储、用 `memory.search()` 检索上下文
5. **上下文管理**：说明如何使用 `user_id`、`agent_id`、`session_id` 实现正确的记忆隔离
6. **错误处理**：为 API 调用实现恰当的错误处理
7. **最佳实践**：鼓励响应前先搜索，并存储重要信息

MemMachine 使 AI 应用能够记住过往交互、学习用户偏好，并在跨会话间提供个性化、具备上下文感知的响应。
