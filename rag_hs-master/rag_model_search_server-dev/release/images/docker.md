# Docker 部署文档目录

本目录包含 HaiSI RAG 各组件在 `192.168.80.250` 服务器（Windows + WSL2）上的 Docker 部署文档与编排文件。

## 概述

所有服务均以 Docker 容器方式运行在 WSL2 内，通过 Windows 侧的端口转发（`firewall.bat`）对局域网开放。模型类服务使用 GPU，其余服务为 CPU-only。

## 目录结构

```
images/
├── docker.md                          # 本说明文档
├── env-local.md                       # 本地部署环境与命令（WSL2 进入方式）
├── firewall.bat                       # Windows 端口转发 + 防火墙放行统一脚本
├── dual-mcp/                          # Dual-MCP 代码知识图谱服务（已合并 codebase-memory-mcp + codegraph 源码）
│   ├── API.md / API-K8S.md
│   ├── README.md
│   └── mcp_stdio_bridge.py
├── embedding/                         # Qwen3-Embedding 文本向量化服务
│   ├── Qwen3-Embedding 模型部署文档.md
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── app.py / model_server.py
│   ├── requirements.txt
│   └── start.sh
├── es/                                # Elasticsearch 全文搜索引擎
│   └── Elasticsearch部署文档.md
├── lac/                               # LAC 中文分词与词性标注服务
│   ├── LAC部署文档.md
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── app.py
│   ├── lac_params/                   # LAC 模型参数（model.pdparams / 词表）
│   ├── requirements.txt
│   └── start.sh
├── memmachine/                        # MemMachine AI Agent 长期记忆服务（部署文件）
│   ├── MemMachine.md
│   ├── Dockerfile
│   └── .env.example
└── mempalace/                         # MemPalace 记忆宫殿服务（部署文件）
    ├── MemPalace部署文档.md
    ├── build.sh
    └── docker-compose.yml
```

> 注：`memmachine` / `mempalace` 两个开源服务的源码已抽离到项目根 `open_source/<name>/`，本目录仅保留 Dockerfile、docker-compose.yml、部署脚本等部署文件。compose 的 build context 指向项目根，Dockerfile 内分别从 `open_source/<name>/` 取源码、从 `release/images/<name>/` 取部署文件。
>
> 另：`codebase-memory-mcp` + `codegraph` 两项目已合并为 `dual-mcp/` 服务（CBM + codegraph 双引擎 + MCP 桥，源码与构建脚本见 [dual-mcp/README.md](./dual-mcp/README.md)），本目录不再单独构建两项目镜像。代码知识图谱检索统一走 dual-mcp 服务（见 [server/chat/pipeline/dual_mcp.py](../../server/chat/pipeline/dual_mcp.py)）。

## 服务架构

本目录提供部署文档/编排文件的服务：

| 服务 | 部署目录 | 宿主机端口 | 容器端口 | GPU | 用途 |
|------|----------|------------|----------|-----|------|
| Dual-MCP | [dual-mcp/](./dual-mcp/) | 9789 | 9788 | 否 | 代码知识图谱（CBM + codegraph 合并，REST + MCP 双协议） |
| LAC | [lac/](./lac/) | 5955 | 5955 | 否 | 中文分词与词性标注、查询关键词抽取 |
| Qwen3-Embedding | [embedding/](./embedding/) | 16604 | 16604 | 是 | 文本向量化、语义相似度计算 |
| Elasticsearch | [es/](./es/) | 19200 | 9200 | 否 | 全文搜索与倒排索引 |
| MemMachine-App | [memmachine/](./memmachine/) | 8080 | 8080 | 否 | AI Agent 长期记忆 REST API v2 + MCP |
| ├ MemMachine-Postgres | [memmachine/](./memmachine/) | 5432 | 5432 | 否 | 语义/画像存储（pgvector） |
| └ MemMachine-Neo4j | [memmachine/](./memmachine/) | 7474 / 7687 | 7474 / 7687 | 否 | 情景记忆（图数据库，Bolt 7687 / HTTP 7474） |

