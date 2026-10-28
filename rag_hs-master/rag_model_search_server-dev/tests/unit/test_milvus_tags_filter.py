"""Milvus tags 过滤 UT。"""
# pylint: disable=no-use-sys-path-insert
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
            # kwargs 实例化。裸 type(attr, (), {}) 不接受 kwargs，故加 __init__。
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


from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService


def _make_svc():
    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "test_kb"
    svc.embed_model = "text-embedding-v3"
    svc.milvus = MagicMock()
    return svc


def test_do_search_tags_builds_json_contains_filter(mocker):
    """do_search(tags=['GPIO','UART']) → filter 含 json_contains OR。"""
    svc = _make_svc()
    mocker.patch.object(MilvusKBService, "_load_milvus", lambda self: None)
    mocker.patch("server.knowledge_base.kb_service.milvus_kb_service.EmbeddingsFunAdapter"
                 ).return_value.embed_query.return_value = [0.1]
    svc.do_search("q", 5, 0.5, tags=["GPIO", "UART"])
    kw = svc.milvus.similarity_search_with_score_by_vector.call_args[1]
    filt = kw.get("filter") or ""
    assert 'json_contains(metadata["tags"], "GPIO")' in filt
    assert 'json_contains(metadata["tags"], "UART")' in filt
    assert " or " in filt


def test_do_search_tags_none_no_filter(mocker):
    """do_search(tags=None, version=None) 且无版本记录 → filter=None。

    P2：version=None 默认查 build_files_version_map；mock 返回 {} 模拟空库，
    filter_expr 保持 None（fail-open/无版本记录不加过滤）。
    """
    svc = _make_svc()
    mocker.patch.object(MilvusKBService, "_load_milvus", lambda self: None)
    mocker.patch("server.knowledge_base.kb_service.milvus_kb_service.EmbeddingsFunAdapter"
                 ).return_value.embed_query.return_value = [0.1]
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={})
    svc.do_search("q", 5, 0.5)
    kw = svc.milvus.similarity_search_with_score_by_vector.call_args[1]
    assert kw.get("filter") is None


def test_do_search_tags_and_version_combined(mocker):
    """tags + version 同时传 → (tags OR) and version。"""
    svc = _make_svc()
    mocker.patch.object(MilvusKBService, "_load_milvus", lambda self: None)
    mocker.patch("server.knowledge_base.kb_service.milvus_kb_service.EmbeddingsFunAdapter"
                 ).return_value.embed_query.return_value = [0.1]
    svc.do_search("q", 5, 0.5, version="rev-1", tags=["GPIO"])
    kw = svc.milvus.similarity_search_with_score_by_vector.call_args[1]
    filt = kw.get("filter") or ""
    assert 'json_contains(metadata["tags"], "GPIO")' in filt
    assert 'metadata["file_version_label"] == "rev-1"' in filt
    assert " and " in filt
