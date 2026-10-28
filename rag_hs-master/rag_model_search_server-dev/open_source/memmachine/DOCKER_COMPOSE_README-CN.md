# MemMachine Docker 安装指南

## 快速开始

### 前置条件
- 已安装 Docker 和 Docker Compose
- 已配置 OpenAI API key

### 1. 配置环境
复制示例环境文件并添加你的 OpenAI API key：
```bash
cp sample_configs/env.dockercompose .env
# 编辑 .env 并添加你的 OPENAI_API_KEY
```

### 2. 配置 MemMachine
复制示例配置文件并根据你的设置进行更新：
```bash
cp sample_configs/episodic_memory_config.sample configuration.yml
# 编辑 configuration.yml 并更新：
# - 将 <YOUR_API_KEY> 替换为你的 OpenAI API key
# - 将 <YOUR_PASSWORD_HERE> 替换为你的 Neo4j 密码
# - 在 Docker 环境下将 host 从 'localhost' 改为 'neo4j'
```

### 3. 启动服务

#### 选项 A：使用 MemMachine Compose 脚本（推荐）
运行启动脚本：
```bash
./memmachine-compose.sh
```

该脚本会：
- ✅ 检查 Docker 和 Docker Compose 的可用性
- ✅ 验证 .env 文件和 OpenAI API key
- ✅ 如需要则检查并创建 configuration.yml
- ✅ 验证配置设置
- ✅ 拉取并启动所有服务（PostgreSQL、Neo4j 5.23、MemMachine）
- ✅ 等待所有服务变为健康状态
- ✅ 显示服务 URL 和连接信息

#### 选项 B：直接使用 Docker Compose
```bash
docker-compose up -d
```

### 4. 访问服务
启动后，你可以访问：

- **MemMachine API**：http://localhost:8080
- **Neo4j Browser**：http://localhost:7474
- **健康检查**：http://localhost:8080/health
- **指标**：http://localhost:8080/metrics

### 5. 测试安装
```bash
# 测试健康检查端点
curl http://localhost:8080/health

# 测试记忆存储
curl -X POST "http://localhost:8080/v1/memories" \
  -H "Content-Type: application/json" \
  -d '{
    "session": {
      "group_id": "test-group",
      "agent_id": ["test-agent"],
      "user_id": ["test-user"],
      "session_id": "test-session-123"
    },
    "producer": "test-user",
    "produced_for": "test-user",
    "episode_content": "Hello, this is a test message",
    "episode_type": "text",
    "metadata": {"test": true}
  }'
```

## 常用命令

### 使用 MemMachine Compose 脚本（推荐）

#### 查看日志
```bash
./memmachine-compose.sh logs
```

#### 停止服务
```bash
./memmachine-compose.sh stop
```

#### 重启服务
```bash
./memmachine-compose.sh restart
```

#### 清理（删除所有数据）
```bash
./memmachine-compose.sh clean
```

#### 显示帮助
```bash
./memmachine-compose.sh help
```

### 直接使用 Docker Compose

#### 查看日志
```bash
docker-compose logs -f
```

#### 停止服务
```bash
docker-compose down
```

#### 重启服务
```bash
docker-compose restart
```

#### 清理（删除所有数据）
```bash
docker-compose down -v
```

## 服务

- **PostgreSQL**（端口 5432）：使用 pgvector 的个人资料记忆存储
- **Neo4j**（端口 7474、7687）：带向量相似度的情节记忆
- **MemMachine**（端口 8080）：主 API 服务器（使用预构建的 `memmachine/memmachine` 镜像）

## 配置

关键文件：
- `.env` - 环境变量
- `configuration.yml` - MemMachine 配置
- `docker-compose.yml` - 服务定义
- `memmachine-compose.sh` - 带验证和健康检查的启动脚本

### ⚠️ 重要配置说明

**1. 数据库配置一致性**
确保 `configuration.yml` 中的数据库配置详情与 `.env` 中的数据库配置详情一致

两个文件必须保持一致：
- 数据库主机名（使用服务名：`postgres`、`neo4j`）
- 数据库端口（PostgreSQL 为 5432，Neo4j 为 7687）
- 数据库凭证（用户名和密码）
- 数据库名称

**2. Configuration.yml 设置**
`configuration.yml` 文件包含 MemMachine 的专用设置：
- **模型配置**：用于 LLM 和 embeddings 的 OpenAI API 设置
- **存储配置**：Neo4j 连接详情
- **记忆设置**：会话记忆容量和限制
- **重排序配置**：搜索和排序算法

**configuration.yml 中需要更新的关键设置：**
- 将 `<YOUR_API_KEY>` 替换为你的 OpenAI API key（同时出现在 Model 和 embedder 部分）
- 将 `<YOUR_PASSWORD_HERE>` 替换为你的 Neo4j 密码
- 确保在 Docker 环境下将 Neo4j 主机设置为 `neo4j`（而不是 `localhost`）

这确保 MemMachine 能够正确连接到 Docker 服务，并使用你的 OpenAI API key 进行 embeddings 和 LLM 操作。
