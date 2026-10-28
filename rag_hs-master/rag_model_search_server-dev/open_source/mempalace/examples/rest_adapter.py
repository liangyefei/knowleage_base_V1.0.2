#!/usr/bin/env python3
"""MemPalace REST adapter — a thin FastAPI layer over the MCP tool handlers.

Why this exists
---------------
MemPalace ships an MCP server (JSON-RPC over stdio, or HTTP at ``/mcp``) and a
CLI. It deliberately ships *no* public REST API — privacy-by-architecture means
verbatim memory never leaves your machine. This adapter is the opt-in escape
hatch for when you DO want to expose search, knowledge-graph query, and diary
storage to another system over a clean RESTful interface.

It does not re-implement search. It imports the same ``tool_search`` /
``tool_kg_query`` handlers the MCP server uses, so input sanitization,
query-decontamination, transient-index retry, and scoring are identical to what
an MCP client gets. The adapter only adds:

  * RESTful GET endpoints (``/search``, ``/kg``) instead of JSON-RPC,
  * bearer-token auth (fail-closed on non-loopback binds),
  * configurable sensitive-field redaction (the headline feature), and
  * a global lock that serializes Chroma access (HNSW single-writer model).

Deployment model
----------------
Run this on the SAME machine as the palace — it is a library call, not a
network hop to MemPalace. Then put Caddy/nginx in front for TLS + access
control before exposing it to a LAN or the internet. Anyone who reaches an
authenticated ``/search`` can read verbatim memory; treat the token like a
database credential.

Install
-------
    pip install fastapi "uvicorn[standard]"

Run
---
    export MEMPALACE_REST_TOKEN="$(openssl rand -hex 32)"
    export MEMPALACE_PALACE_PATH=/path/to/palace        # optional
    python examples/rest_adapter.py                      # loopback :8777

    # or behind Caddy on a public host — bind loopback, let the proxy expose:
    MEMPALACE_REST_TOKEN=... MEMPALACE_REST_HOST=127.0.0.1 python examples/rest_adapter.py

Query
-----
    curl "http://127.0.0.1:8777/search?q=why+GraphQL&limit=5&detail=snippet"
    curl "http://127.0.0.1:8777/kg?entity=Max&direction=both" -H "Authorization: Bearer $TOKEN"

Store
-----
    curl -X POST "http://127.0.0.1:8777/store" -H "Authorization: Bearer $TOKEN" \\
         -H "Content-Type: application/json" \\
         -d '{"entry":"Decided to use GraphQL for the gateway","topic":"arch"}'

Env vars
--------
  MEMPALACE_PALACE_PATH        palace dir (defaults as usual via config)
  MEMPALACE_REST_TOKEN         bearer token; REQUIRED for non-loopback binds
                               (unless MEMPALACE_REST_NO_AUTH=1 on a trusted net)
  MEMPALACE_REST_NO_AUTH       set to 1 to run with NO auth on a network you
                               fully trust (default off; fail-closed otherwise)
  MEMPALACE_REST_HOST          bind host (default 127.0.0.1)
  MEMPALACE_REST_PORT          bind port (default 8777)
  MEMPALACE_REST_DEFAULT_DETAIL  meta | snippet | full (default snippet)
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import threading
import urllib.request
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Query, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

# --- configuration ----------------------------------------------------------

HOST = os.environ.get("MEMPALACE_REST_HOST", "127.0.0.1")
PORT = int(os.environ.get("MEMPALACE_REST_PORT", "8777"))
TOKEN = os.environ.get("MEMPALACE_REST_TOKEN", "").strip()
DEFAULT_DETAIL = os.environ.get("MEMPALACE_REST_DEFAULT_DETAIL", "snippet").strip().lower()
DEFAULT_SNIPPET_LEN = 240
# Explicit opt-out of auth for operators who have decided their network is fully
# trusted (e.g. an isolated LAN where every host is theirs). Default stays off
# so the fail-closed guard below prevents an accidental open bind on a network.
ALLOW_NO_AUTH = os.environ.get("MEMPALACE_REST_NO_AUTH", "").strip().lower() in (
    "1", "true", "yes", "on",
)
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"}


def _is_loopback(host: str) -> bool:
    return (host or "").strip().lower() in LOOPBACK_HOSTS


# Fail-closed: a non-loopback bind without a token is a foot-gun — the palace
# would be readable by anyone who can reach the port. Match MemPalace's own
# HTTP-server posture and refuse to start rather than serve unauthenticated.
# ALLOW_NO_AUTH is the deliberate escape hatch (with a loud warning below), so
# the open posture is never silent.
if not _is_loopback(HOST) and not TOKEN and not ALLOW_NO_AUTH:
    raise SystemExit(
        "MEMPALACE_REST_TOKEN is required when binding a non-loopback host "
        f"(HOST={HOST}). Anyone reachable could read verbatim memory. Set a "
        "token, or — on a network you fully trust — opt into no-auth with "
        "MEMPALACE_REST_NO_AUTH=1."
    )
if not TOKEN:
    # No token at all (loopback tinkering, or an opted-in trusted-network bind).
    # Say so loudly either way so the open posture is visible in the logs.
    reason = (
        "opted in via MEMPALACE_REST_NO_AUTH=1 on a trusted network"
        if ALLOW_NO_AUTH and not _is_loopback(HOST)
        else "loopback only"
    )
    print(
        f"WARNING: MEMPALACE_REST_TOKEN is unset — all endpoints are "
        f"UNAUTHENTICATED ({reason}).",
        flush=True,
    )


# --- handlers are imported lazily so the fail-closed check above runs first --

def _load_handlers():
    """Import the MCP tool handlers on first use.

    Importing ``mempalace.mcp_server`` installs its stdio protection and wires
    the module-level ``_config`` (which reads MEMPALACE_PALACE_PATH). We defer
    it so a misconfigured bind host fails fast at startup instead of after the
    (slow) first import.

    Returns a dict keyed by the adapter's logical operation so callers don't
    depend on a positional tuple that has to be re-numbered every time a new
    handler is wired in.
    """
    _wd_tok = _wd_begin()
    try:
        from mempalace.mcp_server import (tool_diary_write, tool_kg_query, tool_search,
                                          tool_kg_add, tool_add_drawer,
                                          tool_set_file_version,
                                          tool_delete_file_version_chunks)

        return {"search": tool_search, "kg": tool_kg_query, "diary": tool_diary_write,
                "kg_add": tool_kg_add, "add_drawer": tool_add_drawer,
                "set_file_version": tool_set_file_version,
                "delete_file_version_chunks": tool_delete_file_version_chunks}
    finally:
        _wd_end(_wd_tok)


_HANDLERS: dict | None = None
_MEMPALACE_LOCK = threading.Lock()

# patch_watchdog: 卡死看门狗——任一 mempalace 调用超过 45s 未返回即自杀退出，
# 由 K8s restartPolicy 自动重启容器恢复服务（NFS 页等待卡死时进程无法自行恢复，
# 而新进程经实测 1.7s 即可正常检索）。
import os as _os_wd
import time as _time_wd

_WD_INFLIGHT = {}
_WD_LOCK = threading.Lock()
_WD_SEQ = [0]
_WD_TIMEOUT = float(os.environ.get("MEMPALACE_WATCHDOG_TIMEOUT", "45"))


def _wd_begin():
    with _WD_LOCK:
        _WD_SEQ[0] += 1
        _WD_INFLIGHT[_WD_SEQ[0]] = _time_wd.time()
        return _WD_SEQ[0]


def _wd_end(tok):
    with _WD_LOCK:
        _WD_INFLIGHT.pop(tok, None)


def _wd_loop():
    while True:
        _time_wd.sleep(5)
        try:
            with _WD_LOCK:
                if not _WD_INFLIGHT:
                    continue
                oldest = min(_WD_INFLIGHT.values())
            if _time_wd.time() - oldest > _WD_TIMEOUT:
                _os_wd._exit(1)
        except Exception:
            pass


threading.Thread(target=_wd_loop, daemon=True).start()

# Chroma's PersistentClient / HNSW index is a single-writer resource: the MCP
# HTTP transport serializes every request with _HTTP_REQUEST_LOCK for the same
# reason. Reads are mostly safe to interleave, but the embedding model + Chroma
# client carry thread-safety caveats, so we serialize all adapter calls too.

# Multi-palace support. A caller picks a palace by an opaque id ("kb-docs",
# "alice", "team-2026"); we map it to a directory under PALACES_ROOT. The
# default (no palace param) still routes to MempalaceConfig().palace_path, so
# existing single-palace callers are unaffected. See _resolve_palace_path /
# _run_mem_palace for the per-request env-var swap.
PALACES_ROOT = os.environ.get("MEMPALACE_PALACES_ROOT", "/data/palaces")


def _resolve_palace_path(palace_id: str | None) -> str | None:
    """Map an optional palace id to a concrete palace directory.

    Returns ``None`` for "use the default palace" (no palace param), which the
    caller leaves untouched. A non-empty id is sanitized through MemPalace's own
    ``sanitize_name`` (blocks ``..`` / ``/`` / ``\\`` / null / unsafe chars) and
    rooted under PALACES_ROOT, so a caller can never escape to an arbitrary path.
    """
    if not palace_id:
        return None
    from mempalace.config import sanitize_name

    safe = sanitize_name(palace_id, "palace")  # raises ValueError on bad input
    return os.path.join(PALACES_ROOT, safe)


# --- sensitive-field redaction ----------------------------------------------

# Keys that are internal bookkeeping (scoring sort keys, parent IDs) and never
# belong in a clean REST response.
_INTERNAL_KEYS = {k for k in (
    "_sort_key", "_source_file_full", "_chunk_index", "_parent_drawer_id",
    "closet_boost", "effective_distance",
)}

# What each hit keeps in each detail level. Anything not listed is dropped.
#   meta    — metadata + score, NO verbatim text, NO source path
#   snippet — meta + a truncated prefix of the verbatim text (DEFAULT)
#   full    — everything except internal bookkeeping (incl. full verbatim text)
_SEARCH_META_FIELDS = (
    "wing", "room", "source_file", "created_at", "similarity",
    "distance", "matched_via", "context_chunks",
)
DETAIL_LEVELS = ("meta", "snippet", "full")


def _redact_search_hit(hit: dict[str, Any], detail: str, snippet_len: int) -> dict[str, Any]:
    """Apply the detail-level redaction policy to one search hit."""
    if detail == "full":
        # Keep everything MemPalace returns except internal bookkeeping. The
        # caller asked for the raw verbatim text + full source path — this is
        # the trust-the-client tier.
        return {k: v for k, v in hit.items() if k not in _INTERNAL_KEYS}

    # meta / snippet: project down to the allow-list of metadata fields so the
    # response shape is stable (no internal keys leaking through).
    out = {k: hit.get(k) for k in _SEARCH_META_FIELDS if k in hit}
    if detail == "snippet":
        text = hit.get("text") or ""
        out["text"] = text[:snippet_len] + ("…" if len(text) > snippet_len else "")
        out["text_truncated"] = len(text) > snippet_len
        out["text_total_len"] = len(text)
    # detail == "meta": deliberately omit text entirely.
    return out


def _mask_entity(value: Any) -> str:
    """Reduce an entity/fact value to a non-revealing length signal."""
    s = "" if value is None else str(value)
    if not s:
        return ""
    return s[0] + "***" if len(s) > 1 else "***"


def _redact_kg_fact(fact: dict[str, Any], detail: str) -> dict[str, Any]:
    """KG facts are derived triples (predicate + temporal validity) plus entity
    names. They are less sensitive than verbatim drawer text, but a caller that
    only needs the *shape* of an entity's relationships can ask for ``meta`` to
    mask the subject/object values and keep just the predicate + time window."""
    if detail == "full":
        return dict(fact)
    return {
        "predicate": fact.get("predicate"),
        "subject": _mask_entity(fact.get("subject")),
        "object": _mask_entity(fact.get("object")),
        "valid_from": fact.get("valid_from"),
        "valid_to": fact.get("valid_to"),
        "value_redacted": True,
    }


def _normalize_detail(detail: str | None) -> str:
    d = (detail or DEFAULT_DETAIL).strip().lower()
    if d not in DETAIL_LEVELS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"detail must be one of {DETAIL_LEVELS}, got {detail!r}",
        )
    return d


# --- auth dependency --------------------------------------------------------

def require_token(request: Request) -> None:
    """Bearer-token gate. No token configured (loopback-only) ⇒ allow."""
    if not TOKEN:
        return
    provided = request.headers.get("Authorization", "")
    # Constant-time compare — don't leak token length via timing.
    ok = len(provided) == len(f"Bearer {TOKEN}") and \
        _const_time_eq(provided, f"Bearer {TOKEN}")
    if not ok:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing or invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def _const_time_eq(a: str, b: str) -> bool:
    # secrets.compare_digest is the right primitive; keep a tiny shim so the
    # import is local and the intent reads clearly.
    import secrets

    return secrets.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


# --- app --------------------------------------------------------------------

app = FastAPI(
    openapi_url=None,  # 安全：不暴露 /openapi.json 接口清单
    title="MemPalace REST adapter",
    version="0.1.0",
    description="Read-only REST surface over MemPalace search + knowledge graph.",
)


@app.get("/healthz", include_in_schema=False)
def healthz() -> str:
    # Unauthenticated liveness probe — for the reverse proxy / orchestrator.
    return "ok"


@app.get("/")
def root() -> dict[str, Any]:
    return {
        "service": "mempalace-rest-adapter",
        "endpoints": {
            "palace": "POST /palace  (create an empty palace, idempotent)",
            "store": "POST /store  {entry, agent_name, topic, wing}",
            "search": "GET /search?q=...&limit=5&detail=snippet",
            "kg": "GET /kg?entity=...&direction=both",
        },
        "detail_levels": list(DETAIL_LEVELS),
        "auth": "bearer token required" if TOKEN else "OPEN (no auth)",
    }


def _run_mem_palace(fn, *args, palace_path: str | None = None, **kwargs) -> dict[str, Any]:
    """Call a MemPalace handler under the global lock, swallowing stdout/stderr.

    The handlers occasionally print (operator-facing status). In an API context
    that noise belongs in server logs, not on the wire, so we capture it. The
    lock serializes Chroma access (single-writer model). Result is the handler's
    return dict; if it carries an ``error`` key the caller surfaces it as 400.

    ``palace_path``: when set, temporarily points ``MEMPALACE_PALACE_PATH`` at
    the requested multi-palace directory for the duration of this call only.
    ``MempalaceConfig.palace_path`` is a property that re-reads that env var on
    every access, and the swap happens inside the serialized lock, so concurrent
    requests targeting different palaces cannot cross-contaminate. The previous
    value (or its absence) is restored in a finally, even on handler failure.

    patch_watchdog: 整个调用（含排队等锁）纳入看门狗监控——超时未返回时
    由 _wd_loop 自杀退出，K8s 自动重启容器恢复服务。
    """
    global _HANDLERS
    _wd_tok = _wd_begin()
    try:
        if _HANDLERS is None:
            _HANDLERS = _load_handlers()
        buf_out, buf_err = io.StringIO(), io.StringIO()
        saved = os.environ.get("MEMPALACE_PALACE_PATH")
        with _MEMPALACE_LOCK, contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            if palace_path:
                os.environ["MEMPALACE_PALACE_PATH"] = palace_path
            try:
                result = fn(*args, **kwargs)
            finally:
                # Restore exactly: unset if it was never set, else put it back.
                if saved is None:
                    os.environ.pop("MEMPALACE_PALACE_PATH", None)
                else:
                    os.environ["MEMPALACE_PALACE_PATH"] = saved
        return result if isinstance(result, dict) else {"value": result}
    finally:
        _wd_end(_wd_tok)




@app.get("/search")
def search(
    q: str = Query(..., min_length=1, max_length=250, description="Search keywords / question (≤250 chars)."),
    limit: int = Query(5, ge=1, le=100),
    wing: str | None = Query(None),
    room: str | None = Query(None),
    source_file: str | None = Query(None, description="Exact stored source path to filter to."),
    max_distance: float = Query(1.5, ge=0.0, le=2.0),
    context: str | None = Query(None, description="Background context (not embedded)."),
    context_chunks: int = Query(0, ge=0, le=10, description="Expand each result with +/-N neighbor chunks (complete sections)."),
    detail: str | None = Query(None, description=f"One of {DETAIL_LEVELS}. Default: {DEFAULT_DETAIL}."),
    snippet_len: int = Query(DEFAULT_SNIPPET_LEN, ge=16, le=4000),
    palace: str | None = Query(None, description="Palace id to search; omit for the default palace."),
    _auth: None = Depends(require_token),
):
    """Semantic search. Returns metadata + (optionally redacted) verbatim text."""
    handlers = _HANDLERS or _load_handlers()
    detail = _normalize_detail(detail)
    try:
        palace_path = _resolve_palace_path(palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    raw = _run_mem_palace(
        handlers["search"],
        query=q,
        limit=limit,
        wing=wing,
        room=room,
        source_file=source_file,
        max_distance=max_distance,
        context=context,
        context_chunks=context_chunks,
        palace_path=palace_path,
    )

    if "error" in raw:
        # Sanitization / validation failures from the handler → 400.
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=raw["error"])

    hits = raw.get("results", []) or []
    return {
        "query": raw.get("query", q),
        "filters": raw.get("filters"),
        "total_before_filter": raw.get("total_before_filter"),
        "count": len(hits),
        "detail": detail,
        "results": [_redact_search_hit(h, detail, snippet_len) for h in hits],
    }


@app.get("/kg")
def kg(
    entity: str = Query(..., min_length=1, description="Entity name (subject or object)."),
    direction: str = Query("both", pattern="^(outgoing|incoming|both)$"),
    as_of: str | None = Query(None, description="YYYY-MM-DD or YYYY-MM-DDTHH:MM:SSZ temporal filter."),
    detail: str | None = Query(None, description=f"One of {DETAIL_LEVELS}. Default: {DEFAULT_DETAIL}."),
    format: str = Query("facts", pattern="^(facts|graph)$",
                        description="facts=triple list; graph=nodes+edges for graph visualization plugins (always full entity names)."),
    palace: str | None = Query(None, description="Palace id to query; omit for the default palace."),
    _auth: None = Depends(require_token),
):
    """Knowledge-graph query for an entity's relationships."""
    handlers = _HANDLERS or _load_handlers()
    detail = _normalize_detail(detail)
    try:
        palace_path = _resolve_palace_path(palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    raw = _run_mem_palace(handlers["kg"], entity=entity, as_of=as_of, direction=direction, palace_path=palace_path)

    if "error" in raw:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=raw["error"])

    facts = raw.get("facts", []) or []
    if format == "graph":
        nodes: dict[str, dict] = {}
        edges: list[dict] = []
        for f in facts:
            for name in (f.get("subject"), f.get("object")):
                if name and name not in nodes:
                    # 节点类型供前端图谱插件分组着色；目前统一默认"文档"
                    nodes[name] = {"id": name, "name": name, "type": "文档"}
            edges.append({
                "source": f.get("subject"),
                "target": f.get("object"),
                "type": f.get("predicate"),
                "label": f.get("predicate"),
                "direction": f.get("direction"),
                "valid_from": f.get("valid_from"),
                "valid_to": f.get("valid_to"),
                "confidence": f.get("confidence"),
                "current": f.get("current"),
                "source_closet": f.get("source_closet"),
            })
        return {
            "entity": raw.get("entity", entity),
            "as_of": raw.get("as_of"),
            "direction": direction,
            "count": raw.get("count", len(facts)),
            "nodes": list(nodes.values()),
            "edges": edges,
        }
    return {
        "entity": raw.get("entity", entity),
        "as_of": raw.get("as_of"),
        "direction": direction,
        "count": raw.get("count", len(facts)),
        "detail": detail,
        "facts": [_redact_kg_fact(f, detail) for f in facts],
    }


