#!/usr/bin/env bash
# =============================================================================
# MemMachine 本地部署脚本
#
# 职责：
#   1. 检查 Docker / Docker Compose 依赖
#   2. 从 .env.example 生成 .env（首次运行）
#   3. 构建 MemMachine 镜像并启动 postgres + neo4j + memmachine 三服务
#   4. 等待健康检查通过
#   5. 打印服务访问入口
#
# 等效手动命令（在本目录下执行）：
#
#   # 1. 生成 .env（首次）
#   cp .env.example .env
#
#   # 2. 构建镜像（多阶段：builder 安装 uv 依赖 → final 复制 .venv + NLTK 数据）
#   docker compose build
#
#   # 3. 启动三服务（postgres + neo4j + memmachine）
#   docker compose up -d
#
#   # 4. 健康检查
#   curl -fsS http://localhost:8080/api/v2/health
#
#   # 5. 查看日志
#   docker compose logs -f memmachine
#
#   # 6. 停止（见 stop.sh）
#   docker compose down
#
# 用法：
#   bash deploy.sh          # 构建并启动
#   bash deploy.sh --no-build  # 不重新构建，直接启动
#   bash deploy.sh --gpu       # 构建 GPU 版本镜像
#
# 停止：bash stop.sh
# =============================================================================

set -euo pipefail

# 切到本脚本所在目录
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
NO_BUILD=false
GPU_FLAG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build)
            NO_BUILD=true
            shift
            ;;
        --gpu)
            GPU_FLAG="--build-arg GPU=true"
            export GPU=true
            export IMAGE_TAG=gpu
            shift
            ;;
        -h|--help)
            echo "用法: bash deploy.sh [--no-build] [--gpu]"
            echo "  --no-build  不重新构建镜像，直接启动已有镜像"
            echo "  --gpu       构建 GPU 版本镜像（含 sentence-transformers 本地 embedding）"
            exit 0
            ;;
        *)
            error "未知参数: $1"
            ;;
    esac
done

# ── 1. 检查依赖 ──────────────────────────────────────────────────────
info "检查 Docker 依赖..."
if ! command -v docker >/dev/null 2>&1; then
    error "Docker 未安装，请先安装 Docker"
fi

# 检测 docker compose（v2 插件）或 docker-compose（v1 独立二进制）
if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    error "Docker Compose 未安装，请先安装 Docker Compose"
fi
success "Docker 和 Docker Compose 已就绪"

# ── 2. 生成 .env（首次运行）─────────────────────────────────────────
if [ ! -f ".env" ]; then
    warning ".env 文件不存在，从 .env.example 复制..."
    if [ -f ".env.example" ]; then
        cp .env.example .env
        success "已创建 .env 文件"
        info "如需自定义端口/凭证，请编辑 .env 后重新运行本脚本"
    else
        error ".env.example 文件不存在，请检查部署目录完整性"
    fi
else
    success ".env 文件已存在"
fi

# ── 3. 检查 configuration.yml ────────────────────────────────────────
if [ ! -f "configuration.yml" ]; then
    error "configuration.yml 不存在，请检查部署目录完整性"
fi
success "configuration.yml 已存在"

# ── 4. 构建镜像 ──────────────────────────────────────────────────────
if [ "$NO_BUILD" = "false" ]; then
    info "构建 MemMachine 镜像（多阶段构建，首次较慢，约 5-15 分钟）..."
    # shellcheck disable=SC2086
    $COMPOSE_CMD build $GPU_FLAG memmachine || error "镜像构建失败"
    success "镜像构建完成"
else
    info "跳过构建（--no-build）"
fi

# ── 5. 启动服务 ──────────────────────────────────────────────────────
info "启动 postgres + neo4j + memmachine 三服务..."
$COMPOSE_CMD up -d || error "服务启动失败"
success "服务已启动"

# ── 6. 等待健康检查 ──────────────────────────────────────────────────
info "等待服务健康检查通过（最多 180s）..."

