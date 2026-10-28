#!/usr/bin/env python3
"""search 探针进程级缓存 patch + union 词法召回 patch。

patch A（探针缓存）：tool_search 每次调用都跑 _refresh_vector_disabled_flag
→ hnsw_capacity_status → 3 条 sqlite COUNT（chroma.sqlite3 在 NFS 上，
冷 50s/热 1.5s）。按其原始设计意图（仅在 client 缓存重建时跑），改为
进程级只跑一次。幂等字符串 patch。

patch B（union 词法召回）：chromadb 1.5.7 没有 Collection.lexical_search，
candidate_strategy="union" 实际是空操作（异常被 debug 吞掉）；且默认
max_distance=1.5 会直接跳过词法候选。中文查询（如"如何使用USB键盘鼠标"）
经 minilm 向量检索排不进候选池，BM25 无从发力。改为：
 1) 词法召回改走 chroma.sqlite3 自带 FTS5 trigram 索引（复用
    _bm25_only_via_sqlite 机制，中文实测有效）；
 2) 仅严格距离阈值（0<max_distance<1.0）才跳过词法候选；
 3) search_memories 默认 candidate_strategy 改 "union"；
 4) 无 source_file 的 legacy diary chunk 用 parent_entry_id 做去重键、
    topic 做展示名。

patch C（百炼 embedding API）：embedding.py 新增 DashScopeEF 类
（text-embedding-v4，1024 维，批 10，指数退避重试），
MEMPALACE_EMBEDDING_MODEL=dashscope 时启用。实测中文 FAQ 查询：
minilm 正确 chunk 0.430（垫底）→ v4 正确 chunk 0.722（top，margin 0.21）。
"""

import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import sys

TARGET = "/app/.venv/lib/python3.12/site-packages/mempalace/mcp_server.py"
MARKER = "_probe_ts"

OLD_VAR = '''_vector_disabled = False
_vector_disabled_reason = ""
'''
NEW_VAR = '''_vector_disabled = False
_vector_disabled_reason = ""
_probe_ts = None  # NFS 探针缓存：进程级只跑一次（patch_search_probe）
'''

OLD_HEAD = '''    global _vector_disabled, _vector_disabled_reason, _vector_capacity_status
    if not _is_chroma_backend():
        _vector_disabled = False
        _vector_disabled_reason = ""
        _vector_capacity_status = None
        return
'''
NEW_HEAD = '''    global _vector_disabled, _vector_disabled_reason, _vector_capacity_status, _probe_ts
    if _probe_ts is not None:
        return
    _probe_ts = time.time()
    if not _is_chroma_backend():
        _vector_disabled = False
        _vector_disabled_reason = ""
        _vector_capacity_status = None
        return
'''


TIMING_OLD1 = '''    _refresh_vector_disabled_flag()
    result = search_memories('''
TIMING_NEW1 = '''    _t0 = time.time()
    _refresh_vector_disabled_flag()
    print(f"[timing] after-probe {time.time()-_t0:.2f}s", flush=True)
    result = search_memories('''
TIMING_OLD2 = '''    if _vector_disabled:
        result["vector_disabled"] = True'''
TIMING_NEW2 = '''    print(f"[timing] after-search {time.time()-_t0:.2f}s", flush=True)
    if _vector_disabled:
        result["vector_disabled"] = True'''
TIMING_MARKER = "[timing] after-probe"

# quick_check 全库扫描在 NFS 上 60-70s（首查元凶）——改快速模式
REPAIR_TARGET = "/app/.venv/lib/python3.12/site-packages/mempalace/repair.py"
# 预检门与 tools/call 入口计时
PRE_OLD = '''def _mcp_tool_preflight_refusal(req_id, tool_name: str):
    """Run MCP request preflight gates outside handle_request complexity."""

    sqlite_integrity_error = _mcp_sqlite_integrity_refusal(req_id, tool_name)
    if sqlite_integrity_error is not None:
        return sqlite_integrity_error

    return _mcp_peer_writer_refusal(req_id, tool_name)'''
PRE_NEW = '''def _mcp_tool_preflight_refusal(req_id, tool_name: str):
    """Run MCP request preflight gates outside handle_request complexity."""

    _g0 = time.time()
    sqlite_integrity_error = _mcp_sqlite_integrity_refusal(req_id, tool_name)
    print(f"[timing] preflight-integrity {time.time()-_g0:.2f}s", flush=True)
    if sqlite_integrity_error is not None:
        return sqlite_integrity_error

    r = _mcp_peer_writer_refusal(req_id, tool_name)
    print(f"[timing] preflight-writer {time.time()-_g0:.2f}s", flush=True)
    return r'''
PRE_MARKER = "preflight-integrity"

# handle_request 的 tools/call 分支入口
ENTER_OLD = '''    elif method == "tools/call":
        if not isinstance(params, dict) or "name" not in params:'''
ENTER_NEW = '''    elif method == "tools/call":
        print("[timing] tools/call-enter", flush=True)
        if not isinstance(params, dict) or "name" not in params:'''
ENTER_MARKER = "tools/call-enter"

# 完整性门异步化：PRAGMA quick_check 在 NFS 上每进程 60s+，
# 首个 tools/call 乐观放行，检查后台完成后仍保留损坏拦截
ASYNC_OLD = '''def _ensure_sqlite_integrity_status() -> None:
    if not _sqlite_integrity_checked:
        _refresh_sqlite_integrity_status()'''
ASYNC_NEW = '''def _ensure_sqlite_integrity_status() -> None:
    # NFS 上 PRAGMA quick_check 需 60s+：后台异步执行，首个请求不阻塞
    # （检查完成前乐观放行一次；完成后若发现损坏仍会拒绝后续工具）
    if _sqlite_integrity_checked:
        return
    threading.Thread(target=_refresh_sqlite_integrity_status,
                     name="sqlite-integrity-bg", daemon=True).start()'''
ASYNC_MARKER = "sqlite-integrity-bg"

REPAIR_OLD = 'rows = conn.execute("PRAGMA quick_check").fetchall()'
REPAIR_NEW = 'rows = conn.execute("PRAGMA quick_check(1)").fetchall()  # NFS 快速模式（patch）'


# ── patch B: union 词法召回（searcher.py）─────────────────────────────
SEARCHER = "/app/.venv/lib/python3.12/site-packages/mempalace/searcher.py"
UNION_MARKER = "patch_search_union"

# B1: 默认启用 union
U_OLD_DEFAULT = '    candidate_strategy: str = "vector",'
U_NEW_DEFAULT = '    candidate_strategy: str = "union",  # patch_search_union: 词法候选合并默认开启'

# B2: 合并器门 + 词法召回源替换
U_OLD_MERGE = '''    if max_distance > 0.0:
        return

    where = build_where_filter(wing, room, source_file)
    try:
        lexical = drawers_col.lexical_search(
            query=query,
            n_results=n_results * 3,
            where=where or None,
        )
    except UnsupportedCapabilityError:
        raise
    except Exception:
        logger.debug("candidate_strategy=union: lexical fetch failed", exc_info=True)
        return

    bm25_extra = []
    for hit in lexical.hits:
        meta = hit.metadata or {}
        full_source = meta.get("source_file", "") or ""
        bm25_extra.append(
            {
                "text": hit.document or "",
                "wing": meta.get("wing", "unknown"),
                "room": meta.get("room", "unknown"),
                "source_file": Path(full_source).name if full_source else "?",
                "source_path": full_source,
                "created_at": meta.get("filed_at", "unknown"),
                "similarity": None,
                "distance": None,
                "effective_distance": None,
                "closet_boost": 0.0,
                "matched_via": "bm25_backend",
                "bm25_score": round(float(hit.score), 3),
                "_source_file_full": full_source,
                "_chunk_index": meta.get("chunk_index"),
            }
        )'''
U_NEW_MERGE = '''    # patch_search_union: 仅严格阈值（0<max_distance<1.0）才跳过词法候选；
    # 默认 1.5 / 禁用 0.0 均为宽松语义，注入 BM25-only 候选不破坏阈值保证。
    if 0.0 < max_distance < 1.0:
        return

    # patch_search_union: chromadb 1.5.7 无 Collection.lexical_search，
    # 词法召回改走 chroma.sqlite3 的 FTS5 trigram 索引（与 vector-disabled
    # 的 BM25 兜底同一机制，中文实测有效）。
    try:
        from .config import MempalaceConfig

        bm25_res = _bm25_only_via_sqlite(
            query,
            MempalaceConfig().palace_path,
            wing=wing,
            room=room,
            source_file=source_file,
            n_results=n_results * 3,
            max_candidates=20,
            _include_internal=True,
        )
        bm25_hits = bm25_res.get("results", []) if isinstance(bm25_res, dict) else []
    except Exception:
        logger.debug("candidate_strategy=union: sqlite lexical fetch failed", exc_info=True)
        return

    bm25_extra = []
    for hit in bm25_hits:
        full_source = hit.get("source_path") or ""
        bm25_extra.append(
            {
                "text": hit.get("text") or "",
                "wing": hit.get("wing", "unknown"),
                "room": hit.get("room", "unknown"),
                "source_file": hit.get("source_file") or "?",
                "source_path": full_source,
                "created_at": hit.get("created_at", "unknown"),
                "similarity": None,
                "distance": None,
                "effective_distance": None,
                "closet_boost": 0.0,
                "matched_via": "bm25_sqlite",
                "bm25_score": hit.get("bm25_score", 0.0),
                "_source_file_full": hit.get("_source_file_full") or "",
                "_chunk_index": hit.get("_chunk_index"),
            }
        )'''

# B2b: 已打过 B2 的旧文件把候选上限 500 → 100（NFS 元数据拉取 1.2s → 0.2s）
U_OLD_CAP = '''            n_results=n_results * 3,
            max_candidates=500,
            _include_internal=True,'''
U_NEW_CAP = '''            n_results=n_results * 3,
            max_candidates=20,
            _include_internal=True,'''

# B3: 向量路径 entry 的 legacy 兜底（去重键 + 展示名）
U_OLD_VEC_SRC = '            "source_file": Path(source).name if source else "?",'
U_NEW_VEC_SRC = ('            "source_file": meta.get("topic") or '
                 '(Path(source).name if source else "?"),  # patch_search_union')
U_OLD_VEC_FULL = '            "_source_file_full": source,'
U_NEW_VEC_FULL = ('            "_source_file_full": source or meta.get("parent_entry_id") or "",'
                  '  # patch_search_union')

# B4: BM25-sqlite 候选的 legacy 兜底
U_OLD_BM_SRC = '        full_source = meta.get("source_file", "") or ""'
U_NEW_BM_SRC = ('        full_source = meta.get("source_file", "") '
                'or meta.get("parent_entry_id", "") or ""  # patch_search_union')
U_OLD_BM_DISP = '                "source_file": Path(full_source).name if full_source else "?",'
U_NEW_BM_DISP = ('                "source_file": meta.get("topic") or '
                 '(Path(full_source).name if full_source else "?"),  # patch_search_union')

UNION_PATCHES = [
    (U_OLD_DEFAULT, U_NEW_DEFAULT),
    (U_OLD_MERGE, U_NEW_MERGE),
    (U_OLD_VEC_SRC, U_NEW_VEC_SRC),
    (U_OLD_VEC_FULL, U_NEW_VEC_FULL),
    (U_OLD_BM_SRC, U_NEW_BM_SRC),
    (U_OLD_BM_DISP, U_NEW_BM_DISP),
]