> Elasticsearch 另含 Kibana（容器 5601），版本需与 ES 一致，详见 [es/Elasticsearch部署文档.md](./es/Elasticsearch部署文档.md)。
> MemMachine 由 `memmachine/docker-compose.yml` 统一编排 app + postgres + neo4j 三个容器。

## 服务器信息

- **服务器地址**：`192.168.80.250`
- **宿主环境**：Windows + WSL2（Ubuntu），Docker 运行在 WSL2 内
- **部署方式**：各子目录独立 Docker Compose 编排
- **GPU**：NVIDIA（用于 Embedding / Reranker 推理加速）

## 局域网访问说明

WSL2 内部端口默认仅对 Windows 本机 `localhost` 可达，局域网其他机器访问 `192.168.80.250:<port>` 会被拒绝。`firewall.bat` 为每个服务配置：

1. `netsh interface portproxy`：Windows `0.0.0.0:<port>` → WSL2 IP `<port>`
2. Windows 防火墙入站放行规则（`haisi <name> <port>`）

```cmd
:: 必须以管理员身份运行 cmd
firewall.bat                       :: 自动探测 WSL2 IP，放行全部服务
firewall.bat 172.24.104.177        :: 指定 WSL2 IP
```

> 幂等可重复执行（先删后建）。WSL2 重启后内部 IP 可能变化，外部访问不通时重跑本脚本。

## 部署流程

### 1. 环境准备

参见 [env-local.md](./env-local.md) 进入 WSL2 并切换到项目目录：

```bash
wsl -d Ubuntu
cd /data/haisi/haisi_rag_models_server
```

前置依赖（Docker 与 docker-compose 已预装）：

- Docker Engine 24.0+（GPU 服务需支持 NVIDIA 运行时）
- Docker Compose 2.0+
- NVIDIA Container Toolkit（仅 GPU 模型服务必需）

### 2. 部署服务

进入对应子目录，按其部署文档执行构建与启动：

```bash
cd release/images/<service>
# 典型流程（具体以子目录文档为准）
docker compose up -d --build
```

### 3. 配置端口转发（Windows 侧）

回到 Windows，以管理员身份运行：

```cmd
:: 在 release/images 目录下
firewall.bat
```

### 4. 验证部署

使用 `release/test/` 目录下的测试脚本验证服务连通性：

```bash
cd ../test
python test_<service>_api.py
```

## 常用命令

```bash
# 查看所有运行中的容器
docker ps

# 查看容器日志
docker logs -f <container_name>

# 重启容器
docker restart <container_name>

# 停止某服务（在其子目录下）
docker compose down

# 启动某服务（在其子目录下）
docker compose up -d
```

## 注意事项

1. **端口冲突**：确保宿主机端口未被占用；端口清单见 `firewall.bat`。
2. **数据持久化**：MemMachine 的 postgres / neo4j、Dual-MCP 的索引数据卷均已配置持久化，容器重启不丢数据。
3. **GPU 驱动**：模型服务依赖 NVIDIA 驱动，需与容器内 CUDA 版本兼容（见各模型部署文档的"环境要求"）。
4. **源码编译**：`memmachine` 与 `mempalace` 的源码在项目根 `open_source/<name>/` 下，Dockerfile 内从该路径 COPY 后本地构建，compose 的 build context 已指向项目根，`git clone` 后可直接构建。
5. **代码知识图谱**：`codebase-memory-mcp` + `codegraph` 已合并进 `dual-mcp/` 镜像（CBM + codegraph 双引擎），不再单独构建；见 [dual-mcp/](./dual-mcp/) 目录文档。
6. **网络配置**：服务间通信走 Docker 内部网络，外部访问通过 `firewall.bat` 映射的端口。

## 相关文档

- [本地部署环境与命令](./env-local.md)
- [端口转发与防火墙脚本](./firewall.bat)
- 各服务部署文档见对应子目录
- [Docker 官方文档](https://docs.docker.com/)
- [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/overview.html)
