"""_search_milvus_sync tags 过滤 UT。"""
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

# Milvus 连接 shim：milvus_retrieval.py L15 模块级 MilvusClient eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


from server.knowledge_base.search_filter import SearchFilter
from server.chat.pipeline import milvus_retrieval


def test_search_milvus_sync_tags_builds_filter(mocker):
    """tags=['GPIO'] → milvus_client.search 收到 filter 含 json_contains。"""
    fake_client = MagicMock()
    fake_client.search.return_value = [[]]
    mocker.patch.object(milvus_retrieval, "milvus_client", fake_client)
    mocker.patch.object(milvus_retrieval, "get_embeddings",
                        lambda qs: MagicMock(to_list=lambda: [[0.1]]))
    milvus_retrieval._search_milvus_sync("q", 5, "kb", 0.5, filter_opts=SearchFilter(tags=["GPIO"]))
    kw = fake_client.search.call_args[1]
    assert 'json_contains(metadata["tags"], "GPIO")' in kw.get("filter", "")


def test_search_milvus_sync_tags_none_no_filter(mocker):
    """tags=None 且无版本记录 → filter 不在 search_kwargs 或为 None。

    P2：version=None 默认查 build_files_version_map；mock 返回 {} 模拟空库，
    filter_expr 保持 None。
    """
    fake_client = MagicMock()
    fake_client.search.return_value = [[]]
    mocker.patch.object(milvus_retrieval, "milvus_client", fake_client)
    mocker.patch.object(milvus_retrieval, "get_embeddings",
                        lambda qs: MagicMock(to_list=lambda: [[0.1]]))
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={})
    milvus_retrieval._search_milvus_sync("q", 5, "kb", 0.5)
    kw = fake_client.search.call_args[1]
    assert "filter" not in kw or kw.get("filter") is None