# ── patch C: 百炼 DashScope embedding API（embedding.py）──────────────
EMBEDDING = "/app/.venv/lib/python3.12/site-packages/mempalace/embedding.py"
DASH_MARKER = "patch_dashscope"

DASH_OLD_ANCHOR = "def get_embedding_function(device: Optional[str] = None, model: Optional[str] = None):"
DASH_CLASS = '''class DashScopeEF:
    """ChromaDB-compatible EF backed by the 百炼 (DashScope) text-embedding-v4 API.

    中文语义质量显著优于本地 minilm（实测 FAQ 查询：minilm 正确 chunk
    0.430 vs 干扰 0.618；v4 正确 0.722 vs 干扰 0.514）。需要环境变量
    DASHSCOPE_API_KEY；批 10 条一次调用，限流/网络错误指数退避重试 3 次。
    """  # patch_dashscope

    def __init__(self, api_key: str | None = None):
        self._api_key = (api_key or os.environ.get("DASHSCOPE_API_KEY", "")).strip()
        if not self._api_key:
            raise ValueError("DashScopeEF requires DASHSCOPE_API_KEY")

    @staticmethod
    def name() -> str:
        # ChromaDB 持久化 EF 名：重建/在线服务必须同名
        return "dashscope_v4"

    def _embed_batch(self, texts):
        import json
        import time
        import urllib.request

        body = json.dumps({
            "model": "text-embedding-v4",
            "input": {"texts": texts},
            "parameters": {"dimension": 1024},
        }).encode("utf-8")
        last_err = None
        for attempt in range(3):
            req = urllib.request.Request(
                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/"
                "text-embedding/text-embedding",
                data=body,
                headers={"Authorization": f"Bearer {self._api_key}",
                         "Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    resp = json.loads(r.read().decode())
            except Exception as e:  # noqa: BLE001 — 网络错误退避
                last_err = e
                time.sleep(2 ** attempt)
                continue
            if "output" in resp and resp.get("output", {}).get("embeddings"):
                return [e.get("embedding") or [] for e in resp["output"]["embeddings"]]
            last_err = RuntimeError(
                f"dashscope code={resp.get('code')} msg={resp.get('message')}")
            code = str(resp.get("code", ""))
            if code.startswith("Throttling") or code == "InvalidApiKey" or code == "":
                time.sleep(2 ** attempt)
                continue
            break
        raise last_err if last_err else RuntimeError("dashscope embed failed")

    def __call__(self, input):  # noqa: A002 — ChromaDB EF protocol
        if isinstance(input, str):
            input = [input]
        if input is None or len(input) == 0:
            return []
        import numpy as np

        out: list = []
        for start in range(0, len(input), 10):
            texts = [
                (t.replace("\\ud800", " ").replace("\\udfff", " ") if isinstance(t, str) else "")
                for t in input[start:start + 10]
            ]
            embs = np.asarray(self._embed_batch(texts), dtype=np.float32)
            norms = np.linalg.norm(embs, axis=1, keepdims=True) + 1e-12
            out.extend((embs / norms).tolist())
        return out

    def embed_query(self, input):  # noqa: A002 — ChromaDB EF protocol
        return self(input)

    def embed_documents(self, input):  # noqa: A002 — ChromaDB EF protocol
        return self(input)


'''
DASH_NEW_ANCHOR = DASH_CLASS + DASH_OLD_ANCHOR

DASH_OLD_BRANCH = '''        if model == "embeddinggemma":
            ef = EmbeddinggemmaONNX(preferred_providers=providers, intra_op_num_threads=threads)'''
DASH_NEW_BRANCH = '''        if model == "dashscope":
            ef = DashScopeEF()  # patch_dashscope
        elif model == "embeddinggemma":
            ef = EmbeddinggemmaONNX(preferred_providers=providers, intra_op_num_threads=threads)'''


# ── patch C2: palace 级 EF/集合名解析（backends/chroma.py）────────────
CHROMA_BE = "/app/.venv/lib/python3.12/site-packages/mempalace/backends/chroma.py"

C2_OLD_ANCHOR = '''        client = self._client(palace_path)
        hnsw_space = "cosine"
        if options and isinstance(options, dict):
            hnsw_space = options.get("hnsw_space", hnsw_space)

        ef = self._resolve_embedding_function()
        ef_kwargs = {"embedding_function": ef} if ef is not None else {}'''
C2_NEW_ANCHOR = '''        client = self._client(palace_path)
        hnsw_space = "cosine"
        if options and isinstance(options, dict):
            hnsw_space = options.get("hnsw_space", hnsw_space)

        # patch_dashscope: palace 级 EF/集合名切换（百炼 API 迁移）
        collection_name, _ef_model = _palace_ef_override(palace_path, collection_name)
        if _ef_model == "dashscope":
            from ..embedding import get_embedding_function as _gef_dash

            ef = _gef_dash(model="dashscope")
        else:
            ef = self._resolve_embedding_function()
        ef_kwargs = {"embedding_function": ef} if ef is not None else {}'''

C2_OLD_HELPER_ANCHOR = '''class ChromaBackend(BaseBackend):'''
C2_HELPER = '''def _palace_ef_override(palace_path, requested_name):
    """patch_dashscope: 按 palace 的 mempalace_embedder.json 决定集合名与 EF。

    json 中任一集合条目 model_name=="dashscope" 表示该 palace 已迁移到
    百炼 API：请求默认 drawers 集合名时映射到该集合并切 dashscope EF；
    其它 palace 保持原行为（minilm）。重建脚本在完成后写入该 json，
    因此切换/回滚只改 json、无需重启（集合句柄随 sqlite mtime 失效重建）。
    """
    try:
        with open(os.path.join(palace_path, "mempalace_embedder.json"),
                  encoding="utf-8") as f:
            reg = json.load(f)
    except Exception:
        return requested_name, None
    for cname, info in reg.items():
        if isinstance(info, dict) and info.get("model_name") == "dashscope":
            if requested_name in (cname, "mempalace_drawers"):
                return cname, "dashscope"
    return requested_name, None


class ChromaBackend(BaseBackend):'''


# ── patch C3: BM25 词法召回目标集合跟随 palace 切换（searcher.py）────
C3_OLD = '''    if collection_name is None:
        from .config import get_configured_collection_name

        collection_name = get_configured_collection_name()'''
C3_NEW = '''    if collection_name is None:
        from .config import get_configured_collection_name

        collection_name = get_configured_collection_name()
    # patch_dashscope: palace 已迁移百炼时，词法召回目标集合同步切换
    try:
        from .backends.chroma import _palace_ef_override as _peo_dash

        collection_name, _m = _peo_dash(palace_path, collection_name)
    except Exception:
        pass'''


# ── patch O1: union 词法候选与向量查询并行（searcher.py）──────────────
O1_OLD_IMPORTS = '''import logging
import math
import os
import re
import sqlite3'''
O1_NEW_IMPORTS = '''import logging
import math
import os
import re
import sqlite3
import threading
import time'''

O1_HELPERS_ANCHOR = '''def _bm25_only_via_sqlite('''
O1_HELPERS = '''_BM25_PREFETCH = {}  # patch_parallel: key -> {"done", "result"}
_BM25_PREFETCH_LOCK = threading.Lock()


def _start_bm25_prefetch(query, palace_path, wing, room, source_file, n_results):
    """后台线程跑 BM25 词法候选抓取（独立只读 sqlite 连接，与向量查询并行）。"""
    key = (query, palace_path, wing, room, source_file, n_results)
    with _BM25_PREFETCH_LOCK:
        if key in _BM25_PREFETCH:
            return key
        _BM25_PREFETCH[key] = {"done": False, "result": None}

    def _run():
        try:
            res = _bm25_only_via_sqlite(
                query, palace_path, wing=wing, room=room,
                source_file=source_file, n_results=n_results * 3,
                max_candidates=20, _include_internal=True)
        except Exception:
            res = {"results": []}
        with _BM25_PREFETCH_LOCK:
            _BM25_PREFETCH[key]["result"] = res
            _BM25_PREFETCH[key]["done"] = True

    threading.Thread(target=_run, name="bm25-prefetch", daemon=True).start()
    return key


def _get_bm25_prefetch(key, timeout=15.0):
    """取预取结果；超时/不存在返回 None（调用方回退同步路径）。"""
    with _BM25_PREFETCH_LOCK:
        entry = _BM25_PREFETCH.get(key)
    if entry is None:
        return None
    deadline = time.time() + timeout
    while time.time() < deadline:
        with _BM25_PREFETCH_LOCK:
            if entry["done"]:
                res = entry["result"]
                _BM25_PREFETCH.pop(key, None)
                return res
        time.sleep(0.01)
    return None


def _bm25_only_via_sqlite('''

O1_OLD_START = '''    metric = _metric_for_collection(drawers_col)
    where = build_where_filter(wing, room, source_file)'''
O1_NEW_START = '''    metric = _metric_for_collection(drawers_col)
    where = build_where_filter(wing, room, source_file)

    # patch_parallel: union 词法候选与向量查询并行
    if candidate_strategy == "union":
        try:
            _start_bm25_prefetch(query, palace_path, wing, room, source_file, n_results)
        except Exception:
            logger.debug("bm25 prefetch start failed", exc_info=True)'''

O1_OLD_MERGE = '''        from .config import MempalaceConfig

        bm25_res = _bm25_only_via_sqlite(
            query,
            MempalaceConfig().palace_path,
            wing=wing,
            room=room,
            source_file=source_file,
            n_results=n_results * 3,
            max_candidates=20,
            _include_internal=True,
        )'''
O1_NEW_MERGE = '''        from .config import MempalaceConfig

        _palace = MempalaceConfig().palace_path
        # patch_parallel: 优先用与向量查询并行的预取结果
        bm25_res = _get_bm25_prefetch(
            (query, _palace, wing, room, source_file, n_results))
        if bm25_res is None:
            bm25_res = _bm25_only_via_sqlite(
                query,
                _palace,
                wing=wing,
                room=room,
                source_file=source_file,
                n_results=n_results * 3,
                max_candidates=20,
                _include_internal=True,
            )'''


# ── patch O2: DashScope API keep-alive 连接复用（embedding.py）─────────
O2_HELPER_ANCHOR = '''class DashScopeEF:'''
O2_HELPER = '''_DS_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding"
_DS_HOST = "dashscope.aliyuncs.com"
_DS_CONN = None  # patch_dashscope_keepalive: 单连接复用
_DS_CONN_LOCK = threading.Lock()


def _dashscope_post(api_key: str, body: bytes):
    """keep-alive 单连接 POST：省每查询一次 TLS 握手（实测 ~0.15s）。

    连接按需重建（断线/服务端关闭时重试一次）；全周期持锁串行，
    查询路径每请求仅 1 次调用不受影响。
    """
    import http.client
    import json as _json

    global _DS_CONN
    headers = {"Authorization": f"Bearer {api_key}",
               "Content-Type": "application/json"}
    with _DS_CONN_LOCK:
        for attempt in range(2):
            try:
                if _DS_CONN is None:
                    _DS_CONN = http.client.HTTPSConnection(_DS_HOST, timeout=30)
                _DS_CONN.request("POST", _DS_PATH, body=body, headers=headers)
                resp = _DS_CONN.getresponse()
                data = resp.read()
                return _json.loads(data.decode("utf-8"))
            except Exception:
                try:
                    if _DS_CONN is not None:
                        _DS_CONN.close()
                except Exception:
                    pass
                _DS_CONN = None
                if attempt == 0:
                    continue
                raise


class DashScopeEF:'''