class KgAddRequest(BaseModel):
    """Body for ``POST /kg`` — wraps ``tool_kg_add``.

    Explicitly writes one subject → predicate → object triple into the palace's
    knowledge graph. ``/store`` files verbatim diary entries only and does NOT
    extract entities, so this endpoint is the REST write path for graph data.
    Temporal values accept ``YYYY-MM-DD`` or ``YYYY-MM-DDTHH:MM:SSZ``.
    """

    subject: str = Field(..., min_length=1, description="Entity name (subject).")
    predicate: str = Field(..., min_length=1, description="Relationship name.")
    object: str = Field(..., min_length=1, description="Entity name or value (object).")
    valid_from: str | None = Field(None, description="Optional: when the fact became true.")
    valid_to: str | None = Field(None, description="Optional: when the fact stops being true.")
    source_closet: str | None = Field(None, description="Optional provenance: closet id.")
    source_file: str | None = Field(None, description="Optional provenance: source file.")
    source_drawer_id: str | None = Field(None, description="Optional provenance: drawer id.")
    palace: str | None = Field(None, description="Palace id to write into; omit for the default palace.")


@app.post("/kg", status_code=status.HTTP_201_CREATED)
def kg_add(
    body: KgAddRequest,
    _auth: None = Depends(require_token),
):
    """Add a fact (triple) to the knowledge graph. Query it back with GET /kg."""
    handlers = _HANDLERS or _load_handlers()
    try:
        palace_path = _resolve_palace_path(body.palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    raw = _run_mem_palace(
        handlers["kg_add"],
        subject=body.subject,
        predicate=body.predicate,
        object=body.object,
        valid_from=body.valid_from,
        valid_to=body.valid_to,
        source_closet=body.source_closet,
        source_file=body.source_file,
        source_drawer_id=body.source_drawer_id,
        palace_path=palace_path,
    )
    if not raw.get("success", False) or "error" in raw:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=raw.get("error", "kg_add failed"),
        )
    return raw


