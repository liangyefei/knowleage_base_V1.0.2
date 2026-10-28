"""dual-mcp（代码知识图谱）多路检索整合。"""
import asyncio
import os
import re
import threading
import time as _time

import httpx
from langchain.docstore.document import Document
from server._http_constants import (
    HTTP_OK,
    LLM_ROUTE_TIMEOUT_SEC,
    HTTP_MCP_PROJECTS_TIMEOUT_SEC,
    HTTP_MCP_CONNECT_TIMEOUT_SEC,
    HTTP_MCP_FALLBACK_TOTAL_TIMEOUT_SEC,
    HTTP_MCP_QUERY_FALLBACK_TIMEOUT_SEC,
)

from configs import kbs_config, logger
from server.chat.pipeline.constants import _CODE_SOURCE_TOP
from server.chat.pipeline.dual_mcp_circuit import (
    _dual_mcp_available,
    _dual_mcp_record_failure,
    _dual_mcp_record_success,
)
from server.chat.pipeline.milvus_retrieval import _escape_milvus_like, milvus_client
from server.knowledge_base.search_filter import SearchFilter

# 模块级 AsyncClient 单例：跨请求复用连接池，省每请求 1 次 TLS 握手；进程退出时由 httpx 自动清理。
# 与 mempalace_retrieval._mp_client / lac_keywords._lac_client 一致的模式。
# 连接限制：dual_mcp 每请求最多 2 子请求（/projects + /query），高并发下需足够连接池且设上限防泄漏。
# timeout=15s 与原 get_httpx_client(timeout=15) 一致；/projects(5s)、/query(15s) 单请求级 timeout 在调用处保留。
_dual_mcp_client = httpx.AsyncClient(
    timeout=httpx.Timeout(LLM_ROUTE_TIMEOUT_SEC),
    # keepalive_expiry=30 对齐 P3-1(embedding)/C2(reranker)/openai_clients：
    # 短于防火墙/NAT 空闲断开阈值（≥60s），避免复用被静默断开的老连接导致
    # 请求失败误触熔断（连续 3 次失败 cooldown 60s）
    limits=httpx.Limits(max_connections=50, max_keepalive_connections=10, keepalive_expiry=30.0),
)


def _get_dual_mcp_base_url() -> str:
    """读 dual-mcp 容器地址：优先 kbs_config.dual_mcp.base_url，兜底默认值。"""
    try:
        from configs.kb_loader import get_config
        v = get_config("kbs_config.dual_mcp.base_url", None)
        if v:
            return v
    except Exception as e:
        logger.warning(f"[dual-mcp] kb_loader 不可用，回退 kbs_config: {e}")
    try:
        v = kbs_config.get("dual_mcp", {}).get("base_url")
        if v:
            return v
    except Exception as e:
        logger.warning(f"[dual-mcp] failed reading from kbs_config: {e}, use default")
        pass
    return "http://localhost:9789"


def _code_file_abs_path(file_path: str, kb_name: str, project: str) -> str:
    """dual-mcp 命中的 file_path（项目根内相对路径）→ /kb-files 绝对路径。

    前端按 Milvus 文档 source 同款绝对路径引用文件（下载/预览），相对路径
    会导致页面找不到文件。dual-mcp 容器与 RAG 挂同一 PVC（/projects 与
    /kb-files 是同一 export），按存在性尝试 kbId 根目录（手工对齐的代码树）
    与 code/ 子目录（upload_project 的代码落点）；都不存在时仍拼 kbId 根
    （保持绝对路径格式，不阻断）。
    """
    if not file_path:
        return file_path or ""
    from configs import KB_ROOT_PATH
    if file_path.startswith("/"):
        if file_path.startswith("/projects/"):
            return os.path.join(KB_ROOT_PATH, file_path[len("/projects/"):])
        return file_path
    roots = [kb_name] if kb_name else []
    _kb_match = re.search(r"kb[0-9a-f]{20,}", project or "")
    stripped = (_kb_match.group(0) if _kb_match else
                (project[len("projects-"):]
                 if project and project.startswith("projects-") else (project or "")))
    if stripped and stripped not in roots:
        roots.append(stripped)
    for root in roots:
        for cand in (os.path.join(KB_ROOT_PATH, root, file_path),
                     os.path.join(KB_ROOT_PATH, root, "code", file_path)):
            if os.path.isfile(cand):
                return cand
    # 回退用项目根（相对路径的真实基准），其次 kb 名
    return os.path.join(KB_ROOT_PATH, stripped or (kb_name or ""), file_path)