O2_OLD_TRANSPORT = '''        last_err = None
        for attempt in range(3):
            req = urllib.request.Request(
                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/"
                "text-embedding/text-embedding",
                data=body,
                headers={"Authorization": f"Bearer {self._api_key}",
                         "Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    resp = json.loads(r.read().decode())
            except Exception as e:  # noqa: BLE001 — 网络错误退避
                last_err = e
                time.sleep(2 ** attempt)
                continue'''
O2_NEW_TRANSPORT = '''        last_err = None
        for attempt in range(3):
            try:
                resp = _dashscope_post(self._api_key, body)
            except Exception as e:  # noqa: BLE001 — 网络错误退避
                last_err = e
                time.sleep(2 ** attempt)
                continue'''


# ── patch D: context_chunks 相邻块扩展（返回完整章节）──────────────────
# searcher.py
D1_OLD_SIG = '''    candidate_strategy: str = "union",  # patch_search_union: 词法候选合并默认开启
    collection_name: str = None,
) -> dict:'''
D1_NEW_SIG = '''    candidate_strategy: str = "union",  # patch_search_union: 词法候选合并默认开启
    collection_name: str = None,
    context_chunks: int = 0,  # patch_context_chunks: 相邻块扩展
) -> dict:'''

D1_OLD_CALL = '''    hits, strategy_error = _finalize_candidate_hits(
        candidate_strategy=candidate_strategy,
        hits=hits,
        drawers_col=drawers_col,
        query=query,
        wing=wing,
        room=room,
        n_results=n_results,
        max_distance=max_distance,
        source_file=source_file,
    )'''
D1_NEW_CALL = '''    hits, strategy_error = _finalize_candidate_hits(
        candidate_strategy=candidate_strategy,
        hits=hits,
        drawers_col=drawers_col,
        query=query,
        wing=wing,
        room=room,
        n_results=n_results,
        max_distance=max_distance,
        source_file=source_file,
        context_chunks=context_chunks,
    )'''

D1_OLD_FSIG = '''    n_results: int,
    max_distance: float,
    source_file: str = None,
) -> tuple:'''
D1_NEW_FSIG = '''    n_results: int,
    max_distance: float,
    source_file: str = None,
    context_chunks: int = 0,
) -> tuple:'''

D1_OLD_FBODY = '''    hits = _hybrid_rank(hits, query, metric=_metric_for_collection(drawers_col))[:n_results]
    for h in hits:
        h.pop("_sort_key", None)'''
D1_NEW_FBODY = '''    hits = _hybrid_rank(hits, query, metric=_metric_for_collection(drawers_col))[:n_results]
    if context_chunks > 0:
        _hydrate_context(hits, drawers_col, context_chunks)  # patch_context_chunks
    for h in hits:
        h.pop("_sort_key", None)'''

D1_HELPER_ANCHOR = '''def _finalize_candidate_hits('''
D1_HELPER = '''_CTX_CACHE = {}  # patch_context_chunks: gid -> (ts, [(chunk_index, doc)])
_CTX_CACHE_TTL = 300.0  # 组内容写入后不变，TTL 放宽到 5 分钟
_CTX_CACHE_LOCK = threading.Lock()
_MAX_CTX_CHARS = 10000


def _fetch_group_chunks_sqlite(gids):
    """patch_context_chunks: 直读 chroma.sqlite3 按 parent 取同组 chunk。

    chroma 的 where $or 过滤在 NFS 上全表扫 embedding_metadata（首查
    1.5-2s+）；直读 sqlite 与 BM25 词法路径同款（只读连接 + 主键命中），
    同一批拉取 ~50-150ms。分组不可变，配合 5 分钟缓存。
    """
    from .config import MempalaceConfig

    db_path = os.path.join(MempalaceConfig().palace_path, "chroma.sqlite3")
    try:
        conn = sqlite3.connect(sqlite_read_uri(db_path), uri=True)
    except sqlite3.Error:
        return {}
    try:
        ph = ",".join(["?"] * len(gids))
        rows = conn.execute(
            f"SELECT id FROM embedding_metadata WHERE key IN "
            f"('parent_drawer_id', 'parent_entry_id') AND string_value IN ({ph})",
            gids).fetchall()
        ids = [r[0] for r in rows]
        if not ids:
            return {}
        ph2 = ",".join(["?"] * len(ids))
        meta = conn.execute(
            f"SELECT id, key, string_value, int_value FROM embedding_metadata "
            f"WHERE id IN ({ph2})", ids).fetchall()
    finally:
        conn.close()
    by_id = {}
    for emb_id, key, sval, ival in meta:
        d = by_id.setdefault(emb_id, {"doc": "", "ci": None, "gid": None})
        if key == "chroma:document":
            d["doc"] = sval or ""
        elif key == "chunk_index":
            d["ci"] = ival
        elif key in ("parent_drawer_id", "parent_entry_id"):
            d["gid"] = sval
    fetched = {}
    for d in by_id.values():
        if isinstance(d["ci"], int) and d["gid"]:
            fetched.setdefault(d["gid"], []).append((d["ci"], d["doc"]))
    for chunks in fetched.values():
        chunks.sort()
    return fetched


def _hydrate_context(hits, drawers_col, context_chunks):
    """patch_context_chunks: 命中 chunk 拼入同组（parent_drawer_id/
    parent_entry_id）相邻 ±N 块、按 chunk_index 排序——把 800 字符碎片
    还原为完整章节。批量一次 $or 查询 + 30s 进程内缓存。"""
    if context_chunks <= 0 or not hits:
        return
    gids = []
    for h in hits:
        g = h.get("_parent_drawer_id")
        if g and g not in gids:
            gids.append(g)
    if not gids:
        return
    now = time.time()
    groups = {}
    missing = []
    with _CTX_CACHE_LOCK:
        for g in gids:
            ent = _CTX_CACHE.get(g)
            if ent and now - ent[0] < _CTX_CACHE_TTL:
                groups[g] = ent[1]
            else:
                missing.append(g)
    if missing:
        fetched = _fetch_group_chunks_sqlite(missing)
        with _CTX_CACHE_LOCK:
            for g, chunks in fetched.items():
                groups[g] = chunks
                _CTX_CACHE[g] = (now, chunks)
    for h in hits:
        g = h.get("_parent_drawer_id")
        ci = h.get("_chunk_index")
        chunks = groups.get(g) if g else None
        if not chunks or not isinstance(ci, int):
            continue
        pos = 0
        for i, (c, _d) in enumerate(chunks):
            if c == ci:
                pos = i
                break
        start = max(0, pos - context_chunks)
        end = min(len(chunks), pos + context_chunks + 1)
        joined = "\\n\\n".join(d for _c, d in chunks[start:end])
        if len(joined) > _MAX_CTX_CHARS:
            joined = joined[:_MAX_CTX_CHARS] + "\\n\\n[...truncated...]"
        h["text"] = joined
        h["context_chunks"] = context_chunks


def _finalize_candidate_hits('''

D1_OLD_VECPARENT = '''            "_parent_drawer_id": meta.get("parent_drawer_id"),'''
D1_NEW_VECPARENT = '''            "_parent_drawer_id": meta.get("parent_drawer_id") or meta.get("parent_entry_id") or "",  # patch_context_chunks'''

D1_OLD_BM25 = '''            "_source_file_full": full_source,
                "_chunk_index": meta.get("chunk_index"),'''
D1_NEW_BM25 = '''            "_source_file_full": full_source,
                "_chunk_index": meta.get("chunk_index"),
                "_parent_drawer_id": meta.get("parent_drawer_id") or meta.get("parent_entry_id") or "",  # patch_context_chunks'''

D1_OLD_MERGE = '''                "_source_file_full": hit.get("_source_file_full") or "",
                "_chunk_index": hit.get("_chunk_index"),'''
D1_NEW_MERGE = '''                "_source_file_full": hit.get("_source_file_full") or "",
                "_chunk_index": hit.get("_chunk_index"),
                "_parent_drawer_id": hit.get("_parent_drawer_id") or "",'''

# mcp_server.py
D2_OLD_TSIG = '''    max_distance: float = 1.5,
    min_similarity: float = None,
    context: str = None,
):'''
D2_NEW_TSIG = '''    max_distance: float = 1.5,
    min_similarity: float = None,
    context: str = None,
    context_chunks: int = 0,  # patch_context_chunks
):'''

D2_OLD_KW1 = '''        sanitized["clean_query"],
        palace_path=_config.palace_path,
        wing=wing,
        room=room,
        source_file=source_file,
        n_results=limit,
        max_distance=dist,
        vector_disabled=_vector_disabled,
        collection_name=_config.collection_name,
    )'''
D2_NEW_KW1 = '''        sanitized["clean_query"],
        palace_path=_config.palace_path,
        wing=wing,
        room=room,
        source_file=source_file,
        n_results=limit,
        max_distance=dist,
        vector_disabled=_vector_disabled,
        collection_name=_config.collection_name,
        context_chunks=context_chunks,
    )'''

D2_OLD_KW = '''            sanitized["clean_query"],
            palace_path=_config.palace_path,
            wing=wing,
            room=room,
            source_file=source_file,
            n_results=limit,
            max_distance=dist,
            vector_disabled=_vector_disabled,
            collection_name=_config.collection_name,
        )'''
D2_NEW_KW = '''            sanitized["clean_query"],
            palace_path=_config.palace_path,
            wing=wing,
            room=room,
            source_file=source_file,
            n_results=limit,
            max_distance=dist,
            vector_disabled=_vector_disabled,
            collection_name=_config.collection_name,
            context_chunks=context_chunks,
        )'''

D2_OLD_SCHEMA = '''                "context": {
                    "type": "string",
                    "description": "Background context for the search (optional). NOT used for embedding — only for future re-ranking.",
                },
            },
            "required": ["query"],'''
D2_NEW_SCHEMA = '''                "context": {
                    "type": "string",
                    "description": "Background context for the search (optional). NOT used for embedding — only for future re-ranking.",
                },
                "context_chunks": {
                    "type": "integer",
                    "description": "Expand each result with +/-N neighboring chunks from the same document group, joined in chunk order — returns complete sections instead of 800-char fragments. Default 0 (no expansion).",
                    "minimum": 0,
                    "maximum": 10,
                },
            },
            "required": ["query"],'''

# rest_adapter.py
D3_OLD_PARAM = '''    max_distance: float = Query(1.5, ge=0.0, le=2.0),
    context: str | None = Query(None, description="Background context (not embedded)."),'''
D3_NEW_PARAM = '''    max_distance: float = Query(1.5, ge=0.0, le=2.0),
    context: str | None = Query(None, description="Background context (not embedded)."),
    context_chunks: int = Query(0, ge=0, le=10, description="Expand each result with +/-N neighbor chunks (complete sections)."),'''

