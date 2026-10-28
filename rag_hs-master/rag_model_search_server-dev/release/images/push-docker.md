# 构建与推送镜像到华为云镜像仓库

> 目标环境：华为云 x86_64 CPU 服务器。本文档仅构建 CPU 版本镜像，不构建 GPU / arm64 变体。
> 所有镜像初始版本统一为 `v1.0.0`。

> ⚠️ **华为云 SWR 兼容性**：必须用 `sudo docker buildx build --provenance=false --output type=docker`，不能用 `docker build`。
> 原因：BuildKit 默认生成 OCI manifest + provenance attestation，SWR push 时报 `Invalid image, fail to parse 'manifest.json'`。
> 加 `--provenance=false` 禁用 attestation，`--output type=docker` 输出 Docker v2 schema 格式，SWR 可正常解析。

> 仓库地址：`swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/`
> 登录（24h 有效）：`docker login -u cn-south-1@HST3W56SMGQPPOFLZ4SG -p cf77c67e52f8f6fe8efe4370b73ff08a76043fba4e0bb037e8787326af66a844 swr.cn-south-1.myhuaweicloud.com`
> 通用 tag + push 命令范式：
> ```bash
> sudo docker tag {镜像名称}:{版本名称} swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/{镜像名称}:{版本名称}
> sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/{镜像名称}:{版本名称}
> ```

## 构建与推送顺序

建议按以下顺序构建（MemPalace REST 依赖 MCP base 作为构建基底）：

0. rag 模型服务（第 0 节）
1. LAC（第 3 节）
2. MemMachine（第 4 节）
3. MemPalace REST（第 5 节，需先构建 MCP base）
4. Redis（第 8 节）
5. PostgreSQL（第 9 节）
6. Dual-MCP（第 10 节，已合并 codebase-memory-mcp + codegraph）
7. MCP 云端运行（第 5.4 节，HTTP transport 部署命令）
8. RAG 评测平台（第 11 节，源码在同级 `rag_eval_platform/` 项目，构建上下文非本仓库）

## 0. rag 模型服务（CPU）

> 拆分背景：知识库入库（写路径）与问答（读路径）负载特征差异大，K8s 上拆为两个 Deployment 独立扩缩容。
> Phase 2 A 方案：通过 `HAISI_RUN_MODE` 环境变量控制路由挂载（api.py）与 Kafka 启停（startup.py），构建出两个镜像。
> - ingestion 镜像：完全沿用原版 Dockerfile 环境（含 libreoffice/unstructured/tree-sitter/kafka-python + CUDA torch），仅改 `ENV HAISI_RUN_MODE=ingest` + `ENTRYPOINT startup_ingestion.py`。前面层与原版 Dockerfile 完全一致，构建时全 CACHED。
> - qa 镜像：用 requirements-qa.txt 瘦身版（去 unstructured/torch/kafka-python 等 22 个重包），`ENV HAISI_RUN_MODE=qa` + `ENTRYPOINT startup_qa.py`。镜像约 3.19GB（比 ingestion 瘦 1.84GB）。
> - file_chat 跨 Pod：upload_temp_docs 挂在 ingest 块（写共享 Milvus `temp_{id}` collection），qa 侧 file_chat 直查同一 Milvus；ApiRequest 双 client 路由 18 条入库路由走 `INGESTION_API_URL`。
> 详见 `docs/知识库入库与问答拆分方案.md`。

进入目录：`cd /data/haisi/haisi_rag_models_server`

### 0.0 全量镜像（兼容模式，开发/调试用）

`HAISI_RUN_MODE` 未设置 → 挂全部路由 + 启动 Kafka 消费者，等价于拆分前的单进程行为。本地开发、一次性全功能验证用此镜像。

构建：

```bash
sudo docker buildx build --provenance=false --output type=docker -f docker/Dockerfile -t haisi_rag_models_server:v1.0.7 .
```

推送：

```bash
sudo docker tag haisi_rag_models_server:v1.0.7 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi_rag_models_server:v1.0.7
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi_rag_models_server:v1.0.7
```

### 0.1 入库镜像（ingest 模式，K8s ingestion Deployment 用）

`HAISI_RUN_MODE=ingest` → 仅挂载入库 + 摘要路由 + upload_temp_docs，startup_ingestion.py 启动 KafkaCommServer 消费 `kb-document-processing` topic。完全沿用原版 Dockerfile 环境（保留 libreoffice/jdk/ghostscript/unstructured/tree-sitter/kafka-python 全套解析依赖）。

构建：