# --- lifecycle: create an (empty) palace ------------------------------------

@app.post("/palace", status_code=status.HTTP_201_CREATED)
def create_palace(
    palace: str | None = Query(None, description="Palace id to create under /data/palaces/<id>; omit for the default palace."),
    _auth: None = Depends(require_token),
):
    """Explicitly create an empty palace at the configured path.

    A palace is also created lazily on the first ``POST /store`` (diary_write's
    ``create=True``), but that path only materializes the backend once content
    is written — and the first write triggers the embedding-model download. This
    endpoint lets a caller bootstrap an empty palace up front (e.g. to provision
    a fresh volume) without writing anything, by opening the collection with
    ``create=True`` and no documents. It is idempotent: re-calling on an existing
    palace is a no-op that returns ``already_exists``.

    ``?palace=<id>`` scopes creation to a named multi-palace directory.
    """
    import os

    from mempalace.config import MempalaceConfig
    from mempalace.palace import get_collection

    try:
        palace_path = _resolve_palace_path(palace) or MempalaceConfig().palace_path
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    existed_before = os.path.exists(palace_path)
    try:
        with _MEMPALACE_LOCK, contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            get_collection(palace_path, create=True)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"palace creation failed: {exc}",
        )
    return {
        "success": True,
        "palace_path": palace_path,
        "palace_id": palace,
        "already_exists": existed_before,
    }


