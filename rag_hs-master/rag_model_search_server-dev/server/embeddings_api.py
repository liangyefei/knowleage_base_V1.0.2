import hashlib
import json
import threading

from fastapi import Body
from langchain.docstore.document import Document

from configs import EMBEDDING_API_CONFIG, EMBEDDING_MODEL, logger
from server.utils import BaseResponse, list_embed_models

# Embedding Worker 实例缓存：按 embed_model 复用，避免每次请求重新初始化（OpenAI 客户端连接池复用）
_EMBEDDING_WORKER_CACHE: dict[str, object] = {}
_EMBEDDING_WORKER_CACHE_LOCK = threading.Lock()

# Worker 配置指纹：关键配置项的稳定 hash，用于缓存键。
# 配置变更（api_key/endpoint/model_id 等）→ 指纹变 → 缓存未命中 → 重建 worker。
# 纯性能优化项（embedding_dim 等不影响连接）可按需剔除，这里保留全量以最大化正确性。
_EMBEDDING_WORKER_FINGERPRINT_KEYS = (
    "api_base_url", "api_key", "model_id", "embedding_dim",
    "dimensions", "encoding_format", "timeout", "max_workers", "batch_size",
)


def _config_fingerprint(config: dict) -> str:
    """计算 embedding worker 配置指纹（8 位 md5）。"""
    sig = {k: config.get(k) for k in _EMBEDDING_WORKER_FINGERPRINT_KEYS}
    raw = json.dumps(sig, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.md5(raw.encode("utf-8")).hexdigest()[:8]


def _cache_key(embed_model: str, config: dict) -> str:
    """缓存键 = embed_model:配置指纹。配置变更 → 新键 → 重建。"""
    return f"{embed_model}:{_config_fingerprint(config)}"


def _get_embedding_worker_cached(embed_model: str):
    """
    按 embed_model + 配置指纹 获取缓存的 Embedding Worker 实例。
    缓存命中直接返回；未命中则从 EMBEDDING_API_CONFIG 读配置实例化并写入缓存。
    配置变更（指纹变）会自然落到新缓存键，旧 worker 随 GC 回收。
    若 get_embedding_worker 返回 None（未注册），返回 None。
    """
    config = EMBEDDING_API_CONFIG[embed_model]
    key = _cache_key(embed_model, config)

    # 快速路径：缓存命中直接返回（不加锁）
    cached = _EMBEDDING_WORKER_CACHE.get(key)
    if cached is not None:
        return cached

    with _EMBEDDING_WORKER_CACHE_LOCK:
        # 双重检查：防止多个线程同时进入时重复实例化
        cached = _EMBEDDING_WORKER_CACHE.get(key)
        if cached is not None:
            return cached

        from server.embedding_workers import get_embedding_worker
        worker_class = get_embedding_worker(embed_model)
        if worker_class is None:
            return None

        worker = worker_class(
            api_base_url=config.get("api_base_url"),
            api_key=config.get("api_key"),
            model_id=config.get("model_id"),
            embedding_dim=config.get("embedding_dim", 1024),
            dimensions=config.get("dimensions"),
            encoding_format=config.get("encoding_format", "float"),
            timeout=config.get("timeout", 60),
            max_workers=config.get("max_workers", 4),
            batch_size=config.get("batch_size", 10),
        )
        _EMBEDDING_WORKER_CACHE[key] = worker
        return worker


def clear_embedding_worker_cache(embed_model: str = None) -> int:
    """清空 embedding worker 缓存。

    Args:
        embed_model: 指定时只清该模型的所有指纹缓存；None 时清空全部。

    Returns:
        清除的缓存项数。

    用途：
        - 运维热刷新：配置变更后无需重启服务即可重建 worker；
        - 测试隔离：tests/conftest.py autouse fixture 在每个测试后调用，
          避免 _EMBEDDING_WORKER_CACHE 全局缓存导致测试间污染。
    """
    with _EMBEDDING_WORKER_CACHE_LOCK:
        if embed_model is None:
            n = len(_EMBEDDING_WORKER_CACHE)
            _EMBEDDING_WORKER_CACHE.clear()
            return n
        prefix = f"{embed_model}:"
        keys_to_del = [k for k in _EMBEDDING_WORKER_CACHE if k.startswith(prefix)]
        for k in keys_to_del:
            _EMBEDDING_WORKER_CACHE.pop(k, None)
        return len(keys_to_del)


def embed_texts(
        texts: list[str],
        embed_model: str = EMBEDDING_MODEL,
        to_query: bool = False,
) -> BaseResponse:
    """
    对文本进行向量化。返回数据格式：BaseResponse(data=List[List[float]])
    TODO: 也许需要加入缓存机制，减少 token 消耗
    """
    try:
        if embed_model in list_embed_models():  # 使用本地Embeddings模型
            from server.utils import load_local_embeddings

            embeddings = load_local_embeddings(model=embed_model)
            return BaseResponse(data=embeddings.embed_documents(texts))

        if embed_model in EMBEDDING_API_CONFIG:  # 使用在线 API Embeddings 模型
            try:
                worker = _get_embedding_worker_cached(embed_model)
                if worker is None:
                    return BaseResponse(code=500, msg=f"找不到 embedding worker: {embed_model}")
                prompt_name = "query" if to_query else "document"
                embeddings = worker.embed_texts_with_prompt(texts, prompt_name)
                return BaseResponse(data=embeddings)
            except Exception as e:
                logger.error(f"在线 embedding 失败: {e}")
                return BaseResponse(code=500, msg=f"文本向量化错误：{e}")

        return BaseResponse(code=500, msg=f"指定的模型 {embed_model} 不支持 Embeddings 功能。")
    except Exception as e:
        logger.error(e)
        return BaseResponse(code=500, msg=f"文本向量化过程中出现错误：{e}")


async def aembed_texts(
    texts: list[str],
    embed_model: str = EMBEDDING_MODEL,
    to_query: bool = False,
) -> BaseResponse:
    """
    对文本进行向量化。返回数据格式：BaseResponse(data=List[List[float]])
    """
    try:
        if embed_model in list_embed_models():  # 使用本地Embeddings模型
            from server.utils import load_local_embeddings

            embeddings = load_local_embeddings(model=embed_model)
            return BaseResponse(data=await embeddings.aembed_documents(texts))

        if embed_model in EMBEDDING_API_CONFIG:  # 使用在线 API Embeddings 模型
            try:
                worker = _get_embedding_worker_cached(embed_model)
                if worker is None:
                    return BaseResponse(code=500, msg=f"找不到 embedding worker: {embed_model}")
                prompt_name = "query" if to_query else "document"
                embeddings = await worker.aembed_texts_with_prompt(texts, prompt_name)
                return BaseResponse(data=embeddings)
            except Exception as e:
                logger.error(f"在线 embedding 失败: {e}")
                return BaseResponse(code=500, msg=f"文本向量化错误：{e}")

        return BaseResponse(code=500, msg=f"指定的模型 {embed_model} 不支持 Embeddings 功能。")
    except Exception as e:
        logger.error(e)
        return BaseResponse(code=500, msg=f"文本向量化过程中出现错误：{e}")


def embed_texts_endpoint(
        texts: list[str] = Body(..., description="要嵌入的文本列表", examples=[["Hi3798MV310", "海思机顶盒芯片"]]),
        embed_model: str = Body(EMBEDDING_MODEL,
                                description="使用的嵌入模型，支持本地部署的 Embedding 模型（如 qwen3-embedding-api）"),
        to_query: bool = Body(False, description="向量是否用于查询。部分模型对存储/查询向量做了区分优化"),
) -> BaseResponse:
    """
    对文本进行向量化，返回 BaseResponse(data=List[List[float]])
    """
    return embed_texts(texts=texts, embed_model=embed_model, to_query=to_query)


def embed_documents(
        docs: list[Document],
        embed_model: str = EMBEDDING_MODEL,
        to_query: bool = False,
) -> dict:
    """
    将 List[Document] 向量化，转化为 VectorStore.add_embeddings 可以接受的参数
    """
    texts = [x.page_content for x in docs]
    metadatas = [x.metadata for x in docs]
    embeddings = embed_texts(texts=texts, embed_model=embed_model, to_query=to_query).data
    if embeddings is not None:
        return {
            "texts": texts,
            "embeddings": embeddings,
            "metadatas": metadatas,
        }
    return None
