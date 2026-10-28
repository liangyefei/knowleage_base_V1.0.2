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