```bash
sudo docker buildx build --provenance=false --output type=docker -f docker/Dockerfile-ingestion -t haisi-rag-ingestion:v1.0.7 .
```

产物：`haisi-rag-ingestion:v1.0.7`（约 5.03GB）

推送：

```bash
sudo docker tag haisi-rag-ingestion:v1.0.7 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-ingestion:v1.0.7
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-ingestion:v1.0.7
```

### 0.2 问答镜像（qa 模式，K8s qa Deployment 用）

`HAISI_RUN_MODE=qa` → 挂载问答路由（chat/search/llm_model）+ file_chat（直查共享 Milvus `temp_{id}`，不做解析）+ WebUI（3.5-B 并入 QA）。Phase 2 A 方案瘦身版：用 requirements-qa.txt 去掉 unstructured/torch/kafka-python 等 22 个重包，镜像约 3.19GB（比 ingestion 瘦 1.84GB）。

构建：

```bash
sudo docker buildx build --provenance=false --output type=docker -f docker/Dockerfile-qa -t haisi-rag-qa:v1.0.7 .
```

产物：`haisi-rag-qa:v1.0.7`（约 3.19GB）

推送：

```bash
sudo docker tag haisi-rag-qa:v1.0.7 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-qa:v1.0.7
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-qa:v1.0.7
```

说明：

- 三者均基于 `uv:python3.10-bookworm-slim`，对外端口 7861（qa 额外暴露 8501 WebUI）。构建上下文为项目根目录。
- ingestion 镜像前面层（FROM/LABEL/apt/requirements.txt pip/unstructured/tree-sitter/kafka-python）与原版 Dockerfile 完全一致，原版镜像已存在时构建全 CACHED，只重打最后一层（ENV + ENTRYPOINT）。
- qa 镜像用独立 requirements-qa.txt，不装 unstructured/torch/kafka-python，误用 qa 镜像跑 ingest 模式会因缺 kafka-python 导致 KafkaCommServer import 失败（startup.py 已延迟 import，仅 ingest/all 分支触发）。
- 镜像 tag 中的模式后缀（`-ingestion` / `-qa`）即 `HAISI_RUN_MODE` 取值，与 K8s Deployment 的 `env.HAISI_RUN_MODE` 对齐。

## 1. Embedding 模型服务（`release/images/embedding/`）

> ⚠️ **暂不推送华为云**：该服务运行在 GPU 服务器上，目前不上华为云镜像仓库。以下命令仅作留存，需要时取消注释即可执行。

构建：见 `release/images/embedding/` 子目录部署文档。

本地镜像：`haisi-embedding:v1.0.0`

```bash
# sudo docker tag haisi-embedding:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-embedding:v1.0.0
# sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-embedding:v1.0.0
```

## 2. Reranker 重排序模型（`release/images/reranker/`）

> ⚠️ **暂不推送华为云**：该服务运行在 GPU 服务器上，目前不上华为云镜像仓库。以下命令仅作留存，需要时取消注释即可执行。

构建：见 `release/images/reranker/` 子目录部署文档（`Coreb-Code-Reranker模型部署文档.md` 中 `docker build -t coreb-reranker .`）。

本地镜像：`coreb-reranker:v1.0.0`

```bash
# sudo docker tag coreb-reranker:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/coreb-reranker:v1.0.0
# sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/coreb-reranker:v1.0.0
```

## 3. LAC 分词服务（`release/images/lac/`）

进入目录：

```bash
cd /data/haisi/haisi_rag_models_server
cd release/images/lac
```

构建：

```bash
docker build -t haisi-lac:v1.0.0 .
```

说明：基于 PaddlePaddle 2.6.1 的中文分词服务，CPU 推理即可，端口 5955。`Dockerfile` 与 `lac_params/` 模型文件同目录，构建上下文为当前目录。

推送：

```bash
sudo docker tag haisi-lac:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-lac:v1.0.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-lac:v1.0.0
```

> 亦存在 `haisi-lac:1.0` tag，按需推送：
>
> ```bash
> sudo docker tag haisi-lac:1.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-lac:1.0
> sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-lac:1.0
> ```

## 4. MemMachine 长期记忆服务（`release/images/memmachine/`）

进入目录：

```bash
cd /data/haisi/haisi_rag_models_server
```

构建：

```bash
docker build -t haisi-memmachine:v1.0.0 -f release/images/memmachine/Dockerfile .
```

