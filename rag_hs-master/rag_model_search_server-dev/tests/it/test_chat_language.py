"""问答中英文自适应端到端集成测试。

需要运行中的 QA 服务，设置以下环境变量后运行：
  QA_BASE_URL   —— QA 服务地址，如 http://127.0.0.1:7861
  QA_USER_ID    —— 测试用户 UUID（多租户隔离必填）
  QA_KB_NAMES   —— 逗号分隔的有效知识库名，如 hi3798mv310
未设置则整体 skip。

运行：
  set QA_BASE_URL=http://127.0.0.1:7861
  set QA_USER_ID=<uuid>
  set QA_KB_NAMES=hi3798mv310
  python -m pytest tests/it/test_chat_language.py -v -s
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))  # pylint: disable=no-use-sys-path-insert

import pytest

_BASE = os.environ.get("QA_BASE_URL")
_USER = os.environ.get("QA_USER_ID")
_KBS = [s.strip() for s in os.environ.get("QA_KB_NAMES", "").split(",") if s.strip()]

try:
    import httpx
    _HTTPX_OK = True
except ImportError:
    _HTTPX_OK = False

pytestmark = pytest.mark.skipif(
    not (_BASE and _USER and _KBS and _HTTPX_OK),
    reason="未设置 QA_BASE_URL / QA_USER_ID / QA_KB_NAMES 或缺 httpx，跳过端到端语言自适应测试",
)


def _sse_post(url: str, payload: dict, timeout: float = 90.0) -> list[dict]:
    """发 POST，解析 SSE `data:` 行，返回事件 dict 列表。"""
    events = []
    with httpx.Client(timeout=timeout) as c:
        with c.stream("POST", url, json=payload) as r:
            r.raise_for_status()
            for line in r.iter_lines():
                if not line or not line.startswith("data:"):
                    continue
                try:
                    events.append(json.loads(line[5:].strip()))
                except json.JSONDecodeError:
                    pass
    return events


def _answer(events: list[dict]) -> str:
    return "".join(e.get("answer", "") for e in events if "answer" in e)


def _final_docs(events: list[dict]) -> tuple[list, dict]:
    for e in reversed(events):
        if "docs" in e:
            return e["docs"], e.get("kb_stats", {})
    return [], {}


def _cjk_ratio(text: str) -> float:
    if not text:
        return 0.0
    cjk = sum(1 for c in text if "\u4e00" <= c <= "\u9fff")
    letters = sum(1 for c in text if c.isalpha())
    return cjk / letters if letters else 0.0


def _is_english(text: str) -> bool:
    return _cjk_ratio(text) < 0.2


def _is_chinese(text: str) -> bool:
    return _cjk_ratio(text) >= 0.2


@pytest.fixture
def kb_chat_url():
    return "/".join([_BASE.rstrip("/"), "kw", "chat", "multi_knowledge_base_chat"])


@pytest.fixture
def kb_payload():
    def _make(query, language="auto", stream=False):
        return {
            "query": query,
            "knowledge_base_names": _KBS,
            "user_id": _USER,
            "stream": stream,
            "language": language,
            "top_k": 3,
        }
    return _make


class TestChatLanguageAdaptive:
    @staticmethod
    def test_chinese_query_chinese_answer(kb_chat_url, kb_payload):
        events = _sse_post(kb_chat_url, kb_payload("Hi3798MV310 的启动流程是什么？"))
        ans = _answer(events)
        assert ans, "答案为空"
        assert _is_chinese(ans), f"中文提问应中文回答，CJK 占比过低: {_cjk_ratio(ans):.2f}"

    @staticmethod
    def test_english_query_english_answer(kb_chat_url, kb_payload):
        events = _sse_post(kb_chat_url, kb_payload("What is the boot process of Hi3798MV310?"))
        ans = _answer(events)
        assert ans, "答案为空"
        assert _is_english(ans), f"英文提问应英文回答，CJK 占比过高: {_cjk_ratio(ans):.2f}"

    @staticmethod
    def test_explicit_en_on_chinese_query(kb_chat_url, kb_payload):
        events = _sse_post(kb_chat_url, kb_payload("Hi3798MV310 的启动流程是什么？", language="en"))
        ans = _answer(events)
        assert _is_english(ans), "显式 en 覆盖应英文回答"

    @staticmethod
    def test_explicit_zh_on_english_query(kb_chat_url, kb_payload):
        events = _sse_post(kb_chat_url, kb_payload("What is the boot process of Hi3798MV310?", language="zh"))
        ans = _answer(events)
        assert _is_chinese(ans), "显式 zh 覆盖应中文回答"

    @staticmethod
    def test_english_query_route_reasoning_english(kb_chat_url, kb_payload):
        """英文提问时 route_reasoning 事件 content 应为英文（P1 i18n 验证）。"""
        events = _sse_post(kb_chat_url, kb_payload("What is the boot process of Hi3798MV310?", stream=True))
        reasoning = [e.get("content", "") for e in events if e.get("type") == "route_reasoning"]
        assert reasoning, "未收到 route_reasoning 事件"
        # 至少一条进行中文案为英文
        assert any("..." in r and _is_english(r) for r in reasoning) or \
               any(_is_english(r) for r in reasoning), \
               f"route_reasoning 应含英文文案: {reasoning}"
