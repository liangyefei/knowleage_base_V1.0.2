# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**codebase-memory-mcp** is a pure C code intelligence engine that indexes repositories into a SQLite-backed knowledge graph. It ships as a single static binary with no dependencies. Indexed graphs are queried via 15 MCP tools or a Cypher query interface. The project was rewritten from Go to pure C in v0.5.0 — Go PRs cannot be merged.

## Build & Test Commands

All commands use the makefile `Makefile.cbm` (not `make` directly).

```bash
# Build production binary → build/c/codebase-memory-mcp
make -f Makefile.cbm cbm
# Or via the canonical build script (also handles --with-ui, --version, --arch)
scripts/build.sh

# Run all tests (ASan + UBSan, ~2040 cases, clean build first)
scripts/test.sh

# Run a single test suite incrementally (seconds, not minutes)
scripts/test.sh --suites daemon_ipc
scripts/test.sh --suites "arena hash_table"
# List available suite names:
build/c/test-runner --list-suites

# Run focused test suites directly (no clean build, faster iteration)
make -f Makefile.cbm test-focused TEST_SUITES="arena hash_table"

# Foundation-only tests (fast, no extraction/grammar/LSP)
make -f Makefile.cbm test-foundation

# ThreadSanitizer (data-race gate)
scripts/test.sh --tsan

# TSan focused: make -f Makefile.cbm test-tsan (runs the full TSan safelist)

# Bug-reproduction suite (RED by design, non-gating)
make -f Makefile.cbm test-repro

# Lint (clang-tidy + cppcheck + clang-format + NOLINT audit)
scripts/lint.sh            # full (local)
scripts/lint.sh --ci       # CI mode (no clang-tidy)

# Build with embedded graph UI (requires Node.js 22+)
make -f Makefile.cbm cbm-with-ui

# Security audit (requires production binary first)
make -f Makefile.cbm security
```

### Running a focused subset of tests in a single suite

```
scripts/test.sh --suites daemon_ipc
```

Or for a single test case within a suite, use `--suites` with an env var filter:
```
CBM_TEST_CASE=topic_lifecycle scripts/test.sh --suites mcp
```

## Architecture

### Source Layout

```
src/
  foundation/     Arena allocator, hash table, string utils, platform compat, diagnostics
  store/          SQLite graph store (WAL mode, FTS5) — opaque handle, CRUD, search, traversal
  cypher/         Cypher query → SQL translation (MATCH/WHERE/RETURN subset)
  mcp/            MCP server (JSON-RPC 2.0 over stdio, 14+ tools), index supervisor
  pipeline/       Multi-pass indexing pipeline (the core indexing logic)
  discover/       File discovery with gitignore support, language detection
  watcher/        Git-based background auto-sync (file watcher)
  daemon/         Per-account daemon for shared index coordination across agent sessions
  cli/            CLI subcommands (install, config, agent profile detection)
  ui/             Graph visualization HTTP server (first-party httpd on port 9749)
  simhash/        MinHash similarity computation
  semantic/       AST profiling, semantic embeddings, RoTSQ
  git/            Git context tracking
  graph_buffer/   In-memory graph buffer for pipeline write staging
  traces/         OpenTelemetry trace export
  main.c          Entry point — MCP server, CLI dispatcher, daemon modes
internal/cbm/     Tree-sitter AST extraction (64 languages, vendored C grammars, LSP resolvers)
vendored/         sqlite3, yyjson, mimalloc, xxhash, tre (regex), nomic (embedding vectors)
tests/            All C test files (one binary, suite-based runner)
graph-ui/         React/Three.js frontend for graph visualization
```

### Indexing Pipeline

The pipeline is the heart of the system. It runs multiple passes over discovered source files:

1. **Structure** — Project/Folder/Package/File nodes
2. **Definitions** — Extract definitions, build FQN registry
3. **Imports** — Resolve import edges
4. **Calls** — Call resolution via registry + LSP type analysis
5. **Usages** — Usage/type_ref edges
6. **Semantic** — Inherits/decorates/implements edges
7. **Post passes** — Tests, communities (Leiden), HTTP routes, config links, git history, similarity (SimHash/MinHash), semantic embeddings, cross-repo links

Passes are in `src/pipeline/pass_*.c`. The orchestrator is `src/pipeline/pipeline.c`. Each pass reads from the graph buffer or store and writes nodes/edges.

### Graph Store

SQLite in WAL mode with FTS5 for full-text search. The store is an opaque handle (`cbm_store_t`). Key concepts:
- **Nodes** — Functions, Classes, Methods, Modules, Files, HTTP Routes, Resources (K8s)
- **Edges** — CALLS, IMPORTS, INHERITS, HTTP_CALLS, SIMILAR_TO, SEMANTICALLY_RELATED, CONFIGURES, etc.
- **Projects** — Named partitions within a store
- Search uses BM25 (FTS5) + regex + glob filtering
- Architecture queries produce structured summaries (languages, entry points, hotspots, clusters)

### Language Support (Two Layers)

1. **Tree-sitter extraction** (`internal/cbm/`): Grammar loading in `lang_specs.c`, function/call/import extraction in `extract_*.c`
2. **Pipeline passes** (`src/pipeline/`): Call resolution, HTTP route linking, etc.

**Infra-pass pattern** for Dockerfiles, K8s manifests, Kustomize: no new tree-sitter grammar. Instead, add a detection helper in `pass_infrascan.c`, a custom extractor in `internal/cbm/extract_k8s.c`, register the pass in `pipeline.c`, and add tests in `test_pipeline.c`.

### MCP Server

Implements JSON-RPC 2.0 over stdio with support for both newline-delimited and Content-Length framed transports. Tool dispatch is in `src/mcp/mcp.c`. The index supervisor (`src/mcp/index_supervisor.c`) manages isolated worker subprocesses for crash/hang-safe indexing.

### Daemon Architecture

A per-account daemon (`src/daemon/`) coordinates shared indices across multiple agent sessions. Long-lived MCP frontends connect to it via a local transport (IPC on POSIX, named pipe on Windows). One-shot CLI tool calls run in isolated local servers without creating a daemon.

### Test Framework

All C tests compile into a single binary (`build/c/test-runner`) with suite-based runner. Tests use a custom framework defined in `tests/test_framework.h` and `tests/test_helpers.h`. Each `tests/test_*.c` file is a suite that auto-registers via the `TEST(name)` macro. The framework supports `SKIP_PLATFORM` for platform-specific exclusions.

Bug reproductions live in `tests/repro/` with their own runner (`test-repro-runner`) — these are RED by design (tracking open bugs).

## Key Conventions

- **C11 standard** for C, **C++14** for the preprocessor (`internal/cbm/preprocessor.cpp`)
- **Conventional commits**: `type(scope): description` — types: feat, fix, test, refactor, perf, docs, chore
- **DCO required** on every commit: `git commit -s` (enforced by CI)
- **No NOLINT** except `NOLINT(misc-no-recursion)` on functions whitelisted in `src/foundation/recursion_whitelist.h`
- **No skipped tests** — every test must pass or fail (enforced by `scripts/check-no-test-skips.sh`)
- **Pre-commit hooks**: `scripts/install-git-hooks.sh` or `git config core.hooksPath scripts/hooks`
- **Allocator**: mimalloc as global allocator in production builds; test builds use CRT+ASan
- Productions builds use `-O2`, tests use `-g -O1` with ASan+UBSan
- New `system()`, `popen()`, `fork()`, or network calls must be added to `scripts/security-allowlist.txt`
- MCP tool surface changes, new pipeline passes, build system changes, and new dependencies all require prior design discussion in an issue
- Keep PRs under 500 lines, one issue per PR
