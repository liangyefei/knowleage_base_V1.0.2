"""knowledge_file_repository 白盒单元测试。

覆盖 server.db.repository.knowledge_file_repository 全部 11 个函数:
- list_file_num_docs_id_by_kb_name_and_file_name: 1-tuple 强制 int
- list_docs_from_db: file_name/metadata 链深度递增
- delete_docs_from_db: 重入调用 list_docs_from_db(patch 隔离)
- add_docs_to_db: doc_infos=None→False;空列表→True;多元素逐个 add;缺
  id/metadata 键抛 KeyError
- count_files_from_db: 返回 .count() 配置值
- list_files_from_db: 提取 file_name 列表
- add_file_to_db: 重入调用 add_docs_to_db(patch 隔离);kb 不存在直接返回;
  existing_file 存在→更新字段不 add;不存在→构造新 file 且 kb.file_count+=1;
  text_splitter_name 为 None 回退 "SpacyTextSplitter"
- delete_file_from_db: 重入调用 delete_docs_from_db(patch 隔离);existing_file
  不存在直接返回;存在→session.delete + kb 存在 file_count-=1
- delete_files_from_db: 两次 batch delete + kb 存在 file_count=0
- file_exists_in_db: None→False;存在→True
- get_file_detail: 不存在→{};存在→多字段 dict

重入隔离: add_file_to_db→add_docs_to_db、delete_file_from_db→delete_docs_from_db、
delete_docs_from_db→list_docs_from_db,均用 mocker.patch 内层函数隔离。
DB 层全部 mock: patch server.db.session.session_scope,外部调用不传 session。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

import pytest

from server.db.models.knowledge_file_model import KnowledgeFileModel
from server.db.repository import knowledge_file_repository as kfr
from server.db.repository.knowledge_file_repository import (
    add_docs_to_db,
    add_file_to_db,
    count_files_from_db,
    delete_docs_from_db,
    delete_file_from_db,
    delete_files_from_db,
    file_exists_in_db,
    get_file_detail,
    list_docs_from_db,
    list_file_num_docs_id_by_kb_name_and_file_name,
    list_files_from_db,
)
from server.knowledge_base.utils import KnowledgeFile


@pytest.fixture
def mock_session(mocker):
    """替换 with_session 装饰器依赖的 session_scope 上下文管理器。"""
    mock_scope = mocker.patch("server.db.session.session_scope")
    session = mock_scope.return_value.__enter__.return_value
    return session


def _make_kb_file(**overrides):
    """构造 MagicMock(spec=KnowledgeFile) 假模型,含 kb_name/filename/ext/
    document_loader_name/text_splitter_name/get_mtime()/get_size()。"""
    kf = MagicMock(spec=KnowledgeFile)
    kf.kb_name = overrides.get("kb_name", "kb1")
    kf.filename = overrides.get("filename", "doc.pdf")
    kf.ext = overrides.get("ext", ".pdf")
    kf.document_loader_name = overrides.get("document_loader_name", "PDFLoader")
    kf.text_splitter_name = overrides.get("text_splitter_name", "SpacyTextSplitter")
    kf.get_mtime.return_value = overrides.get("mtime", 1234567.0)
    kf.get_size.return_value = overrides.get("size", 1024)
    return kf


# ===================== list_file_num_docs_id_by_kb_name_and_file_name =====================

class TestListFileNumDocsId:
    @staticmethod
    def test_empty(mock_session):
        mock_session.query.return_value.filter_by.return_value.all.return_value = []

        assert list_file_num_docs_id_by_kb_name_and_file_name("kb1", "f.pdf") == []

    @staticmethod
    def test_unpacks_and_forces_int(mock_session):
        """1-tuple 强制 int([("3",),("5",)]→[3,5])。"""
        mock_session.query.return_value.filter_by.return_value.all.return_value = [
            ("3",), ("5",),
        ]

        assert list_file_num_docs_id_by_kb_name_and_file_name("kb1", "f.pdf") == [3, 5]


# ===================== list_docs_from_db =====================

class TestListDocsFromDb:
    @staticmethod
    def test_empty(mock_session):
        mock_session.query.return_value.filter.return_value.all.return_value = []

        assert list_docs_from_db("kb1") == []

    @staticmethod
    def _mk(doc_id, metadata):
        x = MagicMock()
        x.doc_id = doc_id
        x.metadata = metadata
        return x

    def test_no_file_name_no_metadata(self, mock_session):
        """无 file_name/metadata: 链 query.filter.all。"""
        x1 = self._mk("d1", {"k": "v"})
        x2 = self._mk("d2", {})
        mock_session.query.return_value.filter.return_value.all.return_value = [x1, x2]

        data = list_docs_from_db("kb1")

        assert data == [
            {"id": "d1", "metadata": {"k": "v"}},
            {"id": "d2", "metadata": {}},
        ]

    def test_with_file_name(self, mock_session):
        """有 file_name: 链 query.filter.filter.all。"""
        x1 = self._mk("d1", {"k": "v"})
        chain = (mock_session.query.return_value.filter.return_value
                 .filter.return_value.all)
        chain.return_value = [x1]

        data = list_docs_from_db("kb1", file_name="f.pdf")

        assert data == [{"id": "d1", "metadata": {"k": "v"}}]

    def test_with_metadata_multi(self, mock_session):
        """metadata 多项: 链深度 +N(2 项→query.filter.filter.filter.all)。"""
        x1 = self._mk("d1", {"a": "1", "b": "2"})
        chain = (mock_session.query.return_value.filter.return_value
                 .filter.return_value.filter.return_value.all)
        chain.return_value = [x1]

        data = list_docs_from_db("kb1", metadata={"a": "1", "b": "2"})

        assert data == [{"id": "d1", "metadata": {"a": "1", "b": "2"}}]


# ===================== add_docs_to_db =====================

class TestAddDocsToDb:
    @staticmethod
    def test_adds_each_doc(mock_session):
        """多元素: 逐个 session.add,入参为 FileDocModel。"""
        doc_infos = [
            {"id": "d1", "metadata": {"k": "v"}},
            {"id": "d2", "metadata": {}},
        ]

        result = add_docs_to_db("kb1", "f.pdf", doc_infos)

        assert result is True
        assert mock_session.add.call_count == 2
        added_args = [c.args[0] for c in mock_session.add.call_args_list]
        from server.db.models.knowledge_file_model import FileDocModel
        for obj in added_args:
            assert isinstance(obj, FileDocModel)
        assert added_args[0].doc_id == "d1"
        assert added_args[0].meta_data == {"k": "v"}
        assert added_args[1].doc_id == "d2"

    @staticmethod
    def test_raises_key_error_when_missing_id(mock_session):
        """缺 id 键抛 KeyError。"""
        with pytest.raises(KeyError):
            add_docs_to_db("kb1", "f.pdf", [{"metadata": {}}])
        mock_session.add.assert_not_called()

    @staticmethod
    def test_raises_key_error_when_missing_metadata(mock_session):
        """缺 metadata 键抛 KeyError。"""
        with pytest.raises(KeyError):
            add_docs_to_db("kb1", "f.pdf", [{"id": "d1"}])
        mock_session.add.assert_not_called()

    @staticmethod
    def test_returns_false_when_doc_infos_none(mock_session):
        """doc_infos=None→返回 False 且 session.add 未调用。"""
        result = add_docs_to_db("kb1", "f.pdf", None)

        assert result is False
        mock_session.add.assert_not_called()

    @staticmethod
    def test_returns_true_without_add_when_empty(mock_session):
        """空列表→返回 True,不 add。"""
        result = add_docs_to_db("kb1", "f.pdf", [])

        assert result is True
        mock_session.add.assert_not_called()


# ===================== count_files_from_db =====================

class TestCountFilesFromDb:
    @staticmethod
    def test_returns_count_value(mock_session):
        mock_session.query.return_value.filter.return_value.count.return_value = 7

        assert count_files_from_db("kb1") == 7


# ===================== list_files_from_db =====================

class TestListFilesFromDb:
    @staticmethod
    def test_empty(mock_session):
        mock_session.query.return_value.filter.return_value.all.return_value = []

        assert list_files_from_db("kb1") == []

    @staticmethod
    def test_returns_file_name_list(mock_session):
        f1 = MagicMock()
        f1.file_name = "a.pdf"
        f2 = MagicMock()
        f2.file_name = "b.pdf"
        mock_session.query.return_value.filter.return_value.all.return_value = [f1, f2]

        assert list_files_from_db("kb1") == ["a.pdf", "b.pdf"]


# ===================== add_file_to_db =====================

class TestAddFileToDb:
    @staticmethod
    def test_creates_new_file_when_not_exists(mock_session, mocker):
        """kb 存在+existing_file 不存在→构造 new_file 且 kb.file_count+=1 且
        session.add,入参为 KnowledgeFileModel 且字段正确。"""
        kb = MagicMock()
        kb.file_count = 5
        mock_session.query.return_value.filter_by.return_value.first.return_value = kb
        mock_session.query.return_value.filter.return_value.first.return_value = None
        patched_add_docs = mocker.patch.object(kfr, "add_docs_to_db")
        kb_file = _make_kb_file()

        result = add_file_to_db(kb_file, docs_count=3, custom_docs=False, doc_infos=[])

        assert result is True
        mock_session.add.assert_called_once()
        added = mock_session.add.call_args[0][0]
        assert isinstance(added, KnowledgeFileModel)
        assert added.file_name == "doc.pdf"
        assert added.file_ext == ".pdf"
        assert added.kb_name == "kb1"
        assert added.document_loader_name == "PDFLoader"
        assert added.text_splitter_name == "SpacyTextSplitter"
        assert added.file_mtime == 1234567.0
        assert added.file_size == 1024
        assert added.docs_count == 3
        assert added.custom_docs is False
        assert kb.file_count == 6  # 5 + 1
        patched_add_docs.assert_called_once()

    @staticmethod
    def test_falls_back_to_spacy_when_text_splitter_none(mock_session, mocker):
        """text_splitter_name 为 None 回退 "SpacyTextSplitter"。"""
        kb = MagicMock()
        kb.file_count = 0
        mock_session.query.return_value.filter_by.return_value.first.return_value = kb
        mock_session.query.return_value.filter.return_value.first.return_value = None
        mocker.patch.object(kfr, "add_docs_to_db")
        kb_file = _make_kb_file(text_splitter_name=None)

        add_file_to_db(kb_file, doc_infos=[])

        added = mock_session.add.call_args[0][0]
        assert added.text_splitter_name == "SpacyTextSplitter"

    @staticmethod
    def test_returns_true_when_kb_not_exists(mock_session, mocker):
        """kb 不存在(filter_by.first=None)直接 return True,不查 existing_file,
        不 session.add,不调用 add_docs_to_db。"""
        mock_session.query.return_value.filter_by.return_value.first.return_value = None
        patched_add_docs = mocker.patch.object(kfr, "add_docs_to_db")

        result = add_file_to_db(_make_kb_file(), doc_infos=[])

        assert result is True
        mock_session.add.assert_not_called()
        patched_add_docs.assert_not_called()
        # existing_file 查询链未被触发
        mock_session.query.return_value.filter.return_value.first.assert_not_called()

    @staticmethod
    def test_updates_existing_file_without_add(mock_session, mocker):
        """kb 存在+existing_file 存在→更新字段(file_version+=1 等)不 add 新
        file 且 kb.file_count 不变;add_docs_to_db 被调用一次。"""
        kb = MagicMock()
        kb.file_count = 5
        existing_file = MagicMock()
        existing_file.file_version = 1
        mock_session.query.return_value.filter_by.return_value.first.return_value = kb
        mock_session.query.return_value.filter.return_value.first.return_value = existing_file
        patched_add_docs = mocker.patch.object(kfr, "add_docs_to_db")

        result = add_file_to_db(
            _make_kb_file(mtime=2222.0, size=2048),
            docs_count=9, custom_docs=True, doc_infos=[],
        )

        assert result is True
        mock_session.add.assert_not_called()
        # existing_file 字段被更新
        assert existing_file.file_mtime == 2222.0
        assert existing_file.file_size == 2048
        assert existing_file.docs_count == 9
        assert existing_file.custom_docs is True
        assert existing_file.file_version == 2  # 1 + 1
        # kb.file_count 未变
        assert kb.file_count == 5
        patched_add_docs.assert_called_once()


# ===================== delete_file_from_db =====================

class TestDeleteFileFromDb:
    @staticmethod
    def test_deletes_and_decrements_when_found(mock_session, mocker):
        """existing_file 存在→session.delete(existing_file)+ kb 存在 file_count-=1。
        existing_file 与 kb 共用同一 filter.first 链,用 side_effect 区分两次返回。"""
        existing_file = MagicMock()
        kb = MagicMock()
        kb.file_count = 4
        first_mock = mock_session.query.return_value.filter.return_value.first
        first_mock.side_effect = [existing_file, kb]
        patched_delete_docs = mocker.patch.object(kfr, "delete_docs_from_db")

        result = delete_file_from_db(_make_kb_file())

        assert result is True
        mock_session.delete.assert_called_once_with(existing_file)
        patched_delete_docs.assert_called_once()
        assert kb.file_count == 3  # 4 - 1

    @staticmethod
    def test_returns_true_when_existing_file_not_found(mock_session, mocker):
        """existing_file 不存在直接 return True,不 delete,不调用 delete_docs。"""
        mock_session.query.return_value.filter.return_value.first.return_value = None
        patched_delete_docs = mocker.patch.object(kfr, "delete_docs_from_db")

        result = delete_file_from_db(_make_kb_file())

        assert result is True
        mock_session.delete.assert_not_called()
        patched_delete_docs.assert_not_called()


# ===================== delete_files_from_db =====================

class TestDeleteFilesFromDb:
    @staticmethod
    def test_batch_deletes_and_zeroes_file_count(mock_session):
        """两次 batch delete(knowledge_file + file_doc)+ kb 存在 file_count=0。"""
        kb = MagicMock()
        kb.file_count = 10
        mock_session.query.return_value.filter.return_value.first.return_value = kb
        delete_mock = mock_session.query.return_value.filter.return_value.delete

        result = delete_files_from_db("kb1")

        assert result is True
        assert delete_mock.call_count == 2
        for call in delete_mock.call_args_list:
            assert call.kwargs.get("synchronize_session") is False
        assert kb.file_count == 0

    @staticmethod
    def test_no_kb_when_none(mock_session):
        """kb 不存在时不报错,仍两次 delete,返回 True。"""
        mock_session.query.return_value.filter.return_value.first.return_value = None
        delete_mock = mock_session.query.return_value.filter.return_value.delete

        result = delete_files_from_db("kb1")

        assert result is True
        assert delete_mock.call_count == 2


# ===================== file_exists_in_db =====================

class TestFileExistsInDb:
    @staticmethod
    def test_false_when_none(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert file_exists_in_db(_make_kb_file()) is False

    @staticmethod
    def test_true_when_exists(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = MagicMock()

        assert file_exists_in_db(_make_kb_file()) is True


# ===================== get_file_detail =====================

class TestGetFileDetail:
    @staticmethod
    def test_returns_empty_dict_when_not_found(mock_session):
        mock_session.query.return_value.filter.return_value.first.return_value = None

        assert not get_file_detail("kb1", "f.pdf")

    @staticmethod
    def test_returns_full_dict_when_found(mock_session):
        """存在→多字段 dict(kb_name/file_name/file_ext/
        file_version_label/document_loader/text_splitter/create_time/
        file_mtime/file_size/custom_docs/docs_count)。

        P3-1：返回 file_version_label（String，查 FileVersionHistoryModel）。
        file_version（INT）已从 API 响应移除，前端统一用 file_version_label。
        此处 mock 第二个 query（version_label 查询）的 .first() 返回 None → label=""。
        """
        _KB1_KEY = 'kb1'
        _FILE_NAME_KEY = 'f.pdf'
        f = MagicMock()
        f.kb_name = _KB1_KEY
        f.file_name = _FILE_NAME_KEY
        f.file_ext = ".pdf"
        f.document_loader_name = "PDFLoader"
        f.text_splitter_name = "SpacyTextSplitter"
        f.create_time = "2024-01-01"
        f.file_mtime = 1234567.0
        f.file_size = 1024
        f.custom_docs = False
        f.docs_count = 3
        mock_session.query.return_value.filter.return_value.first.return_value = f
        # 第二个 query（version_label）走 .filter().order_by().first()，mock 返回 None
        (mock_session.query.return_value.filter.return_value
         .order_by.return_value.first.return_value) = None

        result = get_file_detail(_KB1_KEY, _FILE_NAME_KEY)

        assert result == {
            "kb_name": _KB1_KEY,
            "file_name": _FILE_NAME_KEY,
            "file_ext": ".pdf",
            "file_version_label": "",
            "document_loader": "PDFLoader",
            "text_splitter": "SpacyTextSplitter",
            "create_time": "2024-01-01",
            "file_mtime": 1234567.0,
            "file_size": 1024,
            "custom_docs": False,
            "docs_count": 3,
        }


# ===================== delete_docs_from_db =====================

class TestDeleteDocsFromDb:
    @staticmethod
    def test_deletes_and_returns_docs_without_file_name(mock_session, mocker):
        """patch list_docs_from_db 隔离重入;无 file_name: 链 query.filter.delete;
        delete + commit(体内+装饰器共 2 次);返回内层函数结果。"""
        docs_returned = [{"id": "d1", "metadata": {"k": "v"}}]
        patched_list = mocker.patch.object(kfr, "list_docs_from_db",
                                           return_value=docs_returned)
        delete_mock = mock_session.query.return_value.filter.return_value.delete

        result = delete_docs_from_db("kb1")

        patched_list.assert_called_once_with(kb_name="kb1", file_name=None)
        assert result == docs_returned
        delete_mock.assert_called_once()
        assert delete_mock.call_args.kwargs.get("synchronize_session") is False
        assert mock_session.commit.call_count == 2  # 体内 + 装饰器

    @staticmethod
    def test_deletes_with_file_name(mock_session, mocker):
        """有 file_name: 链 query.filter.filter.delete。"""
        mocker.patch.object(kfr, "list_docs_from_db", return_value=[])
        delete_mock = (mock_session.query.return_value.filter.return_value
                       .filter.return_value.delete)

        result = delete_docs_from_db("kb1", file_name="f.pdf")

        assert result == []
        delete_mock.assert_called_once()
        assert delete_mock.call_args.kwargs.get("synchronize_session") is False