# --- write: store data ------------------------------------------------------

class StoreRequest(BaseModel):
    """Body for ``POST /store`` — wraps ``tool_diary_write`` or
    ``tool_add_drawer``.

    With ``room`` (and optional ``source_file``) present the entry is filed
    via ``tool_add_drawer`` — the document path: auto-chunking, duplicate
    detection, and per-drawer source_file provenance so semantic search
    results stay traceable. Without ``room`` the legacy verbatim diary
    path is preserved (all existing callers keep working unchanged).
    """

    entry: str = Field(..., min_length=1, description="The verbatim text to store.")
    agent_name: str = Field("mcp", description="Who/what is filing this entry.")
    topic: str = Field("general", description="Free-form topic tag.")
    wing: str = Field("", description="Optional wing; auto-derived from agent_name if blank.")
    palace: str | None = Field(None, description="Palace id to store into; omit for the default palace.")
    room: str | None = Field(None, description="Optional room (e.g. document name); routes to tool_add_drawer with provenance.")
    source_file: str | None = Field(None, description="Source file full name for provenance; used with room.")
    file_version: int | None = Field(None, description="File-level version tag (RAG 文件级多版本管理); stored in chunk metadata and drives active-version filtering in /search.")


@app.post("/store", status_code=status.HTTP_201_CREATED)
def store(
    body: StoreRequest,
    _auth: None = Depends(require_token),
):
    """Store a verbatim entry. Creates the palace on first write.

    MEMPALACE_STORE_PAUSED=1 时拒绝写入（503）——数据库维护/重建期间的
    写暂停开关；调用方（RAG _store_docs_to_mempalace）会静默降级。

    This is the second of the two headline endpoints (store + search). Writes
    are serialized through the same Chroma single-writer lock as reads, and are
    auth-gated — an open write endpoint would let anyone poison the palace.

    ``palace`` in the body scopes the write to a named multi-palace directory.
    """
    handlers = _HANDLERS or _load_handlers()
    if os.environ.get("MEMPALACE_STORE_PAUSED", "") == "1":
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="store 已暂停（MEMPALACE_STORE_PAUSED=1，数据库维护中）",
        )
    try:
        palace_path = _resolve_palace_path(body.palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    if body.room:
        # 文档路径：按文档分 room + source_file 溯源（自动分块）
        raw = _run_mem_palace(
            handlers["add_drawer"],
            wing=body.wing,
            room=body.room,
            content=body.entry,
            source_file=body.source_file,
            file_version=body.file_version,
            palace_path=palace_path,
        )
    else:
        raw = _run_mem_palace(
            handlers["diary"],
            agent_name=body.agent_name,
            entry=body.entry,
            topic=body.topic,
            wing=body.wing,
            palace_path=palace_path,
        )
    # diary_write/add_drawer signal failure with {"success": False, "error": ...}
    # (or a bare {"error": ...}). Either way → 400 so the caller knows nothing
    # was stored; success carries entry_id / drawer_id.
    if not raw.get("success", False) or "error" in raw:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=raw.get("error", "store failed"),
        )
    return raw


