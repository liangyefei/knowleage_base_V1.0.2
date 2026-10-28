#!/usr/bin/env bash
# Dual-MCP container entrypoint.
# Phase 0: discover projects (built-in + mounted + EXTRA_PROJECTS env).
# Phase 1: idempotently index each project in CBM and codegraph.
# Phase 2: start CBM daemon + one codegraph daemon per project (background).
# Phase 3: exec the CBM NL HTTP server (foreground, becomes PID 2 under tini).

set -euo pipefail

CBM_BIN=/opt/codebase-memory-mcp/build/c/codebase-memory-mcp
NL_SERVER=/opt/codebase-memory-mcp/scripts/nl_query_server.py

# Large projects can keep the codegraph main thread busy >60s; the watchdog
# would kill the indexer mid-stride (we hit this on Hi3516CV610). Disable it.
export CODEGRAPH_NO_WATCHDOG=1

# ── Phase 0: project discovery ─────────────────────────────────────────

PROJECTS=()

# Built-in (always present in image, useful for dogfooding / self-query)
for path in /opt/codebase-memory-mcp /opt/codegraph; do
  [ -d "$path" ] && PROJECTS+=("$path")
done

# Mounted projects: every subdir of /projects/ is treated as one project.
# Hidden dirs (starting with .) are skipped.
# 上传项目目录（/projects/<kbId>/code/）由 RAG upload_project 接口经
# POST /projects (name=<kbId>) 建索引，这里跳过，避免重复索引（projects-<kbId>）。
if [ -d /projects ]; then
  while IFS= read -r path; do
    [ -d "${path}/code" ] && continue
    PROJECTS+=("$path")
  done < <(find /projects -mindepth 1 -maxdepth 1 -type d ! -name '.*' | sort)
fi

# Extra explicit paths via env var (space-separated).
if [ -n "${EXTRA_PROJECTS:-}" ]; then
  for path in $EXTRA_PROJECTS; do
    [ -d "$path" ] && PROJECTS+=("$path")
  done
fi

mkdir -p "${CBM_CACHE_DIR:-/var/lib/codebase-memory-mcp}" /var/log

# EFS(SFS Turbo) 挂载点属主可能是非 root（PVC 目录由其他 uid 预创建）。
# CBM 的 cache-private 安全校验要求缓存目录树属主=euid 且 mode=0700，
# 否则 daemon/索引直接失败（"ancestry component validation failed"）。
# SFS Turbo 默认 no_root_squash，root 可 chown；失败时静默继续（如遇
# root_squash 的存储，需换 EVS 或预先在存储侧修目录属主）。
chown -R root:root "${CBM_CACHE_DIR:-/var/lib/codebase-memory-mcp}" 2>/dev/null || true
chmod 0700 "${CBM_CACHE_DIR:-/var/lib/codebase-memory-mcp}" 2>/dev/null || true

# Derive CBM's auto project name: <parent_dir_basename>-<project_basename>
cbm_project_name() {
  local path="$1"
  local parent
  parent="$(basename "$(dirname "$path")")"
  echo "${parent}-$(basename "$path")"
}

echo "======================================================"
echo "  dual-mcp entrypoint"
echo "  CBM_CACHE_DIR=${CBM_CACHE_DIR:-/var/lib/codebase-memory-mcp}"
echo "  NL_PORT=${NL_PORT:-9788}"
echo "  CODEGRAPH_NO_WATCHDOG=$CODEGRAPH_NO_WATCHDOG"
echo "  Projects discovered: ${#PROJECTS[@]}"
for p in "${PROJECTS[@]}"; do echo "    - $p"; done
echo "======================================================"

# ── Phase 1a: CBM indexing ──────────────────────────────────────────────

