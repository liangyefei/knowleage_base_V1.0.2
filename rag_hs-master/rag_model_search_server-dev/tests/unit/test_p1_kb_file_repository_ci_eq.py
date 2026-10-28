"""P1-5 修复回归测试：knowledge_file_repository 的 ilike → _ci_eq 精确匹配。

P1-5：knowledge_file_repository.py 全文 18 处 .ilike() → _ci_eq()。
SQL ILIKE 中 _ 匹配任意单字符、% 匹配任意序列，文件名含 _ 会误匹配
近名文件（如 "rev-1_0" 匹配 "rev-1X0"）。delete_docs_from_db / 
delete_file_from_db 是删除操作，_ 通配符会误删近名文件。

用 SQLite in-memory + StaticPool 做真实 DB 语义测试（StaticPool 保证
所有 session 共享同一连接，支持 delete_docs_from_db 内部嵌套
list_docs_from_db 的 @with_session 重入）。
"""
# pylint: disable=no-use-sys-path-insert
import os
import sys
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import importlib.abc
import importlib.machinery
import importlib.util

# shim: 防 import 时 Milvus/langchain 连接
import types
from unittest.mock import MagicMock

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool


class _LangChainShimLoader(importlib.abc.Loader):
    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            return type(attr, (), {"__init__": lambda self, **kw: self.__dict__.update(kw)})
        module.__getattr__ = __getattr__


class _LangChainShimFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path, target=None):
        if not fullname.startswith("langchain."):
            return None
        if fullname.startswith(("langchain_core", "langchain_community", "langchain_text_splitters")):
            return None
        if fullname in sys.modules:
            return None
        sys.meta_path.remove(self)
        try:
            try:
                real_spec = importlib.util.find_spec(fullname)
            except Exception:
                real_spec = None
        finally:
            sys.meta_path.insert(0, self)
        if real_spec is not None:
            return None
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

import pymilvus


def _mock_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_client

import openai

openai.AsyncOpenAI = _mock_client
openai.OpenAI = _mock_client

from server.db.base import Base
from server.db.models.file_version_history_model import FileVersionHistoryModel
from server.db.models.knowledge_base_model import KnowledgeBaseModel
from server.db.models.knowledge_file_model import FileDocModel, KnowledgeFileModel


_KB_NAME_KEY = 'kb1'
_FILE_NAME_KEY = 'file.md'
_REV_KEY = 'rev-1_0.md'


@pytest.fixture
def real_db(mocker):
    """SQLite in-memory + StaticPool（共享连接，支持嵌套 @with_session）。"""
    engine = create_engine(
        "sqlite:///:memory:",
        poolclass=StaticPool,
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine, tables=[
        KnowledgeBaseModel.__table__,
        KnowledgeFileModel.__table__,
        FileDocModel.__table__,
        FileVersionHistoryModel.__table__,
    ])

    @contextmanager
    def _mock_session_scope():
        session = Session(engine)
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    mocker.patch("server.db.session.session_scope", _mock_session_scope)
    return engine


def _add_file_row(s, kb_name, file_name, file_version=1):
    """插入一行 KnowledgeFileModel。"""
    s.add(KnowledgeFileModel(
        file_name=file_name, file_ext=os.path.splitext(file_name)[-1],
        kb_name=kb_name, document_loader_name="TextLoader",
        text_splitter_name="SpacyTextSplitter", file_mtime=1234567.0,
        file_size=1024, docs_count=1, custom_docs=False,
        file_version=file_version,
    ))


def _add_doc_row(s, kb_name, file_name, doc_id="d1", metadata=None):
    """插入一行 FileDocModel。"""
    import json
    s.add(FileDocModel(
        kb_name=kb_name, file_name=file_name, doc_id=doc_id,
        meta_data=metadata or {"source": file_name},
    ))


_DEFAULT_STATUS = "superseded"


def _add_history_row(s, kb_name, file_name, sha, label):
    """插入一行 FileVersionHistoryModel（status 默认 superseded）。"""
    s.add(FileVersionHistoryModel(
        kb_name=kb_name, file_name=file_name, file_path=os.path.join("/tmp", file_name),
        content_sha256=sha, size=100, file_version_label=label,
        tags="", note="", status=_DEFAULT_STATUS,
    ))


# ── P1-5: _ci_eq 不把 _ 当通配符 ──

class TestListDocsNoWildcard:
    @staticmethod
    def test_underscore_not_wildcard(real_db):
        """P1-5: list_docs_from_db 查 rev-1_0 不匹配 rev-1X0。"""
        _KB1_KEY = 'kb1'
        from server.db.repository.knowledge_file_repository import list_docs_from_db
        with Session(real_db) as s:
            _add_doc_row(s, _KB1_KEY, "rev-1_0.md", doc_id="d1")
            _add_doc_row(s, _KB1_KEY, "rev-1X0.md", doc_id="d2")
            s.commit()

        docs = list_docs_from_db(kb_name=_KB1_KEY, file_name="rev-1_0.md")
        assert len(docs) == 1, f"应只匹配 rev-1_0.md（1 行），实际 {len(docs)} 行"
        assert docs[0]["id"] == "d1"

    @staticmethod
    def test_percent_not_wildcard(real_db):
        """P1-5: list_docs_from_db 查 rev%1 不匹配 revX1。"""
        from server.db.repository.knowledge_file_repository import list_docs_from_db
        with Session(real_db) as s:
            _add_doc_row(s, _KB_NAME_KEY, "rev%1.md", doc_id="d1")
            _add_doc_row(s, _KB_NAME_KEY, "revX1.md", doc_id="d2")
            s.commit()

        docs = list_docs_from_db(kb_name=_KB_NAME_KEY, file_name="rev%1.md")
        assert len(docs) == 1, f"应只匹配 rev%1.md，实际 {len(docs)} 行"
        assert docs[0]["id"] == "d1"

    @staticmethod
    def test_case_insensitive(real_db):
        """P1-5 回归：_ci_eq 保持大小写不敏感（KB1 匹配 kb1）。"""
        from server.db.repository.knowledge_file_repository import list_docs_from_db
        with Session(real_db) as s:
            _add_doc_row(s, "kb1", "file.md", doc_id="d1")
            s.commit()

        docs = list_docs_from_db(kb_name="KB1", file_name="FILE.md")
        assert len(docs) == 1, f"应大小写不敏感匹配，实际 {len(docs)} 行"


