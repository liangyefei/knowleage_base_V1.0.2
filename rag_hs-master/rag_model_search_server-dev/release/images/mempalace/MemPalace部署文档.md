# MemPalace REST 记忆服务部署文档

## 📋 目录

* 概述
* 环境要求
* 部署形态选型
* 项目结构
* 源码与本地改动
* 文件内容
* 部署步骤
* 鉴权模式
* 接口速查
* 测试指南
* 日常运维
* 故障排查
* 与主项目集成
* 版本历史
* 相关文档

***

## 概述

MemPalace 是一个本地优先（local-first）的 AI 记忆库，按「宫殿（palace）→ 翼（wing）→ 房间（room）→ 抽屉（drawer）」分层组织记忆条目，内置 AAAK 索引层、时序知识图谱（KG）与可插拔存储后端，原生提供 MCP 工具集。

本服务把 MemPalace 的 MCP 工具（存 / 查 / 建宫 / 知识图谱）封装为 HTTP 接口，基于 **FastAPI + uvicorn** 实现 REST 适配器（`examples/rest_adapter.py`），作为 HaiSI RAG 的记忆层供主项目通过 HTTP 调用：存原文片段 → 语义检索取回 → 时序事实查询。

**典型应用场景**：

* RAG 流水线中「原文片段存档 + 语义检索取回」
* 多知识库隔离（一个宫殿 = 一个知识库）
* 时序知识图谱查询（谁在什么时候说了什么）

**与主项目的关系**：

主项目 `haisi_rag_models_server` 通过 HTTP 调用本服务：`/store` 存原文 → `/search` 取片段 → `/kg` 取时序事实。无需在主项目镜像里安装 mempalace，只需一个 HTTP 客户端。

| **项**         | **说明**                              |
| -------------- | ------------------------------------- |
| 服务名称       | mempalace-rest                        |
| 服务端口       | 8777                                  |
| 镜像（CPU）    | `haisi-mempalace:rest`                      |
| 镜像（GPU）    | `haisi-mempalace:rest-bgem3`                |
| 容器名         | `mempalace-rest`                      |
| 数据卷         | `mempalace-data`（挂载到 `/data`）    |
| 框架           | FastAPI + uvicorn                     |
| 默认嵌入模型   | `minilm`（384d，英文）                |
| 可选嵌入模型   | `embeddinggemma`（384d，多语言）/ `bgem3`（1024d，CJK，需 GPU） |
| 调用方式       | HTTP（`GET /healthz`、`POST /store` 等） |

> **当前部署位置**：本服务部署在 `192.168.80.250:8777`（Windows 宿主机 + WSL2 形态），可直接调用：
>
> ```bash
> # 健康检查（返回 ok）
> curl http://192.168.80.250:8777/healthz
>
> # 建宫
> curl -X POST http://192.168.80.250:8777/palace \
>     -H "Content-Type: application/json" \
>     -d '{"palace": "kb-docs"}'
>
> # 存记忆
> curl -X POST http://192.168.80.250:8777/store \
>     -H "Content-Type: application/json" \
>     -d '{"palace": "kb-docs", "text": "海思半导体研发新一代芯片"}'
>
> # 语义检索
> curl "http://192.168.80.250:8777/search?palace=kb-docs&q=芯片研发"
> ```

***

## 环境要求

### 硬件要求

| **形态** | **CPU** | **内存** | **GPU** | **磁盘** |
| -------- | ------- | -------- | ------- | -------- |
| CPU（minilm / embeddinggemma） | x86_64，4 核起 | ≥ 4GB | 不需要 | ≥ 1.6GB（镜像 + 缓存） |
| GPU（bgem3，CJK 最强） | x86_64，4 核起 | ≥ 8GB | NVIDIA + ≥ 8GB 显存 | ≥ 8.75GB（含 BGE-M3 模型 2.3GB + cu128 torch wheel 782MB + sentence-transformers） |

> CPU 形态默认用 `minilm`（384d，英文）。中文场景若停留在 CPU，建议切 `embeddinggemma`（384d，多语言）；中文最强召回需切 `bgem3`（1024d），但 `bgem3` 必须跑在 GPU 上（见 `docker-compose.bgem3.yml`）。

> ⚠️ **Blackwell 架构 GPU（如 RTX 5090，compute capability `sm_120`）必须使用 cu128 版本的 torch wheel**。cu121/cu118 等其他版本不含 `sm_120` 算子，GPU 算子无法加载。`./build.sh --gpu` 会校验 `open_source/mempalace/wheels/` 下是否存在 cu128 wheel（782MB），缺失时打印下载命令并退出。

### 软件要求

| **组件**   | **版本要求**          | **说明**                                   |
| ---------- | --------------------- | ------------------------------------------ |
| 操作系统   | Linux（Ubuntu 20.04+ 推荐）或 Windows + WSL2 | 镜像基于 `python:3.12-slim` |
| Docker     | ≥ 20.10               | 支持 BuildKit（Dockerfile 用了 `--mount`） |
| Compose    | v2（`docker compose` 子命令） | 已废弃 `docker-compose` v1                |
| GPU 驱动   | NVIDIA 驱动 + NVIDIA Container Toolkit | 仅 GPU 形态需要；`nvidia-smi` 在测试容器内可见 |

GPU 形态还需在宿主机上验证驱动与 Container Toolkit：

```bash
nvidia-smi                                  # 宿主机 GPU 驱动正常
docker run --rm --gpus all nvidia/cuda:12.8-base nvidia-smi   # Container Toolkit 正常
```

两个命令都正常输出才可用 GPU 形态。

### 磁盘空间预估

| **形态** | **镜像大小** | **运行时缓存** | **合计** |
| -------- | ------------ | -------------- | -------- |
| CPU（minilm） | ~1.6GB | minilm 嵌入模型首次下载约 90MB（落 `/data`） | ~1.7GB |
| CPU（embeddinggemma） | ~1.6GB | embeddinggemma 约 300MB | ~1.9GB |
| GPU（bgem3） | ~3.0GB | BGE-M3 2.3GB + cu128 torch wheel 782MB + sentence-transformers | ~8.75GB |

***

## 部署形态选型

| **形态** | **镜像** | **嵌入模型** | **维度** | **GPU** | **适用场景** |
| -------- | -------- | ------------ | -------- | ------- | ------------ |
| ① CPU 轻量（默认） | `haisi-mempalace:rest` | `minilm` | 384 | 否 | 英文场景、资源紧张、快速试用 |
| ② CPU 多语言 | `haisi-mempalace:rest` | `embeddinggemma` | 384 | 否 | 中文/多语言、无 GPU |
| ③ GPU 中文最强 | `haisi-mempalace:rest-bgem3` | `bgem3`（BGE-M3） | 1024 | 是（强烈建议） | 中文/多语言召回最强、有 NVIDIA GPU |
| ④ 外部后端（可选） | `haisi-mempalace:rest` + 额外配置 | 同上 | 同上 | 视情况 | 超大规模、需 PostgreSQL 生态 |

**选型建议**：

- 中文场景首选 **③ bgem3 + GPU**（召回质量明显优于 384d 模型）。
- 无 GPU 时选 **② embeddinggemma**（多语言 384d，CPU 可跑）作为最低可接受配置。
- 纯英文 / 快速验证选 **① minilm**（最轻量，~80 MB）。
- 形态 ④ 需把后端切到 `pgvector` / `qdrant`（见 `pyproject.toml` 中 `mempalace.backends` 入口），超大规模（百万级 drawer 以上）才考虑，本文档不展开。

