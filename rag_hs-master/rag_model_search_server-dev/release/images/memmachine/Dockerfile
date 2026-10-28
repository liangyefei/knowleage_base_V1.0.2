# =============================================================================
# MemMachine 服务镜像 Dockerfile（多阶段构建）
#
# 架构：
#   1. Builder：基于 python:3.12-slim-trixie，使用 uv 安装 memmachine-server
#      及其依赖到 .venv 虚拟环境
#   2. Final：复制 builder 阶段的 .venv 到精简运行时镜像，下载 NLTK 数据，
#      暴露 8080 端口并启动 memmachine-server
#
# 构建上下文为项目根（docker-compose.yml 中 context: ../../..），故 COPY 路径
# 均相对项目根：open_source/memmachine/（源码）、release/images/memmachine/entrypoint.sh 等
#
# 端口：对外 8080（MemMachine REST API v2 + MCP）
#
# 构建 CPU 镜像：
#   docker build -t haisi-memmachine:cpu .
#
# 构建 GPU 镜像（含 sentence-transformers 本地 embedding GPU 依赖）：
#   docker build --build-arg GPU=true -t haisi-memmachine:gpu .
# =============================================================================

# ── Stage 1: Builder ──────────────────────────────────────────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/python:3.12-slim-trixie AS builder

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# 换国内 Debian 镜像源（阿里云），加速 apt 安装
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true && \
    sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true

# 系统依赖：curl（健康检查）、ca-certificates
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# 配置 pip 使用清华镜像源（先设镜像源，再升级 pip，避免升级时直连 files.pythonhosted.org 超时）
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple && \
    pip config set install.trusted-host pypi.tuna.tsinghua.edu.cn && \
    python -m pip install --upgrade pip

# 从官方 uv 镜像复制 uv / uvx 二进制到 PATH
COPY --from=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/ghcr.io/astral-sh/uv:latest /uv /uvx /usr/local/bin/

WORKDIR /app

# 构建参数：是否包含 GPU 依赖；SCM 版本号（用于 setuptools-scm）
ARG GPU="false"
ARG SCM_VERSION="0.0.0"
ENV SETUPTOOLS_SCM_PRETEND_VERSION=${SCM_VERSION}

# 先复制依赖描述文件，利用 Docker 层缓存（源码变更不重装依赖）
COPY open_source/memmachine/pyproject.toml open_source/memmachine/uv.lock ./

# 配置 uv 使用清华镜像源 + 放宽超时（避免直连 files.pythonhosted.org 慢/超时）
# uv sync 会从 UV_INDEX_URL 下载 wheel，校验 uv.lock 里的 hash 不变 = 安全
ENV UV_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    UV_HTTP_TIMEOUT=300

# 安装依赖到 .venv（不安装项目本身；--no-editable 用于生产镜像）
# --mount=type=cache 共享 uv 下载缓存，加速重复构建
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ "$GPU" = "true" ]; then \
        uv sync --frozen --package memmachine-server \
            --no-install-workspace --no-editable --no-dev --extra gpu; \
    else \
        uv sync --frozen --package memmachine-server \
            --no-install-workspace --no-editable --no-dev; \
    fi

# 复制应用源码（.dockerignore 已排除 docs/examples/integrations/evaluation 等）
COPY open_source/memmachine/ /app

# 安装项目本身（从本地源码安装 memmachine-server 包）
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ "$GPU" = "true" ]; then \
        uv sync --frozen --package memmachine-server --no-editable --no-dev --extra gpu; \
    else \
        uv sync --frozen --package memmachine-server --no-editable --no-dev; \
    fi

# ── Stage 2: Final ────────────────────────────────────────────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/python:3.12-slim-trixie AS final


LABEL maintainer="haisi"
LABEL version="1.0"
LABEL description="MemMachine 长期记忆服务（REST API v2 + MCP，CPU 版本）"

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/.venv/bin:$PATH" \
    HOST=0.0.0.0

# 换国内 Debian 镜像源（阿里云）
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true && \
    sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true

# 运行时系统依赖：curl（健康检查）、ca-certificates
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从 builder 阶段复制已安装好的虚拟环境
COPY --from=builder /app/.venv /app/.venv

# 预下载 NLTK 数据（punkt_tab 用于分句，stopwords 用于 BM25 reranker）
# 容器内无外网时也能正常工作
RUN python -c "import nltk, sys; \
    passed = nltk.download('punkt_tab') and nltk.download('stopwords'); \
    sys.exit(0 if passed else 1)"

# 复制部署文件：入口脚本、默认配置文件
COPY release/images/memmachine/entrypoint.sh /app/entrypoint.sh
# 转换 CRLF → LF（避免 Windows 宿主机创建的脚本在 Linux 容器内因 \r 报错）
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

# 配置文件通过 volume 挂载（docker-compose.yml: ./configuration.yml:/app/configuration.yml）
# 此处仅创建目录占位，实际配置在容器启动时由挂载覆盖
RUN mkdir -p /tmp/memory_logs /tmp/event_vector_indexes /tmp/semantic_vector_indexes

# 暴露 MemMachine API 端口
EXPOSE 8080

# 健康检查：调用 /api/v2/health（见 MemMachine.md §14）
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/api/v2/health || exit 1

# 启动入口
CMD ["/app/entrypoint.sh"]