class TestDeleteDocsNoWildcard:
    @staticmethod
    def test_underscore_not_wildcard_delete(real_db):
        """P1-5: delete_docs_from_db 删 rev-1_0 不删 rev-1X0。

        这是 P1-5 最关键的测试——删除操作的 ilike 通配符会误删近名文件的
        doc 记录。_ci_eq 修复后只删精确匹配的文件。
        """
        from server.db.repository.knowledge_file_repository import delete_docs_from_db
        with Session(real_db) as s:
            _add_doc_row(s, _KB_NAME_KEY, _REV_KEY, doc_id="d1")
            _add_doc_row(s, _KB_NAME_KEY, "rev-1X0.md", doc_id="d2")
            s.commit()

        deleted = delete_docs_from_db(kb_name=_KB_NAME_KEY, file_name=_REV_KEY)
        assert len(deleted) == 1, f"应只删 1 行（rev-1_0.md），实际删 {len(deleted)} 行"
        assert deleted[0]["id"] == "d1"

        # 验证 rev-1X0.md 的 doc 未被误删
        from server.db.repository.knowledge_file_repository import list_docs_from_db
        remaining = list_docs_from_db(kb_name=_KB_NAME_KEY, file_name="rev-1X0.md")
        assert len(remaining) == 1, f"rev-1X0.md 应未被删，实际剩 {len(remaining)} 行"
        assert remaining[0]["id"] == "d2"


class TestFileExistsNoWildcard:
    @staticmethod
    def test_underscore_not_wildcard(real_db):
        """P1-5: file_exists_in_db 查 rev-1_0 在只有 rev-1X0 时返回 False。"""
        from server.db.repository.knowledge_file_repository import file_exists_in_db
        from server.knowledge_base.utils import KnowledgeFile
        with Session(real_db) as s:
            _add_file_row(s, "kb1", "rev-1X0.md")
            s.commit()

        kf = MagicMock(spec=KnowledgeFile)
        kf.kb_name = "kb1"
        kf.filename = "rev-1_0.md"
        assert file_exists_in_db(kf) is False, "rev-1_0 不应匹配 rev-1X0"

    @staticmethod
    def test_exact_match_exists(real_db):
        """P1-5 回归：精确匹配时返回 True。"""
        from server.db.repository.knowledge_file_repository import file_exists_in_db
        from server.knowledge_base.utils import KnowledgeFile
        with Session(real_db) as s:
            _add_file_row(s, "kb1", "rev-1_0.md")
            s.commit()

        kf = MagicMock(spec=KnowledgeFile)
        kf.kb_name = "kb1"
        kf.filename = "rev-1_0.md"
        assert file_exists_in_db(kf) is True


class TestGetFileDetailNoWildcard:
    @staticmethod
    def test_underscore_not_wildcard(real_db):
        """P1-5: get_file_detail 查 rev-1_0 不返回 rev-1X0。"""
        from server.db.repository.knowledge_file_repository import get_file_detail
        with Session(real_db) as s:
            _add_file_row(s, _KB_NAME_KEY, _REV_KEY, file_version=5)
            _add_file_row(s, _KB_NAME_KEY, "rev-1X0.md", file_version=9)
            s.commit()

        detail = get_file_detail(_KB_NAME_KEY, _REV_KEY)
        assert detail["file_name"] == _REV_KEY, f"应只匹配 rev-1_0.md，实际 {detail.get('file_name')}"

    @staticmethod
    def test_returns_file_version_label(real_db):
        """P3-1: get_file_detail 返回 file_version_label（String，查 history 表）。"""
        from server.db.repository.knowledge_file_repository import get_file_detail
        with Session(real_db) as s:
            _add_file_row(s, _KB_NAME_KEY, _FILE_NAME_KEY)
            _add_history_row(s, _KB_NAME_KEY, _FILE_NAME_KEY, "sha123", "v2.0")
            s.commit()

        detail = get_file_detail(_KB_NAME_KEY, _FILE_NAME_KEY)
        assert "file_version_label" in detail, "应包含 file_version_label 字段"
        assert detail["file_version_label"] == "v2.0", (
            f"应返回 history 表最新非 deleted 行的 label v2.0，实际 {detail.get('file_version_label')}")

    @staticmethod
    def test_file_version_label_empty_when_no_history(real_db):
        """P3-1: 无 history 行时 file_version_label 为空串。"""
        from server.db.repository.knowledge_file_repository import get_file_detail
        with Session(real_db) as s:
            _add_file_row(s, "kb1", "file.md")
            s.commit()

        detail = get_file_detail("kb1", "file.md")
        assert detail["file_version_label"] == "", (
            f"无 history 时应为空串，实际 {detail.get('file_version_label')}")
