"""多知识库并发 RAG 问答端点。

FastAPI 路由 /kw/chat/multi_knowledge_base_chat 的入口。检索路、路由、熔断、
版本对比等辅助函数已拆分到 server.chat.pipeline 子包，本文件仅保留端点签名
与 multi_knowledge_base_chat_iterator 编排逻辑。
"""
import asyncio
import json
import os
import time
from collections import OrderedDict
from collections.abc import AsyncIterable
from dataclasses import dataclass

from pydantic import BaseModel, Field
from langchain.prompts.chat import ChatPromptTemplate
from langchain_core.messages import ChatMessage
from sse_starlette.sse import EventSourceResponse

from configs import (
    KB_CHAT_TEMPERATURE,
    LLM_MODELS,
    RAG_CONTEXT_BUDGET,
    RAG_HISTORY_BUDGET_RATIO,
    RAG_HISTORY_TOTAL_BUDGET_RATIO,
    RECALL_TOP_K,
    RERANKER_TOP_N,
    SCORE_THRESHOLD,
    USE_RERANKER,
    logger,
)
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope

# 从 pipeline 子包导入所有辅助函数与状态（原模块级定义已迁移到子包）
from server.chat.pipeline.constants import (
    _CODE_CTX_GOAL,
    _RERANK_TOP_N_MAX,
    _ROUTER_MODEL_NAME,
    _UNANSWERABLE_MARKERS,
    _lang_text,
    RRF_K,
)
from server.chat.pipeline.language_detect import resolve_language
from server.chat.pipeline.doc_utils import (
    _dedupe_docs,
    _join_chunks_with_budget,
    _norm_text,
)
from server.chat.pipeline.dual_mcp import _dual_mcp_search
from server.chat.pipeline.embeddings import aget_embeddings
from server.chat.pipeline.keyword_gates import (
    _has_chitchat_keyword,
    _has_tech_keyword,
    _is_greeting,
)
from server.chat.pipeline.lac_keywords import _extract_keywords_lac
from server.chat.pipeline.markdown_index import _find_heading_line
from server.chat.pipeline.memory_format import (
    _format_conversation,
    _format_profile,
    _search_memory_both,
)
from server.chat.pipeline.mempalace_retrieval import _search_mempalace_async
from server.chat.pipeline.milvus_retrieval import _search_milvus_sync
from server.knowledge_base.search_filter import SearchFilter
from server.chat.pipeline.openai_clients import (
    _main_model_cfg,
    _main_openai_client,
    _router_model_cfg,
    _router_openai_client,
)
from server.chat.pipeline.route_llm import _route_and_rewrite
from server.chat.pipeline.version_diff import (
    _build_verdiff_context,
    _detect_version_diff,
    _detect_version_filter,
    _run_verdiff_for_kbs,
)
from server.chat.utils import History, history_from_backend
from server.knowledge_base.kb_service.base import KBServiceFactory
from server.memory.memory_extractor import extract_and_store_async
from server.reranker.reranker import acompress_documents_bounded, get_reranker_singleton
from server.utils import BaseResponse, get_prompt_split, resolve_prompt_name
from server._http_constants import (
    HTTP_NOT_FOUND,
    LLM_ROUTE_TIMEOUT_SEC,
    HTTP_ASYNC_SHIELD_TIMEOUT_SEC,
)
from configs import BASIC_CONFIG
from server.internet_search.aliyun_search import get_aliyun_search_client

# 主 LLM 生成超时保护（P0-2）：原 _stream_llm_response / _non_stream_llm_response 的
# openai_client.chat.completions.create 无任何超时，DashScope 偶发 hang 时请求无限期占用
# worker 连接，200 并发下拖垮健康探针 → 雪崩。路由 LLM 已有 wait_for(15s) 兜底，主生成补齐。
# 三层超时：流式建立（create→返回 stream）/ chunk idle（两个 chunk 之间）/ 非流式整体。
# 阈值用环境变量覆盖，便于压测调优；默认值兼顾深度思考模式（prefill/思考期较长）。
_LLM_STREAM_CONNECT_TIMEOUT = float(os.environ.get("HAISI_LLM_STREAM_CONNECT_TIMEOUT", "30"))
_LLM_CHUNK_IDLE_TIMEOUT = float(os.environ.get("HAISI_LLM_CHUNK_IDLE_TIMEOUT", "60"))
_LLM_NON_STREAM_TIMEOUT = float(os.environ.get("HAISI_LLM_NON_STREAM_TIMEOUT", "120"))

# P0-3：主 LLM 全局并发信号量。限制同时在飞的主 LLM 生成请求数，防 DashScope 限流
# （批量 429）+ 防 worker httpx 连接池占满。模块级单例，每 worker 独立——uvicorn 多 worker
# 下总并发容量 = N × workers（默认 64 × 4 worker = 256）。队列背压由 uvicorn
# limit_concurrency（默认 1000）兜底，超出 ASGI 层直接 503，故 acquire 不另设超时。
# async with 保证所有退出路径（正常完成/超时降级/异常/客户端断开 GeneratorExit）释放。
# max(1, ...) 守卫防误配 0/负值导致所有 acquire 永久阻塞（同 reranker base.py 模式）。
_LLM_CONCURRENCY = asyncio.Semaphore(max(1, int(os.environ.get("HAISI_LLM_MAX_CONCURRENCY", "64"))))

# P1-3：检索路并发上限信号量。限制同时在飞的检索请求数（每 chat 请求检索阶段算 1 个），
# 防 N 库 × M query × 3 路子任务突发打爆下游（Milvus/dual-mcp/mempalace）连接池。
# 模块级单例，每 worker 独立——总并发容量 = N × workers（默认 128 × 4 worker = 512）。
# 包外层 gather（多库多 query），内层三路 gather 仍并发；队列背压由 uvicorn
# limit_concurrency 兜底。max(1, ...) 守卫防误配 0/负值；async with 保证所有退出路径释放。
_RETRIEVE_SEMAPHORE = asyncio.Semaphore(max(1, int(os.environ.get("HAISI_RETRIEVE_MAX_CONCURRENCY", "128"))))


@dataclass
class ChatContext:
    """multi_knowledge_base_chat_iterator 的共享状态载体，替代 nonlocal 变量。

    外层 multi_knowledge_base_chat 创建后传给 iterator 与 _safe_iterator，
    各 phase 函数通过 ctx 属性读写共享状态，不使用 nonlocal。
    """
    # 请求参数（只读，外层函数填充）
    query: str
    history: list[History]
    valid_kbs: list[str]
    top_k: int
    score_threshold: float
    model_name: str
    prompt_name: str
    deep_think: bool
    stream: bool
    temperature: float
    max_tokens: int | None
    message_id: str
    user_id: str
    conversation_id: str
    # LLM 客户端（外层填充，phase 只读）
    openai_client: object = None      # _main_openai_client
    config: dict = None               # _main_model_cfg
    router_client: object = None      # _router_openai_client
    router_model_id: str = None       # _router_model_cfg.get("model_id", _ROUTER_MODEL_NAME)
    # 请求参数（只读，外层填充，默认 None 表示不过滤）
    tags: list[str] | None = None    # 文件标签过滤（OR 语义，Milvus 路生效）
    version: str | None = None       # 指定版本标签检索（覆盖默认当前版本过滤）
    # 联网搜索控制：None=自动（知识库无命中时触发），True=强制启用（总是搜索并融合结果），False=强制禁用
    enable_internet_search: bool | None = None
    # 检测后的最终回答语言（zh/en）：入口 resolve_language 写入，_phase_generate 取用
    language: str = "zh"
    # 路由阶段产出（_phase_route 写入）
    need_rag: bool = True
    rewritten_query: str | None = None
    sub_queries: list[str] | None = None
    route_keywords: list[str] | None = None
    route_decision: dict = None
    verdiff_intent: dict = None
    mem_task: object = None           # asyncio.Task，_phase_route 创建，_phase_generate 收口
    # 检索阶段产出（_phase_retrieve 写入）
    search_queries: list[str] = None  # [rewritten_query] if rewritten else [query]
    answer_query: str = None          # = query（回答用原始 query）
    docs: list = None                 # List[Document]，RRF+reranker 后
    verdiff_ctx: str = None           # 版本对比分支：diff 上下文
    verdiff_doc: dict = None          # 版本对比分支：diff doc
    # 组装阶段产出（_phase_assemble 写入）
    context: str = ""
    source_documents: list = None
    # 统计与状态（各阶段读写）
    kb_stats: dict = None
    err_handled: bool = False         # 内部 except 已处理标记，_safe_iterator 兜底据此跳过
    profile_prefix: str = ""          # _phase_generate 收口 _finish_mem 后写入


def _on_reasoning(text: str, stream: bool, queue: asyncio.Queue) -> None:
    """路由 LLM reasoning 文本回调：stream 模式塞入队列供主协程逐段 yield。"""
    if stream:
        queue.put_nowait(text)


async def _run_route(
    query: str,
    history,
    router_client,
    router_model_id: str,
    **kwargs,
) -> dict:
    """路由 LLM 调用：意图判定 + 改写 + 多 query 分解，返回决策 dict。

    后 3 个参数（通过 kwargs 传入，避免签名参数过多）:
        stream: bool - 是否流式（必传）
        reasoning_queue: asyncio.Queue - 推理输出队列（必传）
        sentinel: object - 队列结束标识（必传）

    异常或超时时 fail-open 返回走 RAG 的决策；finally 始终塞 sentinel 通知主协程结束。
    超时（15s）兜底：route_llm 内部只设了 HTTP 层 timeout=10，但流式响应建立后
    chunk 间隔无限制，LLM hang 会导致整个 chat 卡死；此处 wait_for 硬超时，
    到点 cancel 内部 task（CancelledError 不会被 _route_and_rewrite 的
    except Exception 捕获，正常传播给 wait_for 抛 TimeoutError），降级走 RAG。
    """
    stream = kwargs.get("stream", False)
    reasoning_queue = kwargs.get("reasoning_queue")
    sentinel = kwargs.get("sentinel")
    try:
        return await asyncio.wait_for(
            _route_and_rewrite(
                query, history, router_client, router_model_id,
                on_reasoning=lambda text: _on_reasoning(text, stream, reasoning_queue),
            ),
            timeout=LLM_ROUTE_TIMEOUT_SEC,
        )
    except asyncio.TimeoutError as e:
        logger.error(f"[route] 路由超时（fail-open 走 RAG，timeout=15s）: {e}")
        return {"need_rag": True, "rewritten_query": None,
                "sub_queries": None, "route_type": "knowledge_qa",
                "reason": f"路由超时，fail-open: {e}"}
    except Exception as e:
        logger.error(f"[route] _run_route 异常: {e}")
        return {"need_rag": True, "rewritten_query": None,
                "sub_queries": None, "route_type": "knowledge_qa",
                "reason": f"路由异常，fail-open: {e}"}
    finally:
        reasoning_queue.put_nowait(sentinel)


