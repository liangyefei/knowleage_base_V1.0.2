#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# MemMachine 服务容器入口脚本
#
# 职责：
#   1. 打印启动信息（版本、端口、配置文件路径、worker 数）
#   2. 验证配置文件存在
#   3. exec memmachine-server，PID 1 = 服务进程，接收 SIGTERM 优雅退出
#
# 说明：
#   - 配置文件路径通过 MEMORY_CONFIG 环境变量传递（docker-compose.yml 设置
#     MEMORY_CONFIG=/app/configuration.yml），服务端 load_configuration() 读取该
#     变量加载 YAML；memmachine-server CLI 不支持 --config 参数
#   - 配置文件通过 docker-compose.yml volume 挂载到 /app/configuration.yml
#   - 修改 configuration.yml 后重启容器即可生效：docker compose restart memmachine
# =============================================================================

# ── 环境变量（带默认值）──────────────────────────────────────────────
MEMORY_CONFIG="${MEMORY_CONFIG:-/app/configuration.yml}"
HOST="${HOST:-0.0.0.0}"
MEMMACHINE_WORKERS="${MEMMACHINE_WORKERS:-1}"
MEMMACHINE_CONFIG_API="${MEMMACHINE_CONFIG_API:-}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"
POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
NEO4J_HOST="${NEO4J_HOST:-neo4j}"
NEO4J_PORT="${NEO4J_PORT:-7687}"

# ── 启动 banner ──────────────────────────────────────────────────────
echo "=========================================="
echo "MemMachine Long-term Memory Service Starting..."
echo "=========================================="
echo "Config file:         ${MEMORY_CONFIG}"
echo "Host:                ${HOST}"
echo "Workers:             ${MEMMACHINE_WORKERS}"
echo "Log level:           ${LOG_LEVEL}"
echo "PostgreSQL:          ${POSTGRES_HOST}:${POSTGRES_PORT}"
echo "Neo4j:               ${NEO4J_HOST}:${NEO4J_PORT}"
echo "memmachine-server:   $(memmachine-server --version 2>/dev/null || echo unknown)"
echo "=========================================="

# ── 1. 验证配置文件存在 ─────────────────────────────────────────────
if [ ! -f "${MEMORY_CONFIG}" ]; then
    echo "[entrypoint] FATAL: 配置文件 ${MEMORY_CONFIG} 不存在" >&2
    echo "[entrypoint] 请检查 docker-compose.yml 的 volumes 挂载与 MEMORY_CONFIG 环境变量" >&2
    exit 1
fi
echo "[entrypoint] 配置文件验证通过 ✓"

# ── 2. 构造启动参数 ──────────────────────────────────────────────────
# memmachine-server CLI（见 MemMachine.md §9.2 与 packages/server/.../app.py）：
#   --stdio              以 MCP stdio 模式运行（不启动 HTTP）
#   --with-config-api    启用配置管理 API（MEMMACHINE_CONFIG_API 非空时）
#   --version            显示版本
# 配置文件路径通过 MEMORY_CONFIG 环境变量传递（load_configuration 读取），
# 不通过 CLI 参数。
ARGS=()

if [ -n "${MEMMACHINE_CONFIG_API}" ]; then
    ARGS+=("--with-config-api")
    echo "[entrypoint] 配置管理 API 已启用"
fi

# ── 3. 启动 memmachine-server ────────────────────────────────────────
# docker-compose.yml 已通过 depends_on + healthcheck 保证 PostgreSQL / Neo4j
# 就绪后再启动 memmachine，无需在此重复探测
#
# exec 让 PID 1 = memmachine-server，接收 docker stop 的 SIGTERM 优雅退出
echo "[entrypoint] 启动 memmachine-server（${HOST}:8080，workers=${MEMMACHINE_WORKERS}）"
echo "=========================================="
exec memmachine-server "${ARGS[@]}"
