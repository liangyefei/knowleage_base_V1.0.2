# Dual-MCP image: codebase-memory-mcp (CBM) + codegraph
# Build context: /data/codebase-memory-mcp/ (codegraph lives at <context>/codegraph/)
#   docker build -f /data/codebase-memory-mcp/docker/Dockerfile \
#                -t dual-mcp:latest /data/codebase-memory-mcp/

FROM node:22-bookworm-slim

# System dependencies (Python for CBM NL server, tini for PID 1, misc utils).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-pip python3-venv \
      bash curl ca-certificates tini \
 && pip install --break-system-packages --no-cache-dir fastapi uvicorn \
 && rm -rf /var/lib/apt/lists/*

# supergateway: bridge stdio MCP servers to HTTP/SSE for remote MCP clients.
RUN npm install -g supergateway

# CBM source files (indexed by CBM at runtime + needed scripts).
COPY src /opt/codebase-memory-mcp/src
COPY internal /opt/codebase-memory-mcp/internal
COPY scripts /opt/codebase-memory-mcp/scripts
COPY tests /opt/codebase-memory-mcp/tests
COPY tools /opt/codebase-memory-mcp/tools
COPY Makefile.cbm /opt/codebase-memory-mcp/

# Prebuilt CBM binary (glibc-linked; bookworm-slim satisfies it).
COPY build/c/codebase-memory-mcp /opt/codebase-memory-mcp/build/c/codebase-memory-mcp

# codegraph (vendored under CBM repo per user setup).
COPY codegraph /opt/codegraph

# Entrypoint + helper scripts.
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/bin/add-project      /usr/local/bin/add-project
COPY docker/bin/list-projects    /usr/local/bin/list-projects
COPY docker/bin/query-project    /usr/local/bin/query-project
COPY docker/bin/remove-project   /usr/local/bin/remove-project

# Permissions, symlinks.
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
# supergateway SSE bridges: empty = skip, otherwise space-separated paths
ENV CBM_SSE_PORT=9800
ENV CG_SSE_BRIDGES=/projects/Hi3516CV610
ENV CG_SSE_PORT_START=9801

EXPOSE 9788 9800 9801
WORKDIR /opt

ENTRYPOINT ["/usr/bin/tini","--","/entrypoint.sh"]
CMD []
