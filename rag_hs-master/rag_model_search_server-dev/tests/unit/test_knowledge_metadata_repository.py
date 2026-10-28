"""knowledge_metadata_repository 白盒单元测试。

覆盖 server.db.repository.knowledge_metadata_repository 的 4 个函数:
- list_summary_from_db: metadata 空链 query.filter.all、1 项链
  query.filter.filter.all;字段名映射 meta_data→metadata(源码访问 x.metadata);
  空→[]
- delete_summary_from_db: patch list_summary_from_db 隔离重入;delete +
  commit 2 次(体内+装饰器)
- add_summary_to_db: 空列表不 add;多元素逐个 session.add 且 commit 2 次
  (体内+装饰器);缺 summary_context/summary_id/doc_ids/metadata 键抛 KeyError
- count_summary_from_db: 返回 .count() 配置值

DB 层全部 mock: patch server.db.session.session_scope,外部调用不传 session。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

import pytest

from server.db.models.knowledge_metadata_model import SummaryChunkModel
from server.db.repository import knowledge_metadata_repository as kmr
from server.db.repository.knowledge_metadata_repository import (
    add_summary_to_db,
    count_summary_from_db,
    delete_summary_from_db,
    list_summary_from_db,
)


@pytest.fixture
def mock_session(mocker):
    """替换 with_session 装饰器依赖的 session_scope 上下文管理器。"""
    mock_scope = mocker.patch("server.db.session.session_scope")
    session = mock_scope.return_value.__enter__.return_value
    return session


# ===================== list_summary_from_db =====================

class TestListSummaryFromDb:
    @staticmethod
    def test_empty(mock_session):
        mock_session.query.return_value.filter.return_value.all.return_value = []

        assert list_summary_from_db("kb1") == []

    @staticmethod
    def _mk(**kw):
        x = MagicMock()
        x.id = kw.get("id", "s-1")
        x.summary_context = kw.get("summary_context", "ctx")
        x.summary_id = kw.get("summary_id", "sid")
        x.doc_ids = kw.get("doc_ids", "d1,d2")
        x.metadata = kw.get("metadata", {"k": "v"})
        return x

    def test_no_metadata(self, mock_session):
        """metadata 空链: query.filter.all。"""
        x1 = self._mk(id="s-1")
        mock_session.query.return_value.filter.return_value.all.return_value = [x1]

        data = list_summary_from_db("kb1")

        assert data == [{
            "id": "s-1", "summary_context": "ctx",
            "summary_id": "sid", "doc_ids": "d1,d2",
            "metadata": {"k": "v"},
        }]

    def test_one_metadata(self, mock_session):
        """1 项 metadata: 链 query.filter.filter.all。"""
        _PAGE_KEY = 'page'
        x1 = self._mk(id="s-1", metadata={_PAGE_KEY: "1"})
        chain = (mock_session.query.return_value.filter.return_value
                 .filter.return_value.all)
        chain.return_value = [x1]

        data = list_summary_from_db("kb1", metadata={_PAGE_KEY: "1"})

        assert data == [{
            "id": "s-1", "summary_context": "ctx",
            "summary_id": "sid", "doc_ids": "d1,d2",
            "metadata": {_PAGE_KEY: "1"},
        }]


# ===================== delete_summary_from_db =====================

class TestDeleteSummaryFromDb:
    @staticmethod
    def test_deletes_and_returns_docs(mock_session, mocker):
        """patch list_summary_from_db 隔离重入;delete(synchronize_session=False)
        + commit 2 次(体内+装饰器);返回内层函数结果。"""
        docs_returned = [{"id": "s-1", "summary_context": "ctx",
                          "summary_id": "sid", "doc_ids": "d1",
                          "metadata": {"k": "v"}}]
        patched_list = mocker.patch.object(kmr, "list_summary_from_db",
                                           return_value=docs_returned)
        delete_mock = mock_session.query.return_value.filter.return_value.delete

        result = delete_summary_from_db("kb1")

        patched_list.assert_called_once_with(kb_name="kb1")
        assert result == docs_returned
        delete_mock.assert_called_once()
        assert delete_mock.call_args.kwargs.get("synchronize_session") is False
        assert mock_session.commit.call_count == 2  # 体内 + 装饰器


# ===================== add_summary_to_db =====================

class TestAddSummaryToDb:
    @staticmethod
    def test_adds_each_summary(mock_session):
        """多元素: 逐个 session.add,入参为 SummaryChunkModel;commit 2 次。"""
        summary_infos = [
            {"summary_context": "c1", "summary_id": "sid1",
             "doc_ids": "d1", "metadata": {"k": "v"}},
            {"summary_context": "c2", "summary_id": "sid2",
             "doc_ids": "d2", "metadata": {}},
        ]

        result = add_summary_to_db("kb1", summary_infos)

        assert result is True
        assert mock_session.add.call_count == 2
        added_args = [c.args[0] for c in mock_session.add.call_args_list]
        for obj in added_args:
            assert isinstance(obj, SummaryChunkModel)
        assert added_args[0].summary_context == "c1"
        assert added_args[0].summary_id == "sid1"
        assert added_args[0].doc_ids == "d1"
        assert added_args[0].meta_data == {"k": "v"}
        assert added_args[1].summary_context == "c2"
        assert mock_session.commit.call_count == 2  # 体内 + 装饰器

    @staticmethod
    def test_empty_list_does_not_add(mock_session):
        """空列表不 session.add;返回 True;commit 2 次(体内+装饰器)。"""
        result = add_summary_to_db("kb1", [])

        assert result is True
        mock_session.add.assert_not_called()
        assert mock_session.commit.call_count == 2

    @staticmethod
    def test_raises_key_error_when_missing_doc_ids(mock_session):
        with pytest.raises(KeyError):
            add_summary_to_db("kb1", [{
                "summary_context": "c", "summary_id": "sid", "metadata": {},
            }])
        mock_session.add.assert_not_called()

    @staticmethod
    def test_raises_key_error_when_missing_metadata(mock_session):
        with pytest.raises(KeyError):
            add_summary_to_db("kb1", [{
                "summary_context": "c", "summary_id": "sid", "doc_ids": "d",
            }])
        mock_session.add.assert_not_called()

    @staticmethod
    def test_raises_key_error_when_missing_summary_context(mock_session):
        with pytest.raises(KeyError):
            add_summary_to_db("kb1", [{
                "summary_id": "sid", "doc_ids": "d", "metadata": {},
            }])
        mock_session.add.assert_not_called()

    @staticmethod
    def test_raises_key_error_when_missing_summary_id(mock_session):
        with pytest.raises(KeyError):
            add_summary_to_db("kb1", [{
                "summary_context": "c", "doc_ids": "d", "metadata": {},
            }])
        mock_session.add.assert_not_called()


# ===================== count_summary_from_db =====================

class TestCountSummaryFromDb:
    @staticmethod
    def test_returns_count_value(mock_session):
        mock_session.query.return_value.filter.return_value.count.return_value = 9

        assert count_summary_from_db("kb1") == 9