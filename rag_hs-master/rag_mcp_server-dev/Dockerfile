# Dual-MCP image: codebase-memory-mcp (CBM) + codegraph + supergateway
#
# Self-contained: open-source sources under open_source/ (codebase-memory-mcp/
# and codegraph/), custom runtime code under src/ (REST server, bridge, sync).
# Multi-stage build: CBM compiled from C source, codegraph compiled from TypeScript source.
# No external dependencies at build time (no git clone, no prebuilt binary download).
#
# Build:
#   cd <repo root>
#   docker build -t dual-mcp .
#
# Approximate build time on a 4-core machine:
#   - CBM compile: 10-15 minutes (vendored tree-sitter grammars)
#   - codegraph npm ci + build: 1-2 minutes
#   - runtime image: 30 seconds

# ── Stage 1: build CBM binary from C source ──────────────────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/debian:bookworm-slim AS cbm-builder

ENV DEBIAN_FRONTEND=noninteractive

RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
    sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null; \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential zlib1g-dev ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src/cbm

# Copy entire CBM source tree (incl. vendored tree-sitter grammars in internal/cbm/vendored/)
COPY open_source/codebase-memory-mcp/ ./

# Build production binary → build/c/codebase-memory-mcp (~280 MB, glibc-linked)
RUN make -f Makefile.cbm cbm

# ── Stage 2: build codegraph dist from TypeScript source ─────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/node:22-bookworm-slim AS codegraph-builder

WORKDIR /src/codegraph

# Install deps first (layer cache: source changes don't re-run npm ci)
COPY open_source/codegraph/package.json open_source/codegraph/package-lock.json ./
RUN npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com

# Build dist/
COPY open_source/codegraph/ ./
RUN npm run build

# ── Stage 3: runtime ─────────────────────────────────────────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/node:22-bookworm-slim

# System deps: Python for CBM NL server (FastAPI/uvicorn), tini for PID 1.
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
    sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null; \
    apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-pip python3-venv \
      bash curl ca-certificates tini \
 && pip install --break-system-packages --no-cache-dir fastapi uvicorn \
 && rm -rf /var/lib/apt/lists/*

# supergateway: bridge stdio MCP → streamable-http for remote clients.
RUN npm install -g supergateway --registry=https://registry.npmmirror.com

# CBM source files (indexed by CBM at runtime + scripts for NL server).
COPY --from=cbm-builder /src/cbm/src           /opt/codebase-memory-mcp/src
COPY --from=cbm-builder /src/cbm/internal      /opt/codebase-memory-mcp/internal
COPY --from=cbm-builder /src/cbm/scripts       /opt/codebase-memory-mcp/scripts
COPY --from=cbm-builder /src/cbm/tests         /opt/codebase-memory-mcp/tests
COPY --from=cbm-builder /src/cbm/tools         /opt/codebase-memory-mcp/tools
COPY --from=cbm-builder /src/cbm/Makefile.cbm  /opt/codebase-memory-mcp/

# CBM prebuilt binary from Stage 1.
COPY --from=cbm-builder /src/cbm/build/c/codebase-memory-mcp \
     /opt/codebase-memory-mcp/build/c/codebase-memory-mcp

# codegraph built artifacts + source (source for indexing at runtime).
COPY --from=codegraph-builder /src/codegraph/dist          /opt/codegraph/dist
COPY --from=codegraph-builder /src/codegraph/node_modules  /opt/codegraph/node_modules
COPY --from=codegraph-builder /src/codegraph/src           /opt/codegraph/src
COPY --from=codegraph-builder /src/codegraph/package.json  /opt/codegraph/

# Entrypoint + helper scripts (from this build context).
COPY src/entrypoint.sh          /entrypoint.sh
COPY src/mcp_stdio_bridge.py    /opt/mcp_stdio_bridge.py
COPY src/bin/add-project        /usr/local/bin/add-project
COPY src/bin/list-projects      /usr/local/bin/list-projects
COPY src/bin/query-project      /usr/local/bin/query-project
COPY src/bin/remove-project     /usr/local/bin/remove-project

# Custom runtime scripts back into CBM scripts dir (entrypoint references
# /opt/codebase-memory-mcp/scripts/...; container layout is unchanged).
COPY src/nl_query_server.py     /opt/codebase-memory-mcp/scripts/nl_query_server.py
COPY src/nlq_constants.py       /opt/codebase-memory-mcp/scripts/nlq_constants.py
COPY src/nlq_store.py           /opt/codebase-memory-mcp/scripts/nlq_store.py
COPY src/nlq_jobs.py            /opt/codebase-memory-mcp/scripts/nlq_jobs.py
COPY src/nlq_query.py           /opt/codebase-memory-mcp/scripts/nlq_query.py
COPY src/nlq_doc_code.py        /opt/codebase-memory-mcp/scripts/nlq_doc_code.py
COPY src/nlq_graph.py           /opt/codebase-memory-mcp/scripts/nlq_graph.py
COPY src/nlq_mempalace.py       /opt/codebase-memory-mcp/scripts/nlq_mempalace.py
COPY src/nlq_pages.py           /opt/codebase-memory-mcp/scripts/nlq_pages.py
COPY src/glossary_extract.py    /opt/codebase-memory-mcp/scripts/glossary_extract.py
COPY src/cbm_sync.py            /opt/codebase-memory-mcp/scripts/cbm_sync.py

# Permissions + codegraph symlink.
RUN chmod +x /entrypoint.sh \
 && chmod +x /usr/local/bin/add-project \
            /usr/local/bin/list-projects \
            /usr/local/bin/query-project \
            /usr/local/bin/remove-project \
 && chmod +x /opt/codebase-memory-mcp/build/c/codebase-memory-mcp \
 && ln -sf /opt/codegraph/dist/bin/codegraph.js /usr/local/bin/codegraph

# Runtime configuration.
ENV CBM_CACHE_DIR=/var/lib/codebase-memory-mcp
ENV NL_PORT=9788
# Node.js setTimeout is a 32-bit signed int (max 2^31-1 ms ≈ 24.8 days).
# Larger values overflow to 1ms and the daemon exits immediately.
ENV CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS=2147483647
ENV CODEGRAPH_TELEMETRY=0
# MCP 桥：CBM_SSE_PORT 设 CBM 桥端口；CG_SSE_BRIDGES 是 codegraph
# 单桥多项目模式的开关（项目按调用传 projectPath 动态选择）。
ENV CBM_SSE_PORT=9800
ENV CG_SSE_BRIDGES=on
ENV CG_SSE_PORT_START=9801

EXPOSE 9788 9800 9801
WORKDIR /opt

ENTRYPOINT ["/usr/bin/tini","--","/entrypoint.sh"]
CMD []