async def _finish_mem(mem_task, history) -> str:
    """收口长期记忆检索任务：返回拼好的 profile_prefix（画像 + 相关对话）。

    mem_task 为 None 时返回空串；超时取消任务，异常返回空串。
    """
    if mem_task is None:
        return ""
    profile_prefix = ""
    try:
        _mem_hits, _conv_hits = await asyncio.wait_for(
            asyncio.shield(mem_task), timeout=HTTP_ASYNC_SHIELD_TIMEOUT_SEC)
        _profile = _format_profile(_mem_hits)
        _conv = _format_conversation(_conv_hits, history)
        if _profile:
            profile_prefix += _profile
            logger.info(f"[memory] 注入用户画像 {len(_mem_hits)} 条")
        if _conv:
            profile_prefix += _conv
            logger.info(f"[memory] 注入相关历史对话 {len(_conv_hits)} 条")
    except asyncio.TimeoutError:
        logger.warning("[memory] 画像/历史检索超时（跳过注入，取消孤儿 task）")
        mem_task.cancel()
    except Exception as _mem_e:
        logger.warning(f"[memory] 画像/历史检索异常（跳过注入）: {_mem_e}")
    return profile_prefix


async def _retrieve_one_kb_one_query(
    kb_name: str, sq: str, emb: list,
    recall_k: int,
    **kwargs,
) -> tuple:
    """单库单query检索：Milvus 文档路 + dual-mcp 代码路 + mempalace 混合路 并发。

    后 3 个参数（通过 kwargs 传入，避免签名参数过多）:
        route_keywords: list - 路由关键词（默认 []）
        score_threshold: float - 余弦相似度阈值（默认 0.0）
        filter_opts: SearchFilter | None - 过滤参数封装（默认 None）

    filter_opts: 封装 version/tags/cur_labels 过滤参数（H3403 参数分组）。
    P2-1：cur_labels 由编排层 _phase_retrieve 统一查询注入（version is None 时），
    避免三路各自查 build_files_version_map 竞态窗口内重复 DB 查询。None 时三路
    自查（向后兼容其他调用方）。
    """
    route_keywords = kwargs.get("route_keywords")
    score_threshold = kwargs.get("score_threshold", 0.0)
    filter_opts = kwargs.get("filter_opts")
    _t_start = time.time()
    results = await asyncio.gather(
        # Bug D/P0：位置参数 None 映射到 version，与 version=version 关键字冲突 → TypeError
        # 删除冗余的 None 位置参数，Milvus 检索路恢复正常
        asyncio.to_thread(_search_milvus_sync, sq, recall_k, kb_name,
                          score_threshold, precomputed_embedding=emb, filter_opts=filter_opts),
        _dual_mcp_search(sq, recall_k, kb_name, filter_opts=filter_opts),
        _search_mempalace_async(sq, kb_name, keywords=route_keywords,
                                filter_opts=filter_opts),
        return_exceptions=True,
    )
    _t_end = time.time()
    logger.info(f"[timing] kbq_retrieve: {_t_end - _t_start:.3f}s, kb={kb_name}, q={sq[:40]}")
    return results


def _build_source_documents(docs: list, used_chunk_ids: set) -> list:
    """组装 source_documents：只返回 used 的块，按文件聚合，透出 tags/file_version_label。

    三端（milvus/mempalace/dual-mcp）检索结果的 metadata 均含 file_version_label(String)
    与 tags(List[str])，此处统一透出至文件级与块级；同文件多块的 tags 求并集去重保序。
    """
    _TITLES_KEY = 'titles'
    _FILE_KEY = 'file'
    _PAGE_KEY = 'page'
    _CONTENT_KEY = 'content'
    _RELEVANCE_SCORE_KEY = 'relevance_score'
    _DOC_TYPE_KEY = 'doc_type'
    _CHUNK_INDEX_KEY = 'chunk_index'
    _FILE_VERSION_LABEL_KEY = 'file_version_label'
    _TAGS_KEY = 'tags'
    _USED_KEY = 'used'
    _NAME_KEY = 'name'
    source_documents = []
    for doc in docs:
        if id(doc) not in used_chunk_ids:
            continue
        filename = doc.metadata.get("source") or ""
        ext = os.path.splitext(filename)[1].lower()
        if ext in ['.pdf', '.doc', '.docx', '.ppt', '.pptx']:
            page_numbers = doc.metadata.get("page_numbers", [])
            page = ",".join(str(p) for p in page_numbers) if page_numbers else ""
        elif ext == '.md':
            _titles = doc.metadata.get(_TITLES_KEY, [])
            _source = doc.metadata.get("source")
            _heading_line = _find_heading_line(_source, _titles)
            page = str(_heading_line) if _heading_line else ""
        else:
            page = ""

        out_dict = {
            _FILE_KEY: filename,
            _PAGE_KEY: page,
            _TITLES_KEY: doc.metadata.get(_TITLES_KEY, []),
            _CONTENT_KEY: doc.page_content,
            _RELEVANCE_SCORE_KEY: doc.metadata.get(_RELEVANCE_SCORE_KEY),
            _DOC_TYPE_KEY: doc.metadata.get(_DOC_TYPE_KEY) or "milvus",
            _CHUNK_INDEX_KEY: doc.metadata.get(_CHUNK_INDEX_KEY),
            "total_chunks": doc.metadata.get("total_chunks"),
            _FILE_VERSION_LABEL_KEY: doc.metadata.get(_FILE_VERSION_LABEL_KEY) or "",
            _TAGS_KEY: doc.metadata.get(_TAGS_KEY) or [],
            _USED_KEY: True,
        }
        # 联网搜索结果透出网页标题 name，供前端 <a href={file}>{name}</a> 跳转；
        # 知识库/代码条目的 name 由上层服务按 file 查库补全，此处不注入避免字段冲突
        if (doc.metadata.get(_DOC_TYPE_KEY) or "milvus") == "internet_search":
            out_dict[_NAME_KEY] = doc.metadata.get("title") or ""
        source_documents.append(out_dict)

    _merged: OrderedDict = OrderedDict()
    for d in source_documents:
        key = d.get(_FILE_KEY)
        if key not in _merged:
            _merged[key] = {
                _FILE_KEY: key,
                _RELEVANCE_SCORE_KEY: [],
                _PAGE_KEY: [],
                _USED_KEY: True,
                _DOC_TYPE_KEY: [],
                _FILE_VERSION_LABEL_KEY: d.get(_FILE_VERSION_LABEL_KEY) or "",
                _TAGS_KEY: list(d.get(_TAGS_KEY) or []),
                "chunks": [],
            }
            # 联网搜索结果：文件级条目同样透出 name（网页标题）
            if d.get(_NAME_KEY):
                _merged[key][_NAME_KEY] = d[_NAME_KEY]
        entry = _merged[key]
        entry[_RELEVANCE_SCORE_KEY].append(d.get(_RELEVANCE_SCORE_KEY))
        page_val = d.get(_PAGE_KEY)
        if page_val:
            entry[_PAGE_KEY].append(page_val)
        doc_type_val = d.get(_DOC_TYPE_KEY)
        if doc_type_val and doc_type_val not in entry[_DOC_TYPE_KEY]:
            entry[_DOC_TYPE_KEY].append(doc_type_val)
        # tags 聚合：同文件多块的 tags 求并集去重保序
        for t in d.get(_TAGS_KEY) or []:
            if t not in entry[_TAGS_KEY]:
                entry[_TAGS_KEY].append(t)
        entry["chunks"].append({
            _CONTENT_KEY: d.get(_CONTENT_KEY),
            _RELEVANCE_SCORE_KEY: d.get(_RELEVANCE_SCORE_KEY),
            _PAGE_KEY: page_val,
            _CHUNK_INDEX_KEY: d.get(_CHUNK_INDEX_KEY),
            _DOC_TYPE_KEY: doc_type_val,
            _FILE_VERSION_LABEL_KEY: d.get(_FILE_VERSION_LABEL_KEY),
            _TAGS_KEY: d.get(_TAGS_KEY),
            _USED_KEY: d.get(_USED_KEY),
        })
    return list(_merged.values())


def _build_route_summary(ctx: ChatContext) -> str:
    """根据路由阶段写入的 kb_stats/ctx，生成完成时态的"分析完成"结论文案。

    用于 _phase_generate 开头补发的 route_reasoning 收尾事件，让前端时间线
    从"正在..."收尾为带结论的完成态，避免回答结束后仍残留进行时提示。
    按 ctx.language 切换中英文，英文回答时时间线不再残留中文。
    """
    kb_stats = ctx.kb_stats
    reason = kb_stats.get("route_reason") or ""
    keywords = ctx.route_keywords or []
    lang = ctx.language

    if reason in ("greeting", "chitchat_keyword"):
        return _lang_text("已识别为闲聊/问候，直接回复", "Identified as chitchat/greeting, replying directly", lang)
    if reason == "version_diff":
        _vd = ctx.verdiff_intent or {}
        if _vd.get("file"):
            return _lang_text(
                f"已识别版本对比意图：{_vd.get('from', '?')} → {_vd.get('to', '?')}（{_vd.get('file')}）",
                f"Identified version comparison intent: {_vd.get('from', '?')} → {_vd.get('to', '?')} ({_vd.get('file')})",
                lang,
            )
        return _lang_text("已识别版本对比意图", "Identified version comparison intent", lang)
    if reason == "route_no_decision_failopen":
        return _lang_text("已按兜底策略检索知识库", "Retrieved knowledge base with fallback strategy", lang)
    if reason == "tech_keyword" or (ctx.need_rag and reason):
        if keywords:
            _kw_zh = '、'.join(str(k) for k in keywords[:4])
            _kw_en = ', '.join(str(k) for k in keywords[:4])
            return _lang_text(
                f"已检索知识库 · 命中关键词：{_kw_zh}",
                f"Retrieved knowledge base - matched keywords: {_kw_en}",
                lang,
            )
        return _lang_text("已检索知识库相关内容", "Retrieved relevant knowledge base content", lang)
    return _lang_text("分析完成", "Analysis complete", lang)


