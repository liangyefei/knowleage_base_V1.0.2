"""ConversationBufferDBMemory 白盒单元测试。

覆盖 server.memory.conversation_db_buffer_memory.ConversationBufferDBMemory:
- TestMemoryVariables: memory_variables 返回 [self.memory_key]
- TestSaveContext / TestClear: no-op(pass),不抛异常
- TestLoadMemoryVariables: patch filter_message 返回固定记录,mock llm.get_num_tokens 小于
  max_token_limit;return_messages=True→直接返回 buffer(消息列表),False→get_buffer_string(字符串)
- TestBuffer: patch filter_message 返回多条记录(Human/AIMessage 对),get_num_tokens 递减
  验证 pop(0) 剪枝;始终超限→清空返回 [];空消息→[]

filter_message 通过 patch server.memory.conversation_db_buffer_memory.filter_message 注入;
llm 用 MagicMock;实例用 pydantic.construct() 绕过 BaseLanguageModel 类型校验。
禁止真实连接 PostgreSQL。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
from langchain.schema import AIMessage, HumanMessage

from server.memory.conversation_db_buffer_memory import ConversationBufferDBMemory


def _make_memory(return_messages=False, max_token_limit=100, llm=None, message_limit=10,
                 memory_key="history"):
    """用 construct() 绕过 BaseLanguageModel 类型校验,构造测试用实例。"""
    mock_llm = llm or MagicMock()
    return ConversationBufferDBMemory.construct(
        conversation_id="conv1",
        user_id="u1",
        llm=mock_llm,
        memory_key=memory_key,
        max_token_limit=max_token_limit,
        message_limit=message_limit,
        return_messages=return_messages,
    )


class TestMemoryVariables:
    @staticmethod
    def test_returns_memory_key_list():
        memory = _make_memory(memory_key="history")
        assert memory.memory_variables == ["history"]

    @staticmethod
    def test_custom_memory_key():
        memory = _make_memory(memory_key="chat_history")
        assert memory.memory_variables == ["chat_history"]


class TestSaveContext:
    @staticmethod
    def test_save_context_is_noop():
        memory = _make_memory()
        memory.save_context({"input": "q"}, {"output": "r"})


class TestClear:
    @staticmethod
    def test_clear_is_noop():
        memory = _make_memory()
        memory.clear()


class TestLoadMemoryVariables:
    @staticmethod
    def test_return_messages_true_returns_buffer_list():
        mock_llm = MagicMock()
        mock_llm.get_num_tokens.return_value = 10
        memory = _make_memory(return_messages=True, max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[{"query": "q1", "response": "r1"}]):
            result = memory.load_memory_variables({})

        assert "history" in result
        buffer = result["history"]
        assert isinstance(buffer, list)
        assert len(buffer) == 2
        assert isinstance(buffer[0], HumanMessage)
        assert buffer[0].content == "q1"
        assert isinstance(buffer[1], AIMessage)
        assert buffer[1].content == "r1"

    @staticmethod
    def test_return_messages_false_returns_buffer_string():
        mock_llm = MagicMock()
        mock_llm.get_num_tokens.return_value = 10
        memory = _make_memory(return_messages=False, max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[{"query": "q1", "response": "r1"}]):
            result = memory.load_memory_variables({})

        assert "history" in result
        buffer = result["history"]
        assert isinstance(buffer, str)
        assert "q1" in buffer
        assert "r1" in buffer
        assert "Human" in buffer
        assert "Assistant" in buffer


class TestBuffer:
    @staticmethod
    def test_pruning_pops_oldest_until_under_limit():
        mock_llm = MagicMock()
        mock_llm.get_num_tokens.side_effect = [200, 150, 50]
        memory = _make_memory(max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[{"query": "q2", "response": "r2"},
                                 {"query": "q1", "response": "r1"}]):
            buffer = memory.buffer

        assert len(buffer) == 2
        assert isinstance(buffer[0], HumanMessage)
        assert buffer[0].content == "q2"
        assert isinstance(buffer[1], AIMessage)
        assert buffer[1].content == "r2"
        assert mock_llm.get_num_tokens.call_count == 3

    @staticmethod
    def test_always_over_limit_clears_all():
        mock_llm = MagicMock()
        mock_llm.get_num_tokens.return_value = 200
        memory = _make_memory(max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[{"query": "q1", "response": "r1"}]):
            buffer = memory.buffer

        assert buffer == []

    @staticmethod
    def test_empty_messages_returns_empty():
        mock_llm = MagicMock()
        memory = _make_memory(max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[]):
            buffer = memory.buffer

        assert buffer == []
        mock_llm.get_num_tokens.assert_not_called()

    @staticmethod
    def test_under_limit_no_pruning():
        mock_llm = MagicMock()
        mock_llm.get_num_tokens.return_value = 50
        memory = _make_memory(max_token_limit=100, llm=mock_llm)

        with patch("server.memory.conversation_db_buffer_memory.filter_message",
                   return_value=[{"query": "q2", "response": "r2"},
                                 {"query": "q1", "response": "r1"}]):
            buffer = memory.buffer

        assert len(buffer) == 4
        assert buffer[0].content == "q1"
        assert buffer[1].content == "r1"
        assert buffer[2].content == "q2"
        assert buffer[3].content == "r2"
        assert mock_llm.get_num_tokens.call_count == 1