> ⚠️ **形态之间嵌入维度不兼容**：`minilm`/`embeddinggemma`（384d）↔ `bgem3`（1024d）切换必须 `mempalace repair rebuild-index` 全量重嵌入，或直接新建宫殿。混用会导致 ChromaDB 报维度错误、`/search` 召回为空（见 [故障排查 Q3](#q3切换嵌入模型后检索失效报维度不匹配)）。

***

## 项目结构

```
release/images/mempalace/
├── MemPalace部署文档.md          # 本文档（服务级部署文档，两次构建 + 运维 + Q&A）
├── docker-compose.yml           # CPU 形态 compose 编排（默认 minilm，可信内网免鉴权）
├── docker-compose.bgem3.yml     # GPU + BGE-M3 形态 compose 编排（需预构建镜像）
├── .env.example                 # 环境变量样例（Bearer Token 鉴权用）
├── build.sh                     # 构建脚本（--gpu 额外构建 bgem3 镜像，会校验 cu128 wheel）
├── start.sh                     # 启动脚本（--gpu 切到 bgem3 compose）
├── stop.sh                      # 停止脚本（默认不删数据卷，提示 -v 风险）
├── firewall.ps1                 # Windows 宿主机 WSL2 端口转发 + 防火墙（add/show/remove）
├── firewall.sh                  # 纯 Linux 形态防火墙放行（ufw / firewalld 自动探测）
├── (见项目根 open_source/mempalace/)   # 上游源码（已移出本目录，至项目根 open_source/mempalace/）：pyproject.toml / uv.lock / mempalace/ 包 / examples/rest_adapter.py / Dockerfile / Dockerfile.rest 等，本地改动改那里
├── mempalace.md                 # 项目概览（架构 / 索引层 / 嵌入模型 / 基准测试）
└── mempalace-api.md             # REST 接口对接文档（端点详情 / Python MempalaceClient）
```

***

## 源码与本地改动

本部署包沿用上游**两次构建**方式：构建上下文是项目根的 `open_source/mempalace/` 子目录（即上游源码），先由 `open_source/mempalace/Dockerfile` 在 builder 阶段 `COPY . /app` 把整个本地源码树拷进镜像、`uv sync --frozen --no-dev --no-editable` 把 `mempalace` 包装入 venv 的 site-packages，产出 MCP/CLI 基础镜像 `haisi-mempalace:latest`；再由 `open_source/mempalace/Dockerfile.rest`（`FROM haisi-mempalace:latest`）叠加 FastAPI + uvicorn + `examples/rest_adapter.py`，产出 REST 适配器镜像 `haisi-mempalace:rest`。因此**用户对源码的本地改动会被直接打包进镜像**，无需 clone GitHub、无需 scp 上传。

### 源码位置

`open_source/mempalace/`（位于项目根）是完整的 MemPalace 源码树，关键文件：

| **路径** | **用途** |
| -------- | -------- |
| `open_source/mempalace/pyproject.toml` | 项目元信息（v3.5.0、Python ≥ 3.9、extras、backends 入口） |
| `open_source/mempalace/uv.lock` | 冻结依赖锁文件（`uv sync --frozen` 严格按此安装） |
| `open_source/mempalace/mempalace/` | Python 包源码（`embedding.py`、`palace.py`、`searcher.py`、`service.py` 等） |
| `open_source/mempalace/examples/rest_adapter.py` | REST 适配器实现（FastAPI + uvicorn，本服务的入口） |
| `open_source/mempalace/Dockerfile` | MCP/CLI 基础镜像构建文件（builder 跑 `uv sync` 解析 lockfile，runtime 拷贝 venv；产出 `haisi-mempalace:latest`） |
| `open_source/mempalace/Dockerfile.rest` | REST 适配器镜像构建文件（`FROM haisi-mempalace:latest`，叠 FastAPI + uvicorn + `rest_adapter.py`；产出 `haisi-mempalace:rest`） |
| `open_source/mempalace/Dockerfile.rest.bgem3` | GPU bgem3 变体（`FROM haisi-mempalace:rest`，叠 cu128 torch + sentence-transformers） |
| `open_source/mempalace/wheels/` | cu128 torch wheel 存放目录（GPU 形态用，需手动下载 782MB） |

### 改源码后重新打包

**用户对源码有本地改动时**，直接改 `open_source/mempalace/` 下的文件，然后重跑构建脚本即可把改动打包进新镜像：

```bash
# 1. 改源码（示例：编辑 rest_adapter.py 或 mempalace/ 包内任意文件）
vim open_source/mempalace/examples/rest_adapter.py

# 2. 重新构建 + 启动（CPU 形态）
./build.sh && ./start.sh

# 或 GPU 形态
./build.sh --gpu && ./start.sh --gpu
```

无需 `git clone`、无需 `scp` 上传——`open_source/mempalace/` 就是构建上下文，`docker build` 直接读这里的文件。

### 新增依赖时更新锁文件

若改动涉及**新增 Python 依赖**（在 `pyproject.toml` 加了包），必须先在 `open_source/mempalace/` 下重新生成 `uv.lock`，否则 `uv sync --frozen` 会因锁文件与 `pyproject.toml` 不一致而失败：

```bash
cd open_source/mempalace
uv lock                # 按 pyproject.toml 重新解析并写回 uv.lock
cd ../..
./build.sh && ./start.sh
```

> ⚠️ `open_source/mempalace/Dockerfile` 用 `uv sync --frozen`（非 `uv sync`），它**禁止**改动锁文件——锁文件与 `pyproject.toml` 不一致时构建直接失败。这是为了可复现构建。所以本地加依赖后务必先 `uv lock` 再 `./build.sh`。

### 构建链路一览

| **步骤** | **命令** | **产物** |
| -------- | -------- | -------- |
| 构建 MCP/CLI 基础镜像 | `./build.sh`（第一次） | `haisi-mempalace:latest`（含本地源码改动） |
| 构建 REST 适配器镜像 | `./build.sh`（第二次，自动续跑） | `haisi-mempalace:rest`（`FROM haisi-mempalace:latest`，叠 FastAPI + `rest_adapter.py`） |
| 构建 GPU 靶像 | `./build.sh --gpu` | 在 `haisi-mempalace:rest` 上叠 `Dockerfile.rest.bgem3` → `haisi-mempalace:rest-bgem3` |
| 启动 CPU | `./start.sh` | `docker compose -f docker-compose.yml up -d`（compose 基于 `Dockerfile.rest` 构建 `haisi-mempalace:rest`，依赖 `haisi-mempalace:latest` 已存在） |
| 启动 GPU | `./start.sh --gpu` | `docker compose -f docker-compose.bgem3.yml up -d` |
| 停止 | `./stop.sh` / `./stop.sh --gpu` | `docker compose down`（保留数据卷） |

***

## 文件内容

### 1. `open_source/mempalace/Dockerfile` + `open_source/mempalace/Dockerfile.rest`（两次构建）

沿用上游两次构建：先 `open_source/mempalace/Dockerfile` 产出 MCP/CLI 基础镜像 `haisi-mempalace:latest`（builder 跑 `uv sync` 解析 lockfile，runtime 拷贝 venv、配非 root 用户 `mempalace` uid/gid 1000、HOME 指向 `/data`、入口 `docker-entrypoint.sh`、`CMD mcp`）；再 `open_source/mempalace/Dockerfile.rest`（`FROM haisi-mempalace:latest`）叠加 FastAPI + uvicorn + `examples/rest_adapter.py` 产出 REST 适配器镜像 `haisi-mempalace:rest`（`ensurepip` + `pip install fastapi uvicorn`，ENTRYPOINT 改写为 `python /app/rest_adapter.py`，覆盖基础镜像的 `docker-entrypoint.sh`）。基础镜像保留 `pip`，以便 bgem3 变体叠加 cu128 torch wheel。

本地改动**仅做国内镜像适配**，不改动构建逻辑：

- `# syntax=docker/dockerfile:1.7` → `# syntax=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/docker/dockerfile:1.7`（华为云拉 BuildKit frontend；本目录 `.dockerignore` 用 `examples/` + `!examples/rest_adapter.py` 例外重新纳入，需 `dockerfile:1.7` frontend 支持，内置 frontend 不处理从已排除目录的重新纳入，会报 `examples/rest_adapter.py: not found`）
- `FROM python:3.12-slim` → `FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim`（华为云镜像）
- `COPY --from=ghcr.io/astral-sh/uv:0.5` → `COPY --from=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/ghcr.io/astral-sh/uv:0.5.11`（华为云镜像 + 锁定 0.5.11）
- `Dockerfile` 在 `apt-get update` 前插阿里云 Debian 镜像源 sed 替换（兼容 `debian.sources` 与 `sources.list` 两种格式，本机访问 `deb.debian.org` 严重限速）

**`open_source/mempalace/Dockerfile`**（产出 `haisi-mempalace:latest`）：

```dockerfile
# syntax=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/docker/dockerfile:1.7

# MemPalace — CPU image.
#
# Multi-stage build using uv (the project ships a uv.lock, so we install from
# the frozen lockfile for reproducible images). The default runtime is the MCP
# server over stdio; the CLI is reachable through the same entrypoint.
#
# Build（在 open_source/mempalace/ 目录下执行，context 用 .）:
#   docker build -t mempalace .
#   docker build -t mempalace --build-arg EXTRAS="extract,spellcheck" .
#
# Run (MCP server over stdio, palace persisted on the host):
#   docker run -i --rm -v mempalace-data:/data mempalace
#
# Run (CLI):
#   docker run --rm -v mempalace-data:/data mempalace search "why GraphQL"
#
# GPU acceleration lives in Dockerfile.gpu (it needs a CUDA base image).

ARG PYTHON_VERSION=3.12

# --- builder ----------------------------------------------------------------
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim AS builder

# uv: fast, lockfile-driven installer. Pinned by digest-less tag for clarity;
# bump deliberately.
COPY --from=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/ghcr.io/astral-sh/uv:0.5.11 /uv /uvx /bin/

# Some transitive deps (grpcio, onnxruntime, tokenizers) ship manylinux wheels
# for cp312, but keep a compiler around so a missing wheel degrades to a source
# build instead of failing the image. Dropped from the final stage.
# 换国内 Debian 镜像源（阿里云），本机访问 deb.debian.org 被严重限速。
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true && \
    sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true && \
    apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app

# Optional extras baked into the image. CPU-safe by default; GPU is a separate
# Dockerfile. Pass a comma-separated list, e.g. EXTRAS="extract,spellcheck".
ARG EXTRAS="extract,spellcheck"

# Layer 1: dependencies only (no project) — cached across source changes.
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=bind,source=pyproject.toml,target=pyproject.toml \
    --mount=type=bind,source=uv.lock,target=uv.lock \
    --mount=type=bind,source=README.md,target=README.md \
    set -e; \
    flags=""; \
    for e in $(echo "${EXTRAS}" | tr ',' ' '); do flags="${flags} --extra ${e}"; done; \
    uv sync --frozen --no-install-project --no-dev ${flags}

# Layer 2: the project itself. --no-editable installs mempalace into the venv's
# site-packages (instead of an .pth pointing at /app), so the runtime stage can
# copy only /app/.venv and drop the source tree.
COPY . /app
RUN --mount=type=cache,target=/root/.cache/uv \
    set -e; \
    flags=""; \
    for e in $(echo "${EXTRAS}" | tr ',' ' '); do flags="${flags} --extra ${e}"; done; \
    uv sync --frozen --no-dev --no-editable ${flags}

# --- runtime ----------------------------------------------------------------
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim AS runtime

LABEL org.opencontainers.image.title="MemPalace" \
      org.opencontainers.image.description="Local-first AI memory — verbatim storage, MCP server + CLI." \
      org.opencontainers.image.source="https://github.com/MemPalace/mempalace" \
      org.opencontainers.image.licenses="MIT"

# /data is the single persistence root: HOME points here, so the palace
# (~/.mempalace/palace), config (~/.mempalace), and the embedding-model cache
# all land under one mountable volume. The default `minilm` model caches under
# ~/.cache/chroma (~80 MB, from ChromaDB's S3); the optional `embeddinggemma`
# model caches under ~/.cache/huggingface (~300 MB). Both lazy-download on
# first use.
ENV HOME=/data \
    PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# Non-root user owning the data volume.
RUN groupadd --gid 1000 mempalace \
    && useradd --uid 1000 --gid 1000 --home-dir /data --create-home mempalace

WORKDIR /app

# The resolved virtualenv from the builder — no build toolchain in this layer.
COPY --from=builder --chown=mempalace:mempalace /app/.venv /app/.venv
COPY --chown=mempalace:mempalace docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER mempalace
VOLUME ["/data"]

# Default to the MCP server; `docker run` it with `-i` for stdio JSON-RPC.
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["mcp"]
```

**`open_source/mempalace/Dockerfile.rest`**（产出 `haisi-mempalace:rest`）：

```dockerfile
# syntax=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/docker/dockerfile:1.7
#
# MemPalace REST adapter image.
#
# Layers FastAPI + the REST adapter (examples/rest_adapter.py) on top of the
# stock CPU image (Dockerfile). FastAPI/uvicorn are not core MemPalace deps
# (the project ships only stdlib HTTP), so they are added as a thin layer here
# rather than baked into the base image.
#
# Build (requires the base image to exist first; 在 open_source/mempalace/ 目录下执行，context 用 .):
#   docker build -t mempalace           -f Dockerfile      .   # base, if missing
#   docker build -t haisi-mempalace:rest      -f Dockerfile.rest .
#
# Run (loopback; put Caddy/nginx in front for TLS before exposing):
#   docker run -d -p 127.0.0.1:8777:8777 \
#     -e MEMPALACE_REST_TOKEN="$TOKEN" -v mempalace-data:/data haisi-mempalace:rest
#
# Expose to a network (operator's call — see examples/rest_adapter.py docstring):
#   -e MEMPALACE_REST_HOST=0.0.0.0 -e MEMPALACE_REST_TOKEN="$TOKEN"  (token REQUIRED)

FROM haisi-mempalace:latest

# The base image's venv was created by uv, which ships no pip. ensurepip
# bootstraps pip into the venv so we can add FastAPI the standard way (uv itself
# isn't in the runtime image — it lives in the builder stage only). Runs as root
# (the base image's final USER is mempalace) so we can write into /app/.venv;
# we drop back to mempalace at the end.
USER root
RUN /app/.venv/bin/python -m ensurepip --upgrade \
    && /app/.venv/bin/python -m pip install --no-cache-dir fastapi "uvicorn[standard]"

# The adapter lives under examples/, which .dockerignore excludes wholesale;
# the !examples/rest_adapter.py exception above re-admits just this file.
COPY --chown=mempalace:mempalace examples/rest_adapter.py /app/rest_adapter.py

USER mempalace

# Loopback + a non-empty token is the safe default. The adapter is fail-closed:
# it REFUSES TO START on a non-loopback bind without MEMPALACE_REST_TOKEN.
ENV MEMPALACE_REST_HOST=127.0.0.1 \
    MEMPALACE_REST_PORT=8777 \
    PYTHONUNBUFFERED=1

EXPOSE 8777

# Override the base image's docker-entrypoint.sh — the REST adapter is its own
# long-running process, not a mempalace subcommand.
ENTRYPOINT ["python", "/app/rest_adapter.py"]
```

### 2. `docker-compose.yml`

CPU 形态 compose 编排。构建上下文指向上游 `open_source/mempalace` 子目录（让 `.dockerignore` 与 `!examples/rest_adapter.py` 例外生效），Dockerfile 指向 `open_source/mempalace/Dockerfile.rest`（`FROM haisi-mempalace:latest`，依赖 `./build.sh` 先产出 `haisi-mempalace:latest`；若 compose up 时基础镜像缺失会构建失败）。默认开启 `MEMPALACE_REST_NO_AUTH=1`（可信内网免鉴权），数据卷 `mempalace-data` 挂载到 `/data`。

```yaml
# MemPalace REST adapter — CPU form (compose).
#
# Builds + runs the self-contained Dockerfile in this directory, producing the
# `haisi-mempalace:rest` image (CPU, FastAPI + uvicorn, port 8777, /data persisted).
#
# Auth posture:
#   CPU form defaults to trusted-LAN no-auth (MEMPALACE_REST_NO_AUTH=1). The
#   adapter is fail-closed otherwise — a non-loopback bind without a token and
#   without NO_AUTH refuses to start. To expose on a public/half-trusted net,
#   generate a token (openssl rand -hex 32), put it in .env (see .env.example),
#   delete the MEMPALACE_REST_NO_AUTH line below, and uncomment the TOKEN line.
#
# Embedding model:
#   Default `minilm` (384d, English). For Chinese/multilingual CPU, switch to
#   `embeddinggemma` (384d) by uncommenting MEMPALACE_EMBEDDING_MODEL. For
#   `bgem3` (1024d, best for CJK) you need GPU — see docker-compose.bgem3.yml
#   and `./build.sh --gpu` (requires NVIDIA Container Toolkit + the cu128
#   torch wheel, ~2.3GB BGE-M3 model at /disk/models/bge-m3).
#
# Usage:
#   docker compose up -d                          # build + start (CPU)
#   docker compose logs -f mempalace-rest
#   docker compose down                           # stop (keeps the volume)
#   docker compose down -v                        # stop + WIPE palace data
#
# Build context is the upstream open_source/mempalace subdir (so .dockerignore + the
# re-admitted examples/rest_adapter.py apply). The Dockerfile.rest lives
# inside open_source/mempalace/ and layers on haisi-mempalace:latest (built first by ./build.sh).

services:
  mempalace-rest:
    build:
      context: ../../../open_source/mempalace
      dockerfile: Dockerfile.rest
    image: haisi-mempalace:rest
    container_name: mempalace-rest
    restart: unless-stopped
    ports:
      - "8777:8777"
    volumes:
      - mempalace-data:/data
    environment:
      MEMPALACE_REST_HOST: "0.0.0.0"
      # 可信内网免鉴权（默认）；公网/半信任网络改用 token（见下行注释与 .env.example）
      MEMPALACE_REST_NO_AUTH: "1"
      # MEMPALACE_REST_TOKEN: "${MEMPALACE_REST_TOKEN}"
      # 嵌入模型覆盖（默认 minilm 英文 384d；中文推荐 embeddinggemma 384d 多语言 或 bgem3 1024d 需 GPU）
      # MEMPALACE_EMBEDDING_MODEL: "embeddinggemma"

volumes:
  mempalace-data:
```

### 3. `docker-compose.bgem3.yml`

GPU + BGE-M3 形态 compose 编排。使用预构建的 `haisi-mempalace:rest-bgem3` 镜像（由 `./build.sh --gpu` 产出），把宿主机 `/disk/models/bge-m3`（~2.3GB 模型文件）只读挂载到 `/models/bge-m3`，并声明 NVIDIA GPU 透传。无 build 段——镜像必须先构建好。

```yaml
# MemPalace REST adapter — GPU + BGE-M3 form (compose).
#
# Runs the `haisi-mempalace:rest-bgem3` image (the upstream `open_source/mempalace/Dockerfile.rest.bgem3`
# layered on top of the `haisi-mempalace:rest` image built by this directory's
# Dockerfile). BGE-M3 (1024d, strong on CJK) runs on CUDA via a cu128 torch
# wheel (Blackwell sm_120 kernels). No build stanza here — the image must be
# pre-built by `./build.sh --gpu`.
#
# Prereqs (all required):
#   1. ./build.sh --gpu          # builds haisi-mempalace:rest (this Dockerfile) then
#                                 # layers Dockerfile.rest.bgem3 → haisi-mempalace:rest-bgem3
#                                 # (also requires the manually-downloaded cu128
#                                 # torch wheel in open_source/mempalace/wheels/, ~782MB)
#   2. NVIDIA Container Toolkit  # on the host, so docker can pass --gpus all
#                                 # (verify: nvidia-smi inside a test container)
#   3. /disk/models/bge-m3       # the BGE-M3 model files (~2.3GB) on the host,
#                                 # bind-mounted read-only at /models/bge-m3
#
# Usage:
#   docker compose -f docker-compose.bgem3.yml up -d
#   docker compose -f docker-compose.bgem3.yml logs -f mempalace-rest
#   docker compose -f docker-compose.bgem3.yml down
#
# Auth posture:
#   Mirrors docker-compose.yml: trusted-LAN no-auth by default. To require a
#   bearer token, set MEMPALACE_REST_TOKEN via .env and delete the NO_AUTH line.

services:
  mempalace-rest:
    image: haisi-mempalace:rest-bgem3
    container_name: mempalace-rest
    restart: unless-stopped
    ports:
      - "8777:8777"
    volumes:
      - mempalace-data:/data
      - /disk/models/bge-m3:/models/bge-m3:ro
    environment:
      MEMPALACE_REST_HOST: "0.0.0.0"
      MEMPALACE_REST_NO_AUTH: "1"
      MEMPALACE_EMBEDDING_MODEL: "bgem3"
      MEMPALACE_BGE_M3_PATH: "/models/bge-m3"
      MEMPALACE_EMBEDDING_DEVICE: "cuda"
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: ["gpu"]

volumes:
  mempalace-data:
```

### 4. `.env.example`

环境变量样例。用于保管 Bearer Token（公网/半信任网络必填）。复制为 `.env` 后填入实际值，`.env` 不要进 git。默认 compose 走 `MEMPALACE_REST_NO_AUTH=1` 免鉴权；启用 token 时需在 `docker-compose.yml` 删除 `NO_AUTH` 行并取消 `MEMPALACE_REST_TOKEN` 注释。

```ini
# 复制为 .env 后填入实际值（.env 不要进 git）
# 生成 token: openssl rand -hex 32

# Bearer Token 鉴权（公网/半信任网络必填；默认 compose 用 MEMPALACE_REST_NO_AUTH=1 免鉴权，启用 token 时在 docker-compose.yml 注释切换并删除 NO_AUTH 行）
MEMPALACE_REST_TOKEN=
```

### 5. `build.sh`

构建脚本（WSL2/Linux 下执行）。默认（无参数）两次 CPU 构建：先 `haisi-mempalace:latest`（MCP/CLI 基础镜像，`-f open_source/mempalace/Dockerfile`），再 `haisi-mempalace:rest`（REST 适配器，`-f open_source/mempalace/Dockerfile.rest`，`FROM haisi-mempalace:latest`）；`--gpu` 额外构建 GPU 镜像 `haisi-mempalace:rest-bgem3`（基于 `open_source/mempalace/Dockerfile.rest.bgem3`，其 `FROM haisi-mempalace:rest`）。`--gpu` 会校验 `open_source/mempalace/wheels/` 下是否有 cu128 torch wheel（782MB），缺失时打印下载命令并退出。

```bash
#!/usr/bin/env bash
set -euo pipefail

# MemPalace —— 构建脚本（WSL2/Linux 下执行）。沿用上游两次构建。
#
# 默认（无参数）两次 CPU 构建：
#   1) haisi-mempalace:latest（MCP/CLI 基础镜像）
#      docker build -t haisi-mempalace:latest -f open_source/mempalace/Dockerfile open_source/mempalace
#   2) haisi-mempalace:rest（REST 适配器，FROM haisi-mempalace:latest）
#      docker build -t haisi-mempalace:rest -f open_source/mempalace/Dockerfile.rest open_source/mempalace
#
# --gpu 额外构建 GPU 镜像：haisi-mempalace:rest-bgem3
#   docker build -t haisi-mempalace:rest-bgem3 -f open_source/mempalace/Dockerfile.rest.bgem3 open_source/mempalace
#   （Dockerfile.rest.bgem3 的 FROM haisi-mempalace:rest，故 --gpu 会先保证 CPU 镜像存在）
#   并要求 open_source/mempalace/wheels/ 下已下载 cu128 torch wheel（782MB，Blackwell sm_120 内核）。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 彩色输出辅助
info()    { printf '\033[0;36m[info]\033[0m  %s\n'    "$*"; }
success() { printf '\033[0;32m[ok]\033[0m    %s\n'    "$*"; }
error()   { printf '\033[0;31m[error]\033[0m %s\n' "$*" >&2; }

GPU=0
for arg in "$@"; do
  case "$arg" in
    --gpu) GPU=1 ;;
    -h|--help)
      cat <<EOF
Usage: $0 [--gpu]
  (无参数)  两次 CPU 构建: haisi-mempalace:latest + haisi-mempalace:rest
  --gpu     额外构建 GPU 镜像:  haisi-mempalace:rest-bgem3
            （需先在 open_source/mempalace/wheels/ 下载 cu128 torch wheel，782MB）
EOF
      exit 0
      ;;
    *) error "未知参数: $arg（见 --help）"; exit 2 ;;
  esac
done

info "构建 MCP/CLI 基础镜像 haisi-mempalace:latest ..."
docker build -t haisi-mempalace:latest -f "$SCRIPT_DIR/../../../open_source/mempalace/Dockerfile" "$SCRIPT_DIR/../../../open_source/mempalace"
success "MCP/CLI 基础镜像构建完成: haisi-mempalace:latest"

info "构建 REST 适配器镜像 haisi-mempalace:rest ..."
docker build -t haisi-mempalace:rest -f "$SCRIPT_DIR/../../../open_source/mempalace/Dockerfile.rest" "$SCRIPT_DIR/../../../open_source/mempalace"
success "REST 适配器镜像构建完成: haisi-mempalace:rest"

if [ "$GPU" -eq 1 ]; then
  info "检查 cu128 torch wheel ..."
  if ! ls "$SCRIPT_DIR/../../../open_source/mempalace/wheels/"*.whl >/dev/null 2>&1; then
    error "未找到 cu128 torch wheel。"
    cat >&2 <<'EOF'
请先在 open_source/mempalace/wheels/ 下下载（782MB）：
  cd open_source/mempalace/wheels
  wget -O torch-2.11.0+cu128-cp312-cp312-manylinux_2_28_x86_64.whl \
    "https://download-r2.pytorch.org/whl/cu128/torch-2.11.0%2Bcu128-cp312-cp312-manylinux_2_28_x86_64.whl"
然后重跑 ./build.sh --gpu
EOF
    exit 1
  fi
  info "构建 GPU REST 镜像 haisi-mempalace:rest-bgem3 ..."
  docker build -t haisi-mempalace:rest-bgem3 \
    -f "$SCRIPT_DIR/../../../open_source/mempalace/Dockerfile.rest.bgem3" "$SCRIPT_DIR/../../../open_source/mempalace"
  success "GPU 镜像构建完成: haisi-mempalace:rest-bgem3"
fi

echo
success "构建完成。下一步："
if [ "$GPU" -eq 1 ]; then
  echo "  ./start.sh --gpu"
else
  echo "  ./start.sh"
fi
```

### 6. `start.sh`

启动脚本。默认启动 CPU 形态（`docker-compose.yml`）；`--gpu` 启动 GPU 形态（`docker-compose.bgem3.yml`）。启动后打印容器状态并提示 WSL2 形态需先在管理员 PowerShell 里执行 `firewall.ps1`，以及健康检查地址。

```bash
#!/usr/bin/env bash
set -euo pipefail

# MemPalace REST —— 启动脚本。
# 默认启动 CPU 形态（docker-compose.yml）；--gpu 启动 GPU 形态（docker-compose.bgem3.yml）。
# GPU 形态要求先 ./build.sh --gpu 构建出 haisi-mempalace:rest-bgem3 镜像。
#
# 不用本脚本的等价命令：
#
#   # CPU 形态（需先 ./build.sh 产出 haisi-mempalace:latest；compose 再基于 Dockerfile.rest
#   #           构建 haisi-mempalace:rest 并 up。若 haisi-mempalace:latest 缺失，compose build 会失败）
#   ./build.sh && docker compose -f docker-compose.yml up -d
#
#   # GPU 形态（需先 ./build.sh --gpu 出 haisi-mempalace:rest-bgem3 镜像）
#   docker compose -f docker-compose.bgem3.yml up -d
#
# 不用 compose、直接 docker run（CPU 形态，需先 ./build.sh 出 haisi-mempalace:rest 镜像）：
#
#   docker run -d \
#     --name mempalace-rest \
#     -p 8777:8777 \
#     -v mempalace-data:/data \
#     -e MEMPALACE_REST_HOST=0.0.0.0 \
#     -e MEMPALACE_REST_NO_AUTH=1 \
#     --restart unless-stopped \
#     haisi-mempalace:rest
#
# 关键参数对应 docker-compose.yml：
#   -p 8777:8777                      端口（host:container）
#   -v mempalace-data:/data            数据卷（宫殿 + minilm 模型缓存，持久化）
#   MEMPALACE_REST_HOST=0.0.0.0       监听所有网卡（容器内）
#   MEMPALACE_REST_NO_AUTH=1           可信内网免鉴权（公网必须改用 MEMPALACE_REST_TOKEN）
#   --restart unless-stopped          崩溃自动重启

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GPU=0
for arg in "$@"; do
  case "$arg" in
    --gpu) GPU=1 ;;
    -h|--help) echo "Usage: $0 [--gpu]"; exit 0 ;;
    *) echo "未知参数: $arg（见 --help）" >&2; exit 2 ;;
  esac
done

if [ "$GPU" -eq 1 ]; then
  COMPOSE_FILE="$SCRIPT_DIR/docker-compose.bgem3.yml"
  echo "启动 GPU 形态: $COMPOSE_FILE"
  echo "（需先 ./build.sh --gpu 构建镜像 haisi-mempalace:rest-bgem3）"
else
  COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
  echo "启动 CPU 形态: $COMPOSE_FILE"
fi

docker compose -f "$COMPOSE_FILE" up -d

echo
echo "=== 容器状态 ==="
docker compose -f "$COMPOSE_FILE" ps
echo
echo "WSL2 形态：需先在管理员 PowerShell 里执行 ./firewall.ps1 -Action add，否则局域网访问不通。"
echo "健康检查: curl http://192.168.80.250:8777/healthz"
```

### 7. `stop.sh`

停止脚本。默认只 `docker compose down`（保留数据卷 `mempalace-data`，宫殿数据与嵌入模型缓存都保留）；不加 `-v`。脚本末尾提示 `-v` 会删除数据卷，慎用。

```bash
#!/usr/bin/env bash
set -euo pipefail

# MemPalace REST —— 停止脚本。
# 默认停止 CPU 形态（docker-compose.yml）；--gpu 停止 GPU 形态（docker-compose.bgem3.yml）。
#
# 注意：docker compose down -v 会删除数据卷 mempalace-data
#       （宫殿数据 + 嵌入模型缓存全部丢失），慎用。本脚本默认只 down，不加 -v。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GPU=0
for arg in "$@"; do
  case "$arg" in
    --gpu) GPU=1 ;;
    -h|--help) echo "Usage: $0 [--gpu]"; exit 0 ;;
    *) echo "未知参数: $arg（见 --help）" >&2; exit 2 ;;
  esac
done

if [ "$GPU" -eq 1 ]; then
  COMPOSE_FILE="$SCRIPT_DIR/docker-compose.bgem3.yml"
else
  COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
fi

echo "停止: $COMPOSE_FILE"
docker compose -f "$COMPOSE_FILE" down
echo
echo "提示: docker compose down -v 会删除数据卷 mempalace-data（宫殿与模型缓存全没），慎用。"
```

### 8. `firewall.ps1`

Windows 宿主机 WSL2 端口转发 + 防火墙放行脚本。面向 105 服务器 Windows + WSL2 部署形态：自动从 `wsl ip addr show eth0` 解析 WSL 虚拟 IP（默认 172.24.104.177），添加 `netsh portproxy` 把 Windows `0.0.0.0:8777` 转发到 WSL 的 8777，并 `New-NetFirewallRule` 放行入站。支持 `-Action add/show/remove`，必须以管理员身份运行。

```powershell
# MemPalace REST —— Windows 宿主机 WSL2 端口转发 + 防火墙放行。
#
# 面向 105 服务器 Windows + WSL2 部署形态：Docker 跑在 WSL2 内，
# 虚拟 IP 默认 172.24.104.177（WSL 重启后可能变化），宿主机局域网 IP 192.168.80.250。
# WSL2 内部监听的端口默认只对 Windows 本机 localhost 可达，局域网其他机器访问
# 192.168.80.250:8777 会 Connection refused，必须配置端口转发 + 防火墙放行。
#
# 必须以管理员身份运行。在普通 PowerShell 里可 elevate：
#   Start-Process pwsh -Verb RunAs -ArgumentList '.\firewall.ps1'
# 或直接以管理员 PowerShell 执行：
#   .\firewall.ps1 -Action add           # 添加端口转发 + 防火墙规则（默认）
#   .\firewall.ps1 -Action show          # 查看当前规则
#   .\firewall.ps1 -Action remove        # 删除端口转发 + 防火墙规则
#   .\firewall.ps1 -Action add -WslIP 172.24.104.177   # 显式指定 WSL IP
#
# WSL 重启后 IP 可能变化，需重跑 -Action add（或先 -Action remove 再 add）。

#Requires -RunAsAdministrator

param(
  [string]$Action = "add",
  [int]$Port = 8777,
  [string]$WslIP = ""
)

# 从 `wsl ip addr show eth0` 输出中解析 172.x.x.x 的 inet 地址（去掉 CIDR 后缀）。
function Resolve-WslIP {
  $raw = wsl ip addr show eth0 2>$null
  if (-not $raw) {
    throw "无法从 'wsl ip addr show eth0' 获取输出。请确认 WSL 已启动。"
  }
  $ip = $null
  foreach ($line in ($raw -split "`n")) {
    if ($line -match '^\s*inet\s+(172\.\d+\.\d+\.\d+)/') {
      $ip = $Matches[1]
      break
    }
  }
  if (-not $ip) {
    throw "未在 wsl 输出中找到 172.x.x.x 的 inet 地址。原始输出:`n$raw"
  }
  return $ip
}