def _matches_to_docs(matches: list[dict], project: str,
                     kb_name: str = "") -> list[Document]:
    """把 dual-mcp /query 的 matches 转成 langchain Document。

    服务端 include_source 时 match 带 source 字段（完整函数体），直接拼进
    page_content——源码在 dual-mcp 侧 CBM 直读，RAG 不再依赖 codegraph。
    """
    _SOURCE_KEY = 'source'
    _FVL_KEY = 'file_version_label'
    _TAGS_KEY = 'tags'
    docs = []
    total = max(len(matches), 1)
    for rank, m in enumerate(matches):
        if not isinstance(m, dict):
            continue
        content = (f"[代码] {m.get('label', '?')} {m.get('name', '?')}"
                   f"（{m.get('file_path', '?')}:{m.get('start_line', '?')}）\n"
                   f"调用关系: in={m.get('in_degree', 0)} out={m.get('out_degree', 0)}")
        src = m.get(_SOURCE_KEY)
        if isinstance(src, dict) and src.get(_SOURCE_KEY):
            content += f"\n【源码】{src.get('file_path', '')}\n{src[_SOURCE_KEY]}"
        # 文件级多版本透出：dual-mcp 按 files_version 映射附的版本号
        fvlabel = m.get(_FVL_KEY)
        if fvlabel is None and isinstance(src, dict):
            fvlabel = src.get(_FVL_KEY)
        tags = m.get(_TAGS_KEY)
        if tags is None and isinstance(src, dict):
            tags = src.get(_TAGS_KEY)
        _rel = m.get("file_path", "")
        _fp_abs = _code_file_abs_path(_rel, kb_name, project)
        meta = {
            _SOURCE_KEY: _fp_abs,
            "doc_type": "code",
            "name": m.get("name", ""),
            "label": m.get("label", ""),
            "file_path": _fp_abs,
            "code_rel": _rel,
            "start_line": m.get("start_line", 0),
            "project": project,
            "code_rank": rank,
            "has_source": bool(isinstance(src, dict) and src.get(_SOURCE_KEY)),
            # dual_mcp 路无真实向量相似度，按服务返回顺序派生 route_score
            # 仅用于 RRF 融合时定 rank（服务返回顺序即相关性顺序）。
            # 不写入 cosine_similarity 字段，避免污染跨路 cosine 比较
            # （RRF 跨路同内容块保留 cosine 更高那份）与 top_score / 前端展示。
            "route_score": max(0.0, 1.0 - rank / (total + 1)),
            "score_source": "dual_mcp",
            "bm25_rank": rank,
        }
        if isinstance(fvlabel, str) and fvlabel:
            meta[_FVL_KEY] = fvlabel
        if isinstance(tags, list) and tags:
            meta[_TAGS_KEY] = tags
        docs.append(Document(
            page_content=content,
            metadata=meta,
        ))
    return docs


async def _query_project(client, base: str, query: str, project: str,
                         **kwargs) -> list[Document]:
    """查询 dual-mcp 项目代码索引。

    后 2 个参数（通过 kwargs 传入，避免签名参数过多）:
        top_k: int - 召回数量（必传）
        kb_name: str - 知识库名（默认 ""，仅用于 doc.metadata 标注）
    """
    top_k = kwargs.get("top_k")
    kb_name = kwargs.get("kb_name", "")
    # P1-2：timeout 15s → 5.0s(total)/2.0s(connect)，与 mempalace 3.0s 对齐量级。
    # 三路 gather 尾延迟由最慢者决定，15s 是最大短板。配合熔断器（连续 3 次失败 cooldown 60s），
    # 首次故障 5s 超时记 failure，最坏 3×5=15s 后熔断打开（原 3×15=45s）；熔断打开后 0s（跳过）。
    # wait_for 兜底连接池 acquire 阻塞（httpx timeout 不约束池等待），对齐 P3-1/C2；
    # 池满时 5s 快速失败 raise → 上层 gather(return_exceptions=True) 记 Exception → 记熔断
    r = await asyncio.wait_for(
        client.post(
            f"{base}/query",
            json={"q": query, "project": project, "top": top_k, "expand": True,
                  "include_source": _CODE_SOURCE_TOP},
            timeout=httpx.Timeout(HTTP_MCP_PROJECTS_TIMEOUT_SEC, connect=HTTP_MCP_CONNECT_TIMEOUT_SEC),
        ),
        timeout=HTTP_MCP_PROJECTS_TIMEOUT_SEC,
    )
    if r.status_code != HTTP_OK:
        # 非 200 必须 raise（而非 return []）：_dual_mcp_search 用
        # any(isinstance(res, Exception)) 判定故障记熔断，空列表非 Exception
        # 会导致 5xx 被记为 success，熔断器永不触发（与 /projects L326 一致）
        raise RuntimeError(f"POST /query -> {r.status_code}")
    data = r.json()
    return _matches_to_docs(data.get("matches", []), project, kb_name)


