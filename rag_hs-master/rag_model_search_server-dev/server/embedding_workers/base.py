"""
HTTP Embeddings 基类

提供基于 HTTP API 的向量化功能，继承自 langchain.embeddings.base.Embeddings
"""
import asyncio

import httpx
import requests
from langchain.embeddings.base import Embeddings

from configs import logger


class HttpEmbeddings(Embeddings):
    """
    基于 HTTP API 的 Embeddings 实现

    通过调用远程 HTTP 服务实现文本向量化功能
    """

    def __init__(
        self,
        api_base_url: str,
        embedding_dim: int = 1024,
        timeout: int = 60,
        max_workers: int = 4,
        batch_size: int = 32,
        **kwargs
    ):
        """
        初始化 HttpEmbeddings

        Args:
            api_base_url: Embedding API 的基础 URL
            embedding_dim: 向量维度，默认 1024
            timeout: 请求超时时间（秒），默认 60
            max_workers: 异步并发最大工作数，默认 4
            batch_size: 异步分片大小，默认 32
            **kwargs: 其他参数
        """
        self.api_base_url = api_base_url.rstrip('/')
        self.embedding_dim = embedding_dim
        self.timeout = timeout
        self.max_workers = max_workers
        self.batch_size = batch_size
        self._session = requests.Session()
        # 懒初始化共享 AsyncClient（复用连接池，避免每请求 TLS 握手）
        self._aclient: httpx.AsyncClient | None = None
        super().__init__(**kwargs)

    def __repr__(self) -> str:
        return f"HttpEmbeddings(api_base_url='{self.api_base_url}', embedding_dim={self.embedding_dim}, timeout={self.timeout}, max_workers={self.max_workers}, batch_size={self.batch_size})"  # pylint: disable=line-too-long

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        """
        批量文本向量化

        Args:
            texts: 待向量化的文本列表

        Returns:
            向量列表，每个向量是一个浮点数列表

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        if not texts:
            logger.warning("embed_documents: 传入的文本列表为空")
            return []

        try:
            url = f"{self.api_base_url}/embed"
            payload = {"texts": texts}

            logger.debug(f"embed_documents: 请求 {url}，文本数量: {len(texts)}")

            response = self._session.post(
                url,
                json=payload,
                timeout=self.timeout
            )
            response.raise_for_status()

            result = response.json()
            embeddings = result.get("embeddings", [])

            if not embeddings:
                logger.warning(f"embed_documents: API 返回空向量，响应: {result}")
                return []

            logger.info(f"embed_documents: 成功获取 {len(embeddings)} 个向量，维度: {result.get('embedding_dim', self.embedding_dim)}")  # pylint: disable=line-too-long

            return embeddings

        except requests.exceptions.Timeout as e:
            error_msg = f"embed_documents: 请求超时，超时时间: {self.timeout}s"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.ConnectionError as e:
            error_msg = f"embed_documents: 连接失败，URL: {self.api_base_url}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.HTTPError as e:
            error_msg = f"embed_documents: HTTP 错误，状态码: {response.status_code}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except Exception as e:
            error_msg = f"embed_documents: 未知错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

    def embed_query(self, text: str) -> list[float]:
        """
        单个查询向量化

        Args:
            text: 待向量化的查询文本

        Returns:
            向量，一个浮点数列表

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        if not text:
            logger.warning("embed_query: 传入的文本为空")
            return []

        try:
            url = f"{self.api_base_url}/embed"
            payload = {
                "texts": [text],
                "prompt_name": "query"
            }

            logger.debug(f"embed_query: 请求 {url}，文本长度: {len(text)}")

            response = self._session.post(
                url,
                json=payload,
                timeout=self.timeout
            )
            response.raise_for_status()

            result = response.json()
            embeddings = result.get("embeddings", [])

            if not embeddings or len(embeddings) == 0:
                logger.warning(f"embed_query: API 返回空向量，响应: {result}")
                return []

            embedding = embeddings[0]
            logger.debug(f"embed_query: 成功获取向量，维度: {len(embedding)}")

            return embedding

        except requests.exceptions.Timeout as e:
            error_msg = f"embed_query: 请求超时，超时时间: {self.timeout}s"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.ConnectionError as e:
            error_msg = f"embed_query: 连接失败，URL: {self.api_base_url}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except requests.exceptions.HTTPError as e:
            error_msg = f"embed_query: HTTP 错误，状态码: {response.status_code}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except Exception as e:
            error_msg = f"embed_query: 未知错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

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

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        """
        异步批量文本向量化

        Args:
            texts: 待向量化的文本列表

        Returns:
            向量列表

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        return await self._aembed_batch(texts)

    async def aembed_query(self, text: str) -> list[float]:
        """
        异步单个查询向量化

        Args:
            text: 待向量化的查询文本

        Returns:
            向量，一个浮点数列表

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        if not text:
            logger.warning("aembed_query: 传入的文本为空")
            return []

        client = await self._get_aclient()
        embeddings = await self._aembed_request(client, [text], prompt_name="query")

        if not embeddings:
            return []

        return embeddings[0]

    async def _get_aclient(self) -> httpx.AsyncClient:
        """
        懒初始化共享 httpx.AsyncClient（复用连接池，避免每请求 TLS 握手）

        显式约束连接池：默认连接池上限过高，并发突发下若部分连接陷入半死
        状态会持续占用池位，导致后续请求永久阻塞在连接 acquire 信号量上
        （该阻塞不受请求 timeout 约束）。这里把池收窄 + keepalive_expiry=30
        （短于多数防火墙/NAT 的空闲断开阈值，避免复用被静默断开的老连接），
        配合 _aembed_request 的 wait_for 兜底，避免池被打坏后无法恢复。
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

    async def _aembed_request(
        self,
        client: httpx.AsyncClient,
        texts: list[str],
        prompt_name: str | None = None
    ) -> list[list[float]]:
        """
        单次异步 Embedding 请求

        Args:
            client: 共享的 httpx.AsyncClient
            texts: 待向量化的文本列表
            prompt_name: 可选 prompt 名称

        Returns:
            向量列表

        Raises:
            ValueError: 当 API 请求失败时抛出
        """
        if not texts:
            return []

        try:
            url = f"{self.api_base_url}/embed"
            payload = {"texts": texts}
            if prompt_name:
                payload["prompt_name"] = prompt_name

            logger.debug(f"_aembed_request: 请求 {url}，文本数量: {len(texts)}")

            # wait_for 兜底：httpx 内置 timeout 不约束连接池 acquire，
            # 池满或半死连接占位时可能永久阻塞；wait_for 超时后取消协程，
            # 连带释放被占用的连接位，使连接池可在超时窗口内自愈。
            response = await asyncio.wait_for(
                client.post(url, json=payload),
                timeout=self.timeout,
            )
            response.raise_for_status()

            result = response.json()
            embeddings = result.get("embeddings", [])

            if not embeddings:
                logger.warning(f"_aembed_request: API 返回空向量，响应: {result}")
                return []

            # A5：校验返回向量数量与输入文本数量一致，防止静默错位
            if len(embeddings) != len(texts):
                raise ValueError(
                    f"embedding 数量不匹配: 请求 {len(texts)} 条, 返回 {len(embeddings)} 条"
                )

            logger.info(f"_aembed_request: 成功获取 {len(embeddings)} 个向量，维度: {result.get('embedding_dim', self.embedding_dim)}")  # pylint: disable=line-too-long

            return embeddings

        except asyncio.TimeoutError as e:
            error_msg = f"_aembed_request: 请求超时（wait_for），超时时间: {self.timeout}s"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except httpx.TimeoutException as e:
            error_msg = f"_aembed_request: 请求超时，超时时间: {self.timeout}s"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except httpx.ConnectError as e:
            error_msg = f"_aembed_request: 连接失败，URL: {self.api_base_url}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except httpx.HTTPStatusError as e:
            error_msg = f"_aembed_request: HTTP 错误，状态码: {response.status_code}，错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

        except Exception as e:
            error_msg = f"_aembed_request: 未知错误: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

    async def _aembed_batch(
        self,
        texts: list[str],
        prompt_name: str | None = None
    ) -> list[list[float]]:
        """
        分片并发批量向量化

        按 batch_size 切片，使用 max_workers 信号量限制并发，
        共享一个 httpx.AsyncClient，按顺序合并结果。

        Args:
            texts: 待向量化的文本列表
            prompt_name: 可选 prompt 名称

        Returns:
            向量列表

        Raises:
            ValueError: 当任一分片请求失败时抛出
        """
        if not texts:
            return []

        chunks = [texts[i:i + self.batch_size] for i in range(0, len(texts), self.batch_size)]
        semaphore = asyncio.Semaphore(self.max_workers)

        client = await self._get_aclient()

        async def _bounded(chunk):
            async with semaphore:
                return await self._aembed_request(client, chunk, prompt_name)

        results = await asyncio.gather(
            *[_bounded(c) for c in chunks],
            return_exceptions=True
        )

        for r in results:
            if isinstance(r, Exception):
                logger.error(f"_aembed_batch: 分片失败: {r}")
                raise r

        all_embeddings = []
        for r in results:
            all_embeddings.extend(r)

        logger.info(f"_aembed_batch: 成功获取 {len(all_embeddings)} 个向量")

        return all_embeddings