switch ($Action) {
  "add" {
    if ([string]::IsNullOrWhiteSpace($WslIP)) {
      $WslIP = Resolve-WslIP
    }
    Write-Host "WSL IP: $WslIP"

    netsh interface portproxy add v4tov4 listenport=$Port listenaddress=0.0.0.0 connectport=$Port connectaddress=$WslIP

    try {
      New-NetFirewallRule -DisplayName "MemPalace $Port" -Direction Inbound -LocalPort $Port -Protocol TCP -Action Allow -ErrorAction Stop | Out-Null
      Write-Host "防火墙规则已创建: MemPalace $Port"
    } catch {
      Write-Host "防火墙规则已存在，忽略: $_"
    }

    Write-Host
    Write-Host "=== portproxy ==="
    netsh interface portproxy show all
    Write-Host
    Write-Host "=== firewall rules ==="
    Get-NetFirewallRule -DisplayName "MemPalace*" | Format-Table DisplayName, Enabled, Direction, Action -AutoSize
    Write-Host
    Write-Host "局域网访问: http://192.168.80.250:$Port/healthz"
    Write-Host "WSL 重启后 IP 可能变化，需重跑 .\firewall.ps1 -Action add 或先 -Action remove 再 add。"
  }
  "show" {
    Write-Host "=== portproxy ==="
    netsh interface portproxy show all
    Write-Host
    Write-Host "=== firewall rules ==="
    Get-NetFirewallRule -DisplayName "MemPalace*" | Format-Table DisplayName, Enabled, Direction, Action -AutoSize
  }
  "remove" {
    netsh interface portproxy delete v4tov4 listenport=$Port listenaddress=0.0.0.0
    try {
      Remove-NetFirewallRule -DisplayName "MemPalace $Port" -ErrorAction Stop
      Write-Host "已删除防火墙规则: MemPalace $Port"
    } catch {
      Write-Host "防火墙规则不存在，忽略: $_"
    }
  }
  default {
    Write-Error "未知 Action: $Action（应为 add / show / remove）"
    exit 2
  }
}
```

### 9. `firewall.sh`

纯 Linux 形态防火墙放行脚本（面向非 WSL2 的直部署形态）。自动探测 `ufw` 或 `firewalld`，放行 8777/tcp；两者皆无则报错并提示手工 iptables 命令。末尾打印安全警告：公网部署必须前置 Nginx + 鉴权（token）。

```bash
#!/usr/bin/env bash
set -euo pipefail

