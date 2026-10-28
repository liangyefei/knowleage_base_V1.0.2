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
