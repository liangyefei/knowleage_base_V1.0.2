#!/usr/bin/env bash
# package.sh — Assemble a deployment tarball WITH FULL SOURCE + pre-built binary.
#
# The package is self-contained: extract on a target host and you can BOTH run
# the pre-built binary immediately AND modify the source + rebuild
# (`make -f Makefile.cbm cbm`). Source for all 158 vendored tree-sitter grammars,
# vendored deps (sqlite3, mimalloc, yyjson, ...), tests, docs, and example chip
# data are included.
#
# Produces: dist/codebase-memory-mcp-<version>-<os>-<arch>.tar.gz
#
# Layout inside the tarball:
#   codebase-memory-mcp-<version>/
#   ├── bin/codebase-memory-mcp       # pre-built static binary (run immediately)
#   ├── src/ internal/ vendored/      # full source
#   ├── tests/ tools/                 # tests + grammar tools source
#   ├── Makefile.cbm                  # build system (rebuild with: make cbm)
#   ├── scripts/                      # build/test/lint/package/serve scripts
#   ├── chips/ regs/                  # example chip specs + register defs
#   ├── docs/                         # all documentation
#   ├── config/mcp.json.template      # MCP client config template
#   ├── README.md CLAUDE.md           # quick start + dev guide
#   └── VERSION
#
# Usage:
#   scripts/package.sh                  # build + package (source + binary)
#   scripts/package.sh --no-build       # package with existing binary
#   scripts/package.sh --binary-only    # legacy: runtime assets only, no source

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="$REPO/open_source/codebase-memory-mcp"
cd "$ROOT"

# ── Args ────────────────────────────────────────────────────────────

DO_BUILD=1
BINARY_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --no-build) DO_BUILD=0 ;;
        --binary-only) BINARY_ONLY=1 ;;
        -h|--help)
            sed -n '2,25p' "$0"
            exit 0
            ;;
    esac
done

# ── Version ─────────────────────────────────────────────────────────

VERSION="$(git -C "$ROOT" describe --tags --always 2>/dev/null || echo "dev")"
OS_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"        # linux / darwin
ARCH_TAG="$(uname -m)"                                    # x86_64 / aarch64
PKG_NAME="codebase-memory-mcp-${VERSION}-${OS_TAG}-${ARCH_TAG}"
STAGING="$ROOT/dist/$PKG_NAME"
TARBALL="$ROOT/dist/${PKG_NAME}.tar.gz"

# ── Build ───────────────────────────────────────────────────────────

BINARY="$ROOT/build/c/codebase-memory-mcp"
if [[ "$DO_BUILD" -eq 1 ]] || [[ ! -x "$BINARY" ]]; then
    echo "==> Building production binary..."
    make -f "$ROOT/Makefile.cbm" cbm
fi
if [[ ! -x "$BINARY" ]]; then
    echo "ERROR: binary not found at $BINARY" >&2
    exit 1
fi
BIN_VERSION="$("$BINARY" --version 2>/dev/null | awk '{print $2}')"
echo "==> Binary: $BINARY ($BIN_VERSION)"

# ── Assemble staging directory ──────────────────────────────────────

echo "==> Assembling $STAGING ..."
rm -rf "$STAGING"
mkdir -p "$STAGING"/{bin,config,scripts,docs}

# Binary (pre-built so the package runs without recompiling)
cp "$BINARY" "$STAGING/bin/codebase-memory-mcp"
chmod +x "$STAGING/bin/codebase-memory-mcp"
strip "$STAGING/bin/codebase-memory-mcp" 2>/dev/null || true