D3_OLD_CALL = '''    raw = _run_mem_palace(
        handlers["search"],
        query=q,
        limit=limit,
        wing=wing,
        room=room,
        source_file=source_file,
        max_distance=max_distance,
        context=context,
        palace_path=palace_path,
    )'''
D3_NEW_CALL = '''    raw = _run_mem_palace(
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
    )'''

# mcp_stdio_bridge.py（直连 /search 透传）
D4_OLD_ARGS = '''    arguments = {"query": q, "limit": limit}
    if wing:
        arguments["wing"] = wing'''
D4_NEW_ARGS = '''    arguments = {"query": q, "limit": limit}
    if context_chunks:
        arguments["context_chunks"] = context_chunks
    if wing:
        arguments["wing"] = wing'''

D4_OLD_SIG = '''async def _direct_search(q, limit, wing, room, source_file, max_distance):'''
D4_NEW_SIG = '''async def _direct_search(q, limit, wing, room, source_file, max_distance, context_chunks=0):'''

D4_OLD_GET = '''                     max_distance: float = Query(1.5, ge=0.0, le=2.0)):
    return await _direct_search(q, limit, wing, room, source_file, max_distance)'''
D4_NEW_GET = '''                     max_distance: float = Query(1.5, ge=0.0, le=2.0),
                     context_chunks: int = Query(0, ge=0, le=10)):
    return await _direct_search(q, limit, wing, room, source_file, max_distance,
                                context_chunks)'''

D4_OLD_POST = '''    return await _direct_search(
        body.get("query") or body.get("q") or "",
        limit, body.get("wing"), body.get("room"), body.get("source_file"),
        max_distance)'''
D4_NEW_POST = '''    return await _direct_search(
        body.get("query") or body.get("q") or "",
        limit, body.get("wing"), body.get("room"), body.get("source_file"),
        max_distance, int(body.get("context_chunks", 0) or 0))'''


# ── patch D5: hydration 改 sqlite 直读（替换已应用的 chroma $or 版）──
D5_OLD_TTL = "_CTX_CACHE_TTL = 30.0"
D5_NEW_TTL = "_CTX_CACHE_TTL = 300.0  # 组内容写入后不变，TTL 放宽到 5 分钟"

D5_OLD_FETCH = '''        conds = []
        for g in missing:
            conds.append({"parent_drawer_id": g})
            conds.append({"parent_entry_id": g})
        try:
            data = drawers_col.get(where={"$or": conds},
                                   include=["documents", "metadatas"])
        except Exception:
            data = {}
        fetched = {}
        for d, m in zip(data.get("documents") or [], data.get("metadatas") or []):
            m = m or {}
            ci = m.get("chunk_index")
            g = m.get("parent_drawer_id") or m.get("parent_entry_id")
            if not isinstance(ci, int) or not g:
                continue
            fetched.setdefault(g, []).append((ci, d or ""))'''
D5_NEW_FETCH = "        fetched = _fetch_group_chunks_sqlite(missing)"

D5_FN_ANCHOR = "_MAX_CTX_CHARS = 10000"
D5_FN = D5_FN_ANCHOR + '''

def _fetch_group_chunks_sqlite(gids):
    # patch_context_chunks: 直读 chroma.sqlite3 按 parent 取同组 chunk。
    # chroma 的 where $or 过滤在 NFS 上全表扫 embedding_metadata（首查
    # 1.5-2s+）；直读 sqlite 与 BM25 词法路径同款（只读连接 + 主键命中），
    # 同批拉取 ~50-150ms。分组不可变，配合 5 分钟缓存。
    from .config import MempalaceConfig

    db_path = os.path.join(MempalaceConfig().palace_path, "chroma.sqlite3")
    try:
        conn = sqlite3.connect(sqlite_read_uri(db_path), uri=True)
    except sqlite3.Error:
        return {}
    try:
        ph = ",".join(["?"] * len(gids))
        rows = conn.execute(
            f"SELECT id FROM embedding_metadata WHERE key IN "
            f"('parent_drawer_id', 'parent_entry_id') AND string_value IN ({ph})",
            gids).fetchall()
        ids = [r[0] for r in rows]
        if not ids:
            return {}
        ph2 = ",".join(["?"] * len(ids))
        meta = conn.execute(
            f"SELECT id, key, string_value, int_value FROM embedding_metadata "
            f"WHERE id IN ({ph2})", ids).fetchall()
    finally:
        conn.close()
    by_id = {}
    for emb_id, key, sval, ival in meta:
        d = by_id.setdefault(emb_id, {"doc": "", "ci": None, "gid": None})
        if key == "chroma:document":
            d["doc"] = sval or ""
        elif key == "chunk_index":
            d["ci"] = ival
        elif key in ("parent_drawer_id", "parent_entry_id"):
            d["gid"] = sval
    fetched = {}
    for d in by_id.values():
        if isinstance(d["ci"], int) and d["gid"]:
            fetched.setdefault(d["gid"], []).append((d["ci"], d["doc"]))
    for chunks in fetched.values():
        chunks.sort()
    return fetched'''



# ── patch D6: FTS 提速（候选上限 30）──────────────────────────────
D6_OLD_TOK = "        tokens = [t for t in _tokenize(query) if len(t) >= 3]"
D6_NEW_TOK = "        tokens = _fts_split_tokens(query)  # patch_d6/d7: FTS 提速"
D6_OLD_CAP = "                max_candidates=20,"
D6_NEW_CAP = "                max_candidates=20,  # patch_d6"

# ── patch D7: 长词拆 trigram helper（2 词版）──────────────────────
D7_HELPER_ANCHOR = "def _bm25_only_via_sqlite("
D7_HELPER = D7_HELPER_ANCHOR.replace("def _bm25_only_via_sqlite(", '''def _fts_split_tokens(query):
    """patch_d8: FTS 只取首尾两个 trigram（首词首 3 字符 + 末词末 3 字符）。

    实测 NFS 上 FTS5 trigram 贴子列表扫描按词数叠加：单词 0.12-0.17s、
    6 词 OR 0.26-0.34s；首尾 2 词兼顾召回（精确短语必含首词）与速度。
    召回质量由 BM25 重排 + 向量通道兜底。"""
    toks = [t for t in _tokenize(query) if len(t) >= 3]
    if not toks:
        return []
    out = [toks[0][:3]]
    last = toks[-1][-3:]
    if last not in out:
        out.append(last)
    return out


def _bm25_only_via_sqlite(''')

# ── patch D8: 旧 6 词版函数体换 2 词版（对已打过 D7 的 pod）────────
D8_OLD_FN = '''def _fts_split_tokens(query):
    """patch_d7: 查询词拆成 FTS 友好 token——长词（CJK 连续串）只取首尾
    3 字符 trigram（FTS5 trigram 短语扫描在 NFS 上 0.5s+/仅 1 命中；
    单 trigram OR 查询 ~50ms）；召回靠候选 BM25 重排，向量通道兜底语义。"""
    out = []
    for t in _tokenize(query):
        if len(t) <= 3:
            if len(t) >= 3:
                out.append(t)
        else:
            out.append(t[:3])
            out.append(t[-3:])
    return out[:6]'''
D8_NEW_FN = '''def _fts_split_tokens(query):
    """patch_d8: FTS 只取首尾两个 trigram（首词首 3 字符 + 末词末 3 字符）。

    实测 NFS 上 FTS5 trigram 贴子列表扫描按词数叠加：单词 0.12-0.17s、
    6 词 OR 0.26-0.34s；首尾 2 词兼顾召回（精确短语必含首词）与速度。
    召回质量由 BM25 重排 + 向量通道兜底。"""
    toks = [t for t in _tokenize(query) if len(t) >= 3]
    if not toks:
        return []
    out = [toks[0][:3]]
    last = toks[-1][-3:]
    if last not in out:
        out.append(last)
    return out'''


# ── patch E1: 完整性检查 marker（同 pod 24h 内跳过，避免后台扫描抢 NFS I/O）──
E1_OLD_HEAD = (
    "    global _sqlite_integrity_checked\n"
    "    global _sqlite_integrity_errors\n"
    "    global _sqlite_integrity_check_error\n"
    "\n"
    "    if not _config.palace_path or not _is_chroma_backend():\n"
)
E1_NEW_HEAD = (
    "    global _sqlite_integrity_checked\n"
    "    global _sqlite_integrity_errors\n"
    "    global _sqlite_integrity_check_error\n"
    "\n"
    "    # patch_integrity_marker: 同 pod 内近期（<24h）已有进程完成 quick_check\n"
    "    # （startup_probe 启动时同步跑）→ 跳过本进程检查，避免后台全库扫描\n"
    "    # 与查询抢 NFS I/O（p90 尖刺根因）。\n"
    "    _marker = \"/tmp/mempalace-integrity-ok.ts\"\n"
    "    try:\n"
    "        if os.path.isfile(_marker) and time.time() - os.path.getmtime(_marker) < 86400:\n"
    "            _sqlite_integrity_checked = True\n"
    "            _sqlite_integrity_errors = []\n"
    "            _sqlite_integrity_check_error = \"\"\n"
    "            return\n"
    "    except OSError:\n"
    "        pass\n"
    "\n"
    "    if not _config.palace_path or not _is_chroma_backend():\n"
)
E1_OLD_TAIL = (
    "    _sqlite_integrity_checked = True\n"
    "\n"
    "    if _sqlite_integrity_errors:\n"
)
E1_NEW_TAIL = (
    "    _sqlite_integrity_checked = True\n"
    "\n"
    "    # patch_integrity_marker: 成功（无错误）→ 写 marker 供同 pod 其它进程跳过\n"
    "    if not _sqlite_integrity_errors:\n"
    "        try:\n"
    "            with open(\"/tmp/mempalace-integrity-ok.ts\", \"w\") as _mf:\n"
    "                _mf.write(str(time.time()))\n"
    "        except OSError:\n"
    "            pass\n"
    "\n"
    "    if _sqlite_integrity_errors:\n"
)


