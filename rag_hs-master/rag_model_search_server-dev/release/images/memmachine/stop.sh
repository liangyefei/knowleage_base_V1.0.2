#!/usr/bin/env bash
# =============================================================================
# MemMachine 服务停止脚本
#
# 职责：
#   1. 停止 postgres + neo4j + memmachine 三容器
#   2. 可选 --clean 删除数据卷（PostgreSQL、Neo4j、日志、向量索引）
#
# 用法：
#   bash stop.sh           # 停止容器（保留数据卷）
#   bash stop.sh --clean   # 停止并删除数据卷（⚠️ 清空所有记忆数据）
#
# 等效手动命令：
#   docker compose down            # 仅停止
#   docker compose down -v         # 停止并删除卷
# =============================================================================

set -euo pipefail

cd "$(dirname "$0")"

# ── 颜色输出 ─────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warning() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1" >&2; exit 1; }

# ── 解析参数 ─────────────────────────────────────────────────────────
CLEAN=false
case "${1:-}" in
    --clean)
        CLEAN=true
        ;;
    -h|--help)
        echo "用法: bash stop.sh [--clean]"
        echo "  (无参数)  停止容器，保留数据卷"
        echo "  --clean   停止并删除数据卷（⚠️ 清空所有记忆数据）"
        exit 0
        ;;
    "")
        ;;
    *)
        error "未知参数: $1"
        ;;
esac

# ── 检测 docker compose ──────────────────────────────────────────────
if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    error "Docker Compose 未安装"
fi

# ── 停止服务 ─────────────────────────────────────────────────────────
if [ "$CLEAN" = "true" ]; then
    warning "⚠️ 即将删除所有数据卷（PostgreSQL、Neo4j、向量索引、日志）"
    warning "⚠️ 所有记忆数据将被永久清除，无法恢复"
    read -p "确认删除？输入 yes 继续: " confirm
    if [ "$confirm" != "yes" ]; then
        info "已取消，数据未删除"
        exit 0
    fi
    info "停止服务并删除数据卷..."
    $COMPOSE_CMD down -v
    success "服务已停止，数据卷已删除"
    echo
    info "重新部署：bash deploy.sh"
else
    info "停止 MemMachine 服务（保留数据卷）..."
    $COMPOSE_CMD down
    success "服务已停止，数据卷保留"
    echo
    info "重新启动：bash deploy.sh --no-build"
    info "清理数据：bash stop.sh --clean"
fi
