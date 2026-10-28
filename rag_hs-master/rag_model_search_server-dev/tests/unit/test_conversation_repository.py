"""conversation_repository 白盒单元测试。

覆盖 server.db.repository.conversation_repository 的 5 个函数:
- add_conversation_to_db: 新增会话(user_id 缺失抛 ValueError；conversation_id
  缺失自动生成 uuid.uuid4().hex(32 位)；显式 id 透传；session.add 入参为
  ConversationModel 实例且 id/chat_type/name/user_id 字段正确)
- list_conversation: 按 chat_type/user_id 可选过滤并组装 dict 列表(无过滤
  query.order_by.all、单项过滤 query.filter.order_by.all、两项过滤
  query.filter.filter.order_by.all、空结果→[])
- get_conversation: 命中返回 dict、未命中返回 None
- update_conversation: 命中设 c.name 返回 c.id、未命中返回 None
- delete_conversation: 归属校验失败(filter_by.first=None)直接 return None 且
  两次 delete 未触发；校验通过触发 message+conversation 两次 delete
  (synchronize_session=False)

DB 层全部 mock: patch server.db.session.session_scope 上下文管理器,使其
__enter__ 返回 MagicMock session,禁止依赖真实 PostgreSQL。外部调用不传
session 参数(由 with_session 装饰器注入)。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

import pytest

from server.db.models.conversation_model import ConversationModel
from server.db.repository.conversation_repository import (
    add_conversation_to_db,
    delete_conversation,
    get_conversation,
    list_conversation,
    update_conversation,
)


@pytest.fixture
def mock_session(mocker):
    """替换 with_session 装饰器依赖的 session_scope 上下文管理器。

    with_session 在 server.db.session 模块全局引用 session_scope,patch 该
    全局即可生效。session_scope() 返回上下文管理器,其 __enter__ 返回
    mock session(MagicMock 自动链式),__exit__ 为 no-op。
    """
    mock_scope = mocker.patch("server.db.session.session_scope")
    session = mock_scope.return_value.__enter__.return_value
    return session


# ===================== add_conversation_to_db =====================

class TestAddConversationToDb:
    @staticmethod
    def test_adds_conversation_model_with_correct_fields(mock_session):
        """session.add 入参为 ConversationModel 实例,且关键字段透传正确。"""
        add_conversation_to_db(
            chat_type="llm_chat", name="my conv",
            conversation_id="conv-1", user_id="u1",
        )

        mock_session.add.assert_called_once()
        added = mock_session.add.call_args[0][0]
        assert isinstance(added, ConversationModel)
        assert added.id == "conv-1"
        assert added.chat_type == "llm_chat"
        assert added.name == "my conv"
        assert added.user_id == "u1"

    @staticmethod
    def test_generates_conversation_id_when_none(mock_session):
        """conversation_id 缺失时源码用 uuid.uuid4().hex 生成 32 位 hex 串。"""
        result = add_conversation_to_db(chat_type="llm_chat", name="n", user_id="u1")

        assert isinstance(result, str)
        assert len(result) == 32
        # 校验为合法 hex(16 进制)
        int(result, 16)
        mock_session.add.assert_called_once()

    @staticmethod
    def test_passes_explicit_conversation_id(mock_session):
        """显式传入 conversation_id 时透传,返回值即该 id。"""
        result = add_conversation_to_db(
            chat_type="llm_chat", name="n",
            conversation_id="conv-fixed", user_id="u1",
        )
        assert result == "conv-fixed"

    @pytest.mark.parametrize("bad_user_id", [None, "", False])
    @staticmethod
    def test_raises_value_error_when_user_id_missing(mock_session, bad_user_id):
        """user_id 为 None/""/False 时源码 `if not user_id` 触发 ValueError,
        且在构造 ConversationModel 之前抛出,故 session.add 未被调用。"""
        with pytest.raises(ValueError):
            add_conversation_to_db(
                chat_type="llm_chat", name="n", user_id=bad_user_id,
            )
        mock_session.add.assert_not_called()


# ===================== list_conversation =====================

class TestListConversation:
    @staticmethod
    def test_empty_result(mock_session):
        """空结果→[]。"""
        mock_session.query.return_value.order_by.return_value.all.return_value = []

        assert not list_conversation()

    @staticmethod
    def _mk(**kw):
        m = MagicMock()
        m.id = kw.get("id", "c-x")
        m.name = kw.get("name", "n")
        m.chat_type = kw.get("chat_type", "llm_chat")
        m.create_time = kw.get("create_time", "2024-01-01")
        m.user_id = kw.get("user_id", "u1")
        return m

    def test_filter_by_both(self, mock_session):
        """两者: 链 query.filter.filter.order_by.all。"""
        c1 = self._mk(id="c1", chat_type="llm_chat", user_id="u1")
        chain = (mock_session.query.return_value.filter.return_value
                 .filter.return_value.order_by.return_value.all)
        chain.return_value = [c1]

        data = list_conversation(chat_type="llm_chat", user_id="u1")

        assert len(data) == 1
        assert data[0]["conversation_id"] == "c1"

    def test_filter_by_chat_type_only(self, mock_session):
        """仅 chat_type: 链 query.filter.order_by.all。"""
        _LLM_CHAT_KEY = 'llm_chat'
        c1 = self._mk(id="c1", chat_type=_LLM_CHAT_KEY)
        chain = (mock_session.query.return_value.filter.return_value
                 .order_by.return_value.all)
        chain.return_value = [c1]

        data = list_conversation(chat_type=_LLM_CHAT_KEY)

        assert len(data) == 1
        assert data[0]["conversation_id"] == "c1"
        assert data[0]["chat_type"] == _LLM_CHAT_KEY

    def test_filter_by_user_id_only(self, mock_session):
        """仅 user_id: 链 query.filter.order_by.all(与 chat_type 同深度)。"""
        _U1_KEY = 'u1'
        c1 = self._mk(id="c1", user_id=_U1_KEY)
        chain = (mock_session.query.return_value.filter.return_value
                 .order_by.return_value.all)
        chain.return_value = [c1]

        data = list_conversation(user_id=_U1_KEY)

        assert len(data) == 1
        assert data[0]["conversation_id"] == "c1"
        assert data[0]["user_id"] == _U1_KEY

    def test_no_filter(self, mock_session):
        """无过滤: 链 query.order_by.all。"""
        c1 = self._mk(id="c1")
        c2 = self._mk(id="c2")
        mock_session.query.return_value.order_by.return_value.all.return_value = [c1, c2]

        data = list_conversation()

        assert data == [
            {"conversation_id": "c1", "name": "n", "chat_type": "llm_chat",
             "create_time": "2024-01-01", "user_id": "u1"},
            {"conversation_id": "c2", "name": "n", "chat_type": "llm_chat",
             "create_time": "2024-01-01", "user_id": "u1"},
        ]


# ===================== get_conversation =====================

class TestGetConversation:
    @staticmethod
    def test_returns_dict_when_found(mock_session):
        _C1_KEY = 'c1'
        _USER_ID_KEY = 'u1'
        c = MagicMock()
        c.id = _C1_KEY
        c.name = "n"
        c.chat_type = "llm_chat"
        c.create_time = "t"
        c.user_id = _USER_ID_KEY
        mock_session.query.return_value.filter_by.return_value.first.return_value = c

        result = get_conversation(_C1_KEY, user_id=_USER_ID_KEY)

        assert result == {"conversation_id": _C1_KEY, "name": "n",
                          "chat_type": "llm_chat", "create_time": "t",
                          "user_id": _USER_ID_KEY}

    @staticmethod
    def test_returns_none_when_not_found(mock_session):
        mock_session.query.return_value.filter_by.return_value.first.return_value = None

        assert get_conversation("not_exist", user_id="u1") is None


# ===================== update_conversation =====================

class TestUpdateConversation:
    @staticmethod
    def test_returns_none_when_not_found(mock_session):
        mock_session.query.return_value.filter_by.return_value.first.return_value = None

        result = update_conversation("not_exist", name="x", user_id="u1")

        assert result is None

    @staticmethod
    def test_sets_name_and_returns_id_when_found(mock_session):
        _CONV_ID_KEY = 'c1'
        c = MagicMock()
        c.id = _CONV_ID_KEY
        mock_session.query.return_value.filter_by.return_value.first.return_value = c

        result = update_conversation(_CONV_ID_KEY, name="new name", user_id="u1")

        assert c.name == "new name"
        assert result == _CONV_ID_KEY


# ===================== delete_conversation =====================

class TestDeleteConversation:
    @staticmethod
    def test_deletes_messages_and_conversation_when_owned(mock_session):
        """校验通过: message 与 conversation 两次 delete(synchronize_session=False)。"""
        c = MagicMock()  # 非空,代表会话存在且归属校验通过
        mock_session.query.return_value.filter_by.return_value.first.return_value = c
        delete_mock = mock_session.query.return_value.filter.return_value.delete

        result = delete_conversation("c1", user_id="u1")

        # 函数末尾无 return,隐式返回 None
        assert result is None
        assert delete_mock.call_count == 2
        for call in delete_mock.call_args_list:
            assert call.kwargs.get("synchronize_session") is False

    @staticmethod
    def test_returns_none_and_no_delete_when_not_owned(mock_session):
        """归属校验失败: filter_by.first=None,直接 return None,两次 delete 未触发。"""
        mock_session.query.return_value.filter_by.return_value.first.return_value = None

        result = delete_conversation("c1", user_id="u1")

        assert result is None
        delete_mock = mock_session.query.return_value.filter.return_value.delete
        delete_mock.assert_not_called()