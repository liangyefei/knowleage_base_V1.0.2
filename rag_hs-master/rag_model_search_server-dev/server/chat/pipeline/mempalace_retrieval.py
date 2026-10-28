"""mempalace 混合检索路：向量 + BM25 union，命中规格类问题。"""
import asyncio
import os
import time

import httpx
from langchain.docstore.document import Document

from configs import MEMPALACE_BASE_URL, MEMPALACE_TIMEOUT, logger
from server.chat.pipeline.mempalace_circuit import (
    _mempalace_available,
    _mempalace_record_failure,
    _mempalace_record_success,
)
from server.knowledge_base.search_filter import SearchFilter

# 模块级 AsyncClient 单例：跨请求复用连接池，省 TLS 握手；进程退出时由 httpx 自动清理
# 连接限制：mempalace 扇出可达 7 子查询/请求，需足够连接池且设上限防泄漏
_mp_client = httpx.AsyncClient(
    timeout=MEMPALACE_TIMEOUT,
    limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
)


def _hits_to_docs(data_list: list, knowledge_base_name: str) -> list[Document]:
    """把 mempalace /search 返回的 (q, data) 元组列表展平为 Document 列表。

    data_list 是 asyncio.gather 收集的 [(q1, data1), (q2, data2), ...]，
    每个 data 是 palace /search 的 JSON（含 results 数组）。跨所有 (q, data)
    按 (source, text[:60]) 去重，保留首次出现。透出 hit 上的 tags 与
    file_version_label（字符串版本号），不再透出 INT file_version。
    """
    docs = []
    seen = set()
    for q, data in data_list:
        if not data:
            continue
        for h in data.get("results", []) or []:
            text = h.get("text") or ""
            if not text.strip():
                continue
            source = h.get("source_path") or h.get("source_file") or "mempalace"
            key = (source, text[:60])
            if key in seen:
                continue
            seen.add(key)
            sim = h.get("similarity")
            fvlabel = h.get("file_version_label")
            tags = h.get("tags")
            # mempalace 写入为逗号分隔字符串（_tags_to_str），读时兼容 str→list
            if isinstance(tags, str):
                from server.knowledge_base.file_version import _tags_from_str
                tags = _tags_from_str(tags)
            meta = {
                "source": source,
                "doc_type": "mempalace",
                "cosine_similarity": float(sim) if sim is not None else 0.0,
                "similarity": sim,
                "bm25_score": h.get("bm25_score"),
                "room": h.get("room"),
                "search_query": q,
            }
            if isinstance(fvlabel, str) and fvlabel:
                meta["file_version_label"] = fvlabel
            if isinstance(tags, list) and tags:
                meta["tags"] = tags
            docs.append(Document(
                page_content=text,
                metadata=meta,
            ))
    logger.info(f"[mempalace_raw] kb={knowledge_base_name} 检索到 {len(docs)} 个文档块:")
    for i, d in enumerate(docs):
        logger.info(
            f"[mempalace_raw] kb={knowledge_base_name} chunk{i + 1}/{len(docs)} | "
            f"source={d.metadata.get('source', '?')} | "
            f"cosine={d.metadata.get('cosine_similarity', 0):.4f} | "
            f"text_preview={d.page_content[:200].replace(chr(10), ' ')!r}"
        )
    return docs


async def _search_mempalace_async(query: str, knowledge_base_name: str,
                                  limit: int = 10,
                                  keywords: list[str] | None = None,
                                  filter_opts: SearchFilter | None = None) -> list[Document]:
    """mempalace 混合检索路（异步）：httpx.AsyncClient 替代 urllib，不阻塞事件循环。

    当传入 keywords 时，额外按单个短关键词各发一次检索（palace 的 FTS5/BM25
    对长问句几乎不命中，短关键词才能激活词法路），结果与原问句结果合并去重。

    filter_opts: 封装 version/tags/cur_labels 过滤参数（H3403 参数分组）。
    version/tags：mempalace 不支持服务端过滤，检索后客户端过滤（与 Milvus 路
    语义对齐，见 doc_utils._matches_version_tags）。version=None 时查当前版本
    映射做默认当前版本过滤。
    cur_labels（P2-1）：编排层注入的当前版本 label 集合，version is None 时用；
    None 则自查 build_files_version_map（向后兼容其他调用方）。
    """
    version = filter_opts.version if filter_opts else None
    tags = filter_opts.tags if filter_opts else None
    cur_labels = filter_opts.cur_labels if filter_opts else None
    _t_mp0 = time.time()
    base = MEMPALACE_BASE_URL or os.environ.get("MEMPALACE_REST_URL", "http://haisi-mempalace-rest:8777")

    # 熔断器：服务不可用或熔断打开时直接返回空，避免每请求等满超时拖垮主链路
    if not _mempalace_available():
        logger.info(f"[mempalace] 跳过检索（熔断中或未启用），kb={knowledge_base_name}")
        return []

    queries = [query] + [str(k).strip() for k in (keywords or [])
                         if k and str(k).strip() and str(k).strip() != query][:6]

    async def _one(client: httpx.AsyncClient, q: str, cc: int):
        try:
            r = await client.get(f"{base}/search", params={
                "q": q, "palace": knowledge_base_name,
                "limit": limit, "detail": "full", "context_chunks": cc})
            r.raise_for_status()
            return q, r.json()
        except Exception as e:
            logger.warning(f"[mempalace] 检索降级为空: q={q!r}, e={e}")
            return q, None

    # 复用单个 AsyncClient 连接池：N 个子请求共享 TCP 连接，省 N-1 次握手
    # 原问句查询保留章节水合（context_chunks=2）兜底跨块答案；
    # 短关键词查询去掉水合（答案句 95% 在本 chunk 内，实测仅 5% 依赖水合），省 ~0.2s/次
    data_list = await asyncio.gather(
        *([_one(_mp_client, query, 2)] + [_one(_mp_client, q, 0) for q in queries[1:]])
    )
    _t_mp1 = time.time()
    logger.info(f"[timing] mempalace_async_search: {_t_mp1 - _t_mp0:.3f}s, "
                f"kb={knowledge_base_name}, n_queries={len(queries)}")

    # 熔断器：全部子请求都失败视为本库检索失败；至少一个成功则记成功
    any_ok = any(data is not None for _, data in data_list)
    if any_ok:
        _mempalace_record_success()
    else:
        _mempalace_record_failure()

    docs = _hits_to_docs(data_list, knowledge_base_name)
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
            logger.warning(f"[mempalace] 查当前版本映射失败（fail-open 不过滤）: {e}")
            cur_labels = None
    # 需要过滤：version 指定 / tags 指定 / cur_labels 非空（默认当前版本）
    if version is not None or tags or cur_labels:
        from server.chat.pipeline.doc_utils import _matches_version_tags
        _before = len(docs)
        docs = [d for d in docs if _matches_version_tags(d, version, tags, cur_labels)]
        if _before != len(docs):
            logger.info(f"[mempalace] version/tags 过滤: {_before} → {len(docs)} 块"
                        f"（version={version}, tags={tags}）")
    return docs
