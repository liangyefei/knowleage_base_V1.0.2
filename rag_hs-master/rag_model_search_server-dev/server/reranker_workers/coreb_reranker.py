"""
Coreb Code Reranker Worker

Coreb Code 模型的文档重排实现，继承自 HttpReranker
提供针对代码检索场景的专业化重排处理
"""
from typing import ClassVar

from configs import logger

from .base import HttpReranker


class CorebCodeReranker(HttpReranker):
    """
    Coreb Code Reranker 实现类 / Coreb Code Reranker Implementation

    针对 Coreb Code 模型的文档重排服务，继承自 HttpReranker
    提供针对代码检索场景的专业化重排处理

    Inherits from HttpReranker for Coreb Code model reranking service.
    Provides specialized reranking for code retrieval scenarios.

    Attributes:
        MODEL_NAME: 模型名称标识符 / Model name identifier
        TASK_TYPES: 支持的任务类型列表 / List of supported task types
    """

    # 模型名称 / Model Name
    MODEL_NAME: ClassVar[str] = "coreb-code-reranker-api"

    # 支持的任务类型 / Supported Task Types
    # TEXT_TO_CODE: 自然语言查询到代码的检索 / Natural language to code retrieval
    # CODE_TO_CODE: 代码到代码的检索 / Code to code retrieval
    # CODE_TO_TEXT: 代码到自然语言的检索 / Code to natural language retrieval
    TASK_TYPES: ClassVar[list[str]] = ["TEXT_TO_CODE", "CODE_TO_CODE", "CODE_TO_TEXT"]

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
        初始化 CorebCodeReranker / Initialize CorebCodeReranker

        Args:
            api_base_url: Reranker API 的基础 URL / Base URL of the Reranker API
            default_task_type: 默认的任务类型，默认 "TEXT_TO_CODE"
                               Default task type, defaults to "TEXT_TO_CODE"
            timeout: 请求超时时间（秒），默认 60 / Request timeout in seconds, defaults to 60
            top_n: 返回的文档数量，默认 3 / Number of documents to return, defaults to 3
            max_workers: 并发评分的最大线程数，默认 8 / Max threads for concurrent scoring, defaults to 8
            **kwargs: 其他参数 / Other parameters
        """
        # 验证任务类型是否有效 / Validate task type
        if default_task_type not in self.TASK_TYPES:
            logger.warning(
                f"__init__: 任务类型 '{default_task_type}' 不在支持列表中，"
                f"可用: {self.TASK_TYPES}，使用默认值 'TEXT_TO_CODE' / "
                f"Task type '{default_task_type}' not in supported list, "
                f"using default 'TEXT_TO_CODE'"
            )
            default_task_type = "TEXT_TO_CODE"

        super().__init__(
            api_base_url=api_base_url,
            default_task_type=default_task_type,
            timeout=timeout,
            top_n=top_n,
            max_workers=max_workers,
            **kwargs
        )

    def __repr__(self) -> str:
        """
        返回对象的字符串表示 / Return String Representation of Object

        Returns:
            包含类名和关键参数的字符串 / String containing class name and key parameters
        """
        return (
            f"CorebCodeReranker(api_base_url='{self.api_base_url}', "
            f"default_task_type='{self.default_task_type}', "
            f"timeout={self.timeout}, top_n={self.top_n}, "
            f"max_workers={self.max_workers})"
        )

    def score_with_task(
        self,
        query: str,
        document: str,
        task_type: str
    ) -> float:
        """
        指定任务类型计算相关性分数 / Calculate Relevance Score with Specified Task Type

        显式指定任务类型来计算查询-文档的相关性分数
        Explicitly specify task type to calculate query-document relevance score.

        Args:
            query: 查询文本 / Query text
            document: 文档内容 / Document content
            task_type: 任务类型，必须是 TASK_TYPES 中的一个
                       Task type, must be one of TASK_TYPES
                       - "TEXT_TO_CODE": 自然语言查询代码 / Natural language querying code
                       - "CODE_TO_CODE": 代码查询代码 / Code querying code
                       - "CODE_TO_TEXT": 代码查询自然语言文档 / Code querying natural language docs

        Returns:
            相关性分数，范围通常在 0-1 之间 / Relevance score, typically in range 0-1

        Raises:
            ValueError: 当任务类型无效或 API 请求失败时抛出
                        Raised when task type is invalid or API request fails

        Example:
            >>> reranker = CorebCodeReranker(api_base_url="http://localhost:8000")
            >>> # 自然语言查询代码片段 / Natural language querying code snippets
            >>> score = reranker.score_with_task(
            ...     query="如何实现快速排序？",
            ...     document="def quicksort(arr): ...",
            ...     task_type="TEXT_TO_CODE"
            ... )
        """
        # 参数验证 / Parameter validation
        if not query:
            logger.warning("score_with_task: 查询文本为空 / Query text is empty")
            return 0.0

        if not document:
            logger.warning("score_with_task: 文档内容为空 / Document content is empty")
            return 0.0

        if task_type not in self.TASK_TYPES:
            error_msg = (
                f"score_with_task: 无效的任务类型 '{task_type}'，"
                f"支持的任务类型: {self.TASK_TYPES} / "
                f"Invalid task type '{task_type}', supported types: {self.TASK_TYPES}"
            )
            logger.error(error_msg)
            raise ValueError(error_msg)

        logger.debug(
            f"score_with_task: 计算分数，任务类型: {task_type} / "
            f"Calculating score, task type: {task_type}"
        )

        # 调用基类的 score 方法 / Call base class score method
        return self.score(query, document, task_type)

    def get_supported_task_types(self) -> list[str]:
        """
        获取支持的任务类型列表 / Get List of Supported Task Types

        Returns:
            支持的任务类型列表 / List of supported task types

        Example:
            >>> reranker = CorebCodeReranker(api_base_url="http://localhost:8000")
            >>> print(reranker.get_supported_task_types())
            ['TEXT_TO_CODE', 'CODE_TO_CODE', 'CODE_TO_TEXT']
        """
        return self.TASK_TYPES.copy()
