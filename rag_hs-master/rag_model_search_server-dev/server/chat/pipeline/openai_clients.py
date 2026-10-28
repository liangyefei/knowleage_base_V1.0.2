"""AsyncOpenAI 客户端缓存与预解析模型配置。

按 api_key+base_url 缓存 AsyncOpenAI 实例，复用底层 httpx 连接池。
"""
import httpx
from openai import AsyncOpenAI

from configs import LLM_MODELS
from server.chat.pipeline.constants import _ROUTER_MODEL_NAME
from server.utils import get_model_worker_config

# 模块级 AsyncOpenAI 客户端缓存（复用连接池，避免每次请求重建）
_openai_client_cache: dict = {}


def _get_openai_client(api_key: str, base_url: str) -> AsyncOpenAI:
    """按 api_key+base_url 缓存 AsyncOpenAI 实例，复用底层 httpx 连接池。

    显式约束 httpx 连接池（与 P3-1 embedding 基类 / aliyun _get_aclient 一致）：
    默认池上限过高，高并发突发下半死连接占位会导致后续请求永久阻塞在连接
    acquire 信号量上（该阻塞不受 SDK timeout 约束）。收窄到 50 + keepalive_expiry=30
    （短于多数防火墙/NAT 空闲断开阈值），配合 _stream_llm_response 的 wait_for 兜底，
    避免池被打坏后无法恢复。max_retries=2 对齐 aliyun embedding。
    """
    _cache_key = f"{api_key}:{base_url}"
    _client = _openai_client_cache.get(_cache_key)
    if _client is None:
        _client = AsyncOpenAI(
            api_key=api_key,
            base_url=base_url,
            max_retries=2,
            http_client=httpx.AsyncClient(
                limits=httpx.Limits(
                    max_connections=50,
                    max_keepalive_connections=10,
                    keepalive_expiry=30.0,
                ),
            ),
        )
        _openai_client_cache[_cache_key] = _client
    return _client


# 模块级预解析配置（启动时即加载，无需运行时判断）
_main_model_cfg = get_model_worker_config(LLM_MODELS[0])
_router_model_cfg = get_model_worker_config(_ROUTER_MODEL_NAME)

# 预创建 AsyncOpenAI 客户端（复用连接池）
_main_openai_client = _get_openai_client(_main_model_cfg.get("api_key"), _main_model_cfg.get("api_base_url"))
_router_openai_client = _get_openai_client(_router_model_cfg.get("api_key"), _router_model_cfg.get("api_base_url"))