for path in "${PROJECTS[@]}"; do
  name="$(cbm_project_name "$path")"
  if "$CBM_BIN" cli --json list_projects 2>/dev/null | grep -q "\"name\":\"${name}\""; then
    echo "[cbm] already indexed: ${name}, skipping"
  else
    echo "[cbm] indexing ${name} (path=${path}) ..."
    if "$CBM_BIN" cli --json index_repository "{\"repo_path\":\"${path}\"}" \
        >/var/log/cbm-index-${name}.log 2>&1; then
      echo "[cbm] indexed ${name}"
    else
      echo "[cbm] WARN: index of ${name} had failures — see /var/log/cbm-index-${name}.log"
    fi
  fi
done

# ── Phase 1b: codegraph init ────────────────────────────────────────────

for path in "${PROJECTS[@]}"; do
  name="$(basename "$path")"
  if [ -f "${path}/.codegraph/codegraph.db" ]; then
    echo "[codegraph] already indexed at ${path}, skipping"
  else
    echo "[codegraph] init ${path} ..."
    if (cd "${path}" && codegraph init .) \
        >/var/log/cg-init-${name}.log 2>&1; then
      echo "[codegraph] initialized ${name}"
    else
      echo "[codegraph] WARN: init of ${path} failed — see /var/log/cg-init-${name}.log"
    fi
  fi
done

# ── Phase 1c: CN→EN 查询词表抽取（LLM，幂等，可选）─────────────────────
# 配了 DASHSCOPE_API_KEY 时，为还没有 .cbm-glossary.yaml 的项目后台抽取
# 中文查询词表（供 search_graph 中文展开用）；逐个串行、失败静默，
# 不阻塞也不影响启动。词表生成后跨重启复用，通常只有新项目会触发。
if [ -n "${DASHSCOPE_API_KEY:-}" ]; then
  # setsid -f 同 Phase 2：防 exec 后子 shell 退出变 zombie
  setsid -f bash -c '
    for path in "$@"; do
      [ -f "${path}/.cbm-glossary.yaml" ] && continue
      python3 /opt/codebase-memory-mcp/scripts/glossary_extract.py "${path}" \
          >>/var/log/glossary-extract.log 2>&1
    done
    echo "[glossary] all done" >>/var/log/glossary-extract.log
  ' _ "${PROJECTS[@]}" &
fi

# ── Phase 2: start daemons ──────────────────────────────────────────────

# CBM daemon: persistent coordinator for shared index.
echo "[cbm] starting daemon"
if "$CBM_BIN" daemon start >/var/log/cbm-daemon.log 2>&1; then
  echo "[cbm] daemon started"
else
  echo "[cbm] WARN: daemon start failed — see /var/log/cbm-daemon.log"
fi

# codegraph MCP daemons: one per project, pre-warmed.
# setsid -f 强制 double-fork：daemon 挂到 tini(pid 1) 名下——Phase 3 的
# exec 会把本 bash 变成 nl_query_server，直接 & 的后台 job 会变成它的
# 子进程，退出后无人 wait() 即成 zombie（曾累计 51 个）。
for path in "${PROJECTS[@]}"; do
  name="$(basename "$path")"
  echo "[codegraph] starting daemon for ${name}"
  setsid -f codegraph serve --mcp --path "${path}" \
      </dev/null >/var/log/cg-${name}.log 2>&1 &
done

# ── Phase 2b: 自写轻量 MCP 桥（stdio → streamable-http，带守护）─────
# 替代 supergateway：其 streamableHttp 实现在慢调用响应乱序时会抛
# "No connection established"（未捕获）导致桥进程崩溃。自写桥按
# "一个 HTTP 请求 = 子进程一次完整往返" 设计，不做跨请求响应路由，
# 乱序崩溃从结构上不可能发生。子进程崩溃自动重拉，桥崩溃 2 秒守护重启。

mcp_bridge_watchdog() {
  local label="$1" logfile="$2"; shift 2
  echo "[mcp-bridge] ${label} starting (watchdog)"
  while true; do
    env "$@" python3 /opt/mcp_stdio_bridge.py >>"${logfile}" 2>&1
    echo "[mcp-bridge] ${label} exited, restarting in 2s"
    sleep 2
  done
}