说明：CPU 版本，**不传** `--build-arg GPU=true`（`Dockerfile` 默认 `GPU=false`）。基于 Python 3.12 + FastAPI，入口 `memmachine-server`。构建上下文为项目根，源码在 `open_source/memmachine/`，部署文件（Dockerfile / entrypoint.sh / configuration.yml）在 `release/images/memmachine/`。

推送：

```bash
# 4.1 MemMachine 应用镜像
sudo docker tag haisi-memmachine:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-memmachine:v1.0.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-memmachine:v1.0.0

# 4.2 Neo4j 依赖镜像（DDN 镜像加速 north-4 → 项目仓库 south-1）
# 公共镜像仓库，拉取无需登录；转推到项目仓库需先完成顶部 docker login
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/neo4j:5.26.4-community
sudo docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/neo4j:5.26.4-community \
            swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/neo4j:5.26.4-community
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/neo4j:5.26.4-community

# 4.3 PostgreSQL with pgvector 依赖镜像（DDN 镜像加速 north-4 → 项目仓库 south-1）
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/pgvector/pgvector:pg16
sudo docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/pgvector/pgvector:pg16 \
            swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/pgvector/pgvector:pg16
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/pgvector/pgvector:pg16
```

## 5. MemPalace 记忆宫殿服务（`release/images/mempalace/`）

> MemPalace 有两个镜像：MCP/CLI 基础镜像（`haisi-mempalace:v1.0.0`，也是 MCP 部署用镜像）与 REST 适配器（`haisi-mempalace:rest-v1.0.0`，把 MCP 工具封装成 REST HTTP 端点）。
> **构建依赖**：REST 的 `Dockerfile.rest` 内 `FROM haisi-mempalace:latest`，需先构建 MCP base 镜像并 retag 为 `:latest` 供 `FROM` 找到。

进入目录：

```bash
cd /data/haisi/haisi_rag_models_server
```

### 5.1 构建 MCP/CLI 基础镜像

```bash
# 1. 构建 MCP/CLI 基础镜像（也是第 5.4 节 MCP 部署用的镜像）
docker build -t haisi-mempalace:v1.0.0 -f open_source/mempalace/Dockerfile open_source/mempalace

# 2. retag 为 :latest，供 Dockerfile.rest 的 FROM 找到
sudo docker tag haisi-mempalace:v1.0.0 haisi-mempalace:latest
```

### 5.2 构建 REST 适配器

```bash
# 3. 构建 REST 适配器
docker build -t haisi-mempalace:rest-v1.0.0 -f open_source/mempalace/Dockerfile.rest open_source/mempalace
```

> 或一键脚本 `bash release/images/mempalace/build.sh` 自动完成两次 CPU 构建（产物为 `:latest` 与 `:rest`），构建后 retag 为 `v1.0.0`：
>
> ```bash
> bash release/images/mempalace/build.sh
> sudo docker tag haisi-mempalace:latest haisi-mempalace:v1.0.0
> sudo docker tag haisi-mempalace:rest haisi-mempalace:rest-v1.0.0
> ```

说明：REST 适配器把 MCP 工具封装成 REST HTTP 端点（`/store`、`/search` 等），**不是 MCP 协议**。如需给 IDE 提供 MCP 协议接入，用第 5.4 节的 `haisi-mempalace:v1.0.0` 镜像。

### 5.3 推送镜像

```bash
# MCP/CLI 基础镜像
sudo docker tag haisi-mempalace:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-mempalace:v1.0.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-mempalace:v1.0.0

# REST 适配器（CPU）
sudo docker tag haisi-mempalace:rest-v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-mempalace:rest-v1.0.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-mempalace:rest-v1.0.0
```

### 5.4 MCP 云端运行（HTTP transport）

> MemPalace 的 MCP server 镜像单独部署，用于给代码 IDE（Claude Code、Codex 等）提供 MCP 协议接入。
> 入口 `mempalace-mcp`，支持 stdio 与 http 两种 transport。云端对外提供 MCP 必须用 `http` transport（stdio 仅限 IDE 本地 spawn）。

```bash
# 生成 Bearer token（云端执行一次，记录此 token）
TOKEN=$(openssl rand -hex 32)
echo "MCP_TOKEN=$TOKEN"

docker run -d --name mempalace-mcp \
  -p 8765:8765 \
  -v mempalace-mcp-data:/data \
  -e MEMPALACE_MCP_HTTP_TOKEN="$TOKEN" \
  --restart unless-stopped \
  haisi-mempalace:v1.0.0 \
  mcp --transport=http --host 0.0.0.0 --port 8765
```

关键参数说明（来自 `mempalace/mempalace/mcp_server.py` 第 198-212 行 + 第 4630、4772 行）：

