import asyncio
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from collections.abc import Sequence
from typing import Any, Union

from configs.logger_manager import logger
from langchain.callbacks.manager import Callbacks
from langchain.retrievers.document_compressors.base import BaseDocumentCompressor
from langchain_core.documents import Document
from llama_index.bridge.pydantic import Field, PrivateAttr

# --------------------------------------------------------------------------
# B1/B2：reranker 并发信号量 + 超时 + 模块级单例。
#
# B1：本地 CrossEncoder 路径用 asyncio.to_thread 丢默认线程池（上限 ~36），200 并发下
# 36 个线程同时 GPU forward（batch_size=32, max_length=1024）显存叠加可能 OOM；
# HttpReranker 路径有自身 timeout（60s）但无全局并发上限。信号量限制同时在飞的
# reranker 调用数，wait_for 防本地 torch 推理卡死无限期占用线程。
# max(1, ...) 守卫防误配 0/负值导致所有 acquire 永久阻塞（同 _LLM_CONCURRENCY 模式）。
#
# B2：原 pipeline 每请求 get_reranker(top_n=rerank_top_n) 重建 LangchainReranker
# （CrossEncoder 对象）/ HttpReranker worker（含 httpx client），高频下对象重建开销可观。
# 单例固定 top_n 为上限值，pipeline 拿到 reranked 结果后自行截取前 rerank_top_n 个
# （reranker 返回已按 score 降序）。
_RERANKER_SEMAPHORE = asyncio.Semaphore(max(1, int(os.environ.get("HAISI_RERANKER_MAX_CONCURRENCY", "8"))))
_RERANKER_TIMEOUT = float(os.environ.get("HAISI_RERANKER_TIMEOUT", "30"))
_reranker_singleton: Any | None = None
_RERANKER_SINGLETON_TOP_N = 30  # 与 pipeline._RERANK_TOP_N_MAX 对齐

# --------------------------------------------------------------------------
# TASK_INFO: https://huggingface.co/BAAI/bge-reranker-large
# TODO 用上述示例重写reranker
# --------------------------------------------------------------------------

class LangchainReranker(BaseDocumentCompressor):
    """Document compressor that uses `Cohere Rerank API`."""
    model_name_or_path: str = Field()
    _model: Any = PrivateAttr()
    top_n: int = Field()
    device: str = Field()
    max_length: int = Field()
    batch_size: int = Field()
    num_workers: int = Field()

    def __init__(self,
                 model_name_or_path: str,
                 top_n: int = 3,
                 device: str = "cuda",
                 max_length: int = 1024,
                 batch_size: int = 32,
                 num_workers: int = 0,
                 ):
        # 懒加载：仅在使用本地模型时才 import，避免模块加载时拉取 torch/sentence_transformers
        from sentence_transformers import CrossEncoder
        self._model = CrossEncoder(model_name=model_name_or_path, max_length=max_length, device=device)
        super().__init__(
            top_n=top_n,
            model_name_or_path=model_name_or_path,
            device=device,
            max_length=max_length,
            batch_size=batch_size,
            num_workers=num_workers,
        )

    def compress_documents(
            self,
            documents: Sequence[Document],
            query: str,
            callbacks: Callbacks | None = None,
    ) -> Sequence[Document]:
        """
        Compress documents using Cohere's rerank API.

        Args:
            documents: A sequence of documents to compress.
            query: The query to use for compressing the documents.
            callbacks: Callbacks to run during the compression process.

        Returns:
            A sequence of compressed documents.
        """
        if len(documents) == 0:  # to avoid empty api call
            return []
        doc_list = list(documents)
        _docs = [d.page_content for d in doc_list]
        sentence_pairs = [[query, _doc] for _doc in _docs]
        results = self._model.predict(sentences=sentence_pairs,
                                      batch_size=self.batch_size,
                                      num_workers=self.num_workers,
                                      convert_to_tensor=True
                                      )
        top_k = self.top_n if self.top_n < len(results) else len(results)

        values, indices = results.topk(top_k)
        final_results = []
        for value, index in zip(values, indices, strict=False):
            doc = doc_list[index]
            doc.metadata["relevance_score"] = value
            final_results.append(doc)
        return final_results

    async def acompress_documents(
            self,
            documents: Sequence[Document],
            query: str,
            callbacks: Callbacks | None = None,
    ) -> Sequence[Document]:
        """
        异步批量文档重排

        CrossEncoder.predict 是同步调用（torch 批量推理，batch_size 已在 __init__ 配置），
        用 asyncio.to_thread 丢到线程池，避免阻塞事件循环。
        """
        if not documents:
            return []
        if not query:
            return []
        return await asyncio.to_thread(self.compress_documents, documents, query, callbacks)


