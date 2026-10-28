"""chat_feedback 接口的白盒单元测试。

覆盖 server.chat.feedback.chat_feedback(message_id, user_id, score, reason):
- 正常路径: patch feedback_message_to_db 成功 → BaseResponse(code=200, msg 含 message_id)
- 异常路径: patch feedback_message_to_db 抛 Exception → BaseResponse(code=500, msg 含 "反馈聊天记录出错")

DB 层全部 mock,禁止真实连接 PostgreSQL。源码:
    def chat_feedback(message_id, user_id, score, reason):
        try:
            feedback_message_to_db(message_id, score, reason, user_id=user_id)
        except Exception as e:
            msg = f"反馈聊天记录出错： {e}"
            return BaseResponse(code=500, msg=msg)
        return BaseResponse(code=200, msg=f"已反馈聊天记录 {message_id}")
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

# 将项目根目录加入 sys.path,确保可 import server、configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import patch

import pytest

from server.chat.feedback import chat_feedback
from server.utils import BaseResponse


class TestChatFeedback:
    """chat_feedback 的正常/异常两条路径。"""

    @staticmethod
    def test_success_returns_200():
        """patch feedback_message_to_db 正常 → BaseResponse(code=200)。

        验证:
        - feedback_message_to_db 以 (message_id, score, reason, user_id=...) 形参调用
        - 返回 BaseResponse,code=200,msg 含 message_id
        """
        _MSG1_KEY = 'msg-1'
        with patch("server.chat.feedback.feedback_message_to_db") as mock_fn:
            result = chat_feedback(message_id=_MSG1_KEY, user_id="u1", score=80, reason="good")

        mock_fn.assert_called_once_with(_MSG1_KEY, 80, "good", user_id="u1")
        assert isinstance(result, BaseResponse)
        assert result.code == 200
        assert _MSG1_KEY in result.msg

    @staticmethod
    def test_exception_returns_500():
        """patch feedback_message_to_db 抛 Exception → BaseResponse(code=500, msg 含 "反馈聊天记录出错")。"""
        with patch("server.chat.feedback.feedback_message_to_db",
                   side_effect=RuntimeError("db down")):
            result = chat_feedback(message_id="msg-2", user_id="u2", score=50, reason="bad")

        assert isinstance(result, BaseResponse)
        assert result.code == 500
        assert "反馈聊天记录出错" in result.msg
        # 异常信息也应被拼接进 msg
        assert "db down" in result.msg