# MemPalace REST —— Linux 防火墙放行（面向纯 Linux 直部署，非 WSL2 形态）。
# WSL2 形态请改用 firewall.ps1（在 Windows 宿主机上以管理员身份运行）。
#
# 自动探测 ufw / firewalld；两者皆无则报错，提示手工放行。

PORT=8777

if command -v ufw >/dev/null 2>&1; then
  echo "检测到 ufw，放行 ${PORT}/tcp ..."
  sudo ufw allow ${PORT}/tcp
  sudo ufw status
elif command -v firewall-cmd >/dev/null 2>&1; then
  echo "检测到 firewalld，放行 ${PORT}/tcp ..."
  sudo firewall-cmd --add-port=${PORT}/tcp --permanent
  sudo firewall-cmd --reload
else
  echo "错误：未检测到 ufw 或 firewall-cmd，请手工放行 ${PORT}/tcp：" >&2
  echo "  iptables: sudo iptables -A INPUT -p tcp --dport ${PORT} -j ACCEPT" >&2
  exit 1
fi

echo
echo "[安全警告] 公网部署必须前置 Nginx + 鉴权（token），裸暴露 ${PORT} 会导致未授权访问。"
echo "  详见同目录 MemPalace部署文档.md 第 8 节（鉴权模式）。"
```

***

## 部署步骤

### 1. 进入部署目录

Windows（PowerShell / Git Bash）：

```bash
cd d:\Code\haisi\haisi_rag_models_server\release\images\mempalace
```

Linux / WSL2：

```bash
cd /data/haisi/haisi_rag_models_server/release/images/mempalace
```

### 2. 赋可执行权限

```bash
chmod +x *.sh
```

### 3. 构建镜像

CPU 形态（默认，`minilm` 嵌入，384d）：

```bash
./build.sh
```

GPU 形态（`bgem3` 嵌入，1024d，CJK 最强）：

```bash
./build.sh --gpu
```

> `--gpu` 会先构建 CPU 镜像 `haisi-mempalace:rest`，再在其上叠加 `open_source/mempalace/Dockerfile.rest.bgem3` 产出 `haisi-mempalace:rest-bgem3`。脚本会校验 `open_source/mempalace/wheels/` 下是否有 cu128 torch wheel（782MB），缺失时打印下载命令并退出：
>
> ```bash
> cd open_source/mempalace/wheels
> wget -O torch-2.11.0+cu128-cp312-cp312-manylinux_2_28_x86_64.whl \
>   "https://download-r2.pytorch.org/whl/cu128/torch-2.11.0%2Bcu128-cp312-cp312-manylinux_2_28_x86_64.whl"
> ```
>
> 下载后重跑 `./build.sh --gpu`。
>
> **构建耗时**：两次 CPU 构建合计约 5–15 分钟（`haisi-mempalace:latest` 的 `uv sync` 按 `uv.lock` 冻结安装占大头，可复现；`haisi-mempalace:rest` 只叠 FastAPI + uvicorn，秒级完成）；bgem3 镜像因 torch wheel 本地安装 + sentence-transformers 拉依赖，约 3–8 分钟。Docker layer cache 命中后，重构建只重跑变化层，秒级返回。改了 `open_source/mempalace/` 下源码后重跑 `./build.sh`，依赖层走缓存，仅源码层重装，通常 1–2 分钟。

### 4. 启动服务

CPU 形态：

```bash
./start.sh
```

GPU 形态：

```bash
./start.sh --gpu
```

### 5. 端口放行

**WSL2 形态（105 服务器）**：在 Windows 宿主机以管理员身份打开 PowerShell，运行 `firewall.ps1`（自动探测 WSL IP 并配 `portproxy` + 防火墙）：

```powershell
# 添加端口转发 + 防火墙规则（默认 action=add）
pwsh .\firewall.ps1 -Action add