# ── Source tree (full, minus build artifacts) ───────────────────────
if [[ "$BINARY_ONLY" -eq 0 ]]; then
    echo "==> Copying source tree (excludes build artifacts)..."
    # tar-pipe copy: streamed, honors excludes, preserves the source as-is.
    # Excluded: build output, dist, VCS, node deps, editor caches, pyc.
    tar -C "$ROOT" -cf - \
        --exclude='build' \
        --exclude='dist' \
        --exclude='.git' \
        --exclude='node_modules' \
        --exclude='__pycache__' \
        --exclude='.vscode' \
        --exclude='.idea' \
        --exclude='*.pyc' \
        --exclude='*.o' \
        --exclude='*.a' \
        src internal vendored tests tools Makefile.cbm \
        2>/dev/null \
        | tar -C "$STAGING" -xf -

    # Optional top-level files (best-effort: skip if absent)
    for f in CLAUDE.md README.md CONTRIBUTING.md MAINTAINERS.md LICENSE \
             install.sh install.ps1; do
        [[ -f "$ROOT/$f" ]] && cp "$ROOT/$f" "$STAGING/"
    done

    # graph-ui frontend source (sans node_modules)
    if [[ -d "$ROOT/graph-ui" ]]; then
        mkdir -p "$STAGING/graph-ui"
        tar -C "$ROOT/graph-ui" -cf - --exclude='node_modules' --exclude='dist' . 2>/dev/null \
            | tar -C "$STAGING/graph-ui" -xf -
    fi
fi

