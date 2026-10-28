"""message_repository 白盒单元测试。

覆盖 server.db.repository.message_repository 中的纯逻辑函数:
- add_message_to_db: 新增聊天记录（user_id 缺失抛 ValueError；正常路径 add→commit→返回 id）
- update_message: 更新聊天记录（m=None 时返回 None）
- get_message_by_id: 查询聊天记录
- feedback_message_to_db: 反馈记录（含 m=None 已知 bug 回归）
- filter_message: 按会话过滤记录并组装 dict 列表

DB 层全部 mock：patch server.db.session.session_scope 上下文管理器，
使其 __enter__ 返回 MagicMock session，禁止依赖真实 PostgreSQL。

注: 源码函数签名与任务描述略有出入，以源码实际行为为准:
- feedback_message_to_db(session, message_id, feedback_score, feedback_reason, user_id)
  （非 message_id/feedback_type/feedback_info）
- add_message_to_db(session, conversation_id, chat_type, query, response="",
                   message_id=None, metadata=None, user_id=None)
  （非 message_data/session_id）；且源码仅调用 session.add→session.commit，
  未调用 session.refresh。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.db.models.message_model import MessageModel
from server.db.repository.message_repository import (
    add_message_to_db,
    feedback_message_to_db,
    filter_message,
    get_message_by_id,
    update_message,
)


@pytest.fixture
def mock_session(mocker):
    """替换 with_session 装饰器依赖的 session_scope 上下文管理器。

    with_session 在 server.db.session 模块全局引用 session_scope，patch 该
    全局即可生效。session_scope() 返回一个上下文管理器，其 __enter__ 返回
    mock session（MagicMock 自动链式），__exit__ 为 no-op。
    """
    mock_scope = mocker.patch("server.db.session.session_scope")
    session = mock_scope.return_value.__enter__.return_value
    return session


# ===================== feedback_message_to_db =====================

class TestFeedbackMessageToDb:
    @staticmethod
    def test_returns_none_when_message_not_found(mock_session):
        """已知 bug 回归：m=None 时不抛 AttributeError，返回 None。

        源码已加兜底 `return m.id if m else None`，避免 m.id 触发
        AttributeError。此用例固化该回归保护。
        """
        mock_session.query.return_value.filter_by.return_value.first.return_value = None

        result = feedback_message_to_db("not_exist_msg", 5, "good", user_id="u1")

        assert result is None
        # 源码函数体内无条件调用 session.commit()，随后 with_session 装饰器再 commit 一次
        assert mock_session.commit.called

    @staticmethod
    def test_sets_feedback_and_returns_id_when_found(mock_session):
        """m 存在时设置 feedback_score/feedback_reason 并返回 m.id。"""
        _MSG1_KEY = 'msg-1'
        mock_model = MagicMock()
        mock_model.id = _MSG1_KEY
        mock_session.query.return_value.filter_by.return_value.first.return_value = mock_model

        result = feedback_message_to_db(_MSG1_KEY, 5, "very good", user_id="u1")

        assert mock_model.feedback_score == 5
        assert mock_model.feedback_reason == "very good"
        assert result == _MSG1_KEY
        assert mock_session.commit.called


# ===================== add_message_to_db =====================

class TestAddMessageToDb:
    @staticmethod
    def test_add_commit_return_id(mock_session):
        """正常新增：构造 MessageModel → session.add → session.commit → 返回 message_id。

        源码未调用 session.refresh，仅 add→commit，故不断言 refresh。
        """
        _MSG_ID_KEY = 'msg-fixed'
        result = add_message_to_db(
            "conv-1", "llm_chat", "你好", response="回答",
            message_id=_MSG_ID_KEY, user_id="u1",
        )

        assert result == _MSG_ID_KEY
        mock_session.add.assert_called_once()
        added = mock_session.add.call_args[0][0]
        assert isinstance(added, MessageModel)
        assert added.id == _MSG_ID_KEY
        assert added.conversation_id == "conv-1"
        assert added.user_id == "u1"
        assert mock_session.commit.called
        # 调用顺序：add 早于 commit（mock_calls 按调用先后记录）
        calls = mock_session.mock_calls
        add_idx = next(i for i, c in enumerate(calls) if c[0] == "add")
        commit_idx = next(i for i, c in enumerate(calls) if c[0] == "commit")
        assert add_idx < commit_idx

    @staticmethod
    def test_raises_value_error_when_user_id_missing(mock_session):
        """user_id 缺失时抛 ValueError，且不应 add。"""
        with pytest.raises(ValueError):
            add_message_to_db("conv-1", "llm_chat", "你好", user_id=None)
        mock_session.add.assert_not_called()

    @staticmethod
    def test_generates_message_id_when_not_provided(mock_session):
        """未传 message_id 时源码用 uuid4().hex 生成，返回值为 32 位 hex 串。"""
        result = add_message_to_db("conv-1", "llm_chat", "你好", user_id="u1")

        assert isinstance(result, str)
        assert len(result) == 32  # uuid4().hex 长度
        mock_session.add.assert_called_once()


# ===================== update_message =====================

class TestUpdateMessage:
    @staticmethod
    def test_updates_and_returns_id_when_found(mock_session):
        _MSG_ID_KEY = 'msg-2'
        mock_model = MagicMock()
        mock_model.id = _MSG_ID_KEY
        mock_session.query.return_value.filter_by.return_value.first.return_value = mock_model

        result = update_message(_MSG_ID_KEY, response="new resp", user_id="u1")

        assert mock_model.response == "new resp"
        assert result == _MSG_ID_KEY
        mock_session.add.assert_called_once_with(mock_model)
        assert mock_session.commit.called

    @staticmethod
    def test_returns_none_when_not_found(mock_session):
        mock_session.query.return_value.filter_by.return_value.first.return_value = None

        result = update_message("not_exist", response="x", user_id="u1")

        assert result is None
        mock_session.add.assert_not_called()


# ===================== get_message_by_id =====================

class TestGetMessageById:
    @staticmethod
    def test_returns_message(mock_session):
        mock_model = MagicMock()
        mock_session.query.return_value.filter_by.return_value.first.return_value = mock_model

        result = get_message_by_id("msg-3")

        assert result is mock_model


# ===================== filter_message =====================

class TestFilterMessage:
    @staticmethod
    def test_returns_list_of_dicts(mock_session):
        """filter_message 将 MessageModel 列表组装为 dict 列表。

        链式调用 query().filter_by().filter().order_by().limit().all() 全部
        经 MagicMock 链式返回，.all() 返回值由测试配置。filter/order_by 的
        入参（MessageModel.response != '' 与 create_time.desc()）为纯 SQLAlchemy
        表达式构造，不触发真实 DB。
        """
        _USER_ID_KEY = 'u1'
        m1 = MagicMock()
        m1.query = "q1"
        m1.response = "r1"
        m1.meta_data = {"k": "v"}
        m1.user_id = _USER_ID_KEY
        m2 = MagicMock()
        m2.query = "q2"
        m2.response = "r2"
        m2.meta_data = {}
        m2.user_id = _USER_ID_KEY
        chain = (mock_session.query.return_value.filter_by.return_value
                 .filter.return_value.order_by.return_value.limit.return_value.all)
        chain.return_value = [m1, m2]

        data = filter_message("conv-1", limit=5, user_id=_USER_ID_KEY)

        assert data == [
            {"query": "q1", "response": "r1", "metadata": {"k": "v"}, "user_id": _USER_ID_KEY},
            {"query": "q2", "response": "r2", "metadata": {}, "user_id": _USER_ID_KEY},
        ]
