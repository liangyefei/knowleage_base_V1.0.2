#!/usr/bin/env bash
# serve.sh — Start the codebase-memory-mcp (CBM) MCP server.
#
# Usage:
#   scripts/serve.sh                       # Start CBM MCP server (stdio)
#   scripts/serve.sh --build               # Rebuild binary first, then start
#   scripts/serve.sh --ui                  # Enable the graph visualization HTTP UI
#   scripts/serve.sh --port 9749           # Custom UI port
#   scripts/serve.sh --profile analysis    # Restricted tool surface
#   scripts/serve.sh --help
#
# The MCP server speaks JSON-RPC 2.0 over stdio. Connect from an MCP client
# (Claude Code, Cursor, etc.) or pipe JSON-RPC messages directly:
#
#   echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}' \
#       | scripts/serve.sh
#
# REST API: enable with --ui (the HTTP server, hosted in the per-account
# daemon, also serves the REST API at http://localhost:<port>/api/v1/...).
# See docs/API_REFERENCE.md for the full endpoint list.
#
# Environment variables:
#   CBM_BINARY      Override the binary path (default: build/c/codebase-memory-mcp)
#   CBM_STORE_DIR   Override the index storage directory
#   CBM_LOG_LEVEL   Log level: debug, info, warn, error (default: info)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../open_source/codebase-memory-mcp" && pwd)"
cd "$ROOT"

# ── Defaults ────────────────────────────────────────────────────────

BINARY="${CBM_BINARY:-$ROOT/build/c/codebase-memory-mcp}"
DO_BUILD=0
UI_FLAG=""
PORT_FLAG=""
PROFILE_FLAG=""

# ── Argument parsing ────────────────────────────────────────────────

usage() {
    cat <<'EOF'
Usage: scripts/serve.sh [options]

Start the codebase-memory-mcp MCP server (JSON-RPC 2.0 over stdio).

Options:
  --build              Rebuild the production binary before starting
  --ui                 Enable the graph visualization HTTP UI (port 9749)
  --port N             Set the HTTP UI port (implies --ui)
  --profile NAME       Tool surface: analysis | scout (restricted) | full (default)
  --binary PATH        Override the binary path
  -h, --help           Show this help

Environment:
  CBM_BINARY           Override binary path
  CBM_STORE_DIR        Override index storage directory
  CBM_LOG_LEVEL        debug | info | warn | error

Examples:
  scripts/serve.sh                         # default stdio MCP server
  scripts/serve.sh --build                 # rebuild then serve
  scripts/serve.sh --ui --port 8080        # serve with UI on port 8080
  scripts/serve.sh --profile analysis      # restricted read-only surface
EOF
    exit 0
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage ;;
        --build) DO_BUILD=1 ;;
        --ui) UI_FLAG="--ui=true" ;;
        --port=*) PORT_FLAG="--port=${arg#--port=}" ;;
        --port) shift_next=1 ;;
        --profile=*) PROFILE_FLAG="--tool-profile=${arg#--profile=}" ;;
        --profile) shift_next=1 ;;
        --binary=*) BINARY="${arg#--binary=}" ;;
        *)
            if [[ "${shift_next:-}" == "port" ]]; then PORT_FLAG="--port=$arg"; shift_next=""; fi
            if [[ "${shift_next:-}" == "profile" ]]; then PROFILE_FLAG="--tool-profile=$arg"; shift_next=""; fi
            ;;
    esac
done

# ── Build if requested or binary missing ────────────────────────────

if [[ "$DO_BUILD" -eq 1 ]] || [[ ! -x "$BINARY" ]]; then
    echo "==> Building codebase-memory-mcp (production binary)..." >&2
    make -f "$ROOT/Makefile.cbm" cbm
    BINARY="$ROOT/build/c/codebase-memory-mcp"
fi

if [[ ! -x "$BINARY" ]]; then
    echo "ERROR: binary not found at $BINARY" >&2
    echo "Run with --build, or: make -f Makefile.cbm cbm" >&2
    exit 1
fi

# ── Print banner ────────────────────────────────────────────────────

VERSION="$("$BINARY" --version 2>/dev/null || echo 'unknown')"

cat >&2 <<EOF
╔══════════════════════════════════════════════════════════════════╗
║          codebase-memory-mcp — MCP Server                        ║
╠══════════════════════════════════════════════════════════════════╣
║  Binary:  $BINARY
║  Version: $VERSION
║  Mode:    stdio (JSON-RPC 2.0)
$( [[ -n "$UI_FLAG" ]] && printf '║  UI:      enabled (http://localhost:%s)\n' "${PORT_FLAG#--port=:-9749}" )
$( [[ -n "$PROFILE_FLAG" ]] && printf '║  Profile: %s\n' "${PROFILE_FLAG#--tool-profile=}" )
╚══════════════════════════════════════════════════════════════════╝

Send JSON-RPC messages on stdin. Example:

  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}

Press Ctrl+C to stop.
EOF

# ── Start the server ────────────────────────────────────────────────

exec "$BINARY" $UI_FLAG $PORT_FLAG $PROFILE_FLAG
