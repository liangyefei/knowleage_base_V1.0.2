#!/usr/bin/env bash
# 验证：文档 ↔ 代码双向检索（CBM）
#
# 通过 env var 覆盖：
#   CBM_PROJECT=xxx        项目名（默认 projects-Hi3516CV610）
#   CBM_DOC_FILTER=xxx     文档名/路径过滤（默认 ISP，匹配 ISP 开发参考文档）
#   CBM_FUNC_FILTER=xxx    函数名过滤（默认 memset_s）
#
# 设为空字符串可跳过对应过滤，显示全部。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${CBM_PROJECT:-projects-Hi3516CV610}"
DOC_FILTER="${CBM_DOC_FILTER:-ISP}"
FUNC_FILTER="${CBM_FUNC_FILTER:-memset_s}"

ARGS=(-p "$PROJECT" --code)
[ -n "$DOC_FILTER" ]  && ARGS+=(--doc "$DOC_FILTER")
[ -n "$FUNC_FILTER" ] && ARGS+=(--func "$FUNC_FILTER")

exec python3 "$SCRIPT_DIR/../tests/test_doc_code_bidirect.py" "${ARGS[@]}"
