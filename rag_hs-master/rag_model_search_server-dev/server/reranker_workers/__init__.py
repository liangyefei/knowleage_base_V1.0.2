"""
Reranker Workers 模块

提供基于 HTTP API 的 Reranker 实现和注册机制
"""
from typing import Dict, Optional, Type

from configs import logger

from .base import HttpReranker
from .coreb_reranker import CorebCodeReranker
from .qwen3_reranker import Qwen3Reranker

__all__ = [
    "HttpReranker",
    "get_reranker_worker",
    "register_reranker_worker",
    "CorebCodeReranker",
    "Qwen3Reranker",
]


# Reranker Worker 注册表
_RERANKER_WORKERS: dict[str, type[HttpReranker]] = {}


def register_reranker_worker(model_name: str, worker_class: type[HttpReranker] | None = None):
    """
    注册 Reranker Worker

    可以作为类装饰器使用，也可以直接调用

    Args:
        model_name: 模型名称
        worker_class: Worker 类，装饰器模式时由 Python 自动传入

    Returns:
        装饰器函数或原类

    Example:
        # 作为装饰器使用
        @register_reranker_worker("my_reranker")
        class MyReranker(HttpReranker):
            pass

        # 直接调用
        register_reranker_worker("my_reranker", MyReranker)
    """
    def decorator(cls: type[HttpReranker]) -> type[HttpReranker]:
        _RERANKER_WORKERS[model_name] = cls
        logger.info(f"注册 Reranker Worker: {model_name} -> {cls.__name__}")
        return cls

    if worker_class is not None:
        return decorator(worker_class)
    return decorator


def get_reranker_worker(model_name: str) -> type[HttpReranker] | None:
    """
    根据模型名称获取对应的 Reranker Worker 类

    Args:
        model_name: 模型名称

    Returns:
        对应的 Worker 类，如果未注册则返回 None
    """
    worker = _RERANKER_WORKERS.get(model_name)

    if worker is None:
        logger.warning(f"未找到 Reranker Worker: {model_name}，可用: {list(_RERANKER_WORKERS.keys())}")

    return worker


def list_reranker_workers() -> dict[str, type[HttpReranker]]:
    """
    列出所有已注册的 Reranker Workers

    Returns:
        模型名称到 Worker 类的映射字典
    """
    return _RERANKER_WORKERS.copy()


# 注册默认的 HttpReranker
register_reranker_worker("default", HttpReranker)
register_reranker_worker("http-reranker", HttpReranker)

# 注册 Coreb Code Reranker Worker
register_reranker_worker("coreb-code-reranker-api", CorebCodeReranker)

# 注册阿里云 DashScope Qwen3 Reranker Worker（qwen3.7-text-rerank）
register_reranker_worker("qwen3.7-text-rerank", Qwen3Reranker)