# ── patch E2: FTS 先 AND 回退首词单扫（召回保住 + 免 OR 双扫）──
E2_OLD = (
    "        if tokens:\n"
    "            fts_query = \" OR \".join(tokens)\n"
    "            filter_sql, filter_params = _metadata_filter_sql(\"embedding_fulltext_search.rowid\")\n"
    "            try:\n"
    "                rows = conn.execute(\n"
    "                    f\"\"\"\n"
    "                    SELECT embedding_fulltext_search.rowid\n"
    "                    FROM embedding_fulltext_search\n"
    "                    JOIN embeddings e ON e.id = embedding_fulltext_search.rowid\n"
    "                    JOIN segments s ON e.segment_id = s.id\n"
    "                    JOIN collections c ON s.collection = c.id\n"
    "                    WHERE embedding_fulltext_search MATCH ?\n"
    "                      AND c.name = ?\n"
    "                    {filter_sql}\n"
    "                    LIMIT ?\n"
    "                    \"\"\",\n"
    "                    (fts_query, collection_name, *filter_params, max_candidates),\n"
    "                ).fetchall()\n"
    "                candidate_ids = [r[0] for r in rows]\n"
    "            except sqlite3.Error:\n"
    "                # FTS5 tokenizer mismatch or syntax error — fall through\n"
    "                # to the recency-window selector below.\n"
    "                logger.debug(\"FTS5 MATCH failed; using recency fallback\", exc_info=True)\n"
    "                use_recency_fallback = True\n"
)
E2_NEW = (
    "        if tokens:\n"
    "            filter_sql, filter_params = _metadata_filter_sql(\"embedding_fulltext_search.rowid\")\n"
    "            # patch_d9: 先 AND（精确短语场景：首尾 trigram 同现才命中，\n"
    "            # 高频词 OR 会把目标 chunk 挤出候选池）；AND 无命中回退首词\n"
    "            # 单扫（便宜且首词必在精确短语中）。\n"
    "            for fts_query in (\" AND \".join(tokens), tokens[0]):\n"
    "                try:\n"
    "                    rows = conn.execute(\n"
    "                        f\"\"\"\n"
    "                        SELECT embedding_fulltext_search.rowid\n"
    "                        FROM embedding_fulltext_search\n"
    "                        JOIN embeddings e ON e.id = embedding_fulltext_search.rowid\n"
    "                        JOIN segments s ON e.segment_id = s.id\n"
    "                        JOIN collections c ON s.collection = c.id\n"
    "                        WHERE embedding_fulltext_search MATCH ?\n"
    "                          AND c.name = ?\n"
    "                        {filter_sql}\n"
    "                        LIMIT ?\n"
    "                        \"\"\",\n"
    "                        (fts_query, collection_name, *filter_params, max_candidates),\n"
    "                    ).fetchall()\n"
    "                    candidate_ids = [r[0] for r in rows]\n"
    "                except sqlite3.Error:\n"
    "                    candidate_ids = []\n"
    "                if candidate_ids:\n"
    "                    break\n"
    "            if not candidate_ids:\n"
    "                # FTS5 tokenizer mismatch / 两轮都无命中 — fall through\n"
    "                # to the recency-window selector below.\n"
    "                logger.debug(\"FTS5 MATCH failed; using recency fallback\", exc_info=True)\n"
    "                use_recency_fallback = True\n"
)


# ── patch C4: embedder 守卫的 current 与 palace 级解析一致（palace.py）─
C4_OLD = '''    if effective is not None and getattr(effective, "model_name", ""):
        current = effective
    else:
        try:
            model_name = current_model_name()
        except Exception:
            return
        if not model_name:
            return  # nameless embedder — cannot enforce identity
        current = EmbedderIdentity(model_name=model_name, dimension=0)'''
C4_NEW = '''    if effective is not None and getattr(effective, "model_name", ""):
        current = effective
    else:
        # patch_dashscope: current 与 palace 级 EF 解析保持一致——
        # 守卫读的是已解析集合（如 _v2）的 sidecar 条目，current 若仍取
        # 全局配置（minilm）会误报 mismatch
        try:
            from .backends.chroma import _palace_ef_override as _peo_dash

            _cname, _model = _peo_dash(palace_path, collection_name)
            if _model:
                current = EmbedderIdentity(model_name=_model, dimension=0)
            else:
                raise RuntimeError("not migrated")
        except Exception:
            try:
                model_name = current_model_name()
            except Exception:
                return
            if not model_name:
                return  # nameless embedder — cannot enforce identity
            current = EmbedderIdentity(model_name=model_name, dimension=0)'''


# ── patch G3: 查询归一化（中文虚词剥离，query_sanitizer.py）──
QS = "/app/.venv/lib/python3.12/site-packages/mempalace/query_sanitizer.py"
G3_HELPER = '''_LAC_FAIL_UNTIL = [0.0]  # LAC 失败冷却：不可达时 30s 内不再尝试


def _lac_strip(q):
    """调用 LAC 微服务分词+词性标注，过滤虚词（助词/连词/介词/叹词/
    语气词/标点/代词）。失败返回 None（调用方回退规则表）。"""
    import json as _json
    import os as _os
    import time as _time
    import urllib.request as _ur
    if _time.time() < _LAC_FAIL_UNTIL[0]:
        return None
    base = _os.environ.get("LAC_SERVICE_URL", "http://haisi-lac-service:5955")
    try:
        body = _json.dumps({"base_word": q}).encode()
        req = _ur.Request(base + "/p/lac", data=body,
                          headers={"Content-Type": "application/json"})
        with _ur.urlopen(req, timeout=0.2) as r:
            out = _json.loads(r.read().decode()).get("output", [[], []])
        words, tags = out[0], out[1]
        # LAC 24 类小标注集：u=助词 c=连词 p=介词 e=叹词 y=语气词
        # w=标点 r=代词（"哪些/这个"）。d=副词保留（"最大/仅"有语义，
        # 疑问副词由规则表兜底）。
        drop = {"u", "c", "p", "e", "y", "w", "r"}
        # 直接拼接不加空格：中文语料是连续写法，插空格会破坏 FTS
        # trigram 命中（"输入图像" 拆成 "输入 图像" 后答案块掉出 top5）
        return "".join(w for w, t in zip(words, tags)
                       if t not in drop and w.strip())
    except Exception:
        _LAC_FAIL_UNTIL[0] = _time.time() + 30.0
        return None


def _strip_cn_stopwords(q):
    """patch_cn_stopwords: 查询归一化——剥离中文虚词/疑问词 + 全角标点转空格。

    自然语言问句里的虚词会稀释向量与 BM25 信号，导致规格类答案块掉出
    top5；剥离后等价于关键词式问法。规则（词内字符保护优先）：
      - 多字疑问词（分别/多少/什么/如何…）无条件删除
      - 全角标点转空格
      - "的"：仅当前后都是 CJK 时保留（目的/的确），否则删除
        （"CV610的NPU" → "CV610 NPU"，无空格问句也生效）
      - 其它单字虚词（在/是/了/上/中/下…）：仅当后一位是 CJK 时保留
        （在线/存在/上电），否则删除（"统计上" → "统计"）
    """
    de = {"的"}
    tail_words = {"在", "是", "了", "为", "与", "和", "或", "及", "之", "也",
                  "都", "被", "把", "将", "给", "对", "从", "向", "请", "上",
                  "中", "下", "呢", "吧", "啊", "哦", "呀", "吗"}
    multis = ("分别", "多少", "什么", "哪些", "请问", "如何", "怎么", "怎样",
              "能否", "可以", "几个", "一个",
              "到底", "究竟", "大概", "大约", "是否", "是不是", "有没有",
              "这个", "那个", "这些", "那些",
              "以及", "而且", "并且", "或者", "但是", "不过", "因为", "所以",
              "另外", "此外", "关于", "对于", "根据", "按照", "通过",
              "麻烦", "帮我", "帮忙", "请帮", "一下")
    lac_result = _lac_strip(q)
    if lac_result is not None:
        return lac_result
    for ch in "、，。；：！？（）【】《》":
        q = q.replace(ch, " ")
    for m in multis:
        q = q.replace(m, " ")
    def _is_cjk(c):
        return 0x4E00 <= ord(c) <= 0x9FFF
    chars = list(q)
    for i, ch in enumerate(chars):
        if ch in de:
            prev_cjk = i > 0 and _is_cjk(chars[i - 1])
            next_cjk = i + 1 < len(chars) and _is_cjk(chars[i + 1])
            if not (prev_cjk and next_cjk):
                chars[i] = " "
        elif ch in tail_words:
            next_cjk = i + 1 < len(chars) and _is_cjk(chars[i + 1])
            if not next_cjk:
                chars[i] = " "
    return " ".join("".join(chars).split())


'''
G3_OLD_CALL = '''    raw_query = strip_lone_surrogates(raw_query.strip())
    original_length = len(raw_query)'''
G3_NEW_CALL = '''    raw_query = strip_lone_surrogates(raw_query.strip())
    raw_query = _strip_cn_stopwords(raw_query)  # patch_cn_stopwords: 查询归一化
    original_length = len(raw_query)'''


# --- patch W（写路径 palace 级 EF/集合名切换，2026-08-31）--------------------
# _palace_ef_override（patch C2）只 hook 了 ChromaBackend.get_collection 读路径；
# 写路径 /store → tool_add_drawer → mcp_server._get_collection 自己直接开
# mempalace_drawers，导致已切 v2(dashscope) 的 palace 新写入落回 v1(minilm)、
# 检索不可见。本 patch 给 _get_collection 的 chroma 分支加同款切换。

W_OLD1 = '''            if create:
                ef = ChromaBackend._resolve_embedding_function()
                ef_kwargs = {"embedding_function": ef} if ef is not None else {}'''
W_NEW1 = '''            # patch_dashscope_write: 写路径 palace 级 EF/集合名切换（与 ChromaBackend.get_collection 一致）
            from .backends.chroma import _palace_ef_override as _peo_w
            _cname_w, _ef_model_w = _peo_w(_config.palace_path, _config.collection_name)
            if _ef_model_w == "dashscope":
                from .embedding import get_embedding_function as _gef_w
                _ef_w = _gef_w(model="dashscope")
            else:
                _ef_w = ChromaBackend._resolve_embedding_function()
            if create:
                ef = _ef_w
                ef_kwargs = {"embedding_function": ef} if ef is not None else {}'''

W_OLD2 = '''                    raw = client.get_collection(_config.collection_name, **ef_kwargs)
                except _ChromaNotFoundError:
                    raw = client.create_collection(
                        _config.collection_name,'''
W_NEW2 = '''                    raw = client.get_collection(_cname_w, **ef_kwargs)
                except _ChromaNotFoundError:
                    raw = client.create_collection(
                        _cname_w,'''

W_OLD3 = '''            elif _collection_cache is None:
                ef = ChromaBackend._resolve_embedding_function()
                ef_kwargs = {"embedding_function": ef} if ef is not None else {}
                raw = client.get_collection(_config.collection_name, **ef_kwargs)'''
W_NEW3 = '''            elif _collection_cache is None:
                ef = _ef_w
                ef_kwargs = {"embedding_function": ef} if ef is not None else {}
                raw = client.get_collection(_cname_w, **ef_kwargs)'''




# --- patch V（新建 palace 默认启用 text-embedding-v4，2026-09-03）----------
# kb307024dd 建库时 palace 无 dashscope 条目 → 写入全走 minilm（中文召回弱）。
# 本 patch：_palace_ef_override 读到 json 缺失且 palace 尚无 chroma.sqlite3
# （=全新 palace）时，直接写回 dashscope 开关 json 并返回 dashscope EF——
# 之后写路径（patch W）会以 DashScopeEF 建集合，读路径同开关。
# 已有数据的旧 palace（有 sqlite 或有 json）行为完全不变。

V_OLD = """    except Exception:
        return requested_name, None
    for cname, info in reg.items():"""
V_NEW = """    except Exception:
        # patch_auto_v4: 新建 palace（无 json 且 sqlite 尚无任何集合）默认直接
        # 启用商用 text-embedding-v4（DashScopeEF）并写回 json 固化开关；已有
        # 数据的旧 palace（已有集合或已有 json）保持原行为不受影响。
        # 注意：PersistentClient 初始化会先建出空的 chroma.sqlite3，
        # 所以新鲜度必须看 collections 表行数，不能看 sqlite 文件是否存在。
        try:
            _fresh_v = False
            _db_v = os.path.join(palace_path, "chroma.sqlite3")
            if os.path.exists(_db_v):
                import sqlite3 as _sq_v
                _cn_v = _sq_v.connect(f"file:{_db_v}?mode=ro", uri=True)
                try:
                    _fresh_v = _cn_v.execute(
                        "SELECT COUNT(*) FROM collections").fetchone()[0] == 0
                except Exception:
                    _fresh_v = True
                finally:
                    _cn_v.close()
            else:
                _fresh_v = True
            if _fresh_v:
                _reg_v = {requested_name: {"model_name": "dashscope", "dimension": 1024}}
                os.makedirs(palace_path, exist_ok=True)
                with open(os.path.join(palace_path, "mempalace_embedder.json"),
                          "w", encoding="utf-8") as _f_v:
                    json.dump(_reg_v, _f_v, indent=2)
                return requested_name, "dashscope"
        except Exception:
            pass
        return requested_name, None
    for cname, info in reg.items():"""