async def _phase_route(ctx: ChatContext) -> AsyncIterable[str]:
    """路由阶段：规则闸门 + 记忆任务创建 + 版本对比意图检测 + LLM 路由 + LAC。

    产出 route_reasoning 事件（stream 模式），路由决策写入 ctx：
    need_rag/rewritten_query/sub_queries/route_keywords/route_decision/verdiff_intent/mem_task/kb_stats。
    """
    _RAG_TRIGGERED_KEY = 'rag_triggered'
    _TYPE_KEY = 'type'
    _ROUTE_REASONING_KEY = 'route_reasoning'
    _STREAM_KEY = 'stream'
    _CONTENT_KEY = 'content'
    _MESSAGE_ID_KEY = 'message_id'
    _ROUTE_REASON_KEY = 'route_reason'
    query = ctx.query
    history = ctx.history
    stream = ctx.stream
    message_id = ctx.message_id
    user_id = ctx.user_id
    conversation_id = ctx.conversation_id
    kb_stats = ctx.kb_stats

    # ── 前置：规则快速路由（问候/闲聊/技术关键词） + LLM 融合调用兜底 ──
    is_greeting = _is_greeting(query)
    _has_chitchat = _has_chitchat_keyword(query)
    _has_tech = _has_tech_keyword(query)
    ctx.need_rag = True
    ctx.rewritten_query = None
    ctx.sub_queries = None
    ctx.route_keywords = None

    # ── 长期记忆：检索用户画像 + 相关历史对话（fail-open，两分支共用）──
    ctx.mem_task = None
    try:
        from server.memory.memmachine_client import get_memmachine, memmachine_enabled
        if memmachine_enabled():
            _mm = get_memmachine()
            ctx.mem_task = asyncio.create_task(
                _search_memory_both(_mm, user_id, conversation_id or "", query))
    except Exception as _mem_e:
        logger.warning(f"[memory] 记忆检索任务创建失败（跳过注入）: {_mem_e}")

    # 版本对比意图提前检测：命中则跳过 LLM 路由，直接走 RAG 版本对比快路径
    ctx.verdiff_intent = _detect_version_diff(query)

    # 单版本检索意图兜底：调用方未传 version 且未命中 verdiff 时，从 query 自动提取版本串。
    # 命中后走正常检索路（version filter == label），不走 verdiff 双轨。
    if ctx.version is None and ctx.verdiff_intent is None:
        _vf = _detect_version_filter(query)
        if _vf:
            ctx.version = _vf
            kb_stats["version_filter_detected"] = _vf
            logger.info(f"[route] 检测到单版本检索意图 version={_vf} | query={query!r}")

    # 规则快速路由：命中问候/闲聊 → 闲聊分支；命中技术关键词 → RAG 分支
    if is_greeting or _has_chitchat:
        ctx.need_rag = False
        _rule_reason = "greeting" if is_greeting else "chitchat_keyword"
        kb_stats[_RAG_TRIGGERED_KEY] = False
        kb_stats["no_hit_reason"] = "chitchat"
        kb_stats[_ROUTE_REASON_KEY] = _rule_reason
        logger.info(f"[route] branch=chitchat reason={_rule_reason} | 跳过 LLM route | query={query!r}")
        if stream:
            yield json.dumps({
                _TYPE_KEY: _ROUTE_REASONING_KEY,
                _STREAM_KEY: False,
                _CONTENT_KEY: _lang_text("正在为您思考回复...", "Thinking about the response...", ctx.language),
                _MESSAGE_ID_KEY: message_id,
            }, ensure_ascii=False)
    elif ctx.verdiff_intent:
        # Bug G：verdiff 优先级高于 tech_keyword（更具体的意图先匹配）
        # "v1.0 和 v2.0 的 GPIO 差异"含技术词但应走 verdiff 而非 tech_keyword
        ctx.need_rag = True
        ctx.route_keywords = []
        ctx.route_decision = {}
        kb_stats[_RAG_TRIGGERED_KEY] = True
        kb_stats[_ROUTE_REASON_KEY] = "version_diff"
        logger.info(f"[route] branch=version_diff | 跳过 LLM route | query={query!r}")
        if stream:
            yield json.dumps({
                _TYPE_KEY: _ROUTE_REASONING_KEY,
                _STREAM_KEY: False,
                _CONTENT_KEY: _lang_text("正在为您对比版本差异...", "Comparing version differences...", ctx.language),
                _MESSAGE_ID_KEY: message_id,
            }, ensure_ascii=False)
    elif _has_tech:
        ctx.need_rag = True
        ctx.route_keywords = await _extract_keywords_lac(query)
        kb_stats[_RAG_TRIGGERED_KEY] = True
        kb_stats[_ROUTE_REASON_KEY] = "tech_keyword"
        logger.info(f"[route] branch=tech_keyword | 跳过 LLM route | query={query!r} | keywords={ctx.route_keywords}")
        if stream:
            yield json.dumps({
                _TYPE_KEY: _ROUTE_REASONING_KEY,
                _STREAM_KEY: False,
                _CONTENT_KEY: _lang_text("正在为您检索知识库相关内容...", "Retrieving relevant knowledge base content...", ctx.language),
                _MESSAGE_ID_KEY: message_id,
            }, ensure_ascii=False)
    else:
        # LLM 融合调用：意图判定 + 改写 + 多query分解
        _route_t0 = time.time()
        _reasoning_queue: asyncio.Queue = asyncio.Queue()
        _SENTINEL = object()

        _route_task = asyncio.create_task(_run_route(
            ctx.query, ctx.history, ctx.router_client, ctx.router_model_id,
            stream=ctx.stream, reasoning_queue=_reasoning_queue, sentinel=_SENTINEL,
        ))
        _lac_task = asyncio.create_task(_extract_keywords_lac(query))

        # B4：try/finally 包裹 task 使用阶段，生成器异常退出/客户端断开
        # （GeneratorExit 不被 except Exception 捕获）时取消未完成 task，避免孤儿
        try:
            if stream:
                while True:
                    _item = await _reasoning_queue.get()
                    if _item is _SENTINEL:
                        break
                    yield json.dumps({
                        _TYPE_KEY: _ROUTE_REASONING_KEY,
                        _STREAM_KEY: True,
                        _CONTENT_KEY: _item,
                        _MESSAGE_ID_KEY: message_id,
                    }, ensure_ascii=False)
                ctx.route_decision = await _route_task
            else:
                ctx.route_decision = await _route_task

            _route_t1 = time.time()
            logger.info(f"[timing] route_and_rewrite: {_route_t1 - _route_t0:.3f}s")

            if ctx.route_decision:
                ctx.need_rag = ctx.route_decision.get("need_rag", True)
                ctx.rewritten_query = ctx.route_decision.get("rewritten_query")
                ctx.sub_queries = ctx.route_decision.get("sub_queries")
                ctx.route_keywords = await _lac_task
                if not ctx.route_keywords:
                    _kw = ctx.route_decision.get("keywords")
                    if isinstance(_kw, list):
                        ctx.route_keywords = [str(k).strip() for k in _kw if k and str(k).strip()][:4]
                _route_type = ctx.route_decision.get("route_type", "knowledge_qa")
                _route_reason = ctx.route_decision.get("reason", "")
                kb_stats[_RAG_TRIGGERED_KEY] = ctx.need_rag
                kb_stats["rewritten_query"] = ctx.rewritten_query or ""
                kb_stats[_ROUTE_REASON_KEY] = _route_reason
                logger.info(
                    f"[route] branch=llm_route | need_rag={ctx.need_rag} | route_type={_route_type} | "
                    f"rewritten={ctx.rewritten_query!r} | sub_queries={ctx.sub_queries} | "
                    f"keywords={ctx.route_keywords} | reason={_route_reason}"
                )
            else:
                logger.warning("[route] 未收到路由决策，fail-open 走 RAG")
                kb_stats[_RAG_TRIGGERED_KEY] = True
                kb_stats[_ROUTE_REASON_KEY] = "route_no_decision_failopen"
        finally:
            # B4：取消未完成的 route/lac task（正常完成时 done()=True 跳过）
            _pending = [_t for _t in (_route_task, _lac_task) if not _t.done()]
            for _t in _pending:
                _t.cancel()
            if _pending:
                await asyncio.gather(*_pending, return_exceptions=True)


