"""knowledge_base_repository 白盒单元测试。

覆盖 server.db.repository.knowledge_base_repository 的 6 个函数:
- add_kb_to_db: kb 不存在(first=None)→构造新 KnowledgeBaseModel 且
  session.add;kb 存在→更新 kb_info/vs_type/embed_model 字段且 session.add
  未被调用;均返回 True
- list_kbs_from_db: 1-tuple 解包([("kb1",),("kb2",)]→["kb1","kb2"]);空→[]
- kb_exists: None→False;存在→True
- load_kb_from_db: None→(None,None,None);存在→(kb_name,vs_type,embed_model)
- delete_kb_from_db: 不存在不 session.delete 仍返回 True;存在 session.delete
  调用一次
- get_kb_detail: 不存在→{};存在→6 字段 dict(kb_name/kb_info/vs_type/
  embed_model/file_count/create_time)

全部用 filter(ilike) 链: query.return_value.filter.return_value.{first/all}。
DB 层全部 mock: patch server.db.session.session_scope,外部调用不传 session。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

import pytest

from server.db.models.knowledge_base_model import KnowledgeBaseModel
from server.db.repository.knowledge_base_repository import (
    add_kb_to_db,
    delete_kb_from_db,
    get_kb_detail,
    kb_exists,
    list_kbs_from_db,
    load_kb_from_db,
)


@pytest.fixture
def mock_session(mocker):
    """替换 with_session 装饰器依赖的 session_scope 上下文管理器。"""
    mock_scope = mocker.patch("server.db.session.session_scope")
    session = mock_scope.return_value.__enter__.return_value
    return session


# ===================== add_kb_to_db =====================

class TestAddKbToDb:
    @staticmethod
    def test_creates_new_kb_when_not_exists(mock_session):
        """kb 不存在: 构造 KnowledgeBaseModel 且 session.add,返回 True。"""
        mock_session.query.return_value.filter.return_value.first.return_value = None

        result = add_kb_to_db("kb1", "info", "chromadb", "bge")

        assert result is True
        mock_session.add.assert_called_once()
        added = mock_session.add.call_args[0][0]
        assert isinstance(added, KnowledgeBaseModel)
        assert added.kb_name == "kb1"
        assert added.kb_info == "info"
        assert added.vs_type == "chromadb"
        assert added.embed_model == "bge"

    @staticmethod
    def test_updates_existing_kb_without_add(mock_session):
        """kb 存在: 更新字段,不 session.add,返回 True。"""
        kb = MagicMock()
        mock_session.query.return_value.filter.return_value.first.return_value = kb

        result = add_kb_to_db("kb1", "new info", "milvus", "m3e")

        assert result is True
        mock_session.add.assert_not_called()
        assert kb.kb_info == "new info"
        assert kb.vs_type == "milvus"
        assert kb.embed_model == "m3e"


# ===================== list_kbs_from_db =====================

class TestListKbsFromDb:
    @staticmethod
    def test_unpacks_single_tuple(mock_session):
        """1-tuple 解包为字符串列表。"""
        mock_session.query.return_value.filter.return_value.all.return_value = [
            ("kb1",), ("kb2",),
        ]

        assert list_kbs_from_db() == ["kb1", "kb2"]

    @staticmethod
    def test_empty(mock_session):
        mock_session.query.return_value.filter.return_value.all.return_value = []

        assert list_kbs_from_db() == []


# ===================== kb_exists =====================

class TestKbExists:
    @staticmethod
    def test_false_when_none(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert kb_exists("kb1") is False

    @staticmethod
    def test_true_when_exists(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = MagicMock()

        assert kb_exists("kb1") is True


# ===================== load_kb_from_db =====================

class TestLoadKbFromDb:
    @staticmethod
    def test_returns_none_triple_when_not_found(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert load_kb_from_db("kb1") == (None, None, None)

    @staticmethod
    def test_returns_triple_when_found(mock_session):
        _KB1_KEY = 'kb1'
        kb = MagicMock()
        kb.kb_name = _KB1_KEY
        kb.vs_type = "chromadb"
        kb.embed_model = "bge"
        mock_session.query.return_value.filter.return_value.first.return_value = kb

        assert load_kb_from_db(_KB1_KEY) == (_KB1_KEY, "chromadb", "bge")


# ===================== delete_kb_from_db =====================

class TestDeleteKbFromDb:
    @staticmethod
    def test_returns_true_without_delete_when_not_found(mock_session):
        """不存在: 不 session.delete,仍返回 True。"""
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert delete_kb_from_db("kb1") is True
        mock_session.delete.assert_not_called()

    @staticmethod
    def test_deletes_once_when_found(mock_session):
        """存在: session.delete 调用一次,返回 True。"""
        kb = MagicMock()
        mock_session.query.return_value.filter.return_value.first.return_value = kb

        assert delete_kb_from_db("kb1") is True
        mock_session.delete.assert_called_once_with(kb)


# ===================== get_kb_detail =====================

class TestGetKbDetail:
    @staticmethod
    def test_returns_empty_dict_when_not_found(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert not get_kb_detail("kb1")

    @staticmethod
    def test_returns_six_fields_dict_when_found(mock_session):
        _KB_NAME_KEY = 'kb1'
        kb = MagicMock()
        kb.kb_name = _KB_NAME_KEY
        kb.kb_info = "info"
        kb.vs_type = "chromadb"
        kb.embed_model = "bge"
        kb.file_count = 3
        kb.create_time = "2024-01-01"
        mock_session.query.return_value.filter.return_value.first.return_value = kb

        result = get_kb_detail(_KB_NAME_KEY)

        assert result == {
            "kb_name": _KB_NAME_KEY,
            "kb_info": "info",
            "vs_type": "chromadb",
            "embed_model": "bge",
            "file_count": 3,
            "create_time": "2024-01-01",
        }
