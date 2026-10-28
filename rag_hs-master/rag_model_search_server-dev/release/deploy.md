# Deploy 部署文档目录

本目录包含海思 RAG 服务器各组件的部署文档和连通性测试脚本，覆盖核心存储、模型服务、长期记忆与代码知识图谱三大类共 9 个组件。

## 目录结构

```
release/
├── deploy.md                              # 本说明文档
├── docker/                               # 各服务的 Docker 部署文档与编排文件
│   ├── docker.md                         # Docker 部署总览（本地 GPU 服务器）
│   ├── env-local.md                      # 本地 WSL2 开发环境入口命令
│   ├── postgresql/                       # PostgreSQL 关系型数据库
│   │   └── PostgreSQL数据库部署文档.md
│   ├── milvus/                           # Milvus 向量数据库
│   │   └── Milvus部署文档.md
│   ├── es/                               # Elasticsearch 搜索引擎
│   │   └── Elasticsearch部署文档.md
│   ├── redis/                            # Redis 缓存服务
│   │   └── Redis部署文档.md
│   ├── embedding/                        # Qwen3-Embedding 文本向量化模型
│   │   └── Qwen3-Embedding 模型部署文档.md
│   ├── reranker/                         # Coreb-Code-Reranker 重排序模型
│   │   └── Coreb-Code-Reranker模型部署文档.md
│   ├── lac/                              # LAC 中文分词服务
│   │   ├── LAC部署文档.md
│   │   ├── Dockerfile
│   │   ├── docker-compose.yml
│   │   ├── app.py
│   │   ├── start.sh
│   │   ├── requirements.txt
│   │   └── lac_params/                  # LAC 模型参数（model.pdparams / 词典）
│   ├── memmachine/                       # MemMachine 长期记忆内核
│   │   ├── MemMachine.md                 # 入门与参考手册
│   │   └── MemMachine/                   # 源码仓库（含 docker-compose.yml、Dockerfile）
│   ├── mempalace/                        # MemPalace 记忆编排容器
│   │   ├── MemPalace部署文档.md
│   │   ├── mempalace-api.md
│   │   ├── mempalace.md
│   │   └── mempalace/                   # 源码仓库
│   └── dual-mcp/                         # Dual-MCP 代码知识图谱（已合并 codebase-memory-mcp + codegraph）
│       ├── API.md / API-K8S.md
│       └── mcp_stdio_bridge.py
└── test/                                 # 连通性测试脚本
    ├── test_postgresql_api.py            # PostgreSQL 连接测试
    ├── test_milvus_api.py                # Milvus 连接测试
    ├── test_elasticsearch_api.py         # Elasticsearch 连接测试
    ├── test_redis_api.py                 # Redis 连接测试
    ├── test_embedding_api.py            # Qwen3-Embedding 模型测试
    ├── test_reranker_api.py              # Coreb-Code-Reranker 模型测试
    └── test_lac_api.py                   # LAC 分词服务测试
```

## 服务清单

所有服务部署在 `192.168.80.250`（Windows 宿主机 + WSL2 形态），通过 Docker Compose 编排。

### 核心存储与基础设施（必选）

海思 RAG 主链路依赖，对应架构文档"数据区"五库协同。

| 服务 | 类型 | 地址 | 宿主机端口 | 容器端口 | 凭证 | 用途 |
|------|------|------|------------|----------|------|------|
| PostgreSQL | 关系型数据库 | 192.168.80.250 | 16602 | 5432 | farai / farai123（DB: faraidb） | 业务数据：会话、消息、知识库元数据、权限策略 |
| Milvus | 向量数据库 | 192.168.80.250 | 19530 | 19530 | root / Milvus | 向量语义检索（ANN），存储文档向量 |
| Elasticsearch | 搜索引擎 | 192.168.80.250 | 19200 | 9200 | 无 | 全文检索（BM25），混合检索召回 |
| Redis | 缓存服务 | 192.168.80.250 | 16379 | 6379 | farai123 | 缓存与会话管理、知识库信息同步 |

### 模型服务（必选）

对应架构文档"应用区·模型服务"，分入库子池与检索子池。

| 服务 | 类型 | 地址 | 宿主机端口 | 容器端口 | GPU | 用途 |
|------|------|------|------------|----------|-----|------|
| Qwen3-Embedding | 文本向量化 | 192.168.80.250 | 16604 | 8080 | 是 | 文本向量化（2560 维），入库子池·向量化 |
| Coreb-Code-Reranker | 重排序模型 | 192.168.80.250 | 16603 | 8080 | 是 | 检索结果重排序，检索子池·精排 |
| LAC | 中文分词 | 192.168.80.250 | 5955 | 5955 | 否 | 中文分词 + 词性标注，查询关键词抽取 |

> 主项目通过 `server/chat/pipeline/lac_keywords.py` 以异步 HTTP（httpx）调用 LAC 微服务，用于 multi chat 路由阶段抽取查询关键词；主项目镜像无需安装 paddlepaddle。

### 长期记忆与代码知识图谱（可选，TODO 对接）