# 查看当前规则
pwsh .\firewall.ps1 -Action show

# 删除规则（WSL IP 变化时先 remove 再 add）
pwsh .\firewall.ps1 -Action remove
```

> WSL2 默认走 NAT，虚拟 IP 默认 `172.24.104.177`（WSL 重启后可能变化），局域网其他机器访问 `192.168.80.250:8777` 默认 Connection refused，必须配 portproxy + 防火墙。

**纯 Linux 形态**：直接跑 `firewall.sh`（自动探测 ufw / firewalld）：

```bash
./firewall.sh
```

> 仅放行真正需要访问的来源 IP 段更安全，例如 `sudo ufw allow from 192.168.81.0/24 to any port 8777 proto tcp`。

### 6. 验证

```bash
# 健康检查（返回 ok）
curl http://192.168.80.250:8777/healthz

# 服务信息与端点清单
curl http://192.168.80.250:8777/
```

预期：`/healthz` 返回 `ok`；`/` 返回 JSON，含端点列表、detail 档位与鉴权状态。

交互式 API 文档：浏览器访问 `http://192.168.80.250:8777/docs`（Swagger UI）或 `http://192.168.80.250:8777/redoc`（ReDoc），token 模式下点右上角 `Authorize` 填 `Bearer <token>` 即可在线试调。