class FileVersionActivateRequest(BaseModel):
    """Body for ``POST /file_version/activate`` — 切换文件 active 版本（回滚面）。"""
    palace: str = Field(..., description="Palace id.")
    source_file: str = Field(..., description="Source file (与 store 的 source_file 一致).")
    file_version: int = Field(..., description="激活的版本号.")


@app.post("/file_version/activate")
def file_version_activate(
    body: FileVersionActivateRequest,
    _auth: None = Depends(require_token),
):
    """切换 (palace, source_file) 的 active 版本——只切指针不动数据，
    /search 的版本过滤立即生效；旧版本 chunk 留存可再切回。"""
    handlers = _HANDLERS or _load_handlers()
    try:
        palace_path = _resolve_palace_path(body.palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    raw = _run_mem_palace(
        handlers["set_file_version"],
        source_file=body.source_file,
        file_version=body.file_version,
        palace_path=palace_path,
    )
    if not raw.get("success", False):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST,
                            detail=raw.get("error", "activate failed"))
    return raw


@app.delete("/chunks")
def delete_chunks(
    palace: str = Query(..., description="Palace id."),
    source_file: str = Query(..., description="Target source file."),
    max_version: int = Query(..., ge=1, description="删除 file_version <= 此值的 chunk（保留策略清理）."),
    _auth: None = Depends(require_token),
):
    """按版本清理 chunk（RAG 保留策略调用）。只删带 file_version 元数据的
    chunk；无版本的存量 chunk 不动（active 过滤已使其不可见）。"""
    handlers = _HANDLERS or _load_handlers()
    try:
        palace_path = _resolve_palace_path(palace)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    raw = _run_mem_palace(
        handlers["delete_file_version_chunks"],
        source_file=source_file,
        max_version=max_version,
        palace_path=palace_path,
    )
    if not raw.get("success", False):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST,
                            detail=raw.get("error", "delete failed"))
    return raw


