"""Milvus 文档检索路（同步）：embedding → search → Document → 余弦过滤。"""
import json
import time

from langchain.docstore.document import Document
from pymilvus import MilvusClient

from configs import kbs_config, logger
from server.chat.pipeline.embeddings import get_embeddings

# Milvus filter 转义 helper：定义在 knowledge_base 层（中立位置），此处 re-export
# 供 dual_mcp.py / 测试复用，避免跨层依赖（chat/pipeline → knowledge_base）。
from server.knowledge_base.milvus_filter import _escape_milvus_like, _escape_milvus_str  # noqa: F401
from server.knowledge_base.search_filter import SearchFilter

# 直接 Milvus 客户端
milvus_host = kbs_config["milvus"]["host"]
milvus_port = kbs_config["milvus"]["port"]
milvus_client = MilvusClient(uri=f"http://{milvus_host}:{milvus_port}")


def _search_milvus_sync(query: str, top_k: int, knowledge_base_name: str,
                        score_threshold: float,
                        **kwargs
                        ) -> list[Document]:
    """Milvus 文档检索路（同步）：fail-open 包装——任何异常返回空列表，不中断主链路。

    与 dual_mcp/mempalace 检索路一致：Milvus 超时/连接失败/数据异常时降级为 []，
    由 RRF 融合层处理空结果，避免单路故障崩溃整个 chat。

    P2-1：cur_labels 由编排层 _phase_retrieve 统一查询注入（version is None 时），
    避免三路各自查 build_files_version_map。None 时自查（向后兼容其他调用方）。
    filter_opts: 封装 version/tags/cur_labels 过滤参数（H3403 参数分组）。
    precomputed_embedding: 预计算的 query embedding（默认 None，通过 kwargs 传入）。
    """
    precomputed_embedding = kwargs.get("precomputed_embedding")
    filter_opts = kwargs.get("filter_opts")
    try:
        return _search_milvus_impl(query, top_k, knowledge_base_name,
                                   score_threshold,
                                   precomputed_embedding=precomputed_embedding,
                                   filter_opts=filter_opts)
    except Exception as e:
        logger.error(f"[milvus] 检索异常（fail-open 返回空列表）: "
                     f"kb={knowledge_base_name} err={type(e).__name__}: {e}")
        return []