async def _phase_retrieve(ctx: ChatContext) -> AsyncIterable[str]:
    """检索阶段：search_queries/answer_query 计算 + 版本对比执行 + 共享 embedding
    + 多库并发检索 + RRF 融合 + 去重 + reranker + 代码路保底。

    产出 route_reasoning 事件（verdiff 分支 stream 模式），检索结果写入 ctx：
    search_queries/answer_query/verdiff_ctx/verdiff_doc/docs。
    """
    _KNOWLEDGE_BASE_NAME_KEY = 'knowledge_base_name'
    _SEARCH_QUERY_KEY = 'search_query'
    _COSINE_SIM_KEY = 'cosine_similarity'
    _RELEVANCE_SCORE_KEY = 'relevance_score'
    query = ctx.query
    valid_kbs = ctx.valid_kbs
    stream = ctx.stream
    message_id = ctx.message_id
    top_k = ctx.top_k
    score_threshold = ctx.score_threshold
    kb_stats = ctx.kb_stats
    rewritten_query = ctx.rewritten_query
    route_keywords = ctx.route_keywords

    # ── need_rag=true：用原始 query 走检索链路 ──
    # [已禁用] 问题改写和多query分解，只用原始 query 检索
    ctx.search_queries = [rewritten_query] if rewritten_query else [query]
    ctx.answer_query = query  # 回答 prompt 用原始 query
    search_queries = ctx.search_queries
    answer_query = ctx.answer_query
    logger.info(f"[route] 检索用 query: {search_queries} | 回答用 query: {answer_query}")

    # ── 版本对比快路径：命中意图则跳过检索，diff 直接作为上下文 ──
    ctx.verdiff_ctx = None
    ctx.verdiff_doc = None
    _verdiff_ctx = ctx.verdiff_ctx
    _vd = ctx.verdiff_intent  # 复用规则闸门阶段提前计算的检测结果
    if _vd is not None:
        if stream:
            yield json.dumps({"type": "route_reasoning",
                              "stream": False,
                              "content": _lang_text("正在对比文件版本差异...", "Comparing file version differences...", ctx.language),
                              "message_id": message_id}, ensure_ascii=False)
        _vres = await asyncio.to_thread(_run_verdiff_for_kbs, valid_kbs, _vd, ctx.query)
        ctx.verdiff_ctx, ctx.verdiff_doc = _build_verdiff_context(_vd, _vres)
        _verdiff_ctx = ctx.verdiff_ctx
        logger.info(f"[route] branch=version_diff | file={_vd.get('file')} "
                    f"from={_vd.get('from')} to={_vd.get('to')} | "
                    f"found={bool(_vres and _vres.get('found'))}")

    # ── 多库并发检索：外层 gather 多库 × 内层 gather 多 query × 三路检索 ──
    t0 = time.time()

    # 预计算所有 search_queries 的 embedding（一次批量调用）
    t_embed_start = time.time()
    all_embeddings = ([] if _verdiff_ctx is not None
                      else (await aget_embeddings(search_queries)).tolist())
    t_embed_end = time.time()
    logger.info(f"[timing] shared_embed: {t_embed_end - t_embed_start:.3f}s, n_queries={len(search_queries)}")

    # TopK 解耦：召回数（RECALL_TOP_K，大）与 Reranker 返回数（top_k，小）分离
    recall_k = RECALL_TOP_K

    # P2-1：编排层统一查 cur_labels_map（per-kb），避免三路各自查 build_files_version_map
    # 竞态窗口内重复 DB 查询（虽有 60s TTL 缓存兜底，但三路并发 miss 仍可能 3 次 DB 查询）。
    # version is None 且非 verdiff 分支才查；build_files_version_map 同步 DB 查询用 to_thread 不阻塞。
    # 多 kb 并发查询；fail-open：单 kb 失败记 None，三路收到 None 会自查兜底（或最终 fail-open）。
    cur_labels_map: dict = {}
    if ctx.version is None and _verdiff_ctx is None and valid_kbs:
        from server.knowledge_base.file_version import build_files_version_map

        async def _query_cur_labels(kb_name: str) -> tuple:
            try:
                m = await asyncio.to_thread(build_files_version_map, kb_name)
                return kb_name, set(l for l in m.values() if l)
            except Exception as e:
                logger.warning(f"[pipeline] kb='{kb_name}' 查当前版本映射失败（fail-open）: {e}")
                return kb_name, None

        _t_cl = time.time()
        _labels_results = await asyncio.gather(*[_query_cur_labels(kb) for kb in valid_kbs])
        cur_labels_map = dict(_labels_results)
        logger.info(f"[timing] cur_labels_map: {time.time() - _t_cl:.3f}s, "
                    f"kbs={len(valid_kbs)}, filled={sum(1 for v in cur_labels_map.values() if v)}")

    # 并发：所有 kb × 所有 search_queries（版本对比分支跳过检索）
    # 顺序约定：外层 qi（query）主序、内层 kb 主序，与下方合并循环的枚举顺序一致，
    # 保证 per_kbq_results[_result_idx] 与 (qi, kb_idx) 映射正确，metadata 不标错。
    # P1-3：检索路并发信号量，限同时在飞的检索请求数防打爆下游连接池。
    # verdiff 分支无检索不占信号量；内层三路 gather 仍并发。acquire_wait 日志
    # 分离排队分量（>0.05s 才记）；t0 已含 embed 故用 _t_sem 独立测等待。
    if _verdiff_ctx is not None:
        per_kbq_results = []
    else:
        _t_sem = time.time()
        async with _RETRIEVE_SEMAPHORE:
            _sem_wait = time.time() - _t_sem
            if _sem_wait > 0.05:
                logger.info(f"[timing] retrieve_sem_acquire_wait: {_sem_wait:.3f}s")
            per_kbq_results = await asyncio.gather(
                *[_retrieve_one_kb_one_query(kb, sq, all_embeddings[qi],
                    recall_k, route_keywords=route_keywords,
                    score_threshold=score_threshold,
                    filter_opts=SearchFilter(tags=ctx.tags, version=ctx.version,
                                             cur_labels=cur_labels_map.get(kb)))
                  for qi, sq in enumerate(search_queries)
                  for kb in valid_kbs],
                return_exceptions=True,
            )
    t2 = time.time()
    logger.info(f"[timing] multi_kbq_search: {t2 - t0:.3f}s, kbs={valid_kbs}, n_queries={len(search_queries)}, recall_k={recall_k}")  # pylint: disable=line-too-long

    # 合并所有结果，按路别收集（版本对比分支无检索结果，跳过）
    doc_list, code_list, mp_list = [], [], []
    _result_idx = 0
    for _, sq in enumerate(search_queries if per_kbq_results else []):
        for _, kb_name in enumerate(valid_kbs):
            res = per_kbq_results[_result_idx]
            _result_idx += 1
            if isinstance(res, Exception):
                logger.warning(f"[pipeline] kb='{kb_name}' q='{sq[:40]}' 检索异常: {res}")
                kb_stats["degraded"] = True
                continue
            doc_docs, code_docs, mp_docs = res[0], res[1], res[2]
            if isinstance(doc_docs, list):
                for d in doc_docs:
                    d.metadata[_KNOWLEDGE_BASE_NAME_KEY] = kb_name
                    d.metadata[_SEARCH_QUERY_KEY] = sq
                doc_list.extend(doc_docs)
            else:
                logger.warning(f"[pipeline] kb='{kb_name}' Milvus 异常: {doc_docs}")
            if isinstance(code_docs, list):
                for d in code_docs:
                    d.metadata[_KNOWLEDGE_BASE_NAME_KEY] = kb_name
                    d.metadata[_SEARCH_QUERY_KEY] = sq
                code_list.extend(code_docs)
            else:
                logger.warning(f"[pipeline] kb='{kb_name}' 代码检索异常: {code_docs}")
            if isinstance(mp_docs, list):
                for d in mp_docs:
                    d.metadata[_KNOWLEDGE_BASE_NAME_KEY] = kb_name
                    d.metadata[_SEARCH_QUERY_KEY] = sq
                mp_list.extend(mp_docs)
            else:
                logger.warning(f"[pipeline] kb='{kb_name}' mempalace 异常: {mp_docs}")
    logger.info(f"[pipeline] 合并 doc={len(doc_list)} code={len(code_list)} "
                f"mempalace={len(mp_list)} kbs={valid_kbs} n_queries={len(search_queries)}")

    # 三路 RRF 融合：跨源 score 尺度不可比，按各路内 rank 派生统一可比分数
    # Bug 4+5 修复：RRF key 从 id(doc) 改为归一化内容哈希——同一文本块被
    # milvus+mempalace 同时命中时是两个不同 Document 对象（id 不同），旧逻辑
    # 分别累积 RRF 分数、dedupe 后只保留一份、另一份的分数丢失。改用内容
    # 哈希后跨路同内容块在 RRF 阶段即合并分数，reranker 看到的是合并后的
    # 真实强度。同时规避 id() 在对象 gc 后被复用导致分数错位累积的（理论）风险。
    rrf_acc: dict = {}  # key=内容哈希 → [累积 rrf 分, doc 引用]
    for route_docs in (doc_list, code_list, mp_list):
        # 按相关性降序定 rank：Milvus/mempalace 用真 cosine_similarity，
        # dual_mcp 路无真向量分数，用 route_score（= 服务返回顺序派生）兜底。
        # RRF 只用 rank，不用分数本身；分数仅决定路内排序。
        route_sorted = sorted(
            route_docs,
            key=lambda d: d.metadata.get(_COSINE_SIM_KEY) or d.metadata.get("route_score", 0.0),
            reverse=True,
        )
        for rank, d in enumerate(route_sorted):
            key = _norm_text(d.page_content)
            if not key:
                continue  # 空内容块跳过（_dedupe_docs 也会丢）
            if key not in rrf_acc:
                rrf_acc[key] = [0.0, d]
            rrf_acc[key][0] += 1.0 / (RRF_K + rank + 1)
            if d.metadata.get(_COSINE_SIM_KEY, 0.0) > rrf_acc[key][1].metadata.get(_COSINE_SIM_KEY, 0.0):
                rrf_acc[key][1].metadata[_COSINE_SIM_KEY] = d.metadata.get(_COSINE_SIM_KEY, 0.0)
    docs = [item[1] for item in sorted(rrf_acc.values(), key=lambda x: x[0], reverse=True)]
    logger.info(f"[pipeline] RRF 融合后候选数: {len(docs)}, top3 cosine="
                f"{[round(d.metadata.get(_COSINE_SIM_KEY, 0), 4) for d in docs[:3]]}")

    # 跨路去重（RRF 后、reranker 前）：RRF 已按内容哈希合并了"完全相同"
    # 的块，这里再处理"一方完整包含另一方"的包含关系，避免长块和它的子块
    # 同时挤进 reranker top_n 与上下文预算
    docs = _dedupe_docs(docs)
    logger.info(f"[pipeline] 跨路去重后候选数: {len(docs)}")

    # 加入reranker（用原始 query 保持与用户意图一致；版本对比分支无候选，跳过）
    if USE_RERANKER and docs:
        logger.info("start RERANKER!")
        # top_n：配置值(RERANKER_TOP_N)为下限，请求 top_k 可按需放宽，上限 _RERANK_TOP_N_MAX
        rerank_top_n = min(max(top_k, RERANKER_TOP_N), _RERANK_TOP_N_MAX)
        if rerank_top_n != RERANKER_TOP_N:
            logger.info(f"[pipeline] reranker top_n 按需放宽: 配置={RERANKER_TOP_N}, "
                        f"请求 top_k={top_k}, 生效={rerank_top_n}")
        reranker_model = get_reranker_singleton()

        if hasattr(reranker_model, 'api_base_url'):
            logger.info(f"使用 HTTP API Reranker: {reranker_model.api_base_url}")
        else:
            logger.info("使用本地 Reranker 模型")

        rrf_fallback = docs[:rerank_top_n]
        try:
            reranked = await acompress_documents_bounded(
                reranker_model, documents=docs, query=answer_query, top_n=rerank_top_n)
            if not reranked:
                logger.warning(f"[pipeline] Reranker 返回空列表，回退到 RRF 排序的 top_{rerank_top_n}")
                docs = rrf_fallback
            else:
                docs = reranked
        except Exception as e:
            logger.error(f"[pipeline] Reranker 异常，回退到 RRF 排序的 top_{rerank_top_n}: {e}")
            docs = rrf_fallback
    t3 = time.time()
    logger.info(f"[timing] reranker: {t3 - t2:.3f}s")

    # 按 Reranker 分数降序排序（没有 Reranker 或 Reranker 失败兜底时按 RRF 顺序保持即可）
    reranker_scores = [d.metadata.get(_RELEVANCE_SCORE_KEY) for d in docs if d.metadata.get(_RELEVANCE_SCORE_KEY) is not None]
    if reranker_scores:
        docs = sorted(docs, key=lambda d: d.metadata.get(_RELEVANCE_SCORE_KEY, 0), reverse=True)
    else:
        # Reranker 未启用或失败兜底：RRF 已排好序，保持顺序即可
        pass

    # 代码路保底：reranker 偏文档语义，代码命中（含源码段）易被挤出 top_n。
    # 最终列表缺 code 或 code 内容不足时，按代码路序（cosine 降序，dual_mcp
    # 路无真 cosine 用 route_score 兜底）补足，实际纳入上下文的数量由 7:3 预算接管。
    if code_list:
        _code_total = sum(len(d.page_content or "") for d in docs
                          if d.metadata.get("doc_type") == "code")
        if _code_total < _CODE_CTX_GOAL:
            # 用内容哈希去重（非 id()）：HttpReranker.acompress_documents 会新建 Document
            # 对象（metadata.copy()），id() 不匹配原对象，导致已选入 docs 的 code 块被
            # 重复追加。改用 _norm_text(page_content) 与 RRF/dedupe 一致。
            _have = {_norm_text(d.page_content) for d in docs}
            for _cd in sorted(code_list,
                              key=lambda d: d.metadata.get(_COSINE_SIM_KEY) or d.metadata.get("route_score", 0.0),
                              reverse=True):
                _key = _norm_text(_cd.page_content)
                if _key in _have:
                    continue
                docs.append(_cd)
                _have.add(_key)
                _code_total += len(_cd.page_content or "")
                if _code_total >= _CODE_CTX_GOAL:
                    break
            if _code_total > 0:
                logger.info(f"[pipeline] 代码路保底后 code={_code_total} chars")

    ctx.docs = docs


