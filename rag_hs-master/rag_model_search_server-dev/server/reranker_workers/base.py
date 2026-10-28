"""
HTTP Reranker 基类

提供基于 HTTP API 的文档重排功能，继承自 langchain.retrievers.document_compressors.base.BaseDocumentCompressor
"""
import asyncio
from collections.abc import Sequence
from concurrent.futures import ThreadPoolExecutor, as_completed

import httpx
import requests
from langchain.callbacks.manager import Callbacks
from langchain.retrievers.document_compressors.base import BaseDocumentCompressor
from langchain_core.documents import Document

from configs import logger


class HttpReranker(BaseDocumentCompressor):
    """
    基于 HTTP API 的 Reranker 实现

    通过调用远程 HTTP 服务实现文档重排功能
    """

    class Config:
        # 允许在 __init__ 中用 self.xxx = ... 赋值非字段属性（api_base_url 等）
        # Allow setting non-field instance attributes in __init__
        extra = "allow"

    def __init__(
        self,
        api_base_url: str,
        default_task_type: str = "TEXT_TO_CODE",
        timeout: int = 60,
        top_n: int = 3,
        max_workers: int = 8,
        **kwargs
    ):
        """
        初始化 HttpReranker

        Args:
            api_base_url: Reranker API 的基础 URL
            default_task_type: 默认的任务类型，默认 "TEXT_TO_CODE"
            timeout: 请求超时时间（秒），默认 60
            top_n: 返回的文档数量，默认 3
            max_workers: 并发评分的最大线程数，默认 8
            **kwargs: 其他参数
        """
        # 必须先调用 super().__init__() 初始化 pydantic 的 __fields_set__，
        # 否则下面的 self.xxx = ... 会抛 AttributeError: object has no attribute '__fields_set__'
        # Must call super().__init__() first to init pydantic __fields_set__,
        # otherwise self.xxx = ... below raises AttributeError.
        super().__init__(**kwargs)
        self.api_base_url = api_base_url.rstrip('/')
        self.default_task_type = default_task_type
        self.timeout = timeout
        self.top_n = top_n
        self.max_workers = max_workers
        self._session = requests.Session()
        # C2：懒初始化共享 httpx.AsyncClient，复用连接池避免每文档 TLS 握手
        self._aclient: httpx.AsyncClient | None = None

    def __repr__(self) -> str:
        return (
            f"HttpReranker(api_base_url='{self.api_base_url}', "
            f"default_task_type='{self.default_task_type}', "
            f"timeout={self.timeout}, top_n={self.top_n}, "
            f"max_workers={self.max_workers})"
        )

    def score(
        self,
        query: str,
        document: str,
        task_type: str | None = None
    ) -> float:
        """
        计算单个查询-文档相关性分数

        Args:
            query: 查询文本
            document: 文档内容
            task_type: 任务类型，如果未指定则使用默认值

        Returns:
            相关性分数

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        if not query:
            logger.warning("score: 查询文本为空")
            return 0.0

        if not document:
            logger.warning("score: 文档内容为空")
            return 0.0

        task_type = task_type or self.default_task_type

        try:
            url = f"{self.api_base_url}/score"
            payload = {
                "query": query,
                "document": document,
                "task_type": task_type
            }

            logger.debug(f"score: 请求 {url}，任务类型: {task_type}")

            response = self._session.post(
                url,
                json=payload,
                timeout=self.timeout
            )
            response.raise_for_status()

            result = response.json()
            score_value = result.get("score", 0.0)

            logger.debug(f"score: 成功获取分数 {score_value:.4f}")

            return score_value

        except requests.exceptions.Timeout as e:
            error_msg = f"score: 请求超时，超时时间: {self.timeout}s"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.ConnectionError as e:
            error_msg = f"score: 连接失败，URL: {self.api_base_url}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.HTTPError as e:
            error_msg = f"score: HTTP 错误，状态码: {response.status_code}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except Exception as e:
            error_msg = f"score: 未知错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

    def compress_documents(
        self,
        documents: Sequence[Document],
        query: str,
        callbacks: Callbacks | None = None,
    ) -> Sequence[Document]:
        """
        批量文档重排

        对每个文档单独调用 API 获取相关性分数，
        根据分数排序返回 top_n 个文档

        Args:
            documents: 待重排的文档序列
            query: 查询文本
            callbacks: 回调函数（暂未使用）

        Returns:
            重排后的文档序列，按相关性分数降序排列，最多返回 top_n 个文档
        """
        if not documents:
            logger.warning("compress_documents: 文档列表为空")
            return []

        if not query:
            logger.warning("compress_documents: 查询文本为空")
            return []

        logger.info(f"compress_documents: 开始处理 {len(documents)} 个文档")

        # 并发为每个文档计算分数
        def _score_one(idx_doc):
            idx, doc = idx_doc
            try:
                score_value = self.score(query, doc.page_content)
                return (score_value, idx, doc)
            except Exception as e:
                logger.warning(f"compress_documents: 文档 {idx} 分数计算失败: {e}，跳过该文档")
                return None

        max_workers = min(self.max_workers, len(documents))
        scored_docs: list[tuple] = []

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(_score_one, (idx, doc))
                       for idx, doc in enumerate(documents)]
            for f in as_completed(futures):
                result = f.result()
                if result is not None:
                    scored_docs.append(result)
                    logger.debug(f"compress_documents: 文档 {result[1]+1}/{len(documents)} 分数: {result[0]:.4f}")

        if not scored_docs:
            logger.warning("compress_documents: 所有文档分数计算失败")
            return []

        # 按分数降序排序
        scored_docs.sort(key=lambda x: x[0], reverse=True)

        # 取 top_n 个文档
        top_k = min(self.top_n, len(scored_docs))
        final_results = []

        for i, (score_value, _, doc) in enumerate(scored_docs[:top_k]):
            # 复制文档以避免修改原始文档
            scored_doc = Document(
                page_content=doc.page_content,
                metadata=doc.metadata.copy()
            )
            scored_doc.metadata["relevance_score"] = score_value
            final_results.append(scored_doc)
            logger.debug(f"compress_documents: 选中文档 {i+1}，分数: {score_value:.4f}")

        logger.info(f"compress_documents: 完成，返回 {len(final_results)} 个文档")

        return final_results

    async def ascore(
        self,
        client: httpx.AsyncClient,
        query: str,
        document: str,
        task_type: str | None = None
    ) -> float | None:
        """
        异步计算单个查询-文档相关性分数

        用共享的 httpx.AsyncClient 发起请求，避免每文档新建连接。
        失败时返回 None（与 sync 版 score 失败抛异常被 compress_documents 跳过等价），
        由 acompress_documents 统一过滤，确保全部失败时返回 [] 触发上层 RRF 兜底。
        """
        if not query or not document:
            return None
        task_type = task_type or self.default_task_type
        url = f"{self.api_base_url}/score"
        payload = {"query": query, "document": document, "task_type": task_type}
        try:
            # C2：wait_for 兜底连接池 acquire 阻塞（httpx timeout 不约束池等待），
            # 对齐 P3-1 _aembed_request。池被打坏时快速失败返回 None。
            resp = await asyncio.wait_for(
                client.post(url, json=payload, timeout=self.timeout),
                timeout=self.timeout,
            )
            resp.raise_for_status()
            return float(resp.json().get("score", 0.0))
        except (asyncio.TimeoutError, httpx.TimeoutException):
            logger.warning(f"ascore: 请求超时（含连接池等待）{self.timeout}s，URL: {url}")
            return None
        except httpx.HTTPStatusError as e:
            logger.warning(f"ascore: HTTP 错误 {resp.status_code}，错误: {e}")
            return None
        except Exception as e:
            logger.warning(f"ascore: 未知错误: {e}")
            return None

    async def acompress_documents(
        self,
        documents: Sequence[Document],
        query: str,
        callbacks: Callbacks | None = None,
    ) -> Sequence[Document]:
        """
        异步批量文档重排

        用 httpx.AsyncClient + asyncio.Semaphore(max_workers) 并发为每个文档
        调用 /score，替代 sync 版的 ThreadPoolExecutor + requests。
        coreb 服务暂无 batch 端点，这里用并发单请求逼近批量效果。
        失败文档（ascore 返回 None）跳过，全部失败返回 [] 以触发上层 RRF 兜底。
        """
        if not documents:
            logger.warning("acompress_documents: 文档列表为空")
            return []
        if not query:
            logger.warning("acompress_documents: 查询文本为空")
            return []

        docs_list = list(documents)
        logger.info(f"acompress_documents: 开始处理 {len(docs_list)} 个文档，并发 {self.max_workers}")

        sem = asyncio.Semaphore(max(1, self.max_workers))

        # C2：共享懒初始化 client，复用连接池（对齐 P3-1 HttpEmbeddings）
        client = await self._get_aclient()
        async def _one(idx: int, doc: Document) -> tuple:
            async with sem:
                s = await self.ascore(client, query, doc.page_content)
                return (s, idx, doc)
        results = await asyncio.gather(*[_one(i, d) for i, d in enumerate(docs_list)])

        # 过滤失败文档（ascore 返回 None），与 sync 版行为一致
        scored = [(s, idx, doc) for s, idx, doc in results if s is not None]
        if not scored:
            logger.warning("acompress_documents: 所有文档分数计算失败")
            return []
        scored.sort(key=lambda x: x[0], reverse=True)
        top_k = min(self.top_n, len(scored))
        final_results = []
        for s, _, doc in scored[:top_k]:
            scored_doc = Document(page_content=doc.page_content, metadata=doc.metadata.copy())
            scored_doc.metadata["relevance_score"] = s
            final_results.append(scored_doc)
        logger.info(f"acompress_documents: 完成，返回 {len(final_results)} 个文档")
        return final_results

    def health_check(self) -> bool:
        """
        健康检查

        调用 GET {api_base_url}/health 检查服务是否正常

        Returns:
            bool: 服务健康返回 True，否则返回 False
        """
        try:
            url = f"{self.api_base_url}/health"

            logger.debug(f"health_check: 请求 {url}")

            response = self._session.get(url, timeout=10)

            if response.status_code == 200:
                logger.info(f"health_check: 服务健康，URL: {self.api_base_url}")
                return True
            logger.warning(f"health_check: 服务异常，状态码: {response.status_code}")
            return False

        except requests.exceptions.Timeout:
            logger.warning(f"health_check: 请求超时，URL: {self.api_base_url}")
            return False

        except requests.exceptions.ConnectionError as e:
            logger.warning(f"health_check: 连接失败，URL: {self.api_base_url}，错误: {e}")
            return False

        except Exception as e:
            logger.warning(f"health_check: 未知错误: {e}")
            return False

    async def _get_aclient(self) -> httpx.AsyncClient:
        """
        懒初始化共享 httpx.AsyncClient（对齐 P3-1 HttpEmbeddings._get_aclient）

        显式约束连接池：默认上限过高，并发突发下部分连接陷入半死状态
        会持续占用池位，导致后续请求永久阻塞在连接 acquire 信号量上
        （该阻塞不受请求 timeout 约束）。池收窄 + keepalive_expiry=30
        （短于多数防火墙/NAT 空闲断开阈值，避免复用被静默断开的老连接）。
        """
        if self._aclient is None:
            self._aclient = httpx.AsyncClient(
                timeout=self.timeout,
                limits=httpx.Limits(
                    max_connections=50,
                    max_keepalive_connections=10,
                    keepalive_expiry=30.0,
                ),
            )
        return self._aclient
