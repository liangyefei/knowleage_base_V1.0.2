"""Milvus chunk metadata 嵌套写入 file_version_label + tags UT。"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import importlib.abc
import importlib.machinery
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock


class _LangChainShimLoader(importlib.abc.Loader):
    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            # 占位类须可被继承（如 EmbeddingsFunAdapter(Embeddings)）且可接受
            # kwargs 实例化（do_add_doc 内部 Document(page_content=..., metadata=m)）。
            # 裸 type(attr, (), {}) 不接受 kwargs（Python 3: TypeError takes no
            # arguments），故加 __init__ 存 kwargs；继承仍正常。
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
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

# Milvus 连接 shim：kb_doc_api.py L39 模块级 MilvusClient eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


from langchain_core.documents import Document

from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService


def _make_svc():
    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "test_kb"
    svc.embed_model = "text-embedding-v3"
    svc.milvus = MagicMock()
    svc.milvus._text_field = "text"
    svc.milvus._vector_field = "vector"
    svc.milvus.col = MagicMock()
    svc.milvus.add_documents.return_value = [1]
    return svc


def test_do_add_doc_writes_label_and_tags_into_nested_metadata():
    """do_add_doc(file_version_label='rev-1', tags=['GPIO']) → 嵌套 metadata
    含 file_version_label='rev-1' / tags=['GPIO']。"""
    svc = _make_svc()
    doc = Document(page_content="hello", metadata={"source": "f.md"})
    svc.do_add_doc([doc], file_version_label="rev-1", tags=["GPIO", ""])

    called = svc.milvus.add_documents.call_args[0][0]
    nested = called[0].metadata["metadata"]
    assert nested["file_version_label"] == "rev-1"
    assert nested["tags"] == ["GPIO"]  # 空串被过滤
    assert "file_version" not in nested  # 不写 INT


def test_do_add_doc_no_label_tags_omits_nested_keys():
    """不传 label/tags 时，嵌套层不出现这两个 key（向后兼容存量）。"""
    svc = _make_svc()
    doc = Document(page_content="hello", metadata={"source": "f.md"})
    svc.do_add_doc([doc])
    called = svc.milvus.add_documents.call_args[0][0]
    nested = called[0].metadata["metadata"]
    assert "file_version_label" not in nested
    assert "tags" not in nested


def test_do_add_doc_with_embeddings_writes_label_and_tags():
    """do_add_doc_with_embeddings 经 _transactional_milvus_insert 设置
    _current_file_version_label/_current_tags，嵌套写入。"""
    svc = _make_svc()
    captured = {}
    def _fake_add(docs):
        captured["nested"] = docs[0].metadata["metadata"]
        return [1]
    svc.milvus.add_documents.side_effect = _fake_add

    doc = Document(page_content="hi", metadata={"source": "f.md"})
    svc._transactional_milvus_insert(
        [doc], [[0.1, 0.2]], file_name="f.md",
        file_version_label="rev-2", tags=["UART"])
    assert captured["nested"]["file_version_label"] == "rev-2"
    assert captured["nested"]["tags"] == ["UART"]


def test_base_add_doc_passes_label_tags_to_transactional_insert(mocker):
    """KBService.add_doc 把 kwargs 里的 file_version_label/tags 透传给
    _transactional_milvus_insert。"""
    # KBService 含 @abstractmethod（do_add_doc 等 8 个），KBService.__new__
    # 会 TypeError；用其具体子类 MilvusKBService（继承 base.add_doc 不重写）
    # 测同一份 base.py add_doc 透传逻辑。
    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "test_kb"
    svc.embed_model = "text-embedding-v3"
    mocker.patch.object(svc, "_batch_embed_with_retry", return_value=[[0.1, 0.2]])
    captured = {}
    def _fake_tx(docs, embs, file_name, **kw):
        captured.update(kw)
        return [1]
    mocker.patch.object(svc, "_transactional_milvus_insert", side_effect=_fake_tx)
    mocker.patch("server.knowledge_base.kb_service.base.add_file_to_db")
    kb_file = MagicMock()
    kb_file.filename = "f.md"
    kb_file.file2text.return_value = [Document(page_content="hi", metadata={"source": "f.md"})]

    svc.add_doc(kb_file, file_version_label="rev-1", tags=["GPIO"])
    assert captured.get("file_version_label") == "rev-1"
    assert captured.get("tags") == ["GPIO"]