async def _phase_assemble(ctx: ChatContext) -> AsyncIterable[str]:
    """组装阶段：预算切分 + 上下文拼接 + source_documents 格式化 + 文件合并 + kb_stats 终态。

    以 async generator 形式产出：联网搜索状态提示事件（仅 should_search 且 ctx.stream 时 yield）。
    其余结果写入 ctx：context / source_documents / kb_stats 终态字段。
    编排处用 async for 遍历，透传 SSE 事件。
    """
    _KB_HIT_KEY = 'kb_hit'
    _HIT_KBS_KEY = 'hit_kbs'
    _KNOWLEDGE_BASE_NAME_KEY = 'knowledge_base_name'
    _RELEVANCE_SCORE_KEY = 'relevance_score'
    _COSINE_SIM_KEY = 'cosine_similarity'
    docs = ctx.docs
    _verdiff_ctx = ctx.verdiff_ctx
    _verdiff_doc = ctx.verdiff_doc
    kb_stats = ctx.kb_stats

    # context 按 7:3 预算分段拼接（文档 70% / 代码 30%），按整块纳入、不拦腰截断
    CONTEXT_BUDGET = RAG_CONTEXT_BUDGET
    doc_docs_ctx = [d for d in docs if d.metadata.get("doc_type") != "code"]
    code_docs_ctx = [d for d in docs if d.metadata.get("doc_type") == "code"]
    doc_budget = int(CONTEXT_BUDGET * 0.7)
    doc_context, used_doc_chunks = _join_chunks_with_budget(doc_docs_ctx, doc_budget)
    code_remain = CONTEXT_BUDGET - len(doc_context)
    code_context, used_code_chunks = _join_chunks_with_budget(code_docs_ctx, code_remain)
    context = (doc_context + "\n" + code_context) if code_context else doc_context
    if _verdiff_ctx is not None:
        # 版本对比分支：检索已被跳过，diff 块即全部上下文
        context = _verdiff_ctx
    # 真正进入 LLM 上下文的块（整块纳入，id 集合用于 docs 响应打 used 标记）
    used_chunk_ids = {id(d) for d in used_doc_chunks + used_code_chunks}
    logger.info(f"[pipeline] context 组装 doc_budget={doc_budget}, doc_context={len(doc_context)} chars, "
                f"code_context={len(code_context)} chars, docs 总数={len(docs)}, "
                f"used_chunks={len(used_chunk_ids)}")

    # ==================== 联网搜索处理 ====================
    # 逻辑:
    # 1. enable_internet_search = True: 强制启用，总是搜索并融合结果（知识库+联网）
    # 2. enable_internet_search = False: 强制禁用，不搜索
    # 3. enable_internet_search = None: 自动模式，配置全局enable=True且知识库未命中时触发
    config = BASIC_CONFIG.get("INTERNET_SEARCH", {})
    global_enabled = config.get("enable", False)
    should_search = False

    # 根据接口参数和全局配置决定是否搜索
    if ctx.enable_internet_search is True:
        # 接口参数强制启用
        should_search = True
    elif ctx.enable_internet_search is False:
        # 接口参数强制禁用
        should_search = False
    else:  # None 自动模式
        # 全局启用且知识库未命中时触发。
        # 注意：kb_stats[kb_hit] 终态在函数末尾才计算，此处恒为初始值 False，
        # 不能用它判断；used_chunk_ids 已在上方算好，为空 = 无知识库块进入 LLM 上下文
        should_search = global_enabled and not used_chunk_ids and _verdiff_ctx is None

    if should_search:
        logger.info(f"[internet_search] 触发联网搜索: query={ctx.query}")
        if ctx.stream:
            # 向前端发送搜索状态提示，和知识库检索保持一致类型
            yield json.dumps({
                "type": "route_reasoning",
                "stream": False,
                "content": _lang_text("正在联网获取最新信息...", "Retrieving the latest information online...", ctx.language),
                "message_id": ctx.message_id,
            }, ensure_ascii=False) + "\n"
        search_client = get_aliyun_search_client()
        if search_client is not None:
            search_docs = await search_client.search(ctx.query)
            if search_docs and len(search_docs) > 0:
                logger.info(f"[internet_search] 联网搜索成功，返回 {len(search_docs)} 条结果")
                kb_stats["internet_search_enabled"] = True
                kb_stats["internet_search_hits"] = len(search_docs)
                if not kb_stats[_KB_HIT_KEY]:
                    kb_stats[_KB_HIT_KEY] = True
                    kb_stats["no_hit_reason"] = None

                # 将联网搜索结果添加到文档列表，后续统一处理；
                # id 同步加入 used_chunk_ids，否则 _build_source_documents 按 used
                # 过滤会把搜索结果丢弃，前端 docs 里看不到联网来源
                for doc in search_docs:
                    docs.append(doc)
                    used_chunk_ids.add(id(doc))

                # 拼接到上下文中
                internet_context = "\n\n--- 联网搜索结果 ---\n"
                for i, doc in enumerate(search_docs):
                    title = doc.metadata.get("title", f"搜索结果{i+1}")
                    url = doc.metadata.get("source", "")
                    snippet = doc.page_content
                    internet_context += f"\n{i+1}. {title}\n"
                    if url:
                        internet_context += f"来源: {url}\n"
                    internet_context += f"摘要: {snippet}\n"

                # 如果原context为空直接用搜索结果，否则追加
                if not context:
                    context = "未在知识库中找到相关信息，以下是来自互联网的搜索结果：\n" + internet_context
                else:
                    context += internet_context
            else:
                logger.info(f"[internet_search] 联网搜索返回空结果")
        else:
            logger.info(f"[internet_search] 搜索客户端未初始化成功，跳过搜索")

    # 将相关文档拼成docs响应，展示余弦相似度和 Reranker 分数，并标记来源知识库与是否被 LLM 实际使用
    # 只返回 used=true 的块（真正进入 LLM 上下文的块），used=false 的块不返回前端
    # C3：_build_source_documents 内部 _find_heading_line 做同步文件 IO，
    # to_thread 移入线程池避免阻塞事件循环（markdown_index 已加锁保护并发）
    source_documents = await asyncio.to_thread(_build_source_documents, docs, used_chunk_ids)
    if _verdiff_doc is not None:
        source_documents.append(_verdiff_doc)

    # 命中统计填充：必须在下方"未找到"占位逻辑之前取真值，否则占位条目会污染 used_count。
    # 仅统计真正进入 LLM 上下文的块（used_chunk_ids 集合），未进上下文的召回块不计入命中。
    _used_docs = [d for d in docs if id(d) in used_chunk_ids]
    kb_stats["retrieved_count"] = len(docs)          # rerank 后候选块数
    kb_stats["context_chunk_count"] = len(used_chunk_ids)   # 进入 LLM 上下文的 chunk 数
    kb_stats["used_count"] = len(source_documents)   # 文件级合并后的来源文档数（含 verdiff）
    kb_stats[_KB_HIT_KEY] = len(source_documents) > 0
    kb_stats[_HIT_KBS_KEY] = sorted({
        d.metadata.get(_KNOWLEDGE_BASE_NAME_KEY)
        for d in _used_docs
        if d.metadata.get(_KNOWLEDGE_BASE_NAME_KEY)
    })
    # Bug H：verdiff 分支 docs=[] → _used_docs=[] → hit_kbs 默认空，但 verdiff_doc 命中
    # 时 kb_hit=True，hit_kbs 应含 verdiff_doc 的 knowledge_base_name（统计口径一致）
    if _verdiff_doc is not None:
        _vk = _verdiff_doc.get(_KNOWLEDGE_BASE_NAME_KEY)
        if _vk:
            kb_stats[_HIT_KBS_KEY] = sorted(set(kb_stats[_HIT_KBS_KEY]) | {_vk})
    # top_score：优先 rerank 相关性分数，缺失时回退 cosine 相似度
    # （milvus 裸路径无 rerank 分，仅有 cosine_similarity）
    _scores = [
        d.metadata.get(_RELEVANCE_SCORE_KEY)
        if d.metadata.get(_RELEVANCE_SCORE_KEY) is not None
        else d.metadata.get(_COSINE_SIM_KEY, 0.0)
        for d in _used_docs
    ]
    kb_stats["top_score"] = max(_scores) if _scores else 0.0
    if not kb_stats[_KB_HIT_KEY]:
        kb_stats["no_hit_reason"] = "retrieval_failed" if kb_stats.get("degraded") else "no_docs"

    # 写入 ctx
    ctx.context = context
    ctx.source_documents = source_documents


