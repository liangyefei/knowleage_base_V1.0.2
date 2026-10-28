"""
阿里云 OpenAI 兼容 Embedding Worker

通过 OpenAI SDK 调用阿里云 DashScope 的 OpenAI 兼容模式 Embedding 接口，
支持 text-embedding-v3 / text-embedding-v4 等模型。

配置示例（model_config.yaml）::

    EMBEDDING_API_CONFIG:
      text-embedding-v3:
        api_key: "sk-xxx"
        api_base_url: "https://llm-fyh907cucteno2xj.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
        model_id: "text-embedding-v3"
        embedding_dim: 1024
        dimensions: 1024
        encoding_format: "float"
        timeout: 60
        max_workers: 4
        batch_size: 32
        provider: "阿里云"
        description: "阿里云 text-embedding-v3 模型，OpenAI 兼容模式"
"""
import asyncio
import os
import time

import httpx
from openai import AsyncOpenAI, OpenAI

from configs import logger
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope

from .base import HttpEmbeddings


def _pool_stats(client) -> str:
    """提取 httpx 连接池状态用于排查 PoolTimeout 根因。

    属性链：AsyncOpenAI._client (httpx.AsyncClient) → ._transport (httpx.AsyncHTTPTransport)
    → ._pool (httpcore.AsyncConnectionPool)。httpcore 0.17 无 stats() 方法（1.0+ 才有），
    需直接读内部字段。

    关键指标：
    - in_use = len(pool.connections)：当前打开的连接数
    - max = pool._max_connections：池上限（配置的 max_connections）
    - waiting = pool._requests：排队等连接的请求数
    超时时若 in_use == max → 池被僵尸连接占满（连接泄漏）；
    若 in_use < max 但 waiting > 0 → 服务端慢/网络阻塞（连接未占满也超时）。
    """
    try:
        http_client = getattr(client, "_client", None)  # AsyncOpenAI._client
        if http_client is None:
            return "stats_unavailable:no_http_client"
        transport = getattr(http_client, "_transport", None)
        if transport is None:
            return "stats_unavailable:no_transport"
        pool = getattr(transport, "_pool", None)
        if pool is None:
            return "stats_unavailable:no_pool"
        # httpcore 1.0+ 优先用 stats()，0.17 走字段直读
        if hasattr(pool, "stats"):
            stats = pool.stats()
            in_use = getattr(stats, "connections", "?")
            max_conn = getattr(stats, "max_connections", "?")
            requests = getattr(stats, "requests", "?")
            return f"in_use={in_use},max={max_conn},waiting={requests}"
        # httpcore 0.17 路径
        connections = getattr(pool, "connections", [])
        in_use = len(connections) if isinstance(connections, (list, set)) else "?"
        max_conn = getattr(pool, "_max_connections", "?")
        requests = getattr(pool, "_requests", "?")
        # _requests 在 httpcore 0.17 是 deque（等待连接的请求队列），取 len 更直观
        if hasattr(requests, "__len__"):
            waiting = len(requests)
        else:
            waiting = requests
        return f"in_use={in_use},max={max_conn},waiting={waiting}"
    except Exception as e:
        return f"stats_unavailable:{e!r}"