# Example chip specs + register defs (runtime data + customization examples)
if [[ -d "$ROOT/chips" ]]; then
    mkdir -p "$STAGING/chips"
    cp "$ROOT"/chips/*.yaml "$STAGING/chips/" 2>/dev/null || true
fi
if [[ -d "$ROOT/regs" ]]; then
    mkdir -p "$STAGING/regs"
    cp "$ROOT"/regs/*.yaml "$STAGING/regs/" 2>/dev/null || true
fi

# Scripts (build/serve/test/install)
cp "$REPO/scripts/serve.sh" "$STAGING/scripts/" 2>/dev/null || true
cp "$REPO/scripts/test_chip_mcp.py" "$STAGING/scripts/" 2>/dev/null || true
chmod +x "$STAGING/scripts/"*.sh "$STAGING/scripts/"*.py 2>/dev/null || true

# MCP client config template
cat > "$STAGING/config/mcp.json.template" <<EOF
{
  "mcpServers": {
    "codebase-memory-mcp": {
      "type": "stdio",
      "command": "\${CBM_HOME}/bin/codebase-memory-mcp",
      "description": "Chip knowledge base — 15 graph analysis tools + REST API"
    }
  }
}
EOF

# Docs
for doc in DEPLOYMENT.md API_REFERENCE.md MCP_TOOLS_REFERENCE.md \
           CHIP_KNOWLEDGE_BASE.md CHIP_CAPABILITIES_PLAN.md FUSION_PLAN.md \
           KNOWLEDGE_BASE_ENHANCEMENT.md; do
    [[ -f "$ROOT/docs/$doc" ]] && cp "$ROOT/docs/$doc" "$STAGING/docs/"
done

# VERSION
cat > "$STAGING/VERSION" <<EOF
codebase-memory-mcp $VERSION
binary_version: $BIN_VERSION
package_date: $(date -u +%Y-%m-%dT%H:%M:%SZ)
os: $OS_TAG
arch: $ARCH_TAG
includes_source: $([[ $BINARY_ONLY -eq 0 ]] && echo yes || echo no)
EOF

# Quick-start README at package root
cat > "$STAGING/README.md" <<EOF
# codebase-memory-mcp $VERSION

Chip knowledge base — pure-C code intelligence engine with chip/IP/register
modeling, document-code linking, 15 MCP tools, and a REST API.

This package includes the FULL SOURCE (src/, internal/, vendored/, tests/) plus
a pre-built binary. You can run the binary immediately, or modify the source and
rebuild.

## Quick start (run pre-built binary)

\`\`\`bash
./bin/codebase-memory-mcp --version
./scripts/install.sh                          # optional: link to /usr/local/bin
./bin/codebase-memory-mcp cli index_repository '{\"repo_path\":\"/path/to/repo\"}'
./scripts/serve.sh                            # start MCP server
./bin/codebase-memory-mcp --ui=true --port=9749   # start REST API
\`\`\`

## Rebuild from source (after modifying)

\`\`\`bash
# Prereqs: C compiler (gcc/clang), make, zlib, git
make -f Makefile.cbm cbm                       # → build/c/codebase-memory-mcp
# Copy over the shipped binary:
cp build/c/codebase-memory-mcp bin/
\`\`\`

## Verify

\`\`\`bash
python3 scripts/test_chip_mcp.py              # MCP + chip KB smoke test
\`\`\`

See docs/DEPLOYMENT.md for the full deployment guide.
EOF

# install.sh (ships inside the package)
cat > "$STAGING/scripts/install.sh" <<'INSTALL_EOF'
#!/usr/bin/env bash
# install.sh — Install the pre-built binary to a system PATH dir.
set -euo pipefail
PKG_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BIN_SRC="$PKG_DIR/bin/codebase-memory-mcp"
INSTALL_DIR="${1:-/usr/local/bin}"

if [[ ! -x "$BIN_SRC" ]]; then
    echo "ERROR: binary not found at $BIN_SRC" >&2
    exit 1
fi

echo "==> Installing codebase-memory-mcp to $INSTALL_DIR"
if [[ ! -d "$INSTALL_DIR" ]]; then
    if [[ -w "$(dirname "$INSTALL_DIR")" ]] || [[ $EUID -eq 0 ]]; then
        mkdir -p "$INSTALL_DIR"
    else
        sudo mkdir -p "$INSTALL_DIR"
    fi
fi
if [[ -w "$INSTALL_DIR" ]] || [[ $EUID -eq 0 ]]; then
    cp "$BIN_SRC" "$INSTALL_DIR/codebase-memory-mcp"
    chmod +x "$INSTALL_DIR/codebase-memory-mcp"
    echo "==> Installed. Verify: codebase-memory-mcp --version"
else
    echo "==> Need sudo to write to $INSTALL_DIR"
    sudo cp "$BIN_SRC" "$INSTALL_DIR/codebase-memory-mcp"
    sudo chmod +x "$INSTALL_DIR/codebase-memory-mcp"
    echo "==> Installed (with sudo). Verify: codebase-memory-mcp --version"
fi

cat <<HINT

Next steps:
  • Index a repo:    codebase-memory-mcp cli index_repository '{"repo_path":"..."}'
  • Run MCP server:  codebase-memory-mcp
  • REST API:        codebase-memory-mcp --ui=true --port=9749
  • Rebuild source:  make -f Makefile.cbm cbm   (in the package dir)
HINT
INSTALL_EOF
chmod +x "$STAGING/scripts/install.sh"

# ── Tar ─────────────────────────────────────────────────────────────

echo "==> Creating $TARBALL ..."
mkdir -p "$ROOT/dist"
tar -C "$ROOT/dist" -czf "$TARBALL" "$PKG_NAME"

# ── Summary ─────────────────────────────────────────────────────────

TAR_SIZE="$(du -h "$TARBALL" | awk '{print $1}')"
FILE_COUNT="$(find "$STAGING" -type f | wc -l)"
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Package built                                           ║"
echo "╠══════════════════════════════════════════════════════════╣"
printf "║  tarball:  %s\n" "$TARBALL"
printf "║  size:     %s\n" "$TAR_SIZE"
printf "║  version:  %s (%s/%s)\n" "$VERSION" "$OS_TAG" "$ARCH_TAG"
printf "║  contents: %d files\n" "$FILE_COUNT"
printf "║  source:   %s\n" "$([[ $BINARY_ONLY -eq 0 ]] && echo 'included (rebuild with: make cbm)' || echo 'not included (--binary-only)')"
echo "╚══════════════════════════════════════════════════════════╝"