def _resolve_project(kb_name: str, projects: list[dict]) -> str | None:
    """kbId/kb名 → dual-mcp 项目名。三级解析：
    1) 精确相等（约定：kbId == 项目名）
    2) 规范化（小写去分隔符）后包含，且唯一命中
    3) 0 个或多个 → None（跳过代码路，防止上下文串项目）
    """
    _NAME_KEY = 'name'
    if not projects:
        return None
    exact = [p.get(_NAME_KEY, "") for p in projects if p.get(_NAME_KEY) == kb_name]
    if exact:
        return exact[0]
    def _norm(s: str) -> str:
        return re.sub(r"[^a-z0-9]", "", (s or "").lower())
    nkb = _norm(kb_name)
    hits = [p.get(_NAME_KEY, "") for p in projects if nkb and nkb in _norm(p.get(_NAME_KEY, ""))]
    if len(hits) == 1:
        return hits[0]
    if len(hits) > 1:
        logger.warning(f"[dual-mcp] kb '{kb_name}' 模糊命中多个项目 {hits}，跳过代码路")
    return None


_CODE_PATH_NORM_CACHE = {}  # (kb, rel) -> (ts, 注册 source 绝对路径或 "")
_KB_SOURCE_MAP_CACHE = {}   # kb -> (ts, 全量 source 绝对路径列表)；归一反查的 KB 级内存映射
_KB_SOURCE_MAP_TTL = 3600   # 与 _CODE_PATH_NORM_CACHE 对齐：1h
# 缓存锁：_normalize_code_source_sync 通过 asyncio.to_thread 在线程池执行，
# 多 worker 线程并发读写同一缓存 dict。单个 dict 操作在 GIL 下原子，但
# check-then-act（读 miss → 查 Milvus → 写）跨多步需同步防止重复查询与脏读。
_cache_lock = threading.Lock()


def _load_kb_sources(kb_name: str) -> list[str]:
    """加载 KB 在 Milvus 注册的全量 source 绝对路径（去重），进程级缓存 1h。

    归一反查用 KB 级内存匹配替代逐 rel Milvus 查询：单次全量查询覆盖整个
    KB，后续所有 rel 命中均走内存后缀匹配；KB 查询失败返回空列表，调用方
    回退原逐 rel 逻辑。失败不写缓存，下次重试。
    """
    _SOURCE_KEY = 'source'
    now = _time.time()
    hit = _KB_SOURCE_MAP_CACHE.get(kb_name)
    if hit and now - hit[0] < _KB_SOURCE_MAP_TTL:
        return hit[1]
    sources: list[str] = []
    try:
        rows = milvus_client.query(collection_name=kb_name,
                                   filter='source like "%"',
                                   output_fields=[_SOURCE_KEY], limit=10000,
                                   # B3：全表扫描 source 预加载，1h 缓存。
                                   # 失败回退逐 rel 反查（更耗资源），给 10s
                                   # 避免高并发下级联回退。
                                   timeout=HTTP_MCP_FALLBACK_TOTAL_TIMEOUT_SEC)
        sources = sorted({r.get(_SOURCE_KEY, "") for r in rows if r.get(_SOURCE_KEY)})
        with _cache_lock:
            _KB_SOURCE_MAP_CACHE[kb_name] = (now, sources)
        logger.info(f"[dual-mcp] KB '{kb_name}' 全量 source 预加载: {len(sources)} 条"
                    f"（{len(rows)} 行 chunk）")
    except Exception as e:
        logger.info(f"[dual-mcp] KB '{kb_name}' 全量 source 预加载失败（回退逐 rel 反查）: {e}")
    return sources