# 等待 PostgreSQL
wait_for_container_health() {
    local container_name="$1"
    local max_wait="$2"
    local waited=0
    while [ $waited -lt $max_wait ]; do
        local status
        status=$(docker inspect --format '{{.State.Health.Status}}' "${container_name}" 2>/dev/null || echo "starting")
        case "$status" in
            healthy)
                echo
                success "${container_name} 健康检查通过 ✓"
                return 0
                ;;
            unhealthy)
                echo
                error "${container_name} 健康检查失败（unhealthy），查看日志：${COMPOSE_CMD} logs ${container_name##*-}"
                ;;
            *)
                printf "."
                sleep 3
                waited=$((waited + 3))
                ;;
        esac
    done
    echo
    warning "${container_name} 在 ${max_wait}s 内未就绪（最后状态: $(docker inspect --format '{{.State.Health.Status}}' "${container_name}" 2>/dev/null || echo unknown)）"
    return 1
}

echo
wait_for_container_health "memmachine-postgres" 120 || true
wait_for_container_health "memmachine-neo4j" 120 || true

# MemMachine 启动较慢（需初始化数据库连接、加载模型配置），给足 180s
wait_for_container_health "memmachine-app" 180 || {
    warning "MemMachine 健康检查未通过，查看日志：${COMPOSE_CMD} logs -f memmachine"
    warning "首次启动需下载 NLTK 数据并初始化数据库，可能需要更长时间"
}

# ── 7. 直接探测 HTTP 健康端点 ────────────────────────────────────────
MEMORY_SERVER_PORT="${MEMORY_SERVER_PORT:-8080}"
info "探测 MemMachine HTTP 健康端点 http://localhost:${MEMORY_SERVER_PORT}/api/v2/health ..."
for i in $(seq 1 20); do
    if curl -fsS "http://localhost:${MEMORY_SERVER_PORT}/api/v2/health" >/dev/null 2>&1; then
        success "MemMachine HTTP 服务已就绪 ✓"
        break
    fi
    if [ "$i" -eq 20 ]; then
        warning "HTTP 健康探测 60s 内未通过，服务可能仍在初始化"
        warning "查看日志：${COMPOSE_CMD} logs -f memmachine"
    fi
    sleep 3
done

# ── 8. 打印服务信息 ──────────────────────────────────────────────────
echo
echo "=========================================="
success "🎉 MemMachine 部署完成！"
echo "=========================================="
echo
echo "服务访问入口："
echo "  📊 API 文档（Swagger UI）: http://localhost:${MEMORY_SERVER_PORT}/docs"
echo "  ❤️  健康检查:              http://localhost:${MEMORY_SERVER_PORT}/api/v2/health"
echo "  📈 Prometheus 指标:        http://localhost:${MEMORY_SERVER_PORT}/api/v2/metrics"
echo "  🗄️  Neo4j Browser:         http://localhost:${NEO4J_HTTP_PORT:-7474}"
echo
echo "数据库访问："
echo "  🐘 PostgreSQL: localhost:${POSTGRES_PORT:-5432}（用户: ${POSTGRES_USER:-memmachine}，数据库: ${POSTGRES_DB:-memmachine}）"
echo "  🔗 Neo4j Bolt:  localhost:${NEO4J_PORT:-7687}（用户: ${NEO4J_USER:-neo4j}）"
echo
echo "常用命令："
echo "  📋 查看日志:    ${COMPOSE_CMD} logs -f"
echo "  📋 仅看应用日志: ${COMPOSE_CMD} logs -f memmachine"
echo "  🛑 停止服务:    bash stop.sh"
echo "  🔄 重启服务:    ${COMPOSE_CMD} restart"
echo "  🧹 清理数据:    ${COMPOSE_CMD} down -v"
echo
echo "快速验证（创建项目 + 添加记忆）："
echo "  curl -X POST http://localhost:${MEMORY_SERVER_PORT}/api/v2/projects \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"org_id\":\"my-org\",\"project_id\":\"my-project\",\"description\":\"测试项目\"}'"
echo