class AliyunOpenAIEmbedding(HttpEmbeddings):
    """
    阿里云 OpenAI 兼容 Embedding 实现 / Aliyun OpenAI-Compatible Embedding

    通过 OpenAI SDK 调用阿里云 DashScope OpenAI 兼容模式的 Embedding 接口。
    继承自 HttpEmbeddings 以复用 batch_size / max_workers / timeout 等配置与异步并发逻辑。

    Inherits from HttpEmbeddings for Aliyun DashScope OpenAI-compatible embedding service.
    Reuses batch_size / max_workers / timeout config and async batching logic.

    注意 / Notes:
        - OpenAI 兼容接口的端点为 {api_base_url}/embeddings
        - 请求体: {"model": ..., "input": [...], "dimensions": ..., "encoding_format": ...}
        - 响应体: {"data": [{"embedding": [...], "index": 0}, ...], "model": ..., "usage": {...}}
        - prompt_name 参数在本实现中不生效（OpenAI 接口不支持该概念），仅为兼容调用方签名保留
    """

    def __init__(
        self,
        api_base_url: str,
        api_key: str = "",
        model_id: str = "text-embedding-v3",
        embedding_dim: int = 1024,
        dimensions: int | None = 1024,
        encoding_format: str = "float",
        timeout: int = 60,
        max_workers: int = 4,
        batch_size: int = 10,
        **kwargs,
    ):
        """
        初始化 AliyunOpenAIEmbedding

        Args:
            api_base_url: OpenAI 兼容 API 基础 URL（到 /v1 结尾）
                          OpenAI-compatible API base URL (ending with /v1)
            api_key: API 密钥 / API key
            model_id: 模型 ID，如 text-embedding-v3 / text-embedding-v4
                      Model ID, e.g. text-embedding-v3 / text-embedding-v4
            embedding_dim: 向量维度（用于日志/校验），默认 1024
                           Vector dimension (for logging/validation), default 1024
            dimensions: 请求时指定的向量维度，None 表示使用模型默认维度
                        Vector dimension specified in request, None for model default
            encoding_format: 编码格式，默认 "float" / Encoding format, default "float"
            timeout: 请求超时时间（秒），默认 60 / Request timeout (seconds), default 60
            max_workers: 异步并发最大工作数，默认 4 / Max async concurrency, default 4
            batch_size: 异步分片大小，默认 10 / Async batch size, default 10
        """
        super().__init__(
            api_base_url=api_base_url,
            embedding_dim=embedding_dim,
            timeout=timeout,
            max_workers=max_workers,
            batch_size=batch_size,
        )
        self.api_key = api_key
        self.model_id = model_id
        self.dimensions = dimensions
        self.encoding_format = encoding_format

        # 创建 OpenAI 客户端（内部复用连接池）
        # Create OpenAI client (reuses connection pool internally)
        self._client = OpenAI(
            api_key=api_key,
            base_url=api_base_url.rstrip('/'),
            timeout=timeout,
        )
        self._aclient: AsyncOpenAI | None = None
        # 异步 embedding 失败计数（观测用，随 worker 生命周期累计）
        self._aembed_fail_count = 0

        logger.info(
            f"AliyunOpenAIEmbedding 初始化完成，模型: {self.model_id}，"
            f"维度: {self.dimensions}，端点: {api_base_url}"
        )

    def __repr__(self) -> str:
        return (
            f"AliyunOpenAIEmbedding(api_base_url='{self.api_base_url}', "
            f"model_id='{self.model_id}', dimensions={self.dimensions}, "
            f"timeout={self.timeout}, max_workers={self.max_workers}, batch_size={self.batch_size})"
        )

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        """
        批量文本向量化 / Batch text vectorization

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized

        Returns:
            向量列表 / List of vectors

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not texts:
            logger.warning("embed_documents: 传入的文本列表为空 / Input text list is empty")
            return []

        try:
            # 按 batch_size 分片，避免单次请求超过阿里云接口上限（10）
            # Shard by batch_size to avoid exceeding the Aliyun per-request limit (10)
            chunks = [texts[i:i + self.batch_size] for i in range(0, len(texts), self.batch_size)]
            logger.debug(
                f"embed_documents: 请求模型 {self.model_id}，文本数量: {len(texts)}，"
                f"分片数: {len(chunks)}，分片大小: {self.batch_size} / "
                f"Request model {self.model_id}, text count: {len(texts)}, "
                f"shards: {len(chunks)}, batch size: {self.batch_size}"
            )
            embeddings: list[list[float]] = []
            for chunk in chunks:
                # with_raw_response 拿到 httpx 响应头提取 x-request-id（openai 1.14.3
                # 普通 create 返回的 CreateEmbeddingResponse 已被 pydantic 剥离 raw headers）
                raw = self._client.embeddings.with_raw_response.create(**self._create_kwargs(chunk))
                # 每片 create 成功即提取 req_id（阿里云限流排查用，debug 级低噪）
                log_dashscope(self.model_id, "embed_shard_done",
                              extract_aliyun_request_id(raw), level="debug",
                              shard=f"{len(chunks)}")
                completion = raw.parse()
                # 按 index 排序确保顺序与输入一致 / Sort by index to preserve input order
                data = sorted(completion.data, key=lambda x: x.index)
                embeddings.extend(item.embedding for item in data)
            logger.info(
                f"embed_documents: 成功获取 {len(embeddings)} 个向量，"
                f"维度: {len(embeddings[0]) if embeddings else 0}"
                f"{fmt_req_id(extract_aliyun_request_id(raw))} / "
                f"Successfully obtained {len(embeddings)} vectors"
            )
            return embeddings
        except Exception as e:
            _req_id = extract_aliyun_request_id(e)
            error_msg = f"embed_documents: 请求失败: {e}{fmt_req_id(_req_id)} / Request failed: {e}"
            logger.error(error_msg)
            log_dashscope(self.model_id, "embed_documents_error",
                          _req_id, level="error", reason=str(e)[:200])
            raise ValueError(error_msg) from e

    def embed_query(self, text: str) -> list[float]:
        """
        单个查询向量化 / Single query vectorization

        Args:
            text: 待向量化的查询文本 / Query text to be vectorized

        Returns:
            向量，一个浮点数列表 / Vector, a list of floats

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not text:
            logger.warning("embed_query: 传入的文本为空 / Input text is empty")
            return []

        try:
            logger.debug(
                f"embed_query: 请求模型 {self.model_id}，文本长度: {len(text)} / "
                f"Request model {self.model_id}, text length: {len(text)}"
            )
            raw = self._client.embeddings.with_raw_response.create(**self._create_kwargs([text]))
            _req_id = extract_aliyun_request_id(raw)
            log_dashscope(self.model_id, "embed_query_done", _req_id, level="debug")
            completion = raw.parse()
            embedding = completion.data[0].embedding
            logger.debug(f"embed_query: 成功获取向量，维度: {len(embedding)}{fmt_req_id(_req_id)} / Successfully obtained vector")  # pylint: disable=line-too-long
            return embedding
        except Exception as e:
            _req_id = extract_aliyun_request_id(e)
            error_msg = f"embed_query: 请求失败: {e}{fmt_req_id(_req_id)} / Request failed: {e}"
            logger.error(error_msg)
            log_dashscope(self.model_id, "embed_query_error",
                          _req_id, level="error", reason=str(e)[:200])
            raise ValueError(error_msg) from e

    def embed_texts_with_prompt(self, texts: list[str], prompt_name: str) -> list[list[float]]:
        """
        使用指定 prompt 进行文本向量化 / Text vectorization with prompt

        OpenAI 兼容接口不支持 prompt_name 概念，此处仅为兼容调用方签名，
        实际调用与 embed_documents 一致。

        The OpenAI-compatible interface does not support the prompt_name concept;
        this method is provided for signature compatibility and behaves like embed_documents.

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized
            prompt_name: prompt 名称（不生效） / Prompt name (no effect)

        Returns:
            向量列表 / List of vectors
        """
        if prompt_name:
            logger.debug(
                f"embed_texts_with_prompt: prompt_name={prompt_name} "
                f"（OpenAI 兼容接口不生效）/ prompt_name has no effect on OpenAI-compatible API"
            )
        return self.embed_documents(texts)

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        """
        异步批量文本向量化（分片并发）/ Async batch vectorization (sharded concurrency)

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized

        Returns:
            向量列表 / List of vectors

        Raises:
            ValueError: 当任一分片请求失败时抛出 / Raised when any shard request fails
        """
        if not texts:
            return []

        chunks = [texts[i:i + self.batch_size] for i in range(0, len(texts), self.batch_size)]
        semaphore = asyncio.Semaphore(self.max_workers)

        async def _bounded(chunk):
            async with semaphore:
                return await self._aembed_request(chunk)

        results = await asyncio.gather(
            *[_bounded(c) for c in chunks],
            return_exceptions=True,
        )

        for r in results:
            if isinstance(r, Exception):
                logger.error(f"aembed_documents: 分片失败: {r} / Shard failed: {r}")
                raise r

        all_embeddings = []
        for r in results:
            all_embeddings.extend(r)

        logger.info(f"aembed_documents: 成功获取 {len(all_embeddings)} 个向量 / Successfully obtained {len(all_embeddings)} vectors")  # pylint: disable=line-too-long
        return all_embeddings

    async def aembed_query(self, text: str) -> list[float]:
        """
        异步单个查询向量化 / Async single query vectorization

        Args:
            text: 待向量化的查询文本 / Query text to be vectorized

        Returns:
            向量，一个浮点数列表 / Vector, a list of floats
        """
        if not text:
            logger.warning("aembed_query: 传入的文本为空 / Input text is empty")
            return []

        embeddings = await self._aembed_request([text])
        return embeddings[0] if embeddings else []

    async def aembed_texts_with_prompt(self, texts: list[str], prompt_name: str) -> list[list[float]]:
        """
        异步使用指定 prompt 进行文本向量化 / Async text vectorization with prompt

        OpenAI 兼容接口不支持 prompt_name 概念，行为与 aembed_documents 一致。

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized
            prompt_name: prompt 名称（不生效） / Prompt name (no effect)

        Returns:
            向量列表 / List of vectors
        """
        if prompt_name:
            logger.debug(
                f"aembed_texts_with_prompt: prompt_name={prompt_name} "
                f"（OpenAI 兼容接口不生效）/ prompt_name has no effect on OpenAI-compatible API"
            )
        return await self.aembed_documents(texts)

    def _create_kwargs(self, texts: list[str]) -> dict:
        """构造 embeddings.create 的请求参数 / Build kwargs for embeddings.create"""
        kwargs = {
            "model": self.model_id,
            "input": texts,
            "encoding_format": self.encoding_format,
        }
        if self.dimensions is not None:
            kwargs["dimensions"] = self.dimensions
        return kwargs

    async def _get_aclient(self) -> AsyncOpenAI:
        """
        懒初始化异步客户端 / Lazily initialize async client

        显式约束 httpx 连接池：默认 AsyncOpenAI 的连接池上限过高（max_connections=1000），
        并发突发下若部分连接陷入半死状态会持续占用池位，导致后续请求永久阻塞在
        连接 acquire 信号量上（该阻塞不受请求 timeout 约束）。这里把池收窄并配 max_retries，
        让连接可被及时回收，配合 _aembed_request 的 wait_for 兜底，避免池被打坏后无法恢复。
        """
        if self._aclient is None:
            self._aclient = AsyncOpenAI(
                api_key=self.api_key,
                base_url=self.api_base_url.rstrip('/'),
                timeout=self.timeout,
                max_retries=2,
                http_client=httpx.AsyncClient(
                    limits=httpx.Limits(
                        max_connections=50,
                        max_keepalive_connections=10,
                        keepalive_expiry=30.0,
                    ),
                    timeout=self.timeout,
                ),
            )
        return self._aclient

    async def _aembed_request(self, texts: list[str]) -> list[list[float]]:
        """
        单次异步 Embedding 请求 / Single async embedding request

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized

        Returns:
            向量列表 / List of vectors

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not texts:
            return []

        _ACCUM_FAIL_PREFIX = '，累计失败 '
        _PID_PREFIX = ' | pid='
        _ERROR_LEVEL_KEY = 'error'
        t0 = time.perf_counter()
        # client_id：跨请求追踪同一 worker 的 client 复用情况（缓存命中/重建）
        client_id = id(self._aclient) if self._aclient is not None else "none"
        try:
            client = await self._get_aclient()
            if id(client) != client_id:
                # 缓存重建（配置指纹变更或首次创建）——记录新 client 实例 id
                client_id = id(client)
                logger.info(
                    f"[aembed] client created | pid={os.getpid()} client_id={client_id} "
                    f"model={self.model_id} endpoint={self.api_base_url}"
                )
            # 请求开始即记 INFO 级日志：生产可见，定位"哪个 worker / 哪个 client / 池配置"
            # 4 个 uvicorn worker 共享日志文件，pid 区分卡死的是哪个进程
            logger.info(
                f"[aembed] request start | pid={os.getpid()} client_id={client_id} "
                f"model={self.model_id} texts={len(texts)} timeout={self.timeout}s "
                f"max_connections=50"
            )
            # 绝对超时兜底：httpx 连接池信号量 acquire 不受请求 timeout 约束，
            # 并发突发可能让请求永久挂在池上；shield+wait_for 保证协程一定被释放，
            # 取消时连带释放被占用的连接位，使连接池可在超时窗口内自愈。
            #
            # 用 shield(task) 隔离：wait_for 超时只取消 shield 包裹的协程，底层 task 仍在
            # openai SDK max_retries=2 retry 路径中跑。超时分支显式 cancel+await retrieve
            # 异常，避免 "Task exception was never retrieved" 警告 + 拿到底层真实异常
            # （APITimeoutError / httpx.PoolTimeout）的 retry 次数与具体类型。
            #
            # with_raw_response 拿到 httpx 响应头提取 x-request-id（openai 1.14.3
            # 普通 create 返回的 CreateEmbeddingResponse 已被 pydantic 剥离 raw headers）。
            task = asyncio.ensure_future(
                client.embeddings.with_raw_response.create(**self._create_kwargs(texts))
            )
            try:
                raw_resp = await asyncio.wait_for(asyncio.shield(task), timeout=self.timeout)
            except asyncio.TimeoutError:
                # wait_for 超时：shield 保护下底层 task 仍在 retry。显式 cancel + await
                # retrieve 异常，否则触发 "Task exception was never retrieved" 警告，
                # 且 task 持有的 httpx 连接无法归还（这正是连接池渐耗尽的根因）。
                retrieved_exc = None
                task.cancel()
                try:
                    await task
                except BaseException as _e:
                    # CancelledError（task 还在 retry 中被中断）或 APITimeoutError
                    # （retry 已耗尽抛出）——均经此 retrieve，task 状态被清理
                    retrieved_exc = _e
                self._aembed_fail_count += 1
                elapsed = time.perf_counter() - t0
                pool_stats = _pool_stats(client)
                # retrieved_exc 类型 + str 是定位根因的关键：
                # - APITimeoutError("Request timed out.") → 服务端慢/网络阻塞
                # - httpx.PoolTimeout → 池被占满（连接泄漏）
                # - CancelledError → task 还在 retry 中（无底层异常）
                error_msg = (
                    f"_aembed_request: 绝对超时 {self.timeout}s（疑似连接池阻塞），"
                    f"文本数量: {len(texts)}，耗时 {elapsed:.3f}s"
                    f"{_ACCUM_FAIL_PREFIX}{self._aembed_fail_count}"
                    f"{_PID_PREFIX}{os.getpid()} client_id={client_id}"
                    f" | pool_stats[{pool_stats}]"
                    f" | underlying_exc={retrieved_exc!r}"
                )
                logger.error(error_msg)
                log_dashscope(self.model_id, "aembed_timeout", None, level=_ERROR_LEVEL_KEY,
                              reason=f"create阶段超时未返回 pool={pool_stats} "
                                     f"underlying={retrieved_exc!r}")
                raise ValueError(error_msg) from retrieved_exc
            # create 成功返回即提取 req_id（阿里云限流排查用）
            _req_id = extract_aliyun_request_id(raw_resp)
            log_dashscope(self.model_id, "aembed_done", _req_id, level="debug")
            completion = raw_resp.parse()
            data = sorted(completion.data, key=lambda x: x.index)
            embeddings = [item.embedding for item in data]
            # A5：校验返回向量数量与输入文本数量一致，防止静默错位
            if len(embeddings) != len(texts):
                raise ValueError(
                    f"embedding 数量不匹配: 请求 {len(texts)} 条, 返回 {len(embeddings)} 条"
                )
            elapsed = time.perf_counter() - t0
            fail_hint = f"{_ACCUM_FAIL_PREFIX}{self._aembed_fail_count}" if self._aembed_fail_count else ""
            logger.info(
                f"_aembed_request: 成功获取 {len(embeddings)} 个向量，耗时 {elapsed:.3f}s"
                f"{fmt_req_id(_req_id)}{fail_hint} / "
                f"Successfully obtained {len(embeddings)} vectors"
            )
            return embeddings
        except asyncio.TimeoutError:
            # 兜底：理论上不会走到这里（上方 wait_for 已处理），但保留以防 shield 失效
            self._aembed_fail_count += 1
            elapsed = time.perf_counter() - t0
            error_msg = (
                f"_aembed_request: 绝对超时 {self.timeout}s（shield 失效兜底），"
                f"文本数量: {len(texts)}，耗时 {elapsed:.3f}s"
                f"{_ACCUM_FAIL_PREFIX}{self._aembed_fail_count}{_PID_PREFIX}{os.getpid()}"
            )
            logger.error(error_msg)
            log_dashscope(self.model_id, "aembed_timeout", None, level=_ERROR_LEVEL_KEY,
                          reason="shield 失效兜底")
            raise ValueError(error_msg) from None
        except Exception as e:
            # 内层超时分支已包装好的诊断 ValueError（含 pool_stats/underlying_exc），
            # 不重新包装，避免：①失败计数双倍累加 ②诊断信息被覆盖 ③二次 ValueError 嵌套
            if isinstance(e, ValueError) and "underlying_exc=" in str(e):
                raise
            self._aembed_fail_count += 1
            elapsed = time.perf_counter() - t0
            _req_id = extract_aliyun_request_id(e)
            # 异常路径同样记 pool stats + pid，定位"异常时池是否被打爆"
            try:
                client = self._aclient
                pool_stats = _pool_stats(client) if client is not None else "no_client"
            except Exception:
                pool_stats = "stats_unavailable"
            error_msg = (
                f"_aembed_request: 请求失败: {e}，文本数量: {len(texts)}，"
                f"耗时 {elapsed:.3f}s{_ACCUM_FAIL_PREFIX}{self._aembed_fail_count}"
                f"{fmt_req_id(_req_id)}{_PID_PREFIX}{os.getpid()} "
                f"pool_stats[{pool_stats}] / Request failed: {e}"
            )
            logger.error(error_msg)
            log_dashscope(self.model_id, "aembed_error", _req_id, level=_ERROR_LEVEL_KEY,
                          reason=str(e)[:200])
            raise ValueError(error_msg) from e
