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
if [ -d /projects ]; then
  while IFS= read -r path; do
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

# ── Phase 2: start daemons ──────────────────────────────────────────────

# CBM daemon: persistent coordinator for shared index.
echo "[cbm] starting daemon"
if "$CBM_BIN" daemon start >/var/log/cbm-daemon.log 2>&1; then
  echo "[cbm] daemon started"
else
  echo "[cbm] WARN: daemon start failed — see /var/log/cbm-daemon.log"
fi

# codegraph MCP daemons: one per project, pre-warmed.
for path in "${PROJECTS[@]}"; do
  name="$(basename "$path")"
  echo "[codegraph] starting daemon for ${name}"
  codegraph serve --mcp --path "${path}" \
      </dev/null >/var/log/cg-${name}.log 2>&1 &
done

# ── Phase 2b: supergateway streamable-http bridges (stdio → HTTP) ──────
# 用 streamableHttp 而非 sse：支持多客户端并发，避免 single-transport crash。

# CBM MCP HTTP bridge (port $CBM_SSE_PORT, default 9800), path /mcp
if [ -n "${CBM_SSE_PORT:-}" ]; then
  echo "[supergateway] CBM MCP HTTP bridge on port ${CBM_SSE_PORT} (path /mcp)"
  supergateway --stdio "${CBM_BIN}" \
      --outputTransport streamableHttp \
      --streamableHttpPath /mcp \
      --host 0.0.0.0 --port "${CBM_SSE_PORT}" \
      </dev/null >/var/log/supergateway-cbm.log 2>&1 &
fi

# codegraph MCP HTTP bridges: one per path in $CG_SSE_BRIDGES (space-separated).
# Ports allocated sequentially from $CG_SSE_PORT_START (default 9801), path /mcp.
if [ -n "${CG_SSE_BRIDGES:-}" ]; then
  cg_port="${CG_SSE_PORT_START:-9801}"
  for path in $CG_SSE_BRIDGES; do
    name="$(basename "$path")"
    echo "[supergateway] codegraph MCP HTTP bridge for ${name} on port ${cg_port} (path /mcp)"
    supergateway --stdio "codegraph serve --mcp --path ${path}" \
        --outputTransport streamableHttp \
        --streamableHttpPath /mcp \
        --host 0.0.0.0 --port "$cg_port" \
        </dev/null >/var/log/supergateway-cg-${name}.log 2>&1 &
    cg_port=$((cg_port + 1))
  done
fi

# ── Phase 3: foreground NL server (becomes main process) ────────────────

echo "[cbm] starting NL server on port ${NL_PORT:-9788}"
exec python3 "${NL_SERVER}" --port "${NL_PORT:-9788}"
