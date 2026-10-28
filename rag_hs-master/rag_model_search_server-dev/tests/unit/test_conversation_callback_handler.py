"""ConversationCallbackHandler 白盒单元测试。

覆盖 server.callback_handler.conversation_callback_handler.ConversationCallbackHandler:
- TestInit: 验证字段赋值(conversation_id/message_id/chat_type/query/user_id/start_at=None)
- TestAlwaysVerbose: always_verbose 返回 True
- TestOnLlmStart: no-op
- TestOnLlmEnd: patch update_message,构造 mock LLMResult(response.generations[0][0].text
  用 SimpleNamespace),断言 update_message 以正确参数被调用一次

update_message 通过 patch server.callback_handler.conversation_callback_handler.update_message 注入,
禁止真实 DB 写入。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.callback_handler.conversation_callback_handler import ConversationCallbackHandler


class TestInit:
    """验证 __init__ 字段赋值。"""

    @staticmethod
    def test_fields_assigned():
        handler = ConversationCallbackHandler(
            conversation_id="conv-1",
            message_id="msg-1",
            chat_type="llm_chat",
            query="你好",
            user_id="u1",
        )
        assert handler.conversation_id == "conv-1"
        assert handler.message_id == "msg-1"
        assert handler.chat_type == "llm_chat"
        assert handler.query == "你好"
        assert handler.user_id == "u1"
        assert handler.start_at is None


class TestAlwaysVerbose:
    """always_verbose 恒为 True。"""

    @staticmethod
    def test_always_verbose_returns_true():
        handler = ConversationCallbackHandler("c", "m", "t", "q", "u")
        assert handler.always_verbose is True


class TestOnLlmStart:
    """on_llm_start 是 no-op。"""

    @staticmethod
    def test_on_llm_start_is_noop():
        handler = ConversationCallbackHandler("c", "m", "t", "q", "u")
        handler.on_llm_start({"foo": "bar"}, ["prompt1", "prompt2"])
        # 无异常即通过


class TestOnLlmEnd:
    """on_llm_end: 提取 response.generations[0][0].text → 调 update_message。"""

    @staticmethod
    def test_on_llm_end_calls_update_message_once():
        """patch update_message,构造 mock response,断言以正确参数调用一次。"""
        handler = ConversationCallbackHandler(
            conversation_id="conv-1",
            message_id="msg-1",
            chat_type="llm_chat",
            query="你好",
            user_id="u1",
        )
        # 构造 mock LLMResult:response.generations[0][0].text
        mock_response = SimpleNamespace(
            generations=[[SimpleNamespace(text="这是生成的回答")]]
        )

        with patch("server.callback_handler.conversation_callback_handler.update_message") as mock_update:
            handler.on_llm_end(mock_response)

        mock_update.assert_called_once_with("msg-1", "这是生成的回答", user_id="u1")

    @staticmethod
    def test_on_llm_end_with_magicmock_response():
        """用 MagicMock 构造 response,验证 text 提取与 update_message 调用。"""
        handler = ConversationCallbackHandler("c", "msg-2", "t", "q", "u2")

        mock_generation = MagicMock()
        mock_generation.text = "mocked answer"
        mock_response = MagicMock()
        mock_response.generations = [[mock_generation]]

        with patch("server.callback_handler.conversation_callback_handler.update_message") as mock_update:
            handler.on_llm_end(mock_response)

        mock_update.assert_called_once_with("msg-2", "mocked answer", user_id="u2")