对应 `TODO.md` 中"4 个项目对接开发以及部署"，海思 RAG 主链路当前未依赖，规划接入。

| 服务 | 类型 | 地址 | 宿主机端口 | 容器端口 | 用途 |
|------|------|------|------------|----------|------|
| MemMachine | 长期记忆内核 | 192.168.80.250 | 8080 | 8080 | 跨会话长期记忆（情景/语义/工作/Profile 四类），需 Postgres + Neo4j |
| MemPalace | 记忆编排容器 | 192.168.80.250 | 8777 | 8777 | 调用 MemMachine，对外暴露 REST 记忆接口（`/store` `/search` `/kg`） |
| Dual-MCP | 代码知识图谱 | 192.168.80.250 | 9789 | 9788 | CBM + codegraph 合并，REST + MCP 双协议对外服务 |

> Dual-MCP 已合并 codebase-memory-mcp + codegraph 两项目源码，不再单独部署两服务。

## 使用说明

### 1. 本地开发环境入口

本地开发基于 WSL2 + Docker，入口命令见 `docker/env-local.md`：

```bash
# 进入 WSL2 Ubuntu 环境
wsl -d Ubuntu

# 进入项目目录
cd /data/haisi/haisi_rag_models_server
```

### 2. 部署服务

参考 `docker/<service>/` 目录下各服务的部署文档进行部署。典型流程：

```bash
# 进入对应服务目录
cd docker/postgresql

# 按部署文档启动 Docker Compose
docker-compose up -d

# 查看容器状态
docker ps
```

Docker 总体部署说明见 [docker/docker.md](docker/docker.md)。

### 3. 测试连通性

部署完成后，运行 `test/` 目录下的测试脚本验证服务是否正常：

```bash
# 进入测试目录
cd release/test

# 测试核心存储
python test_postgresql_api.py        # PostgreSQL（16602）
python test_milvus_api.py            # Milvus（19530）
python test_elasticsearch_api.py     # Elasticsearch（19200）
python test_redis_api.py             # Redis（16379）

# 测试模型服务
python test_embedding_api.py         # Qwen3-Embedding（16604）
python test_reranker_api.py           # Coreb-Code-Reranker（16603）

# 测试分词服务（默认 127.0.0.1，远程用 --host）
python test_lac_api.py --host 192.168.80.250   # LAC（5955）
```

### 4. 依赖安装

运行测试脚本前，请确保安装了相关依赖：

```bash
pip install psycopg2-binary pymilvus elasticsearch redis requests
```

## 部署架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                192.168.80.250（Windows + WSL2 宿主机）                  │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     Docker Engine                                │   │
│  │                                                                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │ PostgreSQL  │  │   Milvus    │  │     ES      │              │   │
│  │  │  :16602     │  │  :19530     │  │  :19200     │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  │                                                                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │    Redis    │  │  Embedding  │  │  Reranker   │              │   │
│  │  │  :16379     │  │  :16604     │  │  :16603     │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  │                                                                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │     LAC     │  │ MemMachine  │  │  MemPalace  │              │   │
│  │  │   :5955     │  │   :8080     │  │   :8777     │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  │                                                                  │   │
│  │  ┌─────────────────────────────────────────────┐                  │   │
│  │  │       Dual-MCP（CBM + codegraph）            │                  │   │
│  │  │                :9789                        │                  │   │
│  │  └─────────────────────────────────────────────┘                  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                ▲
                                │ HTTP
                                │
                  ┌─────────────┴──────────────┐
                  │  haisi_rag_models_server    │
                  │  （API + WebUI，:7861/:8501）│
                  └────────────────────────────┘
```

## 部署顺序建议

1. **存储层**：PostgreSQL → Milvus → Elasticsearch → Redis
2. **模型层**：Qwen3-Embedding → Coreb-Code-Reranker → LAC（可选独立部署）
3. **记忆层（可选）**：MemMachine → MemPalace → Dual-MCP
4. **应用层**：haisi_rag_models_server（主项目，通过 `startup.py` 启动）

## 注意事项

- **测试地址**：测试脚本默认连接 `192.168.80.250`（除 LAC 默认 `127.0.0.1`），生产环境请按实际情况修改
- **凭证安全**：上述凭证为开发环境默认值，生产部署务必修改并接入 KMS 托管密钥
- **GPU 驱动**：Qwen3-Embedding 与 Coreb-Code-Reranker 依赖 NVIDIA 驱动，请确保驱动版本与 CUDA 兼容
- **端口冲突**：部署前确认宿主机端口未被占用
- **数据持久化**：重要服务已配置数据卷，容器重启不会丢失数据；生产环境建议定期备份
- **测试清理**：测试脚本会创建临时数据用于验证功能，测试完成后会自动清理（如 `test_table_haisi`）

## 相关文档

- 项目总览：[../README.md](../README.md)
- 架构设计：[../docs/海思知识库架构设计8.9版本.md](../docs/海思知识库架构设计8.9版本.md)
- Docker 总览：[docker/docker.md](docker/docker.md)
- 本地环境：[docker/env-local.md](docker/env-local.md)
- 四大记忆组件对比：[../../Analysis.md](../../Analysis.md)