# --- MCP JSON-RPC 面：把 RAG search_docs 包成 mempalace_search 工具 -------------
# 供 pod 内 8766 桥（9803 hisi_docs_search_chunk）作为 BRIDGE_UPSTREAM_URL 使用：
# 桥会把外部工具名 hisi_docs_search 改写回 mempalace_search 再打到本端点，
# 本端点转发到 RAG 的 /kw/knowledge_base/search_docs（Milvus 向量检索）。

_RAG_SEARCH_DOCS_URL = os.environ.get(
    "RAG_SEARCH_DOCS_URL",
    "http://haisi-rag-models-server:47861/kw/knowledge_base/search_docs",
)
_DEFAULT_RAG_KB = os.environ.get("RAG_DEFAULT_KB", "kb61ece94b2c094fefb3b6b34ad983ba7a")

def _wrap_search_docs_results(q: str, docs) -> dict:
    """把 search_docs 的 Document list 包装成 mempalace REST 兼容结构。

    9803 直连 /search 与 MCP 工具的既有调用方（如 rest_search_test.py）
    期望 {query, filters, total_before_filter, results:[{text, source_file,
    similarity, ...}]} 形态；search_docs 原生返回裸 list，直接透传会导致
    旧脚本解析 0 条。
    """
    results = []
    for doc in docs or []:
        md = doc.get("metadata") or {}
        src = md.get("source") or ""
        score = doc.get("score")
        results.append({
            "text": doc.get("page_content") or "",
            "wing": None,
            "room": None,
            "source_file": src.split("/")[-1] if src else "",
            "source_path": src,
            "created_at": None,
            "similarity": round(score, 4) if isinstance(score, (int, float)) else None,
            "distance": md.get("distance"),
            "bm25_score": None,
            "matched_via": "search_docs",
        })
    return {
        "query": q,
        "filters": {"wing": None, "room": None, "source_file": None},
        "total_before_filter": len(results),
        "results": results,
    }