async def _phase_generate(ctx: ChatContext) -> AsyncIterable[str]:
    """生成阶段：_finish_mem 收口 + chitchat/RAG 分支 LLM 生成 + 记忆抽取 + docs 下发。

    按 ctx.need_rag 分支：
    - 闲聊（not need_rag）：chitchat 模板 + 空 source_documents + profile_prefix 注入 system 段
    - RAG（need_rag）：empty/prompt_name 模板 + 检索 source_documents + profile_prefix 注入 context + "无法回答"占位
    共用：history 截断 + ChatPromptTemplate + openai_messages 构造 + _stream_llm_response/_non_stream_llm_response 透传。
    """
    _KNOWLEDGE_BASE_CHAT_KEY = 'knowledge_base_chat'
    # 路由/分析阶段收尾：在正式回答开始前，补一条完成时态的 route_reasoning，
    # 前端时间线据此从"正在..."收尾为带结论的完成态，避免回答结束后仍残留进行时提示
    if ctx.stream:
        yield json.dumps({
            "type": "route_reasoning",
            "stream": False,
            "content": _build_route_summary(ctx),
            "message_id": ctx.message_id,
        }, ensure_ascii=False)

    # 记忆检索收口点：三路检索+rerank+context 组装已完成，此刻收并行
    # 跑了全程的记忆结果（正常已就绪零等待；服务降级时最多再等 5s）
    # 闲聊分支原在分支起始收口（L989），RAG 分支原在 context 组装后收口（L1139），合并后统一在生成起始收口
    ctx.profile_prefix = await _finish_mem(ctx.mem_task, ctx.history)

    # 局部别名（从 ctx 取值）
    query = ctx.query
    history = ctx.history
    model_name = ctx.model_name
    prompt_name = ctx.prompt_name
    deep_think = ctx.deep_think
    stream = ctx.stream
    temperature = ctx.temperature
    message_id = ctx.message_id
    user_id = ctx.user_id
    conversation_id = ctx.conversation_id
    openai_client = ctx.openai_client
    config = ctx.config
    kb_stats = ctx.kb_stats

    if not ctx.need_rag:
        # ── 闲聊分支（原 L989-1073）──
        # 闲聊响应分支（含规则命中与 LLM 路由判定为闲聊两种来源）：
        # 统一标记 no_hit_reason=chitchat，规则分支已在路由阶段设置，此处兜底 LLM 路由来源
        if not kb_stats["no_hit_reason"]:
            kb_stats["no_hit_reason"] = "chitchat"
        _tpl = resolve_prompt_name(_KNOWLEDGE_BASE_CHAT_KEY, "chitchat", ctx.language)
        _sys_text, _user_text = get_prompt_split(_KNOWLEDGE_BASE_CHAT_KEY, _tpl)
        if ctx.profile_prefix:
            _sys_text = ctx.profile_prefix + _sys_text
        source_documents = []
        context = ""  # 闲聊分支无 context
        do_unanswerable_check = False
        mode_label = "（闲聊分支）"
        err_log_suffix = " (chitchat)"
        extract_before_yield = True
        log_response_summary = False
        format_question = query  # 闲聊用原始 query
    else:
        # ── RAG 分支（原 L1095-1208）──
        docs = ctx.docs
        _verdiff_ctx = ctx.verdiff_ctx
        context = ctx.context
        source_documents = ctx.source_documents

        if len(docs) == 0 and _verdiff_ctx is None:  # 没有找到相关文档且非版本对比分支，使用empty模板
            _tpl_name = "empty"
        else:
            _tpl_name = prompt_name
        _tpl_name = resolve_prompt_name(_KNOWLEDGE_BASE_CHAT_KEY, _tpl_name, ctx.language)
        _sys_text, _user_text = get_prompt_split(_KNOWLEDGE_BASE_CHAT_KEY, _tpl_name)

        context = context.replace("===", "-")
        context = context.replace("^^^", "")
        if ctx.profile_prefix:
            context = ctx.profile_prefix + context

        if len(source_documents) == 0 and _verdiff_ctx is None:
            # 没有找到相关文档且非版本对比分支才触发占位
            # verdiff 分支即使 verdiff_doc 未命中，context 已含对比提示，交由 LLM 基于 context 回答
            if ctx.language == "en":
                text = "<span style='color:red'>No relevant documents found, unable to answer this question.</span>"
            else:
                text = "<span style='color:red'>未找到相关文档，无法回答该问题。</span>"
            out_dict = {
                "text": text,
                "num": 0,
                "filename": "",
                "url": "",
                "page_content": ""
            }
            source_documents = []
            source_documents.append(out_dict)

        do_unanswerable_check = True
        mode_label = " "
        err_log_suffix = ""
        extract_before_yield = False
        log_response_summary = True
        format_question = ctx.answer_query  # RAG 用 answer_query

    # ── 共用：system/body 已在 YAML 显式拆分，system 作为列表首条稳定前缀走
    #     Dashscope 前缀缓存降低 TTFT，body 含 context/question 占位放历史之后 ──
    _SYSTEM_ROLE_KEY = 'system'
    _USER_ROLE_KEY = 'user'
    _system_msg = (History(role=_SYSTEM_ROLE_KEY, content=_sys_text).to_msg_template(False)
                   if _sys_text else None)
    input_msg = History(role=_USER_ROLE_KEY, content=_user_text).to_msg_template(False)

    # ── 共用：history 截断（两分支预算一致，均用 RAG_CONTEXT_BUDGET 为基数；
    #     闲聊分支原注释"与 RAG 分支保持一致：用 RAG_HISTORY_BUDGET_RATIO 而非硬编码 2048"）──
    new_history = []
    CONTEXT_BUDGET = RAG_CONTEXT_BUDGET
    # assistant 历史消息按比例截断，避免长回答堆进 prompt 拉长 prefill
    _history_budget = int(CONTEXT_BUDGET * RAG_HISTORY_BUDGET_RATIO)
    # A3 代码兜底：确保单条截断上限不超过总预算，防止配置矛盾时截断后仍超总预算 → 历史全丢
    _history_budget = min(_history_budget, int(CONTEXT_BUDGET * RAG_HISTORY_TOTAL_BUDGET_RATIO))
    # 总历史长度上限：超出则倒序保留最近的，防止长对话堆叠拖垮 TTFT
    _total_history_budget = int(CONTEXT_BUDGET * RAG_HISTORY_TOTAL_BUDGET_RATIO)
    _total_len = 0
    # 倒序遍历：优先保留最近的历史，超预算直接 break
    for i in reversed(history):
        # D1：创建副本而非原地突变 i.content，避免污染调用方传入的
        # 原 History 对象（记忆抽取/闲聊与 RAG 分支共享同一 history 列表，
        # 原地截断会让后续请求或记忆抽取拿到被截短的回答）
        if i.role == 'assistant' and len(i.content) > _history_budget:
            i = History(role=i.role, content=i.content[:_history_budget])
        if _total_len + len(i.content) > _total_history_budget:
            break
        _total_len += len(i.content)
        new_history.insert(0, i)

    # RAG 分支保留原日志（闲聊原代码无此日志，保持差异）
    if ctx.need_rag:
        logger.info(f"[history] 保留 {len(new_history)}/{len(history)} 条，"
                    f"总长 {_total_len}/{_total_history_budget} chars")

    _msgs = [i.to_msg_template() for i in new_history] + [input_msg]
    if _system_msg is not None:
        _msgs = [_system_msg] + _msgs
    chat_prompt = ChatPromptTemplate.from_messages(_msgs)

    # 构建 OpenAI 格式的 messages（闲聊分支无 context 占位，RAG 分支用 answer_query 保留用户原意）
    if ctx.need_rag:
        messages = chat_prompt.format_messages(context=context, question=format_question)
    else:
        messages = chat_prompt.format_messages(question=format_question)

    openai_messages = []
    for msg in messages:
        # ChatMessage.type 恒为 "chat"，role_map 取不到会 fallback 成 "user"，
        # 导致 assistant 历史消息丢失角色，这里对 ChatMessage 用 msg.role
        if isinstance(msg, ChatMessage):
            role = msg.role
        else:
            role_map = {"human": _USER_ROLE_KEY, "ai": "assistant", _SYSTEM_ROLE_KEY: _SYSTEM_ROLE_KEY}
            role = role_map.get(msg.type, _USER_ROLE_KEY)
        openai_messages.append({"role": role, "content": msg.content})

    if stream:
        async for _chunk in _stream_llm_response(
            openai_client=openai_client,
            cfg=config,
            model_name=model_name,
            openai_messages=openai_messages,
            temperature=temperature,
            max_tokens=ctx.max_tokens,
            deep_think=deep_think,
            message_id=message_id,
            source_documents=source_documents,
            do_unanswerable_check=do_unanswerable_check,
            kb_stats=kb_stats,
            mode_label=mode_label,
            err_log_suffix=err_log_suffix,
            err_handled_setter=lambda: setattr(ctx, 'err_handled', True),
            user_id=user_id,
            query=query,
            history=history,
            conversation_id=conversation_id,
        ):
            yield _chunk
    else:
        async for _chunk in _non_stream_llm_response(
            openai_client=openai_client,
            cfg=config,
            model_name=model_name,
            openai_messages=openai_messages,
            temperature=temperature,
            max_tokens=ctx.max_tokens,
            deep_think=deep_think,
            message_id=message_id,
            source_documents=source_documents,
            do_unanswerable_check=do_unanswerable_check,
            kb_stats=kb_stats,
            mode_label=("（非流式）" if ctx.need_rag else "（闲聊分支，非流式）"),
            err_log_suffix=err_log_suffix,
            err_handled_setter=lambda: setattr(ctx, 'err_handled', True),
            log_response_summary=log_response_summary,
            extract_before_yield=extract_before_yield,
            user_id=user_id,
            query=query,
            history=history,
            conversation_id=conversation_id,
        ):
            yield _chunk


