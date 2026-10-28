"""阿里云 DashScope 调用可观测性工具：统一提取 requestID + 规范日志。

所有阿里云模型调用（路由 LLM / 主 LLM / 记忆抽取 / Embedding / VLM / Reranker）
在调用 DashScope 后，用本模块提取 requestID 并按统一格式记录日志，便于限流、
超时、429 等问题发生时向阿里云客服提供 requestID 排查。

适配三类 SDK 的响应/异常对象（取法按优先级）：
  - dashscope SDK 响应（TextReRank 等）：.request_id（响应体标准字段）
  - OpenAI SDK 异常（APIStatusError 等）：.request_id（4xx/5xx 时 SDK 从响应头填充；
    APIConnectionError/APITimeoutError 无响应故为 None）
  - OpenAI SDK 非流式响应（LegacyAPIResponse，经 with_raw_response.create 取得）：
    .headers["x-request-id"]（openai 1.14.3 普通 create 返回的 ChatCompletion /
    CreateEmbeddingResponse 已被 pydantic 剥离 raw headers，故非流式调用一律改用
    with_raw_response.create 再 .parse()）
  - OpenAI SDK 流式响应（AsyncStream / Stream）：.response.headers["x-request-id"]
    （AsyncStream.response 为公开 httpx Response 属性，create 返回时 header 即到，
     无需等 chunk——排队式限流场景下 chunk 永不来但 req_id 仍可取到）
  - httpx 直调响应（VLM）：.headers["x-request-id"]

统一日志格式（固定前缀 [dashscope]，便于 grep）：
    [dashscope] model=<模型> phase=<阶段> req_id=<ID|None> [k=v ...]
"""
from typing import Any

from configs import logger

# DashScope OpenAI 兼容模式返回的 requestID 响应头名
_DASHSCOPE_REQ_ID_HEADER = "x-request-id"


def extract_aliyun_request_id(obj: Any) -> str | None:
    """从各类响应/异常对象中提取阿里云 requestID，提取不到返回 None。

    适配顺序（先 dashscope/异常，再 OpenAI with_raw_response，最后流式/httpx）：
      1. dashscope SDK 响应：obj.request_id（无下划线，响应体字段）
      2. OpenAI SDK 异常 APIStatusError：obj.request_id（无下划线，4xx/5xx 时有）
      3. OpenAI SDK 非流式 with_raw_response 响应（LegacyAPIResponse）：
         obj.headers["x-request-id"]（openai 1.14.3 的 _request_id property 不存在，
         故非流式调用点统一改用 with_raw_response.create，由本步从 headers 提取）
      4. OpenAI SDK 流式响应（AsyncStream）：obj.response.headers["x-request-id"]
         httpx 直调响应（VLM）：obj.headers["x-request-id"]
    """
    if obj is None:
        return None

    # 1/2. dashscope SDK 响应 / OpenAI SDK 异常：request_id（不带下划线）
    rid = getattr(obj, "request_id", None)
    if rid:
        return str(rid)

    # 3. OpenAI SDK 非流式响应：_request_id（带下划线 property）
    rid = getattr(obj, "_request_id", None)
    if rid:
        return str(rid)

    # 4. OpenAI SDK 流式响应（有 .response）或 httpx Response（自身即 response），
    #    统一从 headers 取 x-request-id
    resp = getattr(obj, "response", None)
    headers = getattr(resp, "headers", None) if resp is not None else getattr(obj, "headers", None)
    if headers is not None:
        try:
            rid = headers.get(_DASHSCOPE_REQ_ID_HEADER)
        except AttributeError:
            rid = None
        if rid:
            return str(rid)

    return None


def fmt_req_id(req_id: str | None) -> str:
    """格式化 req_id 片段，便于拼接到现有日志行。

    返回形如 " req_id=abc123" 或 " req_id=None"（前导空格，方便直接拼接）。
    """
    return f" req_id={req_id}" if req_id else " req_id=None"


def log_dashscope(
    model: str,
    phase: str,
    req_id: str | None = None,
    level: str = "info",
    **fields: Any,
) -> None:
    """统一输出阿里云调用日志。

    Args:
        model: 模型名（qwen-flash / deepseek-v4-pro / qwen3.7-plus / qwen3.7-text-embedding /
              qwen3.7-text-rerank 等）
        phase: 阶段标识，建议命名 <调用点>_<动作>_<结果>，
               如 route_create_ok / route_timeout / llm_stream_start /
               llm_chunk_idle_timeout / embed_done / vlm_error / rerank_non_200
        req_id: requestID，None 时显式记 req_id=None（便于区分"没取到"与"没记"）
        level: 日志级别 info/debug/warning/error，默认 info
        **fields: 额外键值对（耗时/状态码/原因等），None 值自动跳过
    """
    rid_str = f"req_id={req_id}" if req_id else "req_id=None"
    extra = "".join(f" {k}={v}" for k, v in fields.items() if v is not None)
    msg = f"[dashscope] model={model} phase={phase} {rid_str}{extra}"
    getattr(logger, level, logger.info)(msg)
