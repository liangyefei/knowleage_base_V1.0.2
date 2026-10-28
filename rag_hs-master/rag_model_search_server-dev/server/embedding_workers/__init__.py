"""
Embedding Workers 模块

提供基于 HTTP API 的 Embedding 实现和注册机制
"""
from typing import Dict, Optional, Type

from configs import logger

from .aliyun_openai_embedding import AliyunOpenAIEmbedding
from .base import HttpEmbeddings
from .qwen3_embedding import Qwen3Embedding

__all__ = [
    "HttpEmbeddings",
    "get_embedding_worker",
    "register_embedding_worker",
    "Qwen3Embedding",
    "AliyunOpenAIEmbedding",
]


# Embedding Worker 注册表
_EMBEDDING_WORKERS: dict[str, type[HttpEmbeddings]] = {}


def register_embedding_worker(model_name: str, worker_class: type[HttpEmbeddings] | None = None):
    """
    注册 Embedding Worker

    可以作为类装饰器使用，也可以直接调用

    Args:
        model_name: 模型名称
        worker_class: Worker 类，装饰器模式时由 Python 自动传入

    Returns:
        装饰器函数或原类

    Example:
        # 作为装饰器使用
        @register_embedding_worker("my_model")
        class MyEmbeddings(HttpEmbeddings):
            pass

        # 直接调用
        register_embedding_worker("my_model", MyEmbeddings)
    """
    def decorator(cls: type[HttpEmbeddings]) -> type[HttpEmbeddings]:
        _EMBEDDING_WORKERS[model_name] = cls
        logger.info(f"注册 Embedding Worker: {model_name} -> {cls.__name__}")
        return cls

    if worker_class is not None:
        return decorator(worker_class)
    return decorator


def get_embedding_worker(model_name: str) -> type[HttpEmbeddings] | None:
    """
    根据模型名称获取对应的 Embedding Worker 类

    Args:
        model_name: 模型名称

    Returns:
        对应的 Worker 类，如果未注册则返回 None
    """
    worker = _EMBEDDING_WORKERS.get(model_name)

    if worker is None:
        logger.warning(f"未找到 Embedding Worker: {model_name}，可用: {list(_EMBEDDING_WORKERS.keys())}")

    return worker


def list_embedding_workers() -> dict[str, type[HttpEmbeddings]]:
    """
    列出所有已注册的 Embedding Workers

    Returns:
        模型名称到 Worker 类的映射字典
    """
    return _EMBEDDING_WORKERS.copy()


# 注册默认的 HttpEmbeddings
register_embedding_worker("default", HttpEmbeddings)

# 注册 Qwen3 Embedding Worker
register_embedding_worker("qwen3-embedding-api", Qwen3Embedding)

# 注册阿里云 OpenAI 兼容 Embedding Worker（text-embedding-v3 等）
register_embedding_worker("text-embedding-v3", AliyunOpenAIEmbedding)

register_embedding_worker("qwen3.7-text-embedding", AliyunOpenAIEmbedding)