# CBM MCP HTTP bridge (port $CBM_SSE_PORT, default 9800), path /mcp
if [ -n "${CBM_SSE_PORT:-}" ]; then
  mcp_bridge_watchdog "CBM MCP bridge :${CBM_SSE_PORT}" /var/log/bridge-cbm.log \
      BRIDGE_CHILD_CMD="${CBM_BIN}" \
      BRIDGE_PORT="${CBM_SSE_PORT}" \
      BRIDGE_SHARED_CHILD=1 \
      BRIDGE_MAX_INFLIGHT="${MCP_BRIDGE_MAX_INFLIGHT:-6}" \
      BRIDGE_SERVER_NAME="${MCP_CODE_SERVER_NAME:-hisi-code-mcp}" \
      BRIDGE_TOOL_DESC_PREFIX="【代码/源码】" \
      BRIDGE_TOOL_DESC_OVERRIDES='{"search_code": "【代码/源码·慎用】按文本片段 grep 项目源文件（逐文件读源码，大库上需 1-3 分钟）。查符号/函数/类型/调用链/接口请改用 search_graph（毫秒级，查代码图谱索引）。仅当搜索具体字符串/常量/报错文案等字面量时使用。"}' \
      BRIDGE_TOOL_ALLOWLIST="${MCP_CODE_TOOL_ALLOWLIST:-search_graph,trace_path,get_code_snippet,query_graph,get_graph_schema,get_architecture,search_code,detect_changes,list_projects,index_status,check_index_coverage}" \
      BRIDGE_LOG=/var/log/bridge-cbm-inner.log &
fi

# codegraph MCP HTTP bridge: single bridge, multi-project dynamic mode.
# The project is picked per call via the `projectPath` tool parameter (or the
# client's rootUri at initialize); codegraph opens project DBs lazily.
# CG_SSE_BRIDGES is a non-empty on/off switch; the port is $CG_SSE_PORT_START.
if [ -n "${CG_SSE_BRIDGES:-}" ]; then
  mcp_bridge_watchdog "codegraph bridge (multi-project) :${CG_SSE_PORT_START:-9801}" /var/log/bridge-cg.log \
      BRIDGE_CHILD_CMD_ARGS="codegraph serve --mcp" \
      BRIDGE_PORT="${CG_SSE_PORT_START:-9801}" \
      BRIDGE_SHARED_CHILD=1 \
      BRIDGE_POOL_SIZE="${MCP_CG_POOL_SIZE:-8}" \
      BRIDGE_MAX_INFLIGHT="${MCP_CG_MAX_INFLIGHT:-4}" \
      BRIDGE_SERVER_NAME="${MCP_CODE_SERVER_NAME:-hisi-code-mcp}" \
      BRIDGE_TOOL_DESC_PREFIX="【代码/源码】" \
      BRIDGE_LOG=/var/log/bridge-cg-inner.log &
fi

# ── Phase 3: foreground NL server (becomes main process) ────────────────

# 本地盘方案：CBM db 在 emptyDir（CBM_CACHE_DIR 指本地，启动时由 deployment
# command 从 EFS 全量复制），cbm_sync 每 600s 把本地写回 EFS 持久化
# （sqlite backup API / mtime 增量，不回删）。文件缺失时静默跳过（非本地盘部署）。
if [ -f /opt/cbm_sync.py ]; then
  echo "[cbm] starting cbm_sync (local→EFS 回传)"
  # setsid -f 同 Phase 2：防 cbm_sync 退出后变 nl_query_server 的 zombie
  setsid -f python3 /opt/cbm_sync.py >> /var/log/cbm-sync.log 2>&1 &
fi

echo "[cbm] starting NL server on port ${NL_PORT:-9788}"
exec python3 "${NL_SERVER}" --port "${NL_PORT:-9788}"
