"""文本嵌入：同步/异步封装 configs.embeddings_api。"""
import asyncio
import os

import numpy as np

from configs import EMBEDDING_MODEL
from server.embeddings_api import aembed_texts, embed_texts

# 模块级 embedding 并发信号量：保护 embedding 服务（本地 GPU 或远端 API）。
# query embedding 是每 chat 请求必经路径（检索前先 embed query），200 QPS 下
# 无限制并发可能打爆服务（本地 GPU OOM / 远端 API 429）。与 P0-3 LLM 信号量同理。
_HAISI_EMBEDDING_MAX_CONCURRENCY = max(1, int(os.environ.get("HAISI_EMBEDDING_MAX_CONCURRENCY", "128")))
_EMBEDDING_SEMAPHORE = asyncio.Semaphore(_HAISI_EMBEDDING_MAX_CONCURRENCY)


def get_embeddings(texts):
    """使用配置的embedding模型获取文本向量"""
    result = embed_texts(texts=texts, embed_model=EMBEDDING_MODEL)
    if result.code == 200 and result.data:
        return np.array(result.data, dtype=np.float32)
    raise ValueError(f"Embedding 失败: {result.msg}")


async def aget_embeddings(texts):
    """异步获取文本向量，避免在 async 链路里阻塞事件循环。

    全局信号量限流，保护 embedding 服务（本地 GPU 或远端 API），
    防止高并发下 GPU OOM 或 API 429。
    """
    async with _EMBEDDING_SEMAPHORE:
        result = await aembed_texts(texts=texts, embed_model=EMBEDDING_MODEL)
    if result.code == 200 and result.data:
        return np.array(result.data, dtype=np.float32)
    raise ValueError(f"Embedding 失败: {result.msg}")
