"""mempalace / dual-mcp 检索结果透出 tags + file_version_label UT。"""
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

# Milvus 连接 shim：dual_mcp.py 引 milvus_retrieval.milvus_client，模块级
# MilvusClient eagerly 连接，dev 容器无 Milvus 会 import 崩。替换成返回
# MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


from server.chat.pipeline import dual_mcp, mempalace_retrieval


def test_mempalace_hits_passthrough_tags_and_version_label():
    """_hits_to_docs 透出 hit 上的 tags 与 file_version_label（字符串）。"""
    data = {"results": [{
        "text": "abc",
        "source_path": "a.md",
        "similarity": 0.9,
        "file_version_label": "rev-1",
        "tags": ["GPIO", "UART"],
    }]}
    docs = mempalace_retrieval._hits_to_docs([("q", data)], "kb")
    assert docs[0].metadata.get("tags") == ["GPIO", "UART"]
    assert docs[0].metadata.get("file_version_label") == "rev-1"
    assert "file_version" not in docs[0].metadata


def test_dual_mcp_matches_passthrough_tags_and_version_label():
    """_matches_to_docs 透出 match 上的 tags 与 file_version_label，不再用 INT file_version。"""
    matches = [{
        "label": "func",
        "name": "foo",
        "file_path": "a.c",
        "start_line": 10,
        "in_degree": 1,
        "out_degree": 2,
        "file_version_label": "rev-3",
        "tags": ["I2C"],
    }]
    docs = dual_mcp._matches_to_docs(matches, "proj", "kb")
    assert docs[0].metadata.get("tags") == ["I2C"]
    assert docs[0].metadata.get("file_version_label") == "rev-3"
    assert "file_version" not in docs[0].metadata