# ── patch FV1/FV2：文件级多版本（RAG 文件版本管理）─────────────────
FV1_OLD_TOKENIZE = "def _tokenize(text: str) -> list:"
FV1_NEW_TOKENIZE = '''# ── 文件级多版本：active 版本过滤（RAG 文件版本管理透出）────────────
# file_versions.sqlite3 与 chroma 库同目录、独立于 chroma 内部 schema，
# 记录每个 source_file 的 active 版本；search 只返回每文件 active 版本的
# chunk（无记录的文件不过滤；chunk 无 file_version 元数据视为 v1）。


def _file_versions_conn(palace_path: str):
    """打开（惰性建表）palace 的 file_versions 库；不可用返回 None（不过滤）。"""
    try:
        path = os.path.join(palace_path, "file_versions.sqlite3")
        conn = sqlite3.connect(f"file:{path}?mode=rwc", uri=True, timeout=10)
        conn.execute(
            "CREATE TABLE IF NOT EXISTS file_versions ("
            "source_file TEXT PRIMARY KEY, active_version INTEGER NOT NULL)")
        return conn
    except Exception:
        return None


def set_active_file_version(palace_path: str, source_file: str, version: int) -> bool:
    """登记/切换 source_file 的 active 版本（写入方与回滚方调用）。"""
    conn = _file_versions_conn(palace_path)
    if conn is None:
        return False
    try:
        conn.execute(
            "INSERT INTO file_versions(source_file, active_version) VALUES(?, ?) "
            "ON CONFLICT(source_file) DO UPDATE SET active_version=excluded.active_version",
            (source_file, int(version)))
        conn.commit()
        return True
    except Exception:
        return False
    finally:
        conn.close()


def get_active_file_versions(palace_path: str) -> dict:
    """读 active 版本映射；无库/读失败返回空 dict（过滤零开销关闭）。"""
    conn = _file_versions_conn(palace_path)
    if conn is None:
        return {}
    try:
        return {r[0]: r[1] for r in conn.execute(
            "SELECT source_file, active_version FROM file_versions")}
    except Exception:
        return {}
    finally:
        conn.close()


def _chunk_version_active(active_map: dict, source_file: str, chunk_version) -> bool:
    """chunk 是否属于其 source_file 的 active 版本（无元数据视为 v1）。"""
    if not active_map or not source_file:
        return True
    active = active_map.get(source_file)
    if active is None:
        return True
    v = chunk_version if isinstance(chunk_version, int) else 1
    return v == active


def _tokenize(text: str) -> list:'''

FV1_OLD_OPEN = '''    drawers_col, open_error = _open_search_collection(palace_path, collection_name)
    if open_error:
        return open_error
'''
FV1_NEW_OPEN = '''    drawers_col, open_error = _open_search_collection(palace_path, collection_name)
    if open_error:
        return open_error

    # 文件级多版本：每文件 active 版本过滤（无登记时为空 dict，零开销）
    active_versions = get_active_file_versions(palace_path)
'''

FV1_OLD_SRC = '''        meta = meta or {}
        source = meta.get("source_file", "") or ""
        boost = 0.0'''
FV1_NEW_SRC = '''        meta = meta or {}
        source = meta.get("source_file", "") or ""

        # 文件级多版本：只保留该文件 active 版本的 chunk
        if active_versions and not _chunk_version_active(
                active_versions, source, meta.get("file_version")):
            continue

        boost = 0.0'''


def _fv2_helpers() -> str:
    return '''def _activate_file_version(source_file: str, file_version: int) -> None:
    """登记 source_file 的 active 版本（search 过滤依据）。失败仅告警。"""
    try:
        from mempalace.searcher import set_active_file_version
        if not set_active_file_version(_config.palace_path, source_file, file_version):
            logger.warning("set_active_file_version failed for {}", source_file)
    except Exception:
        logger.warning("activate file version failed for {}", source_file, exc_info=True)


def _retag_file_version(col, drawer_id: str, file_version: int, source_file: str) -> None:
    """把同内容既有 chunk 的 file_version 元数据更新为新版本并切 active。

    用于"相同内容再次写入"（回滚恢复）场景：chunk ID 由内容哈希决定，
    重复写入走 already_exists 分支，这里补齐版本元数据迁移。
    """
    try:
        # 只匹配本次内容对应的 chunk（drawer_id 自身 + 其分块），绝不能按
        # source_file 全量匹配——同文件其他版本的 chunk 也会带相同
        # source_file，会把它们的 file_version 一起改掉（版本过滤失效）。
        res1 = col.get(ids=[drawer_id], include=["documents", "metadatas"])
        res2 = col.get(where={"parent_drawer_id": drawer_id},
                       include=["documents", "metadatas"])
        ids = list(dict.fromkeys((res1.get("ids") or []) + (res2.get("ids") or [])))
        docs = (res1.get("documents") or []) + (res2.get("documents") or [])
        metas = (res1.get("metadatas") or []) + (res2.get("metadatas") or [])
        if not ids:
            return
        new_metas = []
        for m in metas:
            m = dict(m) if isinstance(m, dict) else {}
            m["file_version"] = file_version
            new_metas.append(m)
        col.upsert(ids=ids, documents=docs, metadatas=new_metas)
        _activate_file_version(source_file, file_version)
        logger.info("Retagged {:d} chunks of {} to file_version={}",
                    len(ids), source_file, file_version)
    except Exception:
        logger.warning("retag file_version failed for {}", source_file, exc_info=True)


def tool_set_file_version(source_file: str, file_version: int):
    """切换 source_file 的 active 版本（文件级多版本管理的回滚面）。

    只切指针不动数据：旧版本 chunk 留存于库中，search 的 active 过滤
    立即生效；可再次切回。
    """
    if not source_file or not isinstance(file_version, int):
        return {"success": False, "error": "source_file 与 file_version 必填"}
    _activate_file_version(source_file, file_version)
    return {"success": True, "source_file": source_file,
            "active_version": file_version}


def tool_delete_file_version_chunks(source_file: str, max_version: int):
    """删除 source_file 的 file_version <= max_version 的 chunk（保留策略清理）。

    只删带 file_version 元数据的 chunk；无版本的存量 chunk（视为 v1）
    不动——active 过滤已使其不可见，残留无害。
    """
    col = _get_collection()
    if not col:
        return _collection_error_or_no_palace()
    try:
        res = col.get(where={"source_file": source_file}, include=["metadatas"])
        ids = [i for i, m in zip(res.get("ids") or [], res.get("metadatas") or [])
               if isinstance(m, dict)
               and isinstance(m.get("file_version"), int)
               and m["file_version"] <= max_version]
        # 混血库存在重复元数据行，chroma delete 需去重（DuplicateIDError 坑）
        ids = list(dict.fromkeys(ids))
        if ids:
            col.delete(ids=ids)
        return {"success": True, "source_file": source_file,
                "max_version": max_version, "deleted": len(ids)}
    except Exception as e:
        return {"success": False, "error": str(e)}


'''

FV2_OLD_DEF = "def tool_add_drawer("
FV2_NEW_DEF = None  # 运行时用 _fv2_helpers() 拼接

FV2_OLD_SIG = '''def tool_add_drawer(
    wing: str, room: str, content: str, source_file: str = None, added_by: str = "mcp"
):'''
FV2_NEW_SIG = '''def tool_add_drawer(
    wing: str, room: str, content: str, source_file: str = None, added_by: str = "mcp",
    file_version: int = None,
):'''

FV2_OLD_META = '''    base_meta = {
        "wing": wing,
        "room": room,
        "source_file": source_file or "",
        "added_by": added_by,
        "filed_at": datetime.now().isoformat(),
        "id_recipe": ID_RECIPE,
    }'''
FV2_NEW_META = '''    base_meta = {
        "wing": wing,
        "room": room,
        "source_file": source_file or "",
        "added_by": added_by,
        "filed_at": datetime.now().isoformat(),
        "id_recipe": ID_RECIPE,
    }
    # 文件级多版本：chunk ID 公式（内容哈希）不含版本——未变 chunk 跨版本
    # 复用；版本只进 metadata，供 search 的 active 版本过滤与结果溯源。
    if isinstance(file_version, int):
        base_meta["file_version"] = file_version'''

FV2_OLD_EXIST = '''        existing = col.get(ids=idempotency_probe_ids, include=[])
        if _get_result_ids(existing):
            return {"success": True, "reason": "already_exists", "drawer_id": drawer_id}'''
FV2_NEW_EXIST = '''        existing = col.get(ids=idempotency_probe_ids, include=[])
        if _get_result_ids(existing):
            # 相同内容重复写入（含回滚恢复）：把既有 chunk 的版本元数据
            # 更新为本次 file_version（chunk ID 因内容相同而复用），并切
            # active 指针——零嵌入成本完成版本切换。
            if isinstance(file_version, int) and source_file:
                _retag_file_version(col, drawer_id, file_version, source_file)
            return {"success": True, "reason": "already_exists", "drawer_id": drawer_id}'''

FV2_OLD_OK1 = '''            _metadata_cache = None
            logger.info(f"Filed drawer: {drawer_id} → {wing}/{room}")'''
FV2_NEW_OK1 = '''            _metadata_cache = None
            if isinstance(file_version, int) and source_file:
                _activate_file_version(source_file, file_version)
            logger.info(f"Filed drawer: {drawer_id} → {wing}/{room}")'''

FV2_OLD_OK2 = '''        _metadata_cache = None
        logger.info(f"Filed drawer: {drawer_id} → {wing}/{room} ({len(chunk_ids)} chunks)")'''
FV2_NEW_OK2 = '''        _metadata_cache = None
        if isinstance(file_version, int) and source_file:
            _activate_file_version(source_file, file_version)
        logger.info(f"Filed drawer: {drawer_id} → {wing}/{room} ({len(chunk_ids)} chunks)")'''