***

## 鉴权模式

REST 适配器支持两种鉴权模式，由环境变量 `MEMPALACE_REST_NO_AUTH` 与 `MEMPALACE_REST_TOKEN` 决定。

### 两种模式对比

| **模式** | **环境变量** | **适用网络** | **安全性** | **启动日志** |
| -------- | ------------ | ------------ | ---------- | ------------ |
| 可信内网免鉴权 | `MEMPALACE_REST_NO_AUTH=1` | 完全可信的孤立局域网（每台主机都是自己的） | 低（任何能访问 8777 端口者可读写宫殿） | 打印醒目 `WARNING: ... UNAUTHENTICATED (opted in via MEMPALACE_REST_NO_AUTH=1 on a trusted network)` |
| Bearer Token | `MEMPALACE_REST_TOKEN=<32字节hex>` | 公网 / 半信任网络 / 需前置 TLS 反代 | 高（token 等同数据库凭证） | 启动行显示 `auth=token` |

### 可信内网（默认）

`MEMPALACE_REST_NO_AUTH=1`，无需 token。本目录的 `docker-compose.yml` 与 `docker-compose.bgem3.yml` 默认走此模式，适合部署在可信内网（如 105 服务器局域网）。`MEMPALACE_REST_HOST=0.0.0.0` 绑非 loopback + `MEMPALACE_REST_NO_AUTH=1` 已开，符合适配器 fail-closed 校验，容器可正常启动。

