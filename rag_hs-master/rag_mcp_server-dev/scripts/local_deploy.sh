#!/usr/bin/env bash
# local_deploy.sh — 一键部署所有服务
#
# 用法:
#   scripts/local_deploy.sh                      # 启动所有服务（后台常驻）
#   scripts/local_deploy.sh --stop               # 停止所有服务
#   scripts/local_deploy.sh --status             # 查看服务状态
#   scripts/local_deploy.sh --restart            # 重启所有服务
#
# 启动的服务:
#   1. nl_query_server.py  (9788) — 自然语言查询 FastAPI
#   2. 可选: CBM MCP server (stdio) — 手动启动

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CBM_SRC="$ROOT/open_source/codebase-memory-mcp"
cd "$ROOT"

CBM="$CBM_SRC/build/c/codebase-memory-mcp"
NL_SERVER="$ROOT/src/nl_query_server.py"
NL_PORT="${NL_PORT:-9788}"
PID_DIR="${PID_DIR:-/tmp/cbm-deploy}"
export CBM_CACHE_DIR="${CBM_CACHE_DIR:-/var/lib/codebase-memory-mcp}"

mkdir -p "$PID_DIR" "$CBM_CACHE_DIR"

# ── 函数 ────────────────────────────────────────────────────────────

is_running() {
    local pidfile="$1"
    [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null
}

do_start() {
    echo "══════════════════════════════════════════"
    echo "  codebase-memory-mcp 部署"
    echo "══════════════════════════════════════════"
    echo ""

    # 1. CBM 二进制检查
    if [ ! -x "$CBM" ]; then
        echo "→ 构建 CBM 二进制..."
        make -C "$CBM_SRC" -f Makefile.cbm cbm
    fi
    echo "✓ CBM 二进制: $CBM ($($CBM --version 2>/dev/null | awk '{print $2}'))"

    # 2. 自然语言查询服务 (FastAPI, 9788)
    echo ""
    echo "→ 启动 NL 查询服务 (端口 $NL_PORT)..."
    if is_running "$PID_DIR/nl_server.pid"; then
        echo "  已在运行 (pid $(cat "$PID_DIR/nl_server.pid"))"
    else
        nohup python3 "$NL_SERVER" --port "$NL_PORT" \
            > "$PID_DIR/nl_server.log" 2>&1 &
        echo $! > "$PID_DIR/nl_server.pid"
        sleep 3
        if is_running "$PID_DIR/nl_server.pid"; then
            echo "  ✓ 已启动 (pid $(cat "$PID_DIR/nl_server.pid"))"
        else
            echo "  ✗ 启动失败，查看日志: $PID_DIR/nl_server.log"
            cat "$PID_DIR/nl_server.log" | tail -5
            return 1
        fi
    fi

    # 3. 列出现有项目
    echo ""
    echo "→ 已索引项目:"
    curl -s "http://localhost:$NL_PORT/projects" 2>/dev/null \
        | python3 -c "import sys,json; d=json.load(sys.stdin); [print(f'   {p[\"name\"]:<30} {p[\"nodes\"]:>8} nodes') for p in d.get('projects',[])]" 2>/dev/null || echo "   (服务未响应)"

    # 4. 提示
    echo ""
    echo "══════════════════════════════════════════"
    echo "  部署完成"
    echo "══════════════════════════════════════════"
    echo ""
    echo "  端点:"
    echo "    NL 查询:   http://localhost:$NL_PORT/query  (POST)"
    echo "    Swagger:   http://localhost:$NL_PORT/docs"
    echo "    健康检查:  http://localhost:$NL_PORT/health"
    echo ""
    echo "  查询示例:"
    echo "    curl -X POST http://localhost:$NL_PORT/query \\"
    echo "      -H 'Content-Type: application/json' \\"
    echo "      -d '{\"q\":\"数据库连接初始化\",\"project\":\"data-hunan-bms\"}'"
    echo ""
    echo "    python3 tools/code_search.py \"数据库连接初始化\" -p data-hunan-bms"
    echo ""
    echo "  停止:  scripts/local_deploy.sh --stop"
    echo "  状态:  scripts/local_deploy.sh --status"
}

do_stop() {
    echo "→ 停止所有服务..."
    for svc in nl_server; do
        if is_running "$PID_DIR/${svc}.pid"; then
            pid=$(cat "$PID_DIR/${svc}.pid")
            kill "$pid" 2>/dev/null && echo "  ✓ 已停止 $svc (pid $pid)" || echo "  ✗ 停止 $svc 失败"
            rm -f "$PID_DIR/${svc}.pid"
        else
            echo "  - $svc 未运行"
        fi
    done
    # 清理可能残留的 uvicorn 进程
    pkill -f "nl_query_server.py" 2>/dev/null || true
    echo "  完成"
}

do_status() {
    echo "══════════════════════════════════════════"
    echo "  服务状态"
    echo "══════════════════════════════════════════"
    for svc in nl_server; do
        if is_running "$PID_DIR/${svc}.pid"; then
            echo "  ✓ $svc: 运行中 (pid $(cat "$PID_DIR/${svc}.pid"))"
        else
            echo "  ✗ $svc: 未运行"
        fi
    done
    echo ""
    if is_running "$PID_DIR/nl_server.pid"; then
        echo "  NL 查询服务:"
        curl -s "http://localhost:$NL_PORT/health" 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "    (无响应)"
    fi
}

# ── 主逻辑 ──────────────────────────────────────────────────────────

case "${1:-}" in
    --stop)   do_stop ;;
    --status) do_status ;;
    --restart) do_stop; sleep 2; do_start ;;
    *)        do_start ;;
esac