def _search_milvus_impl(query: str, top_k: int, knowledge_base_name: str,
                        score_threshold: float,
                        **kwargs
                        ) -> list[Document]:
    """Milvus 文档检索路（同步）：embedding → search → Document → 余弦过滤。

    filter_opts: 封装 version/tags/cur_labels 过滤参数（H3403 参数分组）。
    tags（OR 语义）：json_contains 检测 metadata["tags"] 数组；None/空不加过滤。
    version：默认 None 时自动查 build_files_version_map 拿当前所有文件的当前
    label，filter in 列表（避免命中旧版本 chunk）；传值时按 metadata.
    file_version_label 精确匹配（覆盖默认，用于指定版本检索）。
    cur_labels（P2-1）：编排层注入的当前版本 label 集合，version is None 时用；
    None 则自查 build_files_version_map（向后兼容其他调用方）。
    precomputed_embedding: 预计算的 query embedding（默认 None，通过 kwargs 传入）。
    """
    _SOURCE_KEY = 'source'
    _DISTANCE_KEY = 'distance'
    _ENTITY_KEY = 'entity'
    _COSINE_SIM_KEY = 'cosine_similarity'
    precomputed_embedding = kwargs.get("precomputed_embedding")
    filter_opts = kwargs.get("filter_opts")
    version = filter_opts.version if filter_opts else None
    tags = filter_opts.tags if filter_opts else None
    cur_labels = filter_opts.cur_labels if filter_opts else None
    _t0 = time.time()
    if precomputed_embedding is not None:
        e_queries = [precomputed_embedding]
    else:
        encoded_queries = get_embeddings([query])
        e_queries = encoded_queries.tolist()
    _t1 = time.time()
    logger.info("[timing] milvus_embed: %.3fs, kb=%s", _t1 - _t0, knowledge_base_name)
    search_params = kbs_config.get("milvus_kwargs", {}).get("search_params", {})
    search_kwargs = dict(
        collection_name=knowledge_base_name,
        data=e_queries,
        limit=top_k,
        search_params=search_params,
        output_fields=["source", "pk", "text", "metadata"],
        # B3：Milvus 默认 timeout=60s，高并发/不健康时拖垮整个 RAG 链路。
        # 关键路径有 code/memory 兜底，5s 快速失败更安全。
        timeout=5.0,
    )
    # P2 统一 filter 构造：version（指定）or 默认当前版本 + tags 叠加
    # - version 传值：精确匹配该 label（覆盖默认，用于指定版本检索）
    # - version=None：默认查当前所有文件的当前 label（build_files_version_map
    #   带 TTL 缓存），filter in 列表，避免命中旧版本 chunk
    #   P1-1 修复：加 `or file_version_label is null` 让无 label 的文档也通过，
    #   与客户端 _matches_version_tags 的"无 label 保留"语义对齐
    #   （保护历史遗留无 label 文档可检索 + 三路跨路一致）
    # - P2-1：cur_labels 优先用编排层注入（None 时自查，向后兼容其他调用方）
    # - 查映射失败/空库：fail-open 不过滤（filter_expr=None）
    filter_expr = None
    if version is not None:
        # 转义反斜杠和双引号避免 filter 表达式注入/语法错误
        esc = _escape_milvus_str(version)
        filter_expr = f'metadata["file_version_label"] == "{esc}"'
    else:
        if cur_labels is None:
            try:
                from server.knowledge_base.file_version import build_files_version_map
                cur_map = build_files_version_map(knowledge_base_name)  # 内部走 TTL 缓存
                cur_labels = [l for l in cur_map.values() if l]
            except Exception as e:
                logger.warning(f"[milvus] 查当前版本映射失败（fail-open 不过滤）: {e}")
                cur_labels = []
        if cur_labels:
            # review 补强：sorted 让 set/list 路径统一顺序，filter_expr 字符串稳定
            # 可复现（set 注入顺序不固定，原 list 自查保序；sorted 统一按 label 排序）。
            labels_str = ", ".join(f'"{_escape_milvus_str(l)}"' for l in sorted(cur_labels))
            filter_expr = (f'(metadata["file_version_label"] in [{labels_str}]) '
                           f'or (metadata["file_version_label"] is null)')
        else:
            filter_expr = None  # 无版本记录（空库），不加过滤
    # tags 过滤叠加（OR 语义：json_contains 检测 metadata["tags"] 数组）
    if tags:
        tag_clauses = []
        for t in tags:
            esc = _escape_milvus_str(t)
            tag_clauses.append(f'json_contains(metadata["tags"], "{esc}")')
        tags_or = " or ".join(tag_clauses)
        filter_expr = tags_or if filter_expr is None else f"({filter_expr}) and ({tags_or})"
    if filter_expr is not None:
        search_kwargs["filter"] = filter_expr
    result_list = milvus_client.search(**search_kwargs)
    _t2 = time.time()
    logger.info("[timing] milvus_search: %.3fs, kb=%s, results=%d",
                _t2 - _t1, knowledge_base_name, len(result_list[0]) if result_list else 0)

    docs = []
    for sublist in result_list:
        for item in sublist:
            distance = item[_DISTANCE_KEY]
            text = item[_ENTITY_KEY]['text']
            source = item[_ENTITY_KEY][_SOURCE_KEY]
            meta = item[_ENTITY_KEY].get('metadata', {})
            if isinstance(meta, str):
                meta = json.loads(meta)
            if not isinstance(meta, dict):
                meta = {}
            meta[_SOURCE_KEY] = source
            meta[_DISTANCE_KEY] = distance
            doc = Document(page_content=text, metadata=meta)
            docs.append(doc)

    # distance → cosine_similarity 转换：取决于 Milvus metric_type
    # - COSINE/IP：distance 即相似度（越大越好，范围 [-1,1]/[0,∞)），直接存
    # - L2：distance 是欧氏距离（越小越好），需 1/(1+d) 转换为相似度以统一阈值过滤方向
    _metric_type = (
        str(kbs_config.get("milvus_kwargs", {})
            .get("search_params", {})
            .get("metric_type", "COSINE"))
        .upper()
    )
    for doc in docs:
        d = doc.metadata.get(_DISTANCE_KEY, 0)
        if _metric_type == "L2":
            doc.metadata[_COSINE_SIM_KEY] = 1.0 / (1.0 + d)
        else:
            doc.metadata[_COSINE_SIM_KEY] = d

    # 打印 Milvus 检索到的原始文档块（阈值过滤前，能看到完整 top_k 条）
    logger.info(f"[milvus_raw] kb={knowledge_base_name} metric={_metric_type} 检索到 {len(docs)} 个文档块:")
    for i, doc in enumerate(docs):
        logger.info(
            f"[milvus_raw] kb={knowledge_base_name} chunk{i + 1}/{len(docs)} | "
            f"source={doc.metadata.get('source', '?')} | "
            f"distance={doc.metadata.get('distance', 0):.4f} | "
            f"cosine={doc.metadata.get('cosine_similarity', 0):.4f} | "
            f"text_preview={doc.page_content[:200].replace(chr(10), ' ')!r}"
        )

    # score_threshold 过滤：统一为相似度（越大越好），低于阈值的丢弃（只对 doc 路生效）
    if score_threshold is not None and score_threshold > 0:
        docs = [doc for doc in docs if doc.metadata.get(_COSINE_SIM_KEY, 0) >= score_threshold]
    _t3 = time.time()
    logger.info("[timing] milvus_doc_build: %.3fs, kb=%s, docs=%d", _t3 - _t2, knowledge_base_name, len(docs))
    return docs