### Bearer Token（公网/半信任网络）

生成 token：

```bash
export MEMPALACE_REST_TOKEN=$(openssl rand -hex 32)
echo "$MEMPALACE_REST_TOKEN"  # 记下来
```

生成 64 字符 hex（32 字节熵）。写入 `.env`（由 `docker-compose.yml` 自动读取 `${MEMPALACE_REST_TOKEN}`）：

```ini
MEMPALACE_REST_TOKEN=<上面生成的 64 位 hex>
```

在 `docker-compose.yml` 里：

1. 删除 `MEMPALACE_REST_NO_AUTH: "1"` 这一行
2. 取消注释 `MEMPALACE_REST_TOKEN: "${MEMPALACE_REST_TOKEN}"`

启用后，`/palace`、`/store`、`/search`、`/kg` 等业务端点要求请求头 `Authorization: Bearer <token>`；`/healthz` 与 `/` 仍免鉴权（供反代/编排器探活）。

> 也可用 `env_file: .env` 指令把整个 `.env` 全部变量灌进容器，但显式 `${VAR}` 引用更清晰、最小权限。`.env` 加入 `.gitignore` 防止误提交。

### fail-closed 校验

REST 适配器在启动时做硬校验（`examples/rest_adapter.py` 第 102–108 行）：

> **绑非 loopback 地址（`0.0.0.0` 等）且未设 `MEMPALACE_REST_TOKEN` 且未开 `MEMPALACE_REST_NO_AUTH=1` → 容器立即 `SystemExit` 退出，拒绝以无鉴权状态暴露。**

错误信息原文：

```
MEMPALACE_REST_TOKEN is required when binding a non-loopback host (HOST=0.0.0.0).
Anyone reachable could read verbatim memory. Set a token, or — on a network you
fully trust — opt into no-auth with MEMPALACE_REST_NO_AUTH=1.
```

这是防「手滑把无鉴权的记忆库暴露到网络上」的安全护栏，**不会静默放行**。`docker logs mempalace-rest` 可见 `SystemExit` 与上述提示。

### Token 保管注意事项

> ⚠️ token 等同数据库凭证——任何拿到 token 的人都能读取宫殿全部原文（`/search`、`/kg`、`/store`）。务必：
>
> - 不进 git（`.env` 加入 `.gitignore`）；
> - 不进聊天工具 / 截图；
> - 公网部署时前置 Caddy / Nginx 做 HTTPS，token 只在 TLS 隧道内传输。

接口契约细节（鉴权头格式、免鉴权端点、错误码）另见 `mempalace-api.md` 第 2 节「鉴权」。

***

## 接口速查

| **方法** | **路径**      | **鉴权**                          | **说明**                                  |
| -------- | ------------- | --------------------------------- | ----------------------------------------- |
| `GET`    | `/healthz`    | 无                                | 存活探针，返回 `ok`，供反代/编排器用      |
| `GET`    | `/`           | 无                                | 服务信息，列出端点、detail 档位与鉴权状态 |
| `POST`   | `/palace`     | Bearer Token（启用 token 时）     | 创建宫殿（一个宫殿 = 一个独立知识库）      |
| `POST`   | `/store`      | Bearer Token（启用 token 时）     | 存记忆条目（原文 + 元数据，自动嵌入索引） |
| `GET`    | `/search`     | Bearer Token（启用 token 时）     | 语义检索（取回片段拼进 RAG prompt）        |
| `GET`    | `/kg`         | Bearer Token（启用 token 时）     | 时序知识图谱查询（谁在什么时候说了什么）  |

> 完整接口契约（请求/响应字段、错误码、detail 档位、多宫殿机制、Python `MempalaceClient` 客户端封装）见 `mempalace-api.md`：
>
> * 第 3 节「端点详情」（3.1 `/healthz` ~ 3.6 `/kg`）
> * 第 5 节「敏感字段裁剪 detail」
> * 第 6 节「错误码」
> * 第 8 节「Python 应用对接」（含 `MempalaceClient` 封装与端到端示例）

***

## 测试指南

### 1. curl 建宫 → 存 → 查 完整流程

```bash
# 1. 建宫（一个宫殿 = 一个知识库）
curl -X POST http://192.168.80.250:8777/palace \
    -H "Content-Type: application/json" \
    -d '{"palace": "kb-docs"}'

# 2. 存记忆（原文 + 可选元数据，自动嵌入索引）
curl -X POST http://192.168.80.250:8777/store \
    -H "Content-Type: application/json" \
    -d '{"palace": "kb-docs", "text": "海思半导体于 2024 年发布 Hi3798 芯片"}'

# 3. 语义检索（取回片段）
curl "http://192.168.80.250:8777/search?palace=kb-docs&q=芯片发布"

# 4. 时序事实查询
curl "http://192.168.80.250:8777/kg?palace=kb-docs&q=Hi3798"
```

> 启用 Bearer Token 时，所有业务端点请求头加 `-H "Authorization: Bearer $MEMPALACE_REST_TOKEN"`。

### 2. Python 客户端

完整 `MempalaceClient` 封装与端到端示例见 `mempalace-api.md` 第 8 节「Python 应用对接」（8.1 客户端封装、8.2 端到端可运行示例）。

***

## 日常运维

### 查看日志

```bash
docker compose logs -f              # 实时跟随（CPU 形态）
docker compose logs --tail=100      # 最近 100 行
docker logs -f mempalace-rest       # 直接按容器名跟日志
docker logs --tail 100 mempalace-rest
```

GPU 形态加 `-f docker-compose.bgem3.yml`。

### 重启 / 停止

```bash
./stop.sh                           # 停止 CPU 形态（保留数据卷）
./stop.sh --gpu                     # 停止 GPU 形态（保留数据卷）
docker compose restart              # 重启容器（保留数据卷）
docker compose down                 # 停止并删除容器（保留数据卷 mempalace-data）
docker compose down -v              # ⚠️ 同时删除数据卷，宫殿与模型缓存全没，慎用！
```

### 升级（本地源码版）

本部署包从本地 `open_source/mempalace/` 源码构建，升级即「拉新源码 / 改本地源码 → 重新 `./build.sh` → `./start.sh`」：

```bash
# 1. 更新源码（二选一）
#    a) 拉上游最新源码到 open_source/mempalace/（git pull 或重新解压）
#    b) 直接编辑 open_source/mempalace/ 下任意文件（本地改动）
#       若新增了依赖，先 cd open_source/mempalace && uv lock 更新锁文件

# 2. 重新构建镜像（CPU 形态）
./build.sh
# 或 GPU 形态
./build.sh --gpu

# 3. 重启容器（compose up -d 会重建容器，复用数据卷）
./start.sh
# 或 GPU 形态
./start.sh --gpu
```

Docker layer cache 命中后，仅源码层重装，通常 1–2 分钟完成。数据卷 `mempalace-data` 跨升级保留，宫殿数据不丢。

### 进入容器排障

```bash
docker compose exec mempalace-rest bash
# 容器内：
mempalace search "测试"             # 直接跑 CLI
mempalace repair rebuild-index      # 维度切换后全量重嵌入
ls -la /data/.mempalace/            # 查看宫殿文件
```

### 数据备份

```bash
docker run --rm -v mempalace-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/mempalace-data-$(date +%Y%m%d).tar.gz /data
```

生成的 `mempalace-data-YYYYMMDD.tar.gz` 在当前目录，含宫殿、配置、模型缓存。

### 数据恢复

```bash
docker run --rm -v mempalace-data:/data -v "$PWD":/backup alpine \
  sh -c "cd / && tar xzf /backup/mempalace-data-20260807.tar.gz"
```

> 恢复前先 `./stop.sh`（或 `docker compose down`）停止服务，避免容器占用 `/data`。

***

## 故障排查

### Q1：首次 `POST /store` 很慢甚至超时

**现象**：第一次写入卡很久，或客户端超时。

**原因**：首次写入触发嵌入模型加载 / 下载——`minilm` ~80 MB、`embeddinggemma` ~300 MB（首次从 S3/HuggingFace 下载到 `/data/.cache`），`bgem3` 加载本地 2.3 GB 模型到 GPU 约 10 秒。仅首次，之后复用缓存。

**解决**：正常现象，等首次完成后即正常。或先调 `POST /palace` 建库占位（不加载模型、秒回），把首写延迟从「写接口」挪到运维侧主动触发：

