# Dual-MCP Docker Image

A single Docker image bundling [`codebase-memory-mcp`](../../) (CBM) and [`codegraph`](../../codegraph) for **arbitrary multi-project** indexing and retrieval. Drop any project into `/projects/` (or its subdirs) and the container auto-indexes it; runtime helpers let you add/remove/query projects on the fly.

## What the container does on startup

1. **Discovers projects** from:
   - Built-in: `/opt/codebase-memory-mcp`, `/opt/codegraph` (dogfood)
   - Mounted: every subdir of `/projects/`
   - Explicit: any path in `$EXTRA_PROJECTS`
2. **Indexes each project** in both CBM (SQLite knowledge graph) and codegraph (`.codegraph/`) — idempotent, skipped if already indexed.
3. **Starts services**:
   - CBM daemon (persistent index coordinator)
   - CBM NL HTTP query service on port `9788` (foreground)
   - One codegraph MCP daemon per project (pre-warmed, background)

## Build

```bash
docker build -f /data/codebase-memory-mcp/docker/Dockerfile \
             -t dual-mcp:latest \
             /data/codebase-memory-mcp/
```

First build pulls `node:22-bookworm-slim` + Python deps. Expect ~3-5 minutes.

## Recommended run command (multi-project)

```bash
docker run -d --name dual-mcp \
  -p 9789:9788 \
  -v /data:/projects:rw \
  -v cbm-cache:/var/lib/codebase-memory-mcp \
  --restart unless-stopped \
  dual-mcp:latest
```

- `/data` mounted at `/projects` → every subdir becomes a project (Hi3516CV610, codebase-memory-mcp if you keep it there, anything else)
- `cbm-cache` named volume → CBM indexes survive `docker rm`
- codegraph indexes (`.codegraph/`) live in the mounted project dirs → survive on host

First start with N projects takes ~5 min/project for indexing. Subsequent restarts are seconds.

## Environment Variables

| Variable | Default | Effect |
|---|---|---|
| `CBM_CACHE_DIR` | `/var/lib/codebase-memory-mcp` | Where CBM stores SQLite indexes |
| `NL_PORT` | `9788` | CBM NL HTTP port (also `EXPOSE`d) |
| `EXTRA_PROJECTS` | (empty) | Space-separated extra project paths to index on top of `/projects/*` |
| `CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS` | `2147483647` (~24.8 days, Node int32 max) | Daemon idle keepalive |
| `CODEGRAPH_TELEMETRY` | `0` | codegraph anonymous telemetry off |
| `CODEGRAPH_NO_WATCHDOG` | `1` (set by entrypoint) | Disable 60s main-thread watchdog (large projects) |

## Helper Scripts (inside container)

All available via `docker exec dual-mcp <script>`:

### `list-projects`

Show every indexed project across CBM and codegraph + service status.

```bash
docker exec dual-mcp list-projects
```

### `add-project <path>` (runtime registration)

Index a new project without restarting the container.

```bash
docker exec dual-mcp add-project /projects/some-new-repo
```

Skips if already indexed, starts codegraph daemon if not running.

### `query-project <name> <query...>` (convenience CBM query)

```bash
docker exec dual-mcp query-project opt-Hi3516CV610 video init
docker exec dual-mcp query-project opt-codegraph ExtractionOrchestrator
```

### `remove-project <name_or_path>`

Delete CBM + codegraph indexes for a project, stop its daemon.

```bash
docker exec dual-mcp remove-project opt-some-old-repo
```

## Query via HTTP (from host)

```bash
# Health
curl http://localhost:9789/health

# List projects
curl http://localhost:9789/projects

# Query a specific project
curl -X POST http://localhost:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q":"video init","project":"projects-Hi3516CV610"}'

# Swagger UI
open http://localhost:9789/docs
```

The CBM project name is auto-derived as `<parent_dir>-<basename>`. For `/projects/Hi3516CV610` it's `projects-Hi3516CV610`. Run `list-projects` to confirm.

## Query via codegraph CLI (inside container)

```bash
docker exec -w /projects/Hi3516CV610 dual-mcp codegraph status
docker exec -w /projects/Hi3516CV610 dual-mcp codegraph query "viu"
docker exec -w /projects/Hi3516CV610 dual-mcp codegraph explore FunctionA FunctionB
```

## Connect from external MCP clients (stdio)

```bash
# CBM MCP
docker exec -i dual-mcp /opt/codebase-memory-mcp/build/c/codebase-memory-mcp

# codegraph MCP (specify project path)
docker exec -i dual-mcp codegraph serve --mcp --path /projects/Hi3516CV610
```

## Logs

```bash
# NL server (foreground) — main container log
docker logs dual-mcp

# Per-project indexing logs (one-shot at startup)
docker exec dual-mcp ls /var/log/

# codegraph daemon log per project
docker exec dual-mcp cat /var/log/cg-Hi3516CV610.log

# CBM daemon
docker exec dual-mcp cat /var/log/cbm-daemon.log
```

## Troubleshooting

### Container exits immediately
`docker logs dual-mcp` — the NL server failed to start. Most common cause: host port already in use. Remap with `-p <other>:9788`.

### `curl /projects` returns empty
Startup indexing hasn't finished yet. Watch logs until `[cbm] starting NL server on port 9788` appears.

### codegraph init fails / `truncated` warning
The 60s watchdog was killing large-project indexer. The entrypoint sets `CODEGRAPH_NO_WATCHDOG=1` globally; if you still see truncation, manually:
```bash
docker exec -w /projects/<name> dual-mcp codegraph index .
```

### Rebuilding after CBM source changes
The image bakes in the host's prebuilt binary. After modifying CBM source:
```bash
make -f /data/codebase-memory-mcp/Makefile.cbm cbm
docker build -f /data/codebase-memory-mcp/docker/Dockerfile \
             -t dual-mcp:latest /data/codebase-memory-mcp/
docker rm -f dual-mcp
# Re-run with same -v flags; CBM cache (named volume) preserves other projects,
# but the rebuilt CBM project will re-index on next start.
```