def main():
    # 1) mcp_server.py：探针缓存 + 计时埋点
    _UTF8_KEY = 'utf-8'
    _PCT_S = '%s'
    _SEARCHER_OPEN_FAIL_FMT = '[search-probe-patch] searcher.py 打开失败: %s'
    _MCP_OPEN_FAIL_FMT = '[search-probe-patch] mcp_server.py 打开失败: %s'
    _SANITIZE_QUERY_DEF = 'def sanitize_query(raw_query: str) -> dict:'
    try:
        with open(TARGET, encoding=_UTF8_KEY) as f:
            src = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] 打开失败: %s', e)
        return 1
    if MARKER not in src:
        if OLD_VAR not in src or OLD_HEAD not in src:
            logger.info(_PCT_S, '[search-probe-patch] 目标代码块未找到（包版本变化？）——跳过')
            return 0
        src = src.replace(OLD_VAR, NEW_VAR, 1).replace(OLD_HEAD, NEW_HEAD, 1)
        logger.info(_PCT_S, '[search-probe-patch] 已应用：探针进程级缓存')
    if TIMING_MARKER not in src:
        if TIMING_OLD1 in src and TIMING_OLD2 in src:
            src = src.replace(TIMING_OLD1, TIMING_NEW1, 1).replace(TIMING_OLD2, TIMING_NEW2, 1)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：分相计时埋点')
    if PRE_MARKER not in src:
        if PRE_OLD in src:
            src = src.replace(PRE_OLD, PRE_NEW, 1)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：预检门计时埋点')
    if ENTER_MARKER not in src:
        if ENTER_OLD in src:
            src = src.replace(ENTER_OLD, ENTER_NEW, 1)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：tools/call 入口埋点')
    if ASYNC_MARKER not in src:
        if ASYNC_OLD in src:
            src = src.replace(ASYNC_OLD, ASYNC_NEW, 1)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：完整性门异步化')
    with open(TARGET, "w", encoding=_UTF8_KEY) as f:
        f.write(src)

    # 2) repair.py：PRAGMA quick_check → quick_check(1) 快速模式
    try:
        with open(REPAIR_TARGET, encoding=_UTF8_KEY) as f:
            rsrc = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] repair.py 打开失败: %s', e)
        return 1
    if "quick_check(1)" not in rsrc:
        if REPAIR_OLD in rsrc:
            rsrc = rsrc.replace(REPAIR_OLD, REPAIR_NEW, 1)
            with open(REPAIR_TARGET, "w", encoding=_UTF8_KEY) as f:
                f.write(rsrc)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：quick_check(1) 快速模式')
        else:
            logger.info(_PCT_S, '[search-probe-patch] repair.py 目标行未找到——跳过')

    # 3) searcher.py：union 词法召回 patch B
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            ssrc = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    if UNION_MARKER not in ssrc:
        n_applied = 0
        for old, new in UNION_PATCHES:
            if old in ssrc:
                ssrc = ssrc.replace(old, new, 1)
                n_applied += 1
            else:
                logger.info('[search-probe-patch] union 目标块未找到——跳过: %s', old[:60])
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(ssrc)
        logger.info('[search-probe-patch] 已应用：union 词法召回（%s/%s 块）', n_applied, len(UNION_PATCHES))
    else:
        logger.info(_PCT_S, '[search-probe-patch] union 词法召回已存在——跳过')
    # B2b：候选上限 500→100（对已打过 B2 的文件也生效）
    if U_OLD_CAP in ssrc:
        ssrc = ssrc.replace(U_OLD_CAP, U_NEW_CAP, 1)
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(ssrc)
        logger.info(_PCT_S, '[search-probe-patch] 已应用：union 候选上限 100')

    # 4) embedding.py：DashScope EF（patch C）
    try:
        with open(EMBEDDING, encoding=_UTF8_KEY) as f:
            esrc = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] embedding.py 打开失败: %s', e)
        return 1
    if DASH_MARKER not in esrc:
        n_applied = 0
        if DASH_OLD_ANCHOR in esrc:
            esrc = esrc.replace(DASH_OLD_ANCHOR, DASH_NEW_ANCHOR, 1)
            n_applied += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] DashScope 插入锚点未找到——跳过')
        if DASH_OLD_BRANCH in esrc:
            esrc = esrc.replace(DASH_OLD_BRANCH, DASH_NEW_BRANCH, 1)
            n_applied += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] DashScope 分支锚点未找到——跳过')
        with open(EMBEDDING, "w", encoding=_UTF8_KEY) as f:
            f.write(esrc)
        logger.info('[search-probe-patch] 已应用：DashScope EF（%s/2 块）', n_applied)
    else:
        logger.info(_PCT_S, '[search-probe-patch] DashScope EF 已存在——跳过')

    # 5) backends/chroma.py：palace 级 EF/集合名解析（patch C2）
    try:
        with open(CHROMA_BE, encoding=_UTF8_KEY) as f:
            csrc = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] chroma.py 打开失败: %s', e)
        return 1
    n_applied = 0
    if C2_OLD_HELPER_ANCHOR in csrc:
        if "_palace_ef_override" not in csrc:
            csrc = csrc.replace(C2_OLD_HELPER_ANCHOR, C2_HELPER, 1)
            n_applied += 1
    else:
        logger.info(_PCT_S, '[search-probe-patch] chroma.py helper 锚点未找到——跳过')
    if C2_OLD_ANCHOR in csrc:
        csrc = csrc.replace(C2_OLD_ANCHOR, C2_NEW_ANCHOR, 1)
        n_applied += 1
    else:
        logger.info(_PCT_S, '[search-probe-patch] chroma.py get_collection 锚点未找到——跳过')
    with open(CHROMA_BE, "w", encoding=_UTF8_KEY) as f:
        f.write(csrc)
    logger.info('[search-probe-patch] 已应用：palace 级 EF 解析（%s/2 块）', n_applied)

    # 6) searcher.py：BM25 词法召回集合名跟随切换（patch C3）
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            s3src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    if "patch_dashscope: palace 已迁移百炼" not in s3src:
        if C3_OLD in s3src:
            s3src = s3src.replace(C3_OLD, C3_NEW, 1)
            with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
                f.write(s3src)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：BM25 集合名跟随切换')
        else:
            logger.info(_PCT_S, '[search-probe-patch] searcher.py BM25 锚点未找到——跳过')
    else:
        logger.info(_PCT_S, '[search-probe-patch] BM25 集合名切换已存在——跳过')

    # 7) palace.py：embedder 守卫 current 解析对齐（patch C4）
    try:
        with open("/app/.venv/lib/python3.12/site-packages/mempalace/palace.py",
                  encoding=_UTF8_KEY) as f:
            p4src = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] palace.py 打开失败: %s', e)
        return 1
    if "patch_dashscope: current 与 palace 级 EF 解析保持一致" not in p4src:
        if C4_OLD in p4src:
            p4src = p4src.replace(C4_OLD, C4_NEW, 1)
            with open("/app/.venv/lib/python3.12/site-packages/mempalace/palace.py",
                      "w", encoding=_UTF8_KEY) as f:
                f.write(p4src)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：守卫 current 解析对齐')
        else:
            logger.info(_PCT_S, '[search-probe-patch] palace.py 守卫锚点未找到——跳过')
    else:
        logger.info(_PCT_S, '[search-probe-patch] 守卫对齐已存在——跳过')

    # 8) searcher.py：union 词法候选与向量查询并行（patch O1）
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            o1src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    if "patch_parallel" not in o1src:
        n1 = 0
        if O1_OLD_IMPORTS in o1src:
            o1src = o1src.replace(O1_OLD_IMPORTS, O1_NEW_IMPORTS, 1)
            n1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O1 imports 锚点未找到——跳过')
        if O1_HELPERS_ANCHOR in o1src:
            o1src = o1src.replace(O1_HELPERS_ANCHOR, O1_HELPERS, 1)
            n1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O1 helpers 锚点未找到——跳过')
        if O1_OLD_START in o1src:
            o1src = o1src.replace(O1_OLD_START, O1_NEW_START, 1)
            n1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O1 start 锚点未找到——跳过')
        if O1_OLD_MERGE in o1src:
            o1src = o1src.replace(O1_OLD_MERGE, O1_NEW_MERGE, 1)
            n1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O1 merge 锚点未找到——跳过')
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(o1src)
        logger.info('[search-probe-patch] 已应用：union 并行化（%s/4 块）', n1)
    else:
        logger.info(_PCT_S, '[search-probe-patch] union 并行化已存在——跳过')

    # 9) embedding.py：keep-alive 连接复用（patch O2）
    try:
        with open(EMBEDDING, encoding=_UTF8_KEY) as f:
            o2src = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] embedding.py 打开失败: %s', e)
        return 1
    if "patch_dashscope_keepalive" not in o2src:
        n2 = 0
        if O2_HELPER_ANCHOR in o2src:
            o2src = o2src.replace(O2_HELPER_ANCHOR, O2_HELPER, 1)
            n2 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O2 helper 锚点未找到——跳过')
        if O2_OLD_TRANSPORT in o2src:
            o2src = o2src.replace(O2_OLD_TRANSPORT, O2_NEW_TRANSPORT, 1)
            n2 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] O2 transport 锚点未找到——跳过')
        with open(EMBEDDING, "w", encoding=_UTF8_KEY) as f:
            f.write(o2src)
        logger.info('[search-probe-patch] 已应用：keep-alive（%s/2 块）', n2)
    else:
        logger.info(_PCT_S, '[search-probe-patch] keep-alive 已存在——跳过')

    # 10) patch D: context_chunks 相邻块扩展（仅 site-packages 可写文件；
    #     rest_adapter.py / mcp_stdio_bridge.py 是 CM 只读挂载，D3/D4 已
    #     离线打进 CM 文件，此处不再处理）
    d_patches = [
        (SEARCHER, D1_OLD_SIG, D1_NEW_SIG, "searcher 签名"),
        (SEARCHER, D1_OLD_CALL, D1_NEW_CALL, "searcher finalize 调用"),
        (SEARCHER, D1_OLD_FSIG, D1_NEW_FSIG, "searcher finalize 签名"),
        (SEARCHER, D1_OLD_FBODY, D1_NEW_FBODY, "searcher finalize 主体"),
        (SEARCHER, D1_OLD_VECPARENT, D1_NEW_VECPARENT, "searcher 向量 parent"),
        (SEARCHER, D1_OLD_BM25, D1_NEW_BM25, "searcher bm25 parent"),
        (SEARCHER, D1_OLD_MERGE, D1_NEW_MERGE, "searcher merger parent"),
        (TARGET, D2_OLD_TSIG, D2_NEW_TSIG, "tool_search 签名"),
        (TARGET, D2_OLD_SCHEMA, D2_NEW_SCHEMA, "tool schema"),
    ]
    d_files = {}
    d_n = 0
    for path, old, new, label in d_patches:
        src = d_files.get(path)
        if src is None:
            try:
                with open(path, encoding=_UTF8_KEY) as f:
                    src = f.read()
            except OSError as e:
                logger.info('[search-probe-patch] %s 打开失败: %s', path, e)
                return 1
            d_files[path] = src
        if old in src:
            src = src.replace(old, new, 1)
            d_files[path] = src  # 必须写回：字符串不可变，replace 返回新对象
            d_n += 1
        else:
            logger.info('[search-probe-patch] D 锚点未找到——跳过: %s', label)
    for path, src in d_files.items():
        with open(path, "w", encoding=_UTF8_KEY) as f:
            f.write(src)
    # D2 的 kwargs 双处替换（首查 8 空格缩进 + 重试 12 空格缩进）
    try:
        with open(TARGET, encoding=_UTF8_KEY) as f:
            msrc = f.read()
        if D2_OLD_KW1 in msrc:
            msrc = msrc.replace(D2_OLD_KW1, D2_NEW_KW1, 1)
            d_n += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] D kwargs1 未找到——跳过')
        if D2_OLD_KW in msrc:
            msrc = msrc.replace(D2_OLD_KW, D2_NEW_KW, 1)
            d_n += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] D kwargs2 未找到——跳过')
        with open(TARGET, "w", encoding=_UTF8_KEY) as f:
            f.write(msrc)
    except OSError as e:
        logger.info('[search-probe-patch] mcp_server kwargs 处理失败: %s', e)
    # D1 helper 插入（独立判断，避免已应用时重复）
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            hsrc = f.read()
        if "patch_context_chunks: 命中 chunk 拼入同组" not in hsrc:
            if D1_HELPER_ANCHOR in hsrc:
                hsrc = hsrc.replace(D1_HELPER_ANCHOR, D1_HELPER, 1)
                with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
                    f.write(hsrc)
                d_n += 1
            else:
                logger.info(_PCT_S, '[search-probe-patch] D1 helper 锚点未找到——跳过')
        else:
            logger.info(_PCT_S, '[search-probe-patch] D1 helper 已存在——跳过')
    except OSError as e:
        logger.info('[search-probe-patch] searcher helper 处理失败: %s', e)
    logger.info('[search-probe-patch] 已应用：context_chunks 扩展（%s 块）', d_n)

    # 11) patch D5: 已应用旧版 hydration 的 pod 换成 sqlite 直读
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            d5src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    n5 = 0
    if "_fetch_group_chunks_sqlite" not in d5src:
        if D5_FN_ANCHOR in d5src:
            d5src = d5src.replace(D5_FN_ANCHOR, D5_FN, 1)
            n5 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] D5 fn 锚点未找到——跳过')
        if D5_OLD_TTL in d5src:
            d5src = d5src.replace(D5_OLD_TTL, D5_NEW_TTL, 1)
            n5 += 1
        if D5_OLD_FETCH in d5src:
            d5src = d5src.replace(D5_OLD_FETCH, D5_NEW_FETCH, 1)
            n5 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] D5 fetch 锚点未找到——跳过')
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(d5src)
        logger.info('[search-probe-patch] 已应用：hydration sqlite 直读（%s/3 块）', n5)
    # 12) patch D6: FTS 提速（长词拆 trigram + 候选上限 30）
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            d6src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    n6 = 0
    if D6_OLD_TOK in d6src:
        d6src = d6src.replace(D6_OLD_TOK, D6_NEW_TOK, 1)
        n6 += 1
    if "                max_candidates=100, _include_internal=True)" in d6src:
        d6src = d6src.replace(
            "                max_candidates=100, _include_internal=True)",
            "                max_candidates=20, _include_internal=True)  # patch_d6", 1)
        n6 += 1
    elif D6_OLD_CAP in d6src:
        d6src = d6src.replace(
            D6_OLD_CAP, "                max_candidates=20,  # patch_d6\n", 1)
        n6 += 1
    with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
        f.write(d6src)
    logger.info('[search-probe-patch] 已应用：FTS 提速（%s/2 类）', n6)

    # 12a) patch E1: 完整性 marker
    try:
        with open(TARGET, encoding=_UTF8_KEY) as f:
            e1src = f.read()
    except OSError as e:
        logger.info(_MCP_OPEN_FAIL_FMT, e)
        return 1
    n_e1 = 0
    if "patch_integrity_marker" not in e1src:
        if E1_OLD_HEAD in e1src:
            e1src = e1src.replace(E1_OLD_HEAD, E1_NEW_HEAD, 1)
            n_e1 += 1
        if E1_OLD_TAIL in e1src:
            e1src = e1src.replace(E1_OLD_TAIL, E1_NEW_TAIL, 1)
            n_e1 += 1
        with open(TARGET, "w", encoding=_UTF8_KEY) as f:
            f.write(e1src)
        logger.info('[search-probe-patch] 已应用：完整性 marker（%s/2 块）', n_e1)
    else:
        logger.info(_PCT_S, '[search-probe-patch] 完整性 marker 已存在——跳过')

    # 12b) patch D8: 旧 6 词函数体换 2 词版
    if D8_OLD_FN in d6src:
        d6src = d6src.replace(D8_OLD_FN, D8_NEW_FN, 1)
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(d6src)
        logger.info(_PCT_S, '[search-probe-patch] 已应用：FTS 首尾 2 词')
    else:
        logger.info(_PCT_S, '[search-probe-patch] D8 锚点未找到——跳过')

    # 13) patch D7: 长词拆 trigram helper
    if "def _fts_split_tokens" not in d6src:
        if D7_HELPER_ANCHOR in d6src:
            d6src = d6src.replace(D7_HELPER_ANCHOR, D7_HELPER, 1)
            with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
                f.write(d6src)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：长词 trigram 拆分')
        else:
            logger.info(_PCT_S, '[search-probe-patch] D7 锚点未找到——跳过')
    else:
        logger.info(_PCT_S, '[search-probe-patch] 长词 trigram 拆分已存在——跳过')

    # 12a2) patch E2: FTS AND 优先
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            e2src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    if "patch_d9" not in e2src:
        if E2_OLD in e2src:
            e2src = e2src.replace(E2_OLD, E2_NEW, 1)
            with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
                f.write(e2src)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：FTS AND 优先')
        else:
            logger.info(_PCT_S, '[search-probe-patch] E2 锚点未找到——跳过')
    else:
        logger.info(_PCT_S, '[search-probe-patch] FTS AND 优先已存在——跳过')

    # 13) patch G3: 查询归一化（中文虚词剥离）
    try:
        with open(QS, encoding=_UTF8_KEY) as f:
            g3src = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] query_sanitizer.py 打开失败: %s', e)
        return 1
    if "patch_cn_stopwords" not in g3src:
        n_g3 = 0
        if _SANITIZE_QUERY_DEF in g3src:
            g3src = g3src.replace(_SANITIZE_QUERY_DEF,
                                  G3_HELPER + _SANITIZE_QUERY_DEF, 1)
            n_g3 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] G3 函数锚点未找到——跳过')
        if G3_OLD_CALL in g3src:
            g3src = g3src.replace(G3_OLD_CALL, G3_NEW_CALL, 1)
            n_g3 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] G3 调用锚点未找到——跳过')
        with open(QS, "w", encoding=_UTF8_KEY) as f:
            f.write(g3src)
        logger.info('[search-probe-patch] 已应用：查询归一化（%s/2 块）', n_g3)
    else:
        logger.info(_PCT_S, '[search-probe-patch] 查询归一化已存在——跳过')

    # 14) mcp_server.py：写路径 palace 级 EF/集合名切换（patch W，与 C2 读路径对齐）
    try:
        with open(TARGET, encoding=_UTF8_KEY) as f:
            wsrc = f.read()
    except OSError as e:
        logger.info(_MCP_OPEN_FAIL_FMT, e)
        return 1
    if "patch_dashscope_write" not in wsrc:
        n_w = 0
        for wold, wnew in ((W_OLD1, W_NEW1), (W_OLD2, W_NEW2), (W_OLD3, W_NEW3)):
            if wold in wsrc:
                wsrc = wsrc.replace(wold, wnew, 1)
                n_w += 1
            else:
                logger.info(_PCT_S, '[search-probe-patch] patch W 锚点未找到——跳过一块')
        with open(TARGET, "w", encoding=_UTF8_KEY) as f:
            f.write(wsrc)
        logger.info('[search-probe-patch] 已应用：写路径 EF 切换（%s/3 块）', n_w)
    else:
        logger.info(_PCT_S, '[search-probe-patch] 写路径 EF 切换已存在——跳过')

    # 15) backends/chroma.py：新建 palace 默认启用 text-embedding-v4（patch V）
    try:
        with open(CHROMA_BE, encoding=_UTF8_KEY) as f:
            vsrc = f.read()
    except OSError as e:
        logger.info('[search-probe-patch] chroma.py 打开失败: %s', e)
        return 1
    if "patch_auto_v4" not in vsrc:
        if V_OLD in vsrc:
            vsrc = vsrc.replace(V_OLD, V_NEW, 1)
            with open(CHROMA_BE, "w", encoding=_UTF8_KEY) as f:
                f.write(vsrc)
            logger.info(_PCT_S, '[search-probe-patch] 已应用：新 palace 默认 v4（patch V 1/1 块）')
        else:
            logger.info(_PCT_S, '[search-probe-patch] patch V 锚点未找到——跳过')
    else:
        logger.info(_PCT_S, '[search-probe-patch] 新 palace 默认 v4 已存在——跳过')

    # 16) patch FV1: searcher.py 文件级多版本——file_versions active 过滤
    try:
        with open(SEARCHER, encoding=_UTF8_KEY) as f:
            fv1src = f.read()
    except OSError as e:
        logger.info(_SEARCHER_OPEN_FAIL_FMT, e)
        return 1
    if "file_versions.sqlite3" not in fv1src:
        n_fv1 = 0
        if FV1_OLD_TOKENIZE in fv1src:
            fv1src = fv1src.replace(FV1_OLD_TOKENIZE, FV1_NEW_TOKENIZE, 1)
            n_fv1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] FV1 tokenize 锚点未找到——跳过')
        if FV1_OLD_OPEN in fv1src:
            fv1src = fv1src.replace(FV1_OLD_OPEN, FV1_NEW_OPEN, 1)
            n_fv1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] FV1 open 锚点未找到——跳过')
        if FV1_OLD_SRC in fv1src:
            fv1src = fv1src.replace(FV1_OLD_SRC, FV1_NEW_SRC, 1)
            n_fv1 += 1
        else:
            logger.info(_PCT_S, '[search-probe-patch] FV1 source 锚点未找到——跳过')
        with open(SEARCHER, "w", encoding=_UTF8_KEY) as f:
            f.write(fv1src)
        logger.info('[search-probe-patch] 已应用：文件级多版本过滤（%s/3 块）', n_fv1)
    else:
        logger.info(_PCT_S, '[search-probe-patch] 文件级多版本过滤已存在——跳过')

    # 17) patch FV2: mcp_server.py 文件级多版本——file_version 元数据/retag/工具
    try:
        with open(TARGET, encoding=_UTF8_KEY) as f:
            fv2src = f.read()
    except OSError as e:
        logger.info(_MCP_OPEN_FAIL_FMT, e)
        return 1
    if "tool_set_file_version" not in fv2src:
        n_fv2 = 0
        for fvold, fvnew, fvname in (
            (FV2_OLD_DEF, _fv2_helpers() + FV2_OLD_DEF, "add_drawer 定义前插工具"),
            (FV2_OLD_SIG, FV2_NEW_SIG, "add_drawer 签名"),
            (FV2_OLD_META, FV2_NEW_META, "base_meta"),
            (FV2_OLD_EXIST, FV2_NEW_EXIST, "already_exists retag"),
            (FV2_OLD_OK1, FV2_NEW_OK1, "单块写入 activate"),
            (FV2_OLD_OK2, FV2_NEW_OK2, "分块写入 activate"),
        ):
            if fvold in fv2src:
                fv2src = fv2src.replace(fvold, fvnew, 1)
                n_fv2 += 1
            else:
                logger.info('[search-probe-patch] FV2 锚点未找到——跳过（%s）', fvname)
        with open(TARGET, "w", encoding=_UTF8_KEY) as f:
            f.write(fv2src)
        logger.info('[search-probe-patch] 已应用：file_version 写入与工具（%s/6 块）', n_fv2)
    else:
        logger.info(_PCT_S, '[search-probe-patch] file_version 工具已存在——跳过')

    return 0


if __name__ == "__main__":
    sys.exit(main())