def _normalize_code_source_sync(kb_name: str, rels: list[str]) -> dict:
    """dual-mcp 相对路径 → 该 kb 在 Milvus 注册的 source 绝对路径（批量反查）。

    前端点击引用文件时，平台把 docs[].file 剥掉 /kb-files 前缀后与自家
    documents 表精确匹配换 documentId，miss 即"文档不存在"。dual-mcp 项目树
    与 Milvus 注册树可能是同一批文件的两种布局（如 kbId 硬链树 vs
    kb/<名> 注册树），把 code 路统一归一到 Milvus source（= 平台注册路径）
    才能被点击解析。miss 返回 ""（调用方保持原路径）。

    两级缓存：
    1) _CODE_PATH_NORM_CACHE：(kb, rel) -> source，1h TTL，单 rel 快路径
    2) _KB_SOURCE_MAP_CACHE：kb -> 全量 source 列表，1h TTL，miss 时一次性
       加载整 KB source 池做内存后缀匹配，替代原逐 rel Milvus 查询；KB 池
       加载失败回退原 OR 批查 + 逐 rel 补查逻辑。
    """
    _SOURCE_KEY = 'source'
    now = _time.time()
    out, miss = {}, []
    for rel in rels:
        hit = _CODE_PATH_NORM_CACHE.get((kb_name, rel))
        if hit and now - hit[0] < 3600:
            out[rel] = hit[1]
        else:
            miss.append(rel)
    if miss:
        # 优先走 KB 级全量 source 内存匹配（一次 Milvus 查询覆盖整个 KB）
        kb_sources = _load_kb_sources(kb_name)
        if kb_sources:
            _n_hit = 0
            for rel in miss:
                if not rel or '"' in rel:
                    out[rel] = ""
                else:
                    matched = ""
                    for s in kb_sources:
                        if s.endswith("/" + rel):
                            matched = s
                            break
                    out[rel] = matched
                    if matched:
                        _n_hit += 1
                with _cache_lock:
                    _CODE_PATH_NORM_CACHE[(kb_name, rel)] = (now, out[rel])
            logger.info(f"[dual-mcp] code 路径归一（KB 内存匹配）: {_n_hit}/{len(miss)} 命中"
                        f"（KB source 池 {len(kb_sources)} 条）")
            return out
        # KB 全量查询失败：回退原 OR 批查 + 逐 rel 补查
        # 转义 like 通配符（% 和 _）及反斜杠/双引号，避免 rel 路径含 _ 被当通配符误匹配
        conds = [f'source like "%{_escape_milvus_like(rel)}"' for rel in miss if rel and '"' not in rel]
        if conds:
            try:
                rows = milvus_client.query(collection_name=kb_name,
                                           filter=" or ".join(conds),
                                           output_fields=[_SOURCE_KEY], limit=200,
                                           # B3：回退路径，防止预加载失败后再次挂起
                                           timeout=HTTP_MCP_PROJECTS_TIMEOUT_SEC)
                sources = sorted({r.get(_SOURCE_KEY, "") for r in rows if r.get(_SOURCE_KEY)})
                for rel in miss:
                    for s in sources:
                        if s.endswith("/" + rel):
                            out[rel] = s
                            break
            except Exception as e:
                logger.info(f"[dual-mcp] code 路径归一反查 Milvus 失败（保持原路径）: {e}")
        # OR 查询按 chunk 行返回，大文件的多 chunk 会挤占 limit 让小文件漏配：
        # 只对仍未命中的 rel 逐个 limit=1 补查（通常 0-2 个）
        for rel in miss:
            if out.get(rel) or not rel or '"' in rel:
                continue
            try:
                rows = milvus_client.query(collection_name=kb_name,
                                           filter=f'source like "%{_escape_milvus_like(rel)}"',
                                           output_fields=[_SOURCE_KEY], limit=1,
                                           # B3：逐 rel 补查在循环内，给 3s 快速失败
                                           timeout=HTTP_MCP_QUERY_FALLBACK_TIMEOUT_SEC)
                if rows and rows[0].get(_SOURCE_KEY):
                    out[rel] = rows[0][_SOURCE_KEY]
            except Exception as e:
                logger.debug(f"[dual_mcp] rel 归一失败: {e}")
        for rel in miss:
            out.setdefault(rel, "")
            with _cache_lock:
                _CODE_PATH_NORM_CACHE[(kb_name, rel)] = (now, out[rel])
    return out


