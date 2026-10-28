"""阿里云通晓统一搜索API客户端封装。

使用HTTP REST接口调用阿里云UnifiedSearch，提供联网搜索能力，返回结构化搜索结果。

并发优化：
- 复用全局ClientSession复用TCP连接，减少握手开销
- Semaphore限制最大并发请求，避免配额耗尽
- 简单重试机制应对偶发网络错误
"""
import aiohttp
import asyncio
import json
from typing import List, Dict, Any, Optional
from langchain.docstore.document import Document
from configs import KB_CONFIG, logger


class AliyunHttpSearch:
    """阿里云HTTP REST接口调用方式（cloud-iqs.aliyuncs.com）

    并发优化：
    - 全局复用单个ClientSession，复用TCP连接池
    - Semaphore限制最大并发请求数，保护API配额
    - 支持配置最大并发和重试次数
    """

    # 全局共享ClientSession，复用连接池

    _session: Optional[aiohttp.ClientSession] = None

    # 全局并发限制器

    _semaphore: Optional[asyncio.Semaphore] = None

    def __init__(
        self,
        api_url: str,
        api_token: str,
        num_results: int = 5,
        time_range: str = "NoLimit",
        engine_type: str = "CNLiteBasic",
        max_concurrent: int = 5,
        max_retries: int = 2,
    ):
        """
        初始化客户端

        Args:
            api_url: API接口地址
            api_token: API密钥token（用于Authorization Bearer）
            num_results: 返回结果条数
            time_range: 时间范围
            engine_type: 搜索引擎类型
            max_concurrent: 最大并发请求数，默认5
            max_retries: 失败重试次数，默认2
        """
        self.api_url = api_url
        self.api_token = api_token
        self.num_results = num_results
        self.time_range = time_range
        self.engine_type = engine_type
        self.max_concurrent = max_concurrent
        self.max_retries = max_retries

        # 初始化全局并发限制器
        if AliyunHttpSearch._semaphore is None:
            AliyunHttpSearch._semaphore = asyncio.Semaphore(max_concurrent)

    @classmethod
    async def close(cls) -> None:
        """关闭全局session，一般不需要调用，进程退出自动关闭"""
        if cls._session is not None and not cls._session.closed:
            await cls._session.close()
            cls._session = None
            logger.info("[aliyun_search] global ClientSession closed")

    @classmethod
    def _get_session(cls) -> aiohttp.ClientSession:
        """获取或创建全局共享的ClientSession"""
        if cls._session is None or cls._session.closed:
            timeout = aiohttp.ClientTimeout(
                total=30,       # 总超时
                connect=10,      # 连接超时
                sock_connect=10,
                sock_read=20
            )
            conn = aiohttp.TCPConnector(
                limit=10,               # 最大连接数
                limit_per_host=5,       # 同主机最大连接数
                enable_cleanup_closed=True,
                force_close=False       # 保持连接复用
            )
            cls._session = aiohttp.ClientSession(
                timeout=timeout,
                connector=conn
            )
            logger.info("[aliyun_search] global ClientSession initialized, "
                         f"max_connections={conn.limit}, limit_per_host={conn.limit_per_host}")
        return cls._session

    async def search(self, query: str) -> Optional[List[Document]]:
        """
        通过HTTP REST接口执行搜索（带并发限制和重试）

        Args:
            query: 搜索关键词

        Returns:
            搜索结果文档列表，失败返回None
        """
        if AliyunHttpSearch._semaphore is None:
            AliyunHttpSearch._semaphore = asyncio.Semaphore(self.max_concurrent)

        # 获取信号量并发限制
        async with AliyunHttpSearch._semaphore:
            return await self._search_with_retry(query)

    async def _search_with_retry(self, query: str) -> Optional[List[Document]]:
        """带重试的搜索"""
        last_exception: Optional[Exception] = None

        for attempt in range(self.max_retries + 1):
            try:
                result = await self._do_search(query)
                if result is not None:
                    return result
                # 空结果也算成功，但返回空
                return []
            except Exception as e:
                last_exception = e
                if attempt < self.max_retries:
                    logger.warning(f"[aliyun_search] search attempt {attempt+1} failed, retrying... error: {str(e)}")
                    await asyncio.sleep(0.5 * (attempt + 1))  # 指数退避
                else:
                    logger.error(f"[aliyun_search] all {self.max_retries+1} attempts failed: {str(e)}")

        return None

    async def _do_search(self, query: str) -> Optional[List[Document]]:
        """执行单次搜索"""
        try:
            # 构造请求体，兼容UnifiedSearch接口格式
            payload = {
                "query": query,
                "engineType": self.engine_type,
                "timeRange": self.time_range,
                "contents": {
                    "mainText": False,
                    "markdownText": False,
                    "summary": False,
                    "rerankScore": True
                },
                "advancedParams": {
                    "numResults": str(self.num_results)
                }
            }

            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_token}"
            }

            # 使用全局共享session复用连接
            session = self._get_session()
            async with session.post(
                self.api_url,
                json=payload,
                headers=headers
            ) as response:
                if response.status != 200:
                    text = await response.text()
                    logger.error(f"[aliyun_search] http api error, status={response.status}, response={text[:200]}")
                    return None

                data = await response.json()
                page_items = data.get("pageItems", [])
                logger.info(f"[aliyun_search] http api success, got {len(page_items)} results")

            # 解析结果
            if not page_items:
                logger.info("[aliyun_search] http api returned empty results")
                return []

            # 转换为Document列表
            docs: List[Document] = []
            for item in page_items:
                title = item.get("title", "")
                snippet = item.get("snippet", "")
                link = item.get("link", "")
                published_time = item.get("publishedTime")
                rerank_score = item.get("rerankScore")
                hostname = item.get("hostname")

                content = title + "\n" + snippet
                doc = Document(
                    page_content=content,
                    metadata={
                        "source": link,
                        "filename": title,
                        "title": title,
                        "snippet": snippet,
                        "published_time": published_time,
                        "rerank_score": rerank_score,
                        "hostname": hostname,
                        "search_query": query,
                        "doc_type": "internet_search"
                    }
                )
                docs.append(doc)

            # 处理场景化结果
            scene_items = data.get("sceneItems", [])
            if scene_items:
                for scene in scene_items:
                    scene_type = scene.get("type", "")
                    detail = scene.get("detail", "")
                    content = f"场景类型: {scene_type}\n详情: {detail}"
                    doc = Document(
                        page_content=content,
                        metadata={
                            "source": "scene_item",
                            "filename": scene_type,
                            "doc_type": "internet_scene"
                        }
                    )
                    docs.append(doc)

            search_info = data.get("searchInformation", {})
            search_time = search_info.get("searchTime", 0)
            logger.info(f"[aliyun_search] http search completed, search_time={search_time}ms, results={len(docs)}")

            return docs

        except aiohttp.ClientError as e:
            logger.error(f"[aliyun_search] http network error: {str(e)}")
            return None
        except json.JSONDecodeError as e:
            logger.error(f"[aliyun_search] json decode error: {str(e)}")
            return None
        except Exception as e:
            logger.error(f"[aliyun_search] http unexpected exception: {str(e)}")
            return None


def get_aliyun_search_client() -> Optional[AliyunHttpSearch]:
    """获取联网搜索客户端"""
    config = KB_CONFIG.get("INTERNET_SEARCH", {})

    # 检查全局开关
    if not config.get("enable", False):
        return None

    api_url = config.get("api_url", "https://cloud-iqs.aliyuncs.com/search/unified")
    api_token = config.get("api_token", "")

    if not api_token:
        logger.warning("[aliyun_search] api_token not configured, internet search disabled")
        return None

    num_results = config.get("num_results", 5)
    time_range = config.get("time_range", "NoLimit")
    engine_type = config.get("engine_type", "CNLiteBasic")
    # 可配置并发限制，默认最大5个并发请求
    max_concurrent = config.get("max_concurrent", 5)
    # 可配置重试次数，默认2次
    max_retries = config.get("max_retries", 2)

    return AliyunHttpSearch(
        api_url=api_url,
        api_token=api_token,
        num_results=num_results,
        time_range=time_range,
        engine_type=engine_type,
        max_concurrent=max_concurrent,
        max_retries=max_retries
    )