async def _stream_llm_response(
    *,
    openai_client,
    cfg,
    model_name,
    openai_messages,
    temperature,
    max_tokens,
    deep_think,
    message_id,
    source_documents,
    do_unanswerable_check,
    kb_stats,
    mode_label,
    err_log_suffix,
    err_handled_setter,
    user_id,
    query,
    history,
    conversation_id,
):
    """共享的 LLM 流式生成逻辑（闲聊/RAG 分支共用）。

    差异点通过参数注入：
    - source_documents: docs 事件字段（闲聊传 []，RAG 传检索结果）
    - do_unanswerable_check: 是否做"无法回答"检测并校正 kb_stats
    - mode_label / err_log_suffix: 日志标签（区分闲聊/RAG）
    - err_handled_setter: 回调，except 时设置 ctx.err_handled=True
    """
    _MODEL_ID_KEY = 'model_id'
    _ERROR_KEY = 'error'
    _MESSAGE_ID_KEY = 'message_id'
    _ERROR_LEVEL_KEY = 'error'
    full_response = ""
    _req_id = None  # DashScope requestID，create 成功后填充（chunk idle 超时排查限流关键）
    try:
        extra_body = {"enable_thinking": True} if deep_think else {}
        if deep_think:
            logger.info(f"===== 深度思考模式已开启{mode_label}=====")
        else:
            logger.info(f"===== 普通模式{mode_label}=====")

        t4 = time.time()
        # P0-3：全局并发信号量，限制同时在飞的主 LLM 请求数（防 DashScope 批量 429 + 防 httpx
        # 连接池占满）。acquire 在 P0-2 超时之外；队列背压由 uvicorn limit_concurrency 兜底。
        # async with 保证所有退出路径（正常/超时/异常/客户端断开）释放信号量。整段流式期间
        # DashScope 连接持续占用，故信号量必须覆盖 create + chunk 循环，释放早于记忆抽取。
        # t4 在 async with 之前，TTFT(t5-t4)/stream_total(t6-t4) 含 acquire 等待——用户感知
        # 口径（实际等待 = 排队 + LLM）；acquire_wait 日志分离排队分量，压测时可定位瓶颈来源。
        async with _LLM_CONCURRENCY:
            _sem_wait = time.time() - t4
            if _sem_wait > 0.05:
                logger.info(f"[timing] llm_sem_acquire_wait: {_sem_wait:.3f}s{err_log_suffix}")
            # P0-2：流式建立超时保护。create(stream=True) 到返回 stream_resp 这段若 DashScope
            # 连接建立/鉴权/prefill 排队 hang，_LLM_STREAM_CONNECT_TIMEOUT 到点 cancel 并降级。
            try:
                stream_resp = await asyncio.wait_for(
                    openai_client.chat.completions.create(
                        model=cfg.get(_MODEL_ID_KEY, model_name),
                        messages=openai_messages,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        extra_body=extra_body,
                        stream=True,
                    ),
                    timeout=_LLM_STREAM_CONNECT_TIMEOUT,
                )
            except asyncio.TimeoutError as e:
                logger.error(f"[timing] llm_stream_connect_timeout: "
                             f"{_LLM_STREAM_CONNECT_TIMEOUT}s{err_log_suffix}: {e}")
                log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_stream_connect_timeout",
                              None, level=_ERROR_LEVEL_KEY, reason="create阶段未建立连接")
                err_handled_setter()
                yield json.dumps({_ERROR_KEY: "大模型响应建立超时，请重试",
                                  _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
                return

            # create 成功返回即提取 req_id：排队式限流时 chunk 永不来但响应头已到，
            # 此时 req_id 可取；chunk idle 超时日志据此向阿里云客服提供 requestID 排查
            _req_id = extract_aliyun_request_id(stream_resp)
            log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_stream_start",
                          _req_id, level="debug")

            first_token = True
            # P0-2：chunk idle 超时保护。async for 改为 __anext__ + wait_for，两个 chunk 之间
            # 若超过 _LLM_CHUNK_IDLE_TIMEOUT（生成中途网络断/服务 hang），到点 cancel 并降级。
            # 生成中途 hang 是 200 并发雪崩主因——create 已成功返回，旧代码 async for 无限阻塞。
            #
            # try/finally 包裹 while 循环：客户端中途断开时 SSE 层触发 aclose → yield 抛
            # GeneratorExit（BaseException，不被 except Exception 捕获），finally 确保
            # stream_resp.aclose() 释放底层 DashScope HTTP 连接，防止连接池渐耗尽。
            try:
                while True:
                    try:
                        chunk = await asyncio.wait_for(
                            stream_resp.__anext__(),
                            timeout=_LLM_CHUNK_IDLE_TIMEOUT,
                        )
                    except StopAsyncIteration:
                        break
                    except asyncio.TimeoutError as e:
                        logger.error(f"[timing] llm_chunk_idle_timeout: "
                                     f"{_LLM_CHUNK_IDLE_TIMEOUT}s{err_log_suffix}: {e}{fmt_req_id(_req_id)}")
                        log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_chunk_idle_timeout",
                                      _req_id, level=_ERROR_LEVEL_KEY, timeout=f"{_LLM_CHUNK_IDLE_TIMEOUT}s")
                        err_handled_setter()
                        yield json.dumps({_ERROR_KEY: "大模型生成超时，请重试",
                                          _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
                        return
                    if not chunk.choices:
                        continue
                    delta = chunk.choices[0].delta

                    if first_token and (hasattr(delta, "content") and delta.content):
                        t5 = time.time()
                        logger.info(f"[timing] llm_first_token: {t5 - t4:.3f}s (TTFT)")
                        first_token = False

                    if deep_think and hasattr(delta, "reasoning_content") and delta.reasoning_content is not None:
                        yield json.dumps({
                            "reasoning": delta.reasoning_content,
                            "type": "reasoning",
                            _MESSAGE_ID_KEY: message_id
                        }, ensure_ascii=False)

                    if hasattr(delta, "content") and delta.content:
                        full_response += delta.content
                        yield json.dumps({
                            "answer": delta.content,
                            "type": "answer",
                            _MESSAGE_ID_KEY: message_id
                        }, ensure_ascii=False)
            finally:
                # 统一关闭流释放底层连接：正常结束 / chunk idle 超时 return / 客户端断开
                # GeneratorExit / 外层异常，均经此释放 DashScope HTTP 连接
                try:
                    await stream_resp.aclose()
                except Exception as _close_e:
                    logger.debug(f"[llm] stream aclose on exit failed: {_close_e}")

        t6 = time.time()
        logger.info(f"[timing] llm_stream_total: {t6 - t4:.3f}s")

        extract_and_store_async(user_id, query, full_response, history,
                                conversation_id=conversation_id or "")

        _resp_lower = full_response.lower()
        if do_unanswerable_check and any(m in _resp_lower for m in _UNANSWERABLE_MARKERS):
            kb_stats["no_hit_reason"] = "unanswerable"
            kb_stats["kb_hit"] = False
            yield json.dumps({"docs": [], "kb_stats": kb_stats, _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
        else:
            yield json.dumps({"docs": source_documents, "kb_stats": kb_stats, _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)  # pylint: disable=line-too-long
    except Exception as e:
        _req_id = extract_aliyun_request_id(e) or _req_id
        logger.error(f"multi_knowledge_base_chat stream interrupted{err_log_suffix}: {e}{fmt_req_id(_req_id)}")
        log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_stream_error",
                      _req_id, level=_ERROR_LEVEL_KEY, reason=str(e)[:200])
        err_handled_setter()
        err_msg = "生成内容被内容安全系统拦截，请调整问题后重试" if "inappropriate content" in str(e).lower() else str(e)
        yield json.dumps({_ERROR_KEY: err_msg, _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
        return


async def _non_stream_llm_response(
    *,
    openai_client,
    cfg,
    model_name,
    openai_messages,
    temperature,
    max_tokens,
    deep_think,
    message_id,
    source_documents,
    do_unanswerable_check,
    kb_stats,
    mode_label,
    err_log_suffix,
    err_handled_setter,
    log_response_summary,
    extract_before_yield,
    user_id,
    query,
    history,
    conversation_id,
):
    """共享的 LLM 非流式生成逻辑（闲聊/RAG 分支共用）。

    差异点通过参数注入：同 _stream_llm_response，额外：
    - log_response_summary: 是否输出 [pipeline] 响应 日志（仅 RAG non-stream）
    - extract_before_yield: 记忆抽取在 yield 前还是后（闲聊前，RAG 后）
    """
    _MODEL_ID_KEY = 'model_id'
    _ERROR_KEY = 'error'
    _MESSAGE_ID_KEY = 'message_id'
    _ERROR_LEVEL_KEY = 'error'
    _req_id = None  # DashScope requestID，非流式 create 成功后填充（超时/异常排查限流）
    try:
        extra_body = {"enable_thinking": True} if deep_think else {}
        if deep_think:
            logger.info(f"===== 深度思考模式已开启{mode_label}=====")
        else:
            logger.info(f"===== 普通模式{mode_label}=====")

        t4 = time.time()
        # P0-3：全局并发信号量（同流式）。非流式 create 返回即释放 DashScope 连接，
        # 故信号量仅包 create 调用，release 后再处理响应/记忆抽取。
        # t4 在 async with 之前，non_stream 总时(t6-t4) 含 acquire 等待——用户感知口径；
        # acquire_wait 日志分离排队分量。
        async with _LLM_CONCURRENCY:
            _sem_wait = time.time() - t4
            if _sem_wait > 0.05:
                logger.info(f"[timing] llm_sem_acquire_wait: {_sem_wait:.3f}s{err_log_suffix}")
            # P0-2：非流式整体超时保护。一次性 await，DashScope 完整生成 hang 时
            # _LLM_NON_STREAM_TIMEOUT 到点 cancel 并降级。非流式需等完整生成，阈值较流式宽松。
            # 用 with_raw_response 拿到 httpx 响应头提取 x-request-id（openai 1.14.3
            # 普通 create 返回的 ChatCompletion 已被 pydantic 剥离 raw headers）。
            try:
                raw_resp = await asyncio.wait_for(
                    openai_client.chat.completions.with_raw_response.create(
                        model=cfg.get(_MODEL_ID_KEY, model_name),
                        messages=openai_messages,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        extra_body=extra_body,
                    ),
                    timeout=_LLM_NON_STREAM_TIMEOUT,
                )
            except asyncio.TimeoutError as e:
                logger.error(f"[timing] llm_non_stream_timeout: "
                             f"{_LLM_NON_STREAM_TIMEOUT}s{err_log_suffix}: {e}")
                log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_non_stream_timeout",
                              None, level=_ERROR_LEVEL_KEY, reason="create阶段超时未返回")
                err_handled_setter()
                yield json.dumps({_ERROR_KEY: "大模型响应超时，请重试",
                                  _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
                return
        # 非流式 create 成功返回即提取 req_id（异常对象优先，回退 create 响应）
        _req_id = extract_aliyun_request_id(raw_resp)
        log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_non_stream_done",
                      _req_id, level="debug")
        response = raw_resp.parse()
        t6 = time.time()
        logger.info(f"[timing] llm_non_stream: {t6 - t4:.3f}s")

        reasoning_content = ""
        if deep_think and hasattr(response.choices[0].message, "reasoning_content"):
            reasoning_content = response.choices[0].message.reasoning_content or ""

        full_response = response.choices[0].message.content or ""

        _resp_lower = full_response.lower()
        if do_unanswerable_check and any(m in _resp_lower for m in _UNANSWERABLE_MARKERS):
            kb_stats["no_hit_reason"] = "unanswerable"
            kb_stats["kb_hit"] = False

        if extract_before_yield:
            extract_and_store_async(user_id, query, full_response, history,
                                    conversation_id=conversation_id or "")

        if reasoning_content:
            yield json.dumps({
                "answer": full_response,
                "reasoning": reasoning_content,
                "docs": source_documents,
                "kb_stats": kb_stats,
                _MESSAGE_ID_KEY: message_id
            }, ensure_ascii=False)
        else:
            yield json.dumps({
                "answer": full_response,
                "docs": source_documents,
                "kb_stats": kb_stats,
                _MESSAGE_ID_KEY: message_id
            }, ensure_ascii=False)

        if log_response_summary:
            logger.info(
                f"[pipeline] 响应 | answer_len={len(full_response) if full_response else 0} | "
                f"docs_count={len(source_documents)} | "
                f"answer_preview={full_response[:100] if full_response else ''}{'...' if full_response and len(full_response) > 100 else ''}"  # pylint: disable=line-too-long
            )

        if not extract_before_yield:
            extract_and_store_async(user_id, query, full_response, history,
                                    conversation_id=conversation_id or "")
    except Exception as e:
        _req_id = extract_aliyun_request_id(e) or _req_id
        logger.error(f"multi_knowledge_base_chat stream interrupted{err_log_suffix}: {e}{fmt_req_id(_req_id)}")
        log_dashscope(cfg.get(_MODEL_ID_KEY, model_name), "llm_non_stream_error",
                      _req_id, level=_ERROR_LEVEL_KEY, reason=str(e)[:200])
        err_handled_setter()
        err_msg = "生成内容被内容安全系统拦截，请调整问题后重试" if "inappropriate content" in str(e).lower() else str(e)
        yield json.dumps({_ERROR_KEY: err_msg, _MESSAGE_ID_KEY: message_id}, ensure_ascii=False)
        return


class MultiKbChatRequest(BaseModel):
    query: str = Field(..., description="用户输入的问题", examples=["Hi3798MV310 和 Hi3516CV610 的 HDMI 配置差异？"])  # pylint: disable=line-too-long
    knowledge_base_names: list[str] = Field(..., description="知识库名称列表，支持多个库（对应 Milvus collection）", examples=[["hi3798mv310", "hi3516cv610"]])  # pylint: disable=line-too-long
    top_k: int = Field(RERANKER_TOP_N, description="最终返回给 LLM 的文档数量（Reranker 精排后），召回数由 RECALL_TOP_K 控制")  # pylint: disable=line-too-long
    score_threshold: float = Field(
            SCORE_THRESHOLD,
            description="知识库匹配严格度，0-1 之间，0 全要，1 最严格，建议 0.5 左右",
            ge=0,
            le=1
    )
    conversation_id: str = Field("", description="对话 ID，用于关联会话历史（为空时新建会话）")
    user_id: str = Field(..., description="用户 ID（必填，UUID 格式，用于多租户隔离）")
    history: list[History] = Field(
            [],
            description="历史对话（由前端传入）",
            examples=[[
                {"role": "user",
                 "content": "Hi3798MV310 支持哪些视频输出接口？"},
                {"role": "assistant",
                 "content": "Hi3798MV310 支持 HDMI 2.0、CVbs、YPbPr 等视频输出接口，其中 HDMI 2.0 可支持 4K@60Hz 输出。"}]]  # pylint: disable=line-too-long
    )
    stream: bool = Field(False, description="是否流式输出（SSE）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")  # pylint: disable=line-too-long
    temperature: float = Field(KB_CHAT_TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)  # pylint: disable=line-too-long
    max_tokens: int | None = Field(
            None,
            description="限制 LLM 生成的 Token 数量，None 表示使用模型默认最大值"
    )
    prompt_name: str = Field(
            "default",
            description="使用的 prompt 模板名称（在 configs/prompt_templates.yaml 中配置）"
    )
    enable_internet_search: bool | None = Field(
            None,
            description="是否启用联网搜索：None=自动（知识库未命中时触发），True=强制启用（总是搜索并融合结果），False=强制禁用"
    )
    deep_think: bool = Field(False, description="是否开启深度思考模式")
    tags: list[str] | None = Field(
            None,
            description="文件标签过滤（OR 语义，Milvus 路生效，为空则不过滤）"
    )
    version: str | None = Field(
            None,
            description="指定版本标签检索（覆盖默认当前版本过滤）"
    )
    language: str = Field(
            "auto",
            description="回答语言：auto=自动检测，zh=强制中文，en=强制英文"
    )


async def multi_knowledge_base_chat(request: MultiKbChatRequest):
    query = request.query
    knowledge_base_names = request.knowledge_base_names
    top_k = request.top_k
    score_threshold = request.score_threshold
    conversation_id = request.conversation_id
    user_id = request.user_id
    history = request.history
    stream = request.stream
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens
    prompt_name = request.prompt_name
    enable_internet_search = request.enable_internet_search
    deep_think = request.deep_think
    tags = request.tags
    version = request.version
    language = request.language
    # 入口日志：记录请求关键参数
    logger.info(
        f"[pipeline] 请求 | query={query[:80]}{'...' if len(query) > 80 else ''} | "
        f"kbs={knowledge_base_names} | top_k={top_k} | conv_id={conversation_id[:20] if conversation_id else 'None'} | "
        f"history_len={len(history)} | stream={stream} | model={model_name}"
    )
    # 入口：校验知识库，过滤掉不存在的（部分不存在则跳过并记 warning，全部不存在返回 404）
    # 并发校验，避免 N 个库串行叠加延迟
    def _check_one_kb(kb_name: str) -> str | None:
        if KBServiceFactory.get_service_by_name(kb_name) is None:
            logger.warning(f"[pipeline] 知识库 '{kb_name}' 不存在，已跳过")
            return None
        return kb_name

    check_results = await asyncio.gather(
        *[asyncio.to_thread(_check_one_kb, name) for name in knowledge_base_names]
    )
    valid_kbs: list[str] = [name for name in check_results if name is not None]
    if not valid_kbs:
        msg = f"未找到任何有效知识库：{knowledge_base_names}"
        logger.info(msg)
        return BaseResponse(code=HTTP_NOT_FOUND, msg=msg)

    # 入口：生成 message_id（已禁用数据库持久化）
    message_id = ""


    logger.info(f"model_name:{model_name}")

    # 入口：适配后端传入的 history（assistant.content 是 SSE chunk 对象，取 answer 文本）
    history: list[History] = history_from_backend(history)

    # version 空串/纯空白归一化为 None：路由 _detect_version_filter 兜底用
    # is None 判断（见下方 if ctx.version is None），空串会绕过兜底 → 检索
    # 走 file_version_label == "" 精确匹配 → 误过滤掉所有有 label 的文档
    if version is not None:
        version = version.strip() or None

    # 语言检测：auto 自动检测 zh/en，zh/en 强制覆盖；kill-switch 关闭时强制 zh
    # to_thread 避免 langdetect 同步 CPU 推理阻塞 async 事件循环（英文 cache miss 时）
    _ctx_language = await asyncio.to_thread(resolve_language, query, language)
    logger.info(f"[lang] query_lang={_ctx_language} | query={query[:60]}")

    ctx = ChatContext(
        query=query,
        history=history,
        valid_kbs=valid_kbs,
        top_k=top_k,
        score_threshold=score_threshold,
        model_name=model_name,
        prompt_name=prompt_name,
        deep_think=deep_think,
        stream=stream,
        temperature=temperature,
        max_tokens=max_tokens,
        message_id=message_id,
        user_id=user_id,
        conversation_id=conversation_id,
        tags=tags,
        version=version,
        enable_internet_search=enable_internet_search,
        language=_ctx_language,
        openai_client=_main_openai_client,
        config=_main_model_cfg,
        router_client=_router_openai_client,
        router_model_id=_router_model_cfg.get("model_id", _ROUTER_MODEL_NAME),
        kb_stats={
            "rag_triggered": True,
            "kb_hit": False,
            "no_hit_reason": "",
            "retrieved_count": 0,
            "context_chunk_count": 0,
            "used_count": 0,
            "hit_kbs": [],
            "rewritten_query": "",
            "top_score": 0.0,
            "route_reason": "",
            "degraded": False,
        },
    )

    async def multi_knowledge_base_chat_iterator(
            ctx: ChatContext,
    ) -> AsyncIterable[str]:
        if ctx.max_tokens is not None and ctx.max_tokens <= 0:
            ctx.max_tokens = None

        # Phase 1: 路由（规则闸门 + 记忆任务 + 版本对比检测 + LLM 路由）
        async for event in _phase_route(ctx):
            yield event

        # Phase 2-3: 检索 + 组装（仅 need_rag=True 时执行；闲聊分支 need_rag=False 跳过，
        #   verdiff 分支 need_rag=True 仍执行但 _phase_retrieve 内走 verdiff 快路径）
        if ctx.need_rag:
            async for event in _phase_retrieve(ctx):
                yield event
            # _phase_assemble 以 async generator 形式产出联网搜索状态提示事件，
            # 用 async for 遍历透传 SSE 事件，其余结果写入 ctx
            async for event in _phase_assemble(ctx):
                yield event

        # Phase 4: 生成（_finish_mem 收口 + chitchat/RAG 分支 LLM 生成）
        async for event in _phase_generate(ctx):
            yield event

    async def _safe_iterator():
        # 外层兜底：捕获 iterator 在 LLM 调用前（模型配置/检索/reranker/prompt 构造）抛出的异常，
        # 更新 message_id 对应的 DB 记录，避免遗留 response=None 的脏记录（已禁用数据库持久化）
        try:
            async for chunk in multi_knowledge_base_chat_iterator(ctx):
                yield chunk
        except Exception as e:
            if not ctx.err_handled:
                logger.error(f"multi_knowledge_base_chat iterator uncaught: {e}")
                yield json.dumps({"error": str(e), "message_id": message_id}, ensure_ascii=False)
            # 不再 raise：错误已通过 SSE 事件发给前端，再 raise 会导致 ASGI 崩溃
            return
        finally:
            # B2：生成器异常退出/客户端断开（GeneratorExit 不被 except Exception 捕获）
            # 时取消未完成的 mem_task，避免孤儿记忆检索 task 持续占用连接/协程
            if ctx.mem_task is not None and not ctx.mem_task.done():
                ctx.mem_task.cancel()
                try:
                    await asyncio.gather(ctx.mem_task, return_exceptions=True)
                except Exception as e:
                    logger.warning(f"[multi-kb-chat] failed to cancel memory task: {e}")

    return EventSourceResponse(_safe_iterator())
