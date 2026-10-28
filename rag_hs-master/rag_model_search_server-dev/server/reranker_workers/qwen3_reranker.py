"""
Qwen3 Reranker Worker

基于阿里云 DashScope 的 qwen3.7-text-rerank 模型文档重排实现，继承自 HttpReranker。
通过 dashscope SDK 的 TextReRank.call 接口实现批量文档重排，适用于通用文本检索场景。

配置示例（model_config.yaml）::

    RERANKER_API_CONFIG:
      qwen3.7-text-rerank:
        api_key: "sk-xxx"
        api_base_url: "https://llm-fyh907cucteno2xj.cn-beijing.maas.aliyuncs.com/compatible-api/v1"
        model_id: "qwen3.7-text-rerank"
        instruct: "Given a web search query, retrieve relevant passages that answer the query."
        return_documents: true
        timeout: 60
        top_n: 3
        max_workers: 8
        provider: "阿里云"
        description: "阿里云 qwen3.7-text-rerank 模型，DashScope SDK 调用"
"""
import asyncio
from collections.abc import Sequence
from http import HTTPStatus
from typing import ClassVar

from langchain_core.documents import Document

from configs import logger
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope

from .base import HttpReranker


class Qwen3Reranker(HttpReranker):
    """
    Qwen3 Reranker 实现类 / Qwen3 Reranker Implementation

    通过阿里云 DashScope SDK 调用 qwen3-rerank 模型的 TextReRank 接口实现文档重排。
    继承自 HttpReranker 以复用 BaseDocumentCompressor 接口与注册机制，
    但实际请求通过 dashscope.TextReRank.call 完成，而非基类的 /score HTTP 端点。

    Inherits from HttpReranker for the BaseDocumentCompressor interface and registry.
    Actual requests are made via dashscope.TextReRank.call instead of the base /score endpoint.

    Attributes:
        MODEL_NAME: 模型名称标识符 / Model name identifier
    """

    # 模型名称 / Model Name
    MODEL_NAME: ClassVar[str] = "qwen3.7-text-rerank"

    def __init__(
        self,
        api_base_url: str,
        api_key: str = "",
        model_id: str = "qwen3-rerank",
        instruct: str = "",
        return_documents: bool = True,
        timeout: int = 60,
        top_n: int = 3,
        max_workers: int = 8,
        default_task_type: str = "TEXT_TO_CODE",
        **kwargs
    ):
        """
        初始化 Qwen3Reranker / Initialize Qwen3Reranker

        Args:
            api_base_url: DashScope API 基础 URL（到 /compatible-api/v1 结尾），
                          同时用于日志展示 / DashScope API base URL, also used for logging
            api_key: DashScope API 密钥 / DashScope API key
            model_id: 模型 ID，如 qwen3-rerank / Model ID, e.g. qwen3-rerank
            instruct: 重排指令文本，用于引导模型理解查询意图 / Rerank instruct text
            return_documents: 响应是否携带 document 字段，默认 True
                              Whether response includes document field, default True
            timeout: 请求超时时间（秒），默认 60 / Request timeout (seconds), default 60
            top_n: 返回的文档数量，默认 3 / Number of documents to return, default 3
            max_workers: 保留参数（DashScope 为单次批量请求，不并发），默认 8
                         Reserved (DashScope is a single batch request, no concurrency)
            default_task_type: 保留参数（基类兼容），不参与 DashScope 调用
                               Reserved for base-class compatibility, not used by DashScope
            **kwargs: 其他参数 / Other parameters
        """
        super().__init__(
            api_base_url=api_base_url,
            default_task_type=default_task_type,
            timeout=timeout,
            top_n=top_n,
            max_workers=max_workers,
            **kwargs
        )
        self.api_key = api_key
        self.model_id = model_id
        self.instruct = instruct
        self.return_documents = return_documents

        # 配置 dashscope SDK 全局参数 / Configure dashscope SDK globals
        # 懒加载：仅在使用时 import，避免模块加载时强制依赖 / Lazy import to avoid hard dependency
        import dashscope
        if api_key:
            dashscope.api_key = api_key
        # 不覆盖 SDK 默认端点：配置里的 api_base_url 是 compatible-api/v1（OpenAI 兼容），
        # 但 rerank 走原生 api/v1 端点，用 compatible-api 会 404

        logger.info(
            f"Qwen3Reranker 初始化完成，模型: {self.model_id}，"
            f"端点: {api_base_url} / "
            f"Qwen3Reranker initialized, model: {self.model_id}"
        )

    def __repr__(self) -> str:
        return (
            f"Qwen3Reranker(api_base_url='{self.api_base_url}', "
            f"model_id='{self.model_id}', timeout={self.timeout}, "
            f"top_n={self.top_n}, return_documents={self.return_documents})"
        )

    def compress_documents(
        self,
        documents: Sequence[Document],
        query: str,
        callbacks=None,
    ) -> Sequence[Document]:
        """
        批量文档重排 / Batch document reranking

        通过 dashscope.TextReRank.call 一次性提交所有文档进行批量重排，
        按返回的 relevance_score 降序排列，最多返回 top_n 个文档。

        Submits all documents to dashscope.TextReRank.call in one batch,
        sorts by returned relevance_score descending, returns up to top_n documents.

        Args:
            documents: 待重排的文档序列 / Sequence of documents to rerank
            query: 查询文本 / Query text
            callbacks: 回调函数（暂未使用） / Callbacks (unused)

        Returns:
            重排后的文档序列，按相关性分数降序排列 / Reranked documents sorted by score desc

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not documents:
            logger.warning("compress_documents: 文档列表为空 / Document list is empty")
            return []

        if not query:
            logger.warning("compress_documents: 查询文本为空 / Query text is empty")
            return []

        import dashscope

        docs_list = list(documents)
        doc_texts = [d.page_content for d in docs_list]

        try:
            logger.info(
                f"compress_documents: 请求模型 {self.model_id}，"
                f"文档数量: {len(doc_texts)} / "
                f"Request model {self.model_id}, doc count: {len(doc_texts)}"
            )
            resp = dashscope.TextReRank.call(
                model=self.model_id,
                query=query,
                documents=doc_texts,
                top_n=self.top_n,
                return_documents=self.return_documents,
                instruct=self.instruct or None,
            )
            # dashscope SDK 响应自带 request_id（响应体字段），提取便于限流排查
            _req_id = extract_aliyun_request_id(resp)
            log_dashscope(self.model_id, "rerank_call_done", _req_id, level="debug",
                          docs=len(doc_texts))
        except Exception as e:
            error_msg = f"compress_documents: 请求异常: {e} / Request exception: {e}"
            logger.error(error_msg)
            log_dashscope(self.model_id, "rerank_call_error",
                          extract_aliyun_request_id(e), level="error", reason=str(e)[:200])
            raise ValueError(error_msg) from e

        if resp.status_code != HTTPStatus.OK:
            error_msg = (
                f"compress_documents: DashScope 返回非 200，"
                f"status_code={resp.status_code}，code={getattr(resp, 'code', '')}，"
                f"message={getattr(resp, 'message', '')}{fmt_req_id(_req_id)} / "
                f"DashScope non-200 response"
            )
            logger.error(error_msg)
            log_dashscope(self.model_id, "rerank_non_200", _req_id, level="error",
                          status=resp.status_code)
            raise ValueError(error_msg)

        results = getattr(resp, "output", {}).get("results", []) if hasattr(resp, "output") else []
        if not results:
            logger.warning("compress_documents: DashScope 返回空结果 / DashScope returned empty results")
            return []

        final_results: list[Document] = []
        for item in results:
            idx = item.get("index")
            score = item.get("relevance_score", 0.0)
            if idx is None or idx < 0 or idx >= len(docs_list):
                logger.warning(f"compress_documents: 跳过越界 index={idx} / Skip out-of-range index")
                continue
            doc = docs_list[idx]
            scored_doc = Document(
                page_content=doc.page_content,
                metadata=doc.metadata.copy()
            )
            scored_doc.metadata["relevance_score"] = score
            final_results.append(scored_doc)
            logger.debug(f"compress_documents: 选中文档 index={idx}，分数: {score:.4f}")

        logger.info(f"compress_documents: 完成，返回 {len(final_results)} 个文档{fmt_req_id(_req_id)} / Done, returned {len(final_results)} docs")  # pylint: disable=line-too-long
        return final_results

    async def acompress_documents(
        self,
        documents: Sequence[Document],
        query: str,
        callbacks=None,
    ) -> Sequence[Document]:
        """
        异步批量文档重排 / Async batch document reranking

        DashScope SDK 本身是同步调用，且 TextReRank.call 已一次性批量提交所有文档，
        这里用 asyncio.to_thread 把同步批量调用丢到线程池，避免阻塞事件循环。
        """
        if not documents:
            return []
        if not query:
            return []
        return await asyncio.to_thread(self.compress_documents, documents, query, callbacks)

    def score(
        self,
        query: str,
        document: str,
        task_type: str | None = None
    ) -> float:
        """
        计算单个查询-文档相关性分数 / Calculate single query-document relevance score

        通过 DashScope TextReRank 接口对单个文档重排并返回其 relevance_score。
        task_type 参数在本实现中不生效（DashScope 无该概念），仅为兼容基类签名保留。

        Args:
            query: 查询文本 / Query text
            document: 文档内容 / Document content
            task_type: 任务类型（不生效） / Task type (no effect)

        Returns:
            相关性分数 / Relevance score

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not query or not document:
            logger.warning("score: 查询或文档为空 / Query or document is empty")
            return 0.0

        import dashscope

        try:
            resp = dashscope.TextReRank.call(
                model=self.model_id,
                query=query,
                documents=[document],
                top_n=1,
                return_documents=False,
                instruct=self.instruct or None,
            )
            _req_id = extract_aliyun_request_id(resp)
            log_dashscope(self.model_id, "rerank_score_done", _req_id, level="debug")
        except Exception as e:
            error_msg = f"score: 请求异常: {e} / Request exception: {e}"
            logger.error(error_msg)
            log_dashscope(self.model_id, "rerank_score_error",
                          extract_aliyun_request_id(e), level="error", reason=str(e)[:200])
            raise ValueError(error_msg) from e

        if resp.status_code != HTTPStatus.OK:
            error_msg = f"score: DashScope 返回非 200，status_code={resp.status_code}{fmt_req_id(_req_id)}"
            logger.error(error_msg)
            log_dashscope(self.model_id, "rerank_score_non_200", _req_id, level="error",
                          status=resp.status_code)
            raise ValueError(error_msg)

        results = getattr(resp, "output", {}).get("results", []) if hasattr(resp, "output") else []
        if not results:
            return 0.0
        return results[0].get("relevance_score", 0.0)

    def health_check(self) -> bool:
        """
        健康检查 / Health check

        DashScope 无独立 /health 端点，这里通过一次最小重排请求探活。

        Returns:
            bool: 服务可用返回 True，否则返回 False
        """
        try:
            import dashscope
            resp = dashscope.TextReRank.call(
                model=self.model_id,
                query="ping",
                documents=["pong"],
                top_n=1,
                return_documents=False,
            )
            _req_id = extract_aliyun_request_id(resp)
            ok = resp.status_code == HTTPStatus.OK
            if ok:
                log_dashscope(self.model_id, "rerank_health_ok", _req_id, level="debug")
                logger.info(f"health_check: DashScope 服务健康，模型: {self.model_id}{fmt_req_id(_req_id)}")
            else:
                log_dashscope(self.model_id, "rerank_health_non_200", _req_id,
                              level="warning", status=resp.status_code)
                logger.warning(f"health_check: DashScope 服务异常，status_code={resp.status_code}{fmt_req_id(_req_id)}")
            return ok
        except Exception as e:
            logger.warning(f"health_check: 未知错误: {e}")
            log_dashscope(self.model_id, "rerank_health_error",
                          extract_aliyun_request_id(e), level="warning", reason=str(e)[:150])
            return False