- `--transport=http`：切到 HTTP MCP（默认是 stdio，云端不能用）
- `--host 0.0.0.0`：绑非 loopback 时**强制要求** `MEMPALACE_MCP_HTTP_TOKEN`，否则拒绝启动（防裸暴露）
- `--port 8765`：HTTP MCP 监听端口，路径 `/mcp`
- `MEMPALACE_MCP_HTTP_TOKEN`：Bearer token 鉴权

### 5.5 IDE 接入示例

Claude Code：

```bash
claude mcp add mempalace \
  --transport http \
  --url http://<云IP>:8765/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

Codex（`~/.codex/config.toml`）：

```toml
[mcp_servers.mempalace]
transport = "http"
url = "http://<云IP>:8765/mcp"
headers = { Authorization = "Bearer <TOKEN>" }
```

## 6-7. ~~CodeGraph~~ / ~~Codebase-Memory-MCP~~（已合并至 Dual-MCP）

> 2026-10 清理：`codebase-memory-mcp` + `codegraph` 两项目源码已合并进 `release/images/dual-mcp/` 镜像（CBM + codegraph 双引擎），不再单独构建 `haisi-codegraph` / `haisi-cbm` 镜像。构建命令见第 10 节 Dual-MCP。

## 8. Redis 缓存服务（`release/images/redis/`）

进入目录：

```bash
cd release/images/redis
```

构建：

```bash
docker build -t haisi-redis:v1.0.0 .
```

说明：基于 Redis 7 Alpine，附加 `redis.conf` 配置与密码鉴权，端口 16379→6379。

推送：

```bash
sudo docker tag haisi-redis:7-alpine swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-redis:7-alpine
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-redis:7-alpine
```

> 注：构建产物为 `:v1.0.0`，推送时按发布约定使用 `:7-alpine` tag，按需 retag 后再推。

## 9. PostgreSQL 数据库（`release/images/postgresql/`）

进入目录：

```bash
cd release/images/postgresql
```

构建：

```bash
docker build -t haisi-postgresql:v1.0.0 .
```

说明：基于 SCL PostgreSQL 15 c9s 镜像，附加时区（Asia/Shanghai）与健康检查，端口 16602→5432。

推送：

```bash
sudo docker tag haisi-postgresql:15-c9s swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-postgresql:15-c9s
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-postgresql:15-c9s
```

> 注：构建产物为 `:v1.0.0`，推送时按发布约定使用 `:15-c9s` tag，按需 retag 后再推。

## 10. Dual-MCP 代码知识图谱服务（`release/images/dual-mcp/`）

构建：见 `release/images/dual-mcp/` 子目录文档（`dual-mcp/docker-shell.md` §3）。自包含构建，CBM C 源码 + codegraph TypeScript 源码已 vendored 在目录内，构建时无需 git clone 外部仓库。

```bash
cd haisi_rag_models_server/release/images/dual-mcp
sudo docker buildx build --provenance=false --output type=docker -t haisi-dual-mcp:v1.5.2 .
```

> ⚠️ 构建时须加 `--provenance=false --output type=docker`，否则 BuildKit 生成 OCI manifest，推送 SWR 报 `Invalid image, fail to parse 'manifest.json'`。

本地镜像：`haisi-dual-mcp:v1.5.2`

推送：

```bash
sudo docker tag haisi-dual-mcp:v1.5.2 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-dual-mcp:v1.5.2
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-dual-mcp:v1.5.2
```

## 11. RAG 评测平台（`rag_eval_platform/`）

> ⚠️ **源码位置**：本项目源码不在 `haisi_rag_models_server` 仓库内，而是位于其同级目录 `rag_eval_platform/`。本节构建上下文为 `rag_eval_platform/` 项目根目录，Dockerfile 在 `rag_eval_platform/docker/Dockerfile`。
>
> 镜像特点：多阶段构建（`node:20-alpine` 编前端 + `python:3.11-slim` 跑后端），前端构建产物内置于 `/app/app/static`。SQLite + 单 volume `/app/data`（数据库 / 日志 / 报告），CPU 即可运行，端口 8000。部署详情见 [评测平台部署文档](./eval/评测平台部署文档.md)。

进入目录：

```bash
cd /data/haisi/rag_eval_platform
```

构建：

```bash
sudo docker buildx build --provenance=false --output type=docker \
  -f docker/Dockerfile \
  -t haisi-eval-platform:v1.0.0 .