# P1-1：/projects 全量项目列表 TTL 缓存。项目列表只在入库新项目时变，变化极少，
# 全局缓存一份省每请求 1 段 HTTP RTT（5-50ms）。TTL 60s 平衡新鲜度与收益。
# 模块级单例，每 worker 独立；asyncio.Lock + double-check 防冷启动 thundering herd
# （200 并发同时 miss 只发 1 次拉取，其余等锁后命中缓存）。Python 3.10.6 模块级
# asyncio.Lock 不在 __init__ 绑定 loop（同 P0-3 Semaphore）。缓存只在熔断关闭后生效
# （_dual_mcp_search 上方已 _dual_mcp_available() 检查）；拉取失败不写缓存（raise
# → except 记 failure + 熔断，下次重试）。
_projects_cache: dict = {}             # "_all" -> (ts, projects_list)
_projects_cache_ttl = 60.0
_projects_cache_lock = asyncio.Lock()


async def _get_projects_cached(client, base: str) -> tuple:
    """/projects 全量列表 TTL 缓存（全局，非 per-kb）。

    返回 (projects, from_cache)：from_cache=True 表示命中缓存未发 HTTP 请求。
    """
    _ALL_KEY = '_all'
    now = _time.time()
    hit = _projects_cache.get(_ALL_KEY)
    if hit and now - hit[0] < _projects_cache_ttl:
        return hit[1], True
    async with _projects_cache_lock:
        # double-check：等锁期间可能已被其他协程填充
        now = _time.time()
        hit = _projects_cache.get(_ALL_KEY)
        if hit and now - hit[0] < _projects_cache_ttl:
            return hit[1], True
        # review 补强：等锁期间熔断可能已被其他协程打开（前 N 次串行拉取失败触发），
        # 拉取前再检查一次。否则 200 并发故障冷启动会退化成串行 5s 超时
        # （第 N+1 个起每个 5s，199×5≈995s）；加检查后熔断已开则立即 raise，
        # 锁释放，后续协程快速失败（~0s）。except 端配合「熔断已开则不记 failure」
        # 避免重复记延长 cooldown（_dual_mcp_record_failure 每次 streak≥threshold
        # 都 reset open_until）。
        if not _dual_mcp_available():
            raise RuntimeError("dual-mcp 熔断中，跳过 /projects 拉取")
        # wait_for 兜底连接池 acquire 阻塞（httpx timeout 不约束池等待），对齐 P3-1/C2
        r = await asyncio.wait_for(
            client.get(f"{base}/projects", timeout=HTTP_MCP_PROJECTS_TIMEOUT_SEC),
            timeout=HTTP_MCP_PROJECTS_TIMEOUT_SEC,
        )
        if r.status_code != HTTP_OK:
            raise RuntimeError(f"GET /projects -> {r.status_code}")
        projects = [p for p in r.json().get("projects", []) if isinstance(p, dict)]
        _projects_cache[_ALL_KEY] = (_time.time(), projects)
        logger.info(f"[dual-mcp] /projects 缓存填充: n={len(projects)}, ttl={_projects_cache_ttl}s")
        return projects, False


