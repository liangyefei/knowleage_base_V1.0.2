#!/usr/bin/env python3
# @Time : 2026/8/20
# @Author : Hetao
# @File : [Code] --> callback_utils.py
# @Software: PyCharm
# @Function: Kafka 回调投递公共逻辑。提取自 processing_engine.py 与 kafka_consumer.py，
#           消除两处重复定义，保证回调契约（NO_CALLBACK_EVENTS / payload 形状）单点维护。
#           契约来源：kafka-structure.md 第 2.2 节 "是否回调: 否"、第 3.3 节回调信封格式。
import json
from datetime import datetime, timezone, timedelta

# 模型端无副作用、无需回调投递的 eventType
# 契约来源：kafka-structure.md 第 2.2 节 "是否回调: 否"
# - KB_UPDATED：元信息更新，模型端无对应动作
# - DOCUMENT_ARCHIVED：归档下线，模型端无对应副作用（不投递回调）
NO_CALLBACK_EVENTS = {"KB_UPDATED", "DOCUMENT_ARCHIVED"}


# 北京时间时区（UTC+8）。回调字段 callbackedAt/processedAt/failedAt 统一用北京时间，
# 避免后端按字面值解析时差 8 小时
_BEIJING_TZ = timezone(timedelta(hours=8))


def _now_iso() -> str:
    """当前北京时间（UTC+8）的 ISO-8601 字符串。"""
    return datetime.now(_BEIJING_TZ).isoformat()


def _format_duration_hms(seconds: float) -> str:
    """把秒数格式化为 HH:MM:SS 字符串。

    Args:
        seconds: 时长（秒，可为小数）

    Returns:
        ``HH:MM:SS`` 格式字符串，例如 ``02:15:30``。超过 99 小时也用 HHH: 形式扩展。
    """
    total = int(max(0.0, seconds))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"


def _extract_chunk_count(data) -> int:
    """从任务返回的 data 字段尽量提取 chunk 数量，提取不到返回 0（不抛异常）。"""
    try:
        if isinstance(data, dict):
            for k in ("chunkCount", "chunk_count", "total"):
                v = data.get(k)
                if isinstance(v, (int, float)) and v >= 0:
                    return int(v)
            # dict 内首个元素为 dict 的 list 字段，递归求和
            for v in data.values():
                if isinstance(v, list) and v and isinstance(v[0], dict):
                    return _extract_chunk_count(v)
            return 0
        if isinstance(data, list):
            return sum(_extract_chunk_count(x) for x in data)
        return 0
    except Exception:
        return 0


def build_completed_payload(data, metrics: dict = None) -> dict:
    """构造 PROCESSING_COMPLETED 回调 payload。

    Args:
        data: 任务返回的 data 字段（用于提取 chunkCount），无 data 时传 None。
        metrics: 任务耗时/回调时间等指标。若提供则合入 payload 顶层，字段：
            - totalDuration: 从消费 Kafka 消息到回调的总耗时（HH:MM:SS 格式）
            - callbackedAt: 回调时间点（ISO-8601 北京时间 UTC+8）

    契约来源：kafka-structure.md 第 3.2 节 PROCESSING_COMPLETED —— 仅 processedAt + chunkCount。
    不加 error 字段（契约未定义；多字段可能触发后端严格 schema 解析失败）。
    """
    payload = {
        "processedAt": _now_iso(),
        "chunkCount": _extract_chunk_count(data),
    }
    if metrics:
        payload.update(metrics)
    return payload


def build_failed_payload(code, msg, metrics: dict = None) -> dict:
    """构造 PROCESSING_FAILED 回调 payload。

    Args:
        code: 错误码（任务返回的 code，如 500）。
        msg: 错误信息（任务返回的 msg；为空时使用默认错误信息）。
        metrics: 任务耗时/回调时间等指标，字段同 build_completed_payload。
    """
    error = msg if msg else f"任务执行失败 (code={code})"
    payload = {
        "failedAt": _now_iso(),
        "errorCode": str(code),
        "error": error,
    }
    if metrics:
        payload.update(metrics)
    return payload


def safe_json_dumps(value, **kwargs) -> str:
    """序列化为 JSON 字符串并对花括号转义，避免 loguru 把 ``{...}`` 当 format 占位符。

    loguru 默认对日志消息字符串调 ``str.format()``，若消息含 ``{"messageId": "..."}`` 这种
    JSON 片段，``{"messageId"`` 会被当成字段占位符解析，抛 ``KeyError('"messageId"')``
    （key 是带双引号的字符串），原异常信息被掩盖，调试时只看到 ``error='"messageId"'``。

    本函数在 ``json.dumps`` 输出基础上做 ``{ -> {{`` 与 ``} -> }}`` 转义，
    loguru format 时会把 ``{{`` / ``}}`` 还原成字面 ``{`` / ``}``，不再误判占位符。

    Args:
        value: 待序列化的对象（dict/list/任意可 JSON 序列化对象）。
        **kwargs: 透传给 ``json.dumps``，如 ``ensure_ascii=False``、``default=str``。

    Returns:
        转义后的 JSON 字符串，可直接拼入 logger 调用的消息文本。
    """
    kwargs.setdefault("ensure_ascii", False)
    kwargs.setdefault("default", str)
    return json.dumps(value, **kwargs).replace("{", "{{").replace("}", "}}")


def safe_str(obj) -> str:
    """对任意对象的 ``str()`` 输出做花括号转义，避免 loguru format 坑。

    适用于异常对象、配置 dict、HTTP 响应体等 ``str()`` 可能含 ``{`` 字符的对象
    （如 ``KafkaConfigurationError: Unrecognized configs: {foo: bar}``）。

    Args:
        obj: 任意对象（异常、dict、字符串等）。

    Returns:
        ``str(obj)`` 并对 ``{`` / ``}`` 转义后的字符串。
    """
    return str(obj).replace("{", "{{").replace("}", "}}")


def safe_repr(obj) -> str:
    """对任意对象的 ``repr()`` 输出做花括号转义，避免 loguru format 坑。

    适用于 raw bytes、复杂数据结构等需要 ``repr`` 才能看清类型的对象
    （如 ``b'{"messageId": "..."}'`` 的 repr 会保留 ``{`` 字符）。

    Args:
        obj: 任意对象。

    Returns:
        ``repr(obj)`` 并对 ``{`` / ``}`` 转义后的字符串。
    """
    return repr(obj).replace("{", "{{").replace("}", "}}")