if __name__ == "__main__":
    from configs import (
        MODEL_PATH as _MODEL_PATH,
        RERANKER_MAX_LENGTH as _RERANKER_MAX_LENGTH,
        RERANKER_MODEL as _RERANKER_MODEL,
        USE_RERANKER as _USE_RERANKER,
    )
    from server.utils import embedding_device

    if _USE_RERANKER:
        reranker_model_path = _MODEL_PATH["reranker"].get(_RERANKER_MODEL, "BAAI/bge-reranker-large")
        logger.info("-----------------model path------------------")
        logger.info("{}", reranker_model_path)
        _ = LangchainReranker(top_n=3,
                              device=embedding_device(),
                              max_length=_RERANKER_MAX_LENGTH,
                              model_name_or_path=reranker_model_path
                              )


def get_reranker(
    model_name: str = None,
    top_n: int = 3,
    device: str = "cuda",
    max_length: int = 1024
) -> Union[LangchainReranker, "HttpReranker"]:
    """
    获取 Reranker 实例
    
    Args:
        model_name: 模型名称，如果为 None 则从配置读取
        top_n: 返回的文档数量
        device: 本地模型设备
        max_length: 本地模型最大长度
    
    Returns:
        LangchainReranker 或 HttpReranker 实例
    """
    from configs import MODEL_PATH, RERANKER_API_CONFIG, RERANKER_MAX_LENGTH, RERANKER_MODEL, USE_RERANKER

    if not USE_RERANKER:
        return None

    model_name = model_name or RERANKER_MODEL

    # 检查是否为 API 配置的 reranker
    if model_name in RERANKER_API_CONFIG:
        from server.reranker_workers import get_reranker_worker
        config = RERANKER_API_CONFIG[model_name]
        worker_class = get_reranker_worker(model_name)
        # 透传所有配置项；基类 HttpReranker.__init__ 通过 **kwargs 吸收未识别字段，
        # 各 Worker 子类按需从显式参数中取用（如 Qwen3Reranker 的 api_key/model_id/instruct）
        # Pass through all config keys; base HttpReranker.__init__ absorbs unknown keys via **kwargs,
        # each worker subclass picks what it needs from explicit params.
        return worker_class(
            api_base_url=config.get("api_base_url"),
            api_key=config.get("api_key", ""),
            model_id=config.get("model_id", ""),
            instruct=config.get("instruct", ""),
            return_documents=config.get("return_documents", True),
            default_task_type=config.get("default_task_type", "TEXT_TO_CODE"),
            timeout=config.get("timeout", 60),
            # 配置值为下限：调用方传更大的 top_n 时按需放宽（pipeline 按请求 top_k 放宽并自设上限）
            top_n=max(config.get("top_n", top_n), top_n),
            max_workers=config.get("max_workers", 8)
        )

    # 本地模型
    model_path = MODEL_PATH.get("reranker", {}).get(model_name, f"BAAI/{model_name}")
    return LangchainReranker(
        top_n=top_n,
        device=device,
        max_length=max_length or RERANKER_MAX_LENGTH,
        model_name_or_path=model_path
    )


def get_reranker_singleton() -> Any:
    """返回模块级 reranker 单例（B2，固定 top_n=上限值）。

    高频场景避免每请求重建 CrossEncoder/HttpReranker worker 对象。单例 top_n 固定为
    _RERANKER_SINGLETON_TOP_N（与 pipeline._RERANK_TOP_N_MAX 对齐），pipeline 调
    acompress_documents_bounded 时传 top_n=rerank_top_n 截取前 N 个。USE_RERANKER=False
    时返回 None（与 get_reranker 一致）。首次创建失败不缓存 None，下次重试。
    """
    global _reranker_singleton
    if _reranker_singleton is None:
        _reranker_singleton = get_reranker(top_n=_RERANKER_SINGLETON_TOP_N)
    return _reranker_singleton


async def acompress_documents_bounded(
    reranker_model: Any,
    documents: Sequence[Document],
    query: str,
    top_n: int | None = None,
) -> Sequence[Document]:
    """带并发信号量 + 超时的 reranker 调用包装（B1）。

    本地 CrossEncoder 路径 asyncio.to_thread 丢线程池，200 并发下 GPU 显存叠加可能 OOM；
    wait_for 防本地 torch 推理卡死无限期占用线程。top_n 截取由调用方传入（单例固定上限值，
    reranked 已按 score 降序，直接切片）。异常向上抛，由调用方 try/except fail-open 回退 RRF。
    """
    async with _RERANKER_SEMAPHORE:
        reranked = await asyncio.wait_for(
            reranker_model.acompress_documents(documents=documents, query=query),
            timeout=_RERANKER_TIMEOUT,
        )
    if top_n is not None and len(reranked) > top_n:
        reranked = reranked[:top_n]
    return reranked
