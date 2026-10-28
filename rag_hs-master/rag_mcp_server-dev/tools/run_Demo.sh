#!/usr/bin/env bash
# Demo: 自然语言 → 知识图谱 + 源码
#
# 项目名通过 env var 覆盖：CBM_PROJECT=xxx ./run_Demo.sh
# 默认 projects-Hi3516CV610（容器内典型项目）
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${CBM_PROJECT:-projects-Hi3516CV610}"
exec python3 "$SCRIPT_DIR/../tests/e2e_test.py" --demo "tdes init" -p "$PROJECT" -n 2 --code --graph-json