```

> ⚠️ 构建时须加 `--provenance=false --output type=docker`，否则 BuildKit 生成 OCI manifest，推送 SWR 报 `Invalid image, fail to parse 'manifest.json'`。

产物：`haisi-eval-platform:v1.0.0`（约 500MB，前端构建产物约 20MB + Python 运行时 + 依赖）

推送：

```bash
sudo docker tag haisi-eval-platform:v1.0.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-eval-platform:v1.0.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-eval-platform:v1.0.0
```

说明：

- 源码仓库自带 `docker-compose.yml` + `.env.docker` + `scripts/deploy.sh`，本地 / 单机部署可直接在 `rag_eval_platform/` 目录执行 `./scripts/deploy.sh` 一键构建 + 启动 + 健康检查（详见部署文档「方式一」）。
- 目标服务器从 SWR 拉镜像运行（无源码场景）见部署文档「方式二：纯 `docker run`」。
- 远端 RAG 知识库地址、登录凭证、知识库列表、裁判模型等运行时配置不打包进镜像，启动后通过浏览器在「系统配置」页面填写。
- 容器入口 `docker-entrypoint.sh` 会自动建表 + 跑迁移脚本 + 种子配置，重启幂等。
- 数据持久化目录 `/app/data`（SQLite + logs + reports），需挂载到宿主机 volume；SQLite WAL 模式，运行期间禁止外部进程读写 `.db-wal` 文件。

## 一键推送所有镜像

> 适用于已构建全部本地镜像后，批量 tag + push 的场景。

```bash
$registry = "swr.cn-south-1.myhuaweicloud.com/hisilicon-rag"
$images = @(
    # 以下两个镜像运行在 GPU 服务器上，暂不上华为云，需要时取消注释即可
    # "haisi-embedding:v1.0.0",
    # "coreb-reranker:v1.0.0",
    "haisi-lac:1.0",
    "haisi-lac:v1.0.0",
    "haisi-memmachine:cpu",
    "haisi-mempalace:v1.0.0",
    "haisi-mempalace:rest",
    "haisi-redis:7-alpine",
    "haisi-postgresql:15-c9s",
    "haisi-dual-mcp:v1.0.0",
    "haisi-eval-platform:v1.0.0"
)
foreach ($img in $images) {
    sudo docker tag $img "$registry/$img"
    sudo docker push "$registry/$img"
}

# Neo4j 依赖镜像（从 DDN 镜像加速仓库转推，名称含路径，单独处理）
$ddnNeo4j = "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/neo4j:5.26.4-community"
docker pull $ddnNeo4j
sudo docker tag  $ddnNeo4j "$registry/neo4j:5.26.4-community"
sudo docker push            "$registry/neo4j:5.26.4-community"

# PostgreSQL with pgvector 依赖镜像（同上，DDN 转推）
$ddnPgvector = "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/pgvector/pgvector:pg16"
docker pull $ddnPgvector
sudo docker tag  $ddnPgvector "$registry/pgvector/pgvector:pg16"
sudo docker push              "$registry/pgvector/pgvector:pg16"
```

## 说明

1. **构建在前**：以上命令假设在项目根 `haisi_rag_models_server/` 下执行（子目录服务需先 `cd` 到对应目录）。构建完成后按各节推送命令推到华为云 SWR。
2. **架构限定**：目标服务器为华为云 x86（amd64）CPU 节点，本文档仅构建与推送 CPU 版本镜像。
3. **GPU 跳过**：`haisi-memmachine:gpu`、`haisi-mempalace:rest-bgem3`（依赖 CUDA）不在本文档范围。如后续部署到 GPU 节点，参考各子目录部署文档。
4. **版本 tag**：初始版本统一用 `v1.0.0`。如需发布正式版本，可替换为语义化版本号（如 `v1.1.0`、`v2.0.0`）。部分服务推送时使用发布约定 tag（如 Redis `:7-alpine`、PostgreSQL `:15-c9s`），构建产物为 `:v1.0.0` 时需 retag 后再推送。
5. **构建缓存**：首次构建较慢（拉基础镜像 + 编译依赖），后续构建命中 Docker layer cache 会快很多。如遇网络问题，可配置 Docker 镜像加速器（华为云镜像站 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/`）。
6. **Embedding / Reranker**：这两个服务运行在 GPU 服务器上，暂不上华为云，本文档不包含其构建命令。需要时参考 `release/images/embedding/` 与 `release/images/reranker/` 各自的部署文档。
7. **拉取镜像**：目标服务器执行 `docker pull swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/<镜像名称>:<版本名称>` 即可。