async def _dual_mcp_search(query: str, top_k: int = 8,
                           knowledge_base_name: str = "",
                           filter_opts: SearchFilter | None = None) -> list[Document]:
    """dual-mcp 代码检索路。任何异常降级为空列表，绝不中断主链路。

    filter_opts: 封装 version/tags/cur_labels 过滤参数（H3403 参数分组）。
    version/tags：dual-mcp 不支持服务端过滤，检索后客户端过滤（与 Milvus 路语义
    对齐，见 doc_utils._matches_version_tags）。version=None 时查当前版本映射
    做默认当前版本过滤（有 label 但不在 cur_labels 的旧 chunk 过滤掉）。
    cur_labels（P2-1）：编排层注入的当前版本 label 集合，version is None 时用；
    None 则自查 build_files_version_map（向后兼容其他调用方）。
    """
    version = filter_opts.version if filter_opts else None
    tags = filter_opts.tags if filter_opts else None
    cur_labels = filter_opts.cur_labels if filter_opts else None
    _CODE_REL_KEY = 'code_rel'
    _t_dmc0 = _time.time()
    # 熔断器：服务不可用或熔断打开时直接返回空，避免每请求等满 15s 超时拖垮主链路
    if not _dual_mcp_available():
        logger.info(f"[dual-mcp] 跳过检索（熔断中或未启用），kb={knowledge_base_name}")
        return []
    base = _get_dual_mcp_base_url()
    try:
        client = _dual_mcp_client
        # P1-1：/projects 走 TTL 缓存（全局 60s），省每请求 1 段 RTT。拉取失败由
        # _get_projects_cached 内 raise，被下方 except 记 failure + 熔断（不写缓存，下次重试）。
        projects, _from_cache = await _get_projects_cached(client, base)
        _t_dmc1 = _time.time()
        logger.info(f"[timing] dual_mcp_projects: {_t_dmc1 - _t_dmc0:.3f}s, "
                    f"n={len(projects)}, cache={'hit' if _from_cache else 'miss'}")
        # kbId == dual-mcp 项目名约定：三级解析，唯一命中才查
        project = _resolve_project(knowledge_base_name, projects)
        if project is None:
            logger.info(f"[dual-mcp] kb '{knowledge_base_name}' 未唯一匹配 dual-mcp 项目，跳过代码路")
            _dual_mcp_record_success()  # 服务正常，仅 kb 无对应项目
            return []
        results = await asyncio.gather(
            _query_project(client, base, query, project,
                           top_k=top_k, kb_name=knowledge_base_name),
            return_exceptions=True,
        )
        # /query 在 gather(return_exceptions=True) 内，超时/异常被吞为 result
        # 不会抛出。检查 results 是否含 Exception 判定服务故障（含 /query 超时）
        if any(isinstance(res, Exception) for res in results):
            _dual_mcp_record_failure()
        else:
            _dual_mcp_record_success()
        _t_dmc2 = _time.time()
        logger.info(f"[timing] dual_mcp_query: {_t_dmc2 - _t_dmc1:.3f}s, project={project}")
    except Exception as e:
        # review 补强：熔断已开（如 _get_projects_cached 锁内检查 raise）则不重复记 failure，
        # 避免 _dual_mcp_record_failure 每次 streak≥threshold reset open_until 导致 cooldown
        # 反复延长（熔断永远不恢复）。熔断关闭时记 failure 触发正常熔断逻辑。
        if _dual_mcp_available():
            _dual_mcp_record_failure()  # /projects 故障 → 记熔断
        _t_dmc_err = _time.time()
        logger.warning(f"[dual-mcp] 检索降级为空: {e},耗时={_t_dmc_err - _t_dmc0:.3f}s")
        return []
    merged: list[Document] = []
    for res in results:
        if isinstance(res, list):
            merged.extend(res)
    # code docs 的 file 归一到 Milvus 注册路径（平台点击换 documentId 依赖）
    _rels = sorted({d.metadata[_CODE_REL_KEY] for d in merged
                    if d.metadata.get("doc_type") == "code" and d.metadata.get(_CODE_REL_KEY)})
    if _rels:
        try:
            _norm = await asyncio.to_thread(_normalize_code_source_sync,
                                            knowledge_base_name, _rels)
        except Exception as e:
            logger.info(f"[dual-mcp] code 路径归一异常（保持原路径）: {e}")
            _norm = {}
        _n_hit = 0
        for d in merged:
            _s = _norm.get(d.metadata.get(_CODE_REL_KEY) or "") if d.metadata.get("doc_type") == "code" else ""
            if _s:
                d.metadata["source"] = _s
                d.metadata["file_path"] = _s
                _n_hit += 1
        if _norm:
            logger.info(f"[dual-mcp] code 路径归一: {sum(1 for v in _norm.values() if v)}/{len(_rels)}"
                        f" 个文件命中 Milvus 注册路径（{ _n_hit } 个 code 块）")
    # P2 客户端 version/tags 过滤（与 Milvus 路语义对齐）
    # version=None 时查当前版本映射做默认当前版本过滤（与 Milvus filter_expr 一致）
    # P2-1：cur_labels 优先用编排层注入（None 时自查，向后兼容其他调用方）
    if cur_labels is None and version is None:
        try:
            from server.knowledge_base.file_version import build_files_version_map
            # C4：build_files_version_map 是同步 SQLAlchemy DB 查询，to_thread 避免阻塞事件循环
            _vmap = await asyncio.to_thread(build_files_version_map, knowledge_base_name)
            cur_labels = set(l for l in _vmap.values() if l)
        except Exception as e:
            logger.warning(f"[dual-mcp] 查当前版本映射失败（fail-open 不过滤）: {e}")
            cur_labels = None
    # 需要过滤：version 指定 / tags 指定 / cur_labels 非空（默认当前版本）
    if version is not None or tags or cur_labels:
        from server.chat.pipeline.doc_utils import _matches_version_tags
        _before = len(merged)
        merged = [d for d in merged if _matches_version_tags(d, version, tags, cur_labels)]
        if _before != len(merged):
            logger.info(f"[dual-mcp] version/tags 过滤: {_before} → {len(merged)} 块"
                        f"（version={version}, tags={tags}）")
    return merged
