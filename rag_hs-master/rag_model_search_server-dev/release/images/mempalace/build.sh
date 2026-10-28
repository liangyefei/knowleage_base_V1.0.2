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