```bash
curl -X POST "http://192.168.80.250:8777/palace" -H "Authorization: Bearer $TOKEN"
```

### Q2：容器启动即退出，日志含 `MEMPALACE_REST_TOKEN is required when binding a non-loopback host`

**现象**：`./start.sh` 后 `docker compose ps` 显示 `Exited`，日志含上述错误（见 [鉴权模式 fail-closed 校验](#fail-closed-校验)）。

**原因**：fail-closed 校验未通过——绑了 `0.0.0.0`（非 loopback）但既未设 `MEMPALACE_REST_TOKEN` 也未开 `MEMPALACE_REST_NO_AUTH=1`，适配器拒绝以无鉴权暴露。

**解决**：二选一——

- 设 token：`.env` 写 `MEMPALACE_REST_TOKEN=$(openssl rand -hex 32)`，compose 引用 `${MEMPALACE_REST_TOKEN}`；
- 或可信内网免鉴权：compose 设 `MEMPALACE_REST_NO_AUTH: "1"`（本部署包默认即此）。

### Q3：切换嵌入模型后检索失效 / 报维度不匹配

**现象**：从 `minilm`/`embeddinggemma` 切到 `bgem3`（或反向）后，`/search` 召回为空或 Chroma 报维度错误。

**原因**：`minilm`/`embeddinggemma` 是 384 维，`bgem3` 是 1024 维，ChromaDB 的 HNSW 索引维度固定，新旧向量不能混存混查。

**解决**：全量重嵌入或建新宫：

```bash
docker compose exec mempalace-rest mempalace repair rebuild-index
```

或直接 `docker compose down -v` 删数据卷重建全新宫殿（数据丢失，确认后再做）。

### Q4：GPU 形态容器启动失败，或容器内 `nvidia-smi` 看不到 GPU

**现象**：bgem3 容器启动失败，或日志报 CUDA 不可用、回退 CPU。

**原因**：宿主机缺 NVIDIA 驱动，或未装 NVIDIA Container Toolkit，或 Compose 的 `deploy.resources` 声明未生效，或 cu128 torch wheel 未装进镜像。

**解决**：

```bash
nvidia-smi                                                    # 宿主机驱动
docker run --rm --gpus all nvidia/cuda:12.8-base nvidia-smi   # Toolkit
```

两个都正常才可用 GPU 形态。Blackwell（RTX 5090，`sm_120`）必须用 cu128 torch wheel（由 `./build.sh --gpu` 装入），cu121/cu118 等其他版本没有 `sm_120` 算子。

### Q5：`curl /search` 返回 400 `No palace found`

**现象**：查某宫殿返回 `{"detail": "No palace found"}`。

**原因**：宫殿已建（或自动建）但里面还没数据，ChromaDB 集合为空。

**解决**：先写入数据再查：

```bash
curl -X POST "http://192.168.80.250:8777/store" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"entry":"第一条记录","topic":"test"}'
```

### Q6：`palace` 参数含 `..` / `/` / `\` 被 400 拒绝

**现象**：`curl ".../search?palace=../etc"` 返回 400。

**原因**：`sanitize_name` 防路径穿越，palace 名含 `..`、`/`、`\`、空字符等不安全字符一律拒绝。

**解决**：用合法 palace 名（字母、数字、`-`、`_`），如 `kb-docs`、`alice`、`team-2026`。

### Q7：中文检索召回差

**现象**：`/search` 中文 query 命中不准、召回低。

**原因**：默认 `minilm` 是英文模型，中文语义建模弱；或 `max_distance` 默认 1.5 过松导致弱匹配。

**解决**：

- 换多语言 / 中文模型：`MEMPALACE_EMBEDDING_MODEL=embeddinggemma`（CPU 可跑）或 `bgem3`（最强，需 GPU）；
- 或临时调严阈值：`/search?q=...&max_distance=0.6`（0=只召回几乎相同的）；
- 切模型后需 `mempalace repair rebuild-index` 重嵌入（见 Q3）。

### Q8：从局域网访问 `192.168.80.250:8777` 不通（Connection refused）

**现象**：局域网其他机器访问 `192.168.80.250:8777` Connection refused，但容器内 `/healthz` 正常。

**原因**：WSL2 形态未配 `firewall.ps1` portproxy / 防火墙未放行 / WSL IP 变化。

**解决**：管理员 PowerShell 跑 `pwsh .\firewall.ps1 -Action add`；WSL 重启后 IP 变化需重跑（或先 `-Action remove` 再 `add`）。

### 快速排障命令

```bash
# 实时日志
docker logs -f mempalace-rest

# 最近 100 行
docker logs --tail 100 mempalace-rest

# 重启
docker restart mempalace-rest

# 进入容器排障
docker exec -it mempalace-rest bash

# 重建索引（维度变更后）
docker exec mempalace-rest mempalace repair rebuild-index
```

***

## 与主项目集成

### 调用链路

主项目 `haisi_rag_models_server` 通过 HTTP 调用本服务：

1. **`/store` 存原文**：RAG 流水线把切片后的原文片段 + 元数据存进宫殿
2. **`/search` 取片段**：用户 Query 来时，先语义检索取回相关片段，拼进 RAG prompt
3. **`/kg` 取时序事实**：需要「谁在什么时候说了什么」时，查时序知识图谱

### Python 客户端示例

完整 `MempalaceClient` 封装与端到端示例见 `mempalace-api.md` 第 8 节。主项目可直接复用该客户端：

```python
from mempalace_api_client import MempalaceClient

client = MempalaceClient("http://192.168.80.250:8777", token=None)  # 可信内网免鉴权

# 存
client.store(palace="kb-docs", text="海思半导体研发新一代芯片")

# 搜
hits = client.search(palace="kb-docs", q="芯片研发", top_k=5)

# 查时序事实
facts = client.kg(palace="kb-docs", q="Hi3798")
```

> 客户端封装与字段细节见 `mempalace-api.md` 第 8.1 节。

### 与已部署服务的端口共存表

| **服务**        | **端口** | **说明**                |
| --------------- | -------- | ----------------------- |
| LAC 分词        | 5955     | 中文分词 + 词性标注      |
| Embedding       | 16604    | Qwen3-Embedding 嵌入    |
| Reranker        | 16603    | Coreb-Code-Reranker 重排 |
| Milvus          | 19530    | 向量库                  |
| Elasticsearch   | 19200    | 全文检索                |
| PostgreSQL      | 16602    | 关系数据库              |
| Redis           | 16379    | 缓存                    |
| **MemPalace**   | **8777** | **AI 记忆层（本服务）** |

***

## 版本历史

| **版本** | **日期**     | **更新内容**                                              |
| -------- | ------------ | --------------------------------------------------------- |
| v1.0.0   | 2026-08-09   | 初始版本，CPU REST + GPU bgem3 双形态部署包（自包含 Dockerfile、双 compose、build/start/stop 脚本、WSL2/Linux 双形态防火墙脚本） |
| v1.1.0   | 2026-08-09   | 合并 `mempalace部署说明.md` 为单一部署文档：新增「部署形态选型」「源码与本地改动」「日常运维」章节；鉴权模式补 fail-closed 错误原文与 token 保管注意事项；故障排查吸收 Q1–Q8（含原 6 类与上游 Q1–Q7 去重）；构建方式统一为 `./build.sh`（自包含，构建上下文 `open_source/mempalace` 即本地源码）；删除上游三段式构建命令与「获取源码」章节。 |
| v1.2.0   | 2026-08-10   | 切换回上游两次构建：删除外层合并式 `Dockerfile`，改用内层 `open_source/mempalace/Dockerfile`（产出 `haisi-mempalace:latest`，MCP/CLI 基础镜像）+ `open_source/mempalace/Dockerfile.rest`（产出 `haisi-mempalace:rest`，`FROM haisi-mempalace:latest`）；`build.sh` 默认无参路径改回两次构建；`docker-compose.yml` 的 `build.dockerfile` 改为 `Dockerfile.rest`；内层两个 Dockerfile 仅做国内镜像适配（华为云 syntax/python/uv + 阿里云 apt），不改动构建逻辑。 |

***

## 相关文档

* [MemPalace 项目说明](./mempalace.md)（架构 / AAAK 索引层 / 嵌入模型 / 基准测试）
* [MemPalace REST 接口对接文档](./mempalace-api.md)（完整 API + Python `MempalaceClient` 客户端）
* [LAC 中文分词服务部署文档](../lac/LAC部署文档.md)（参考样例，结构对齐）
* [部署总览](../docker.md)