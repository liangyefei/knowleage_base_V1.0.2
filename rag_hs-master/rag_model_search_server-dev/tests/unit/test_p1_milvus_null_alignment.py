"""P1-1 回归测试：Milvus 默认当前版本 filter 加 `or is null` 对齐客户端语义。

背景：version=None + cur_labels 非空时，
- 客户端 _matches_version_tags 保留无 label 文档（保护 code 路代码 chunk）
- Milvus 服务端 `in [labels]` 排除无 label 文档（null 不匹配 in）
三路语义相反。P1-1 修复：Milvus filter 改为
`(file_version_label in [...]) or (file_version_label is null)`
让无 label 文档也通过，三路一致。

本测试验证三处 filter 构造（milvus_retrieval / kb_doc_api / milvus_kb_service）
在 version=None + cur_labels 非空时都含 `or ... is null`，
且 version 指定时不含 `is null`（精确匹配不放宽）。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import importlib.abc
import importlib.machinery
import importlib.util
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
            return type(attr, (), {"__init__": lambda self, **kw: self.__dict__.update(kw)})
        module.__getattr__ = __getattr__


class _LangChainShimFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path, target=None):
        if not fullname.startswith("langchain."):
            return None
        if fullname.startswith(("langchain_core", "langchain_community",
                                "langchain_text_splitters")):
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

from server.knowledge_base.search_filter import SearchFilter


def test_milvus_retrieval_default_filter_includes_null(mocker):
    """P1-1: milvus_retrieval 默认 filter 含 `or ... is null`。"""
    from server.chat.pipeline import milvus_retrieval as mr

    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"file_a.md": "v2", "file_b.md": "v1"})
    fake_client = MagicMock()
    fake_client.search = MagicMock(return_value=[[]])
    mocker.patch.object(mr, "milvus_client", fake_client)
    mocker.patch.object(mr, "get_embeddings",
                        return_value=MagicMock(tolist=lambda: [[0.1]]))

    mr._search_milvus_impl("q", 5, "kb", 0.0, precomputed_embedding=None, filter_opts=SearchFilter())

    filt = fake_client.search.call_args.kwargs.get("filter", "")
    assert 'in [' in filt, f"应含 in filter，实际={filt}"
    assert 'is null' in filt, f"P1-1: 默认 filter 应含 `or is null`，实际={filt}"
    assert '"v2"' in filt and '"v1"' in filt


def test_milvus_retrieval_version_specified_no_null(mocker):
    """P1-1: version 指定时 filter 是精确匹配，不含 `is null`。"""
    from server.chat.pipeline import milvus_retrieval as mr

    fake_client = MagicMock()
    fake_client.search = MagicMock(return_value=[[]])
    mocker.patch.object(mr, "milvus_client", fake_client)
    mocker.patch.object(mr, "get_embeddings",
                        return_value=MagicMock(tolist=lambda: [[0.1]]))

    mr._search_milvus_impl("q", 5, "kb", 0.0, precomputed_embedding=None, filter_opts=SearchFilter(version="v1.0"))

    filt = fake_client.search.call_args.kwargs.get("filter", "")
    assert 'metadata["file_version_label"] == "v1.0"' in filt
    assert 'is null' not in filt, "version 指定时不应含 is null"


def test_milvus_retrieval_null_with_tags_combination(mocker):
    """P1-1: 默认 filter + tags 叠加时，`or is null` 仍在括号内（不被 tags 破坏）。"""
    from server.chat.pipeline import milvus_retrieval as mr

    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": "v1"})
    fake_client = MagicMock()
    fake_client.search = MagicMock(return_value=[[]])
    mocker.patch.object(mr, "milvus_client", fake_client)
    mocker.patch.object(mr, "get_embeddings",
                        return_value=MagicMock(tolist=lambda: [[0.1]]))

    mr._search_milvus_impl("q", 5, "kb", 0.0, precomputed_embedding=None, filter_opts=SearchFilter(tags=["GPIO"]))

    filt = fake_client.search.call_args.kwargs.get("filter", "")
    # 结构：((in [...]) or (is null)) and (tags)
    assert 'is null' in filt
    assert 'json_contains' in filt
    assert 'and' in filt
    # is null 应在 and 之前的括号组内（version 子句），不被 and 拆散
    _null_idx = filt.find('is null')
    _and_idx = filt.find(' and ')
    assert _null_idx < _and_idx, f"`is null` 应在 `and` 之前（version 子句内），实际={filt}"


def test_kb_doc_api_search_milvus_default_includes_null(mocker):
    """P1-1: kb_doc_api._search_milvus 默认 filter 含 `or is null`。"""
    from server.knowledge_base import kb_doc_api as api

    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": "v1"})
    mocker.patch.object(api, "get_embeddings",
                        return_value=MagicMock(tolist=lambda: [[0.1]]))
    fake_client = MagicMock()
    fake_client.search = MagicMock(return_value=[[]])
    mocker.patch.object(api, "client", fake_client)

    api._search_milvus("q", 5, "kb", 0.0, filter_opts=SearchFilter())

    filt = fake_client.search.call_args.kwargs.get("filter", "")
    assert 'in [' in filt
    assert 'is null' in filt, f"P1-1: kb_doc_api 默认 filter 应含 `or is null`，实际={filt}"


def test_milvus_kb_service_do_search_default_includes_null(mocker):
    """P1-1: milvus_kb_service.do_search 默认 filter 含 `or is null`。"""
    from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService

    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": "v1"})
    mocker.patch.object(MilvusKBService, "_load_milvus")
    _fake_emb = mocker.patch("server.knowledge_base.kb_service.base.embed_texts")
    _fake_emb.return_value.data = [[0.1, 0.2]]
    mocker.patch("server.knowledge_base.kb_service.milvus_kb_service.score_threshold_process",
                 return_value=[])

    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "kb"
    svc.embed_model = "m"
    svc.milvus = MagicMock()
    svc.milvus.similarity_search_with_score_by_vector = MagicMock(return_value=[])

    MilvusKBService.do_search(svc, "q", 5, 0.0, version=None, tags=None)

    filt = svc.milvus.similarity_search_with_score_by_vector.call_args.kwargs.get("filter", "")
    assert 'in [' in filt
    assert 'is null' in filt, f"P1-1: do_search 默认 filter 应含 `or is null`，实际={filt}"