_MCP_TOOL = {
    "name": "mempalace_search",
    "description": "search_docs 封装（Milvus 向量检索 RAG 知识库文档）",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "查询问题（自然语言）"},
            "limit": {"type": "integer", "description": "返回条数（默认 5）"},
            "knowledge_base_name": {"type": "string", "description": f"知识库 kbId（默认 {_DEFAULT_RAG_KB}）"},
        },
        "required": ["query"],
    },
}


@app.post("/mcp")
async def mcp_search_docs_endpoint(request: Request):
    """最小 MCP JSON-RPC 实现（initialize / tools/list / tools/call）。"""
    try:
        obj = json.loads(await request.body())
    except Exception:
        return JSONResponse(
            {"jsonrpc": "2.0", "id": None,
             "error": {"code": -32700, "message": "请求体不是合法 JSON"}},
            status_code=400)
    method = obj.get("method") if isinstance(obj, dict) else None
    rid = obj.get("id") if isinstance(obj, dict) else None

    if method == "initialize":
        return JSONResponse({"jsonrpc": "2.0", "id": rid, "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "search-docs-adapter", "version": "1.0"},
        }})
    if method == "tools/list":
        return JSONResponse({"jsonrpc": "2.0", "id": rid,
                             "result": {"tools": [_MCP_TOOL]}})
    if method == "tools/call":
        try:
            args = (obj.get("params") or {}).get("arguments") or {}
            q = (args.get("query") or "").strip()
            if not q:
                return JSONResponse({"jsonrpc": "2.0", "id": rid,
                                     "error": {"code": -32602,
                                               "message": "缺少 query 参数"}})
            limit = int(args.get("limit", 5))
            kb = args.get("knowledge_base_name") or _DEFAULT_RAG_KB
            body = json.dumps({
                "query": q,
                "knowledge_base_name": kb,
                "top_k": limit,
                "score_threshold": 0.5,
                "file_name": "",
                "metadata": {},
            }).encode()
            req = urllib.request.Request(
                _RAG_SEARCH_DOCS_URL, data=body,
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode())
            wrapped = _wrap_search_docs_results(q, data)
            return JSONResponse({"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text",
                             "text": json.dumps(wrapped, ensure_ascii=False)}]}})
        except Exception as e:
            return JSONResponse({"jsonrpc": "2.0", "id": rid,
                                 "error": {"code": -32000,
                                           "message": f"search_docs 调用失败: "
                                                      f"{type(e).__name__}: {e}"}})
    return JSONResponse({"jsonrpc": "2.0", "id": rid,
                         "error": {"code": -32601,
                                   "message": f"未知方法: {method}"}})


# --- entrypoint -------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    if TOKEN:
        auth_label = "token"
    elif ALLOW_NO_AUTH:
        auth_label = "OPEN (no-auth, trusted network)"
    else:
        auth_label = "OPEN (loopback)"
    print(
        f"MemPalace REST adapter → http://{HOST}:{PORT}  "
        f"(auth={auth_label}, default_detail={DEFAULT_DETAIL})",
        flush=True,
    )
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
