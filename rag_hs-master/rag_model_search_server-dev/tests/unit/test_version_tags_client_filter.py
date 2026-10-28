"""Task 5 / Bug D：三路过滤一致 UT。

验证 dual_mcp / mempalace 检索路客户端 version/tags 过滤与 Milvus 路语义对齐：
- version 指定：精确匹配，无 label 不匹配
- version=None + cur_labels 非空：旧 label 过滤掉，无 label 保留
- version=None + cur_labels 空：fail-open 不过滤
- tags：OR 语义
- _dual_mcp_search / _search_mempalace_async 传 version/tags 后调 _matches_version_tags

环境说明（复用 test_chat_version_filter 的 shim 模式）。
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

from unittest.mock import AsyncMock, MagicMock


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

from langchain.docstore.document import Document

# ── 辅助类 ──

class _FakeResponse:
    def __init__(self, status=200, payload=None):
        self.status_code = status
        self._payload = payload or {}

    @staticmethod
    def raise_for_status():
        pass

    def json(self):
        return self._payload


def _mkdoc(label=None, tags=None):
    meta = {}
    if label is not None:
        meta["file_version_label"] = label
    if tags is not None:
        meta["tags"] = tags
    return Document(page_content="x", metadata=meta)


def _patch_dual_mcp_http(mocker):
    """统一 mock dual_mcp 的 HTTP 流程：直接 patch 模块级单例 _dual_mcp_client.get。

    与 mempalace 测试（mocker.patch.object(mp._mp_client, "get", ...)）一致，
    dual_mcp 改用模块级单例后不再走 get_httpx_client → async with 流程。
    """
    from server.chat.pipeline import dual_mcp
    mock_resp = _FakeResponse(200, {"projects": []})
    mocker.patch.object(dual_mcp._dual_mcp_client, "get", AsyncMock(return_value=mock_resp))


# ── _matches_version_tags 单元测试 ──

def test_matches_version_specified_exact():
    """version 指定 → 精确匹配，无 label 不匹配。"""
    _V12_KEY = 'v1.2'
    from server.chat.pipeline.doc_utils import _matches_version_tags
    assert _matches_version_tags(_mkdoc(label=_V12_KEY), version=_V12_KEY, tags=None) is True
    assert _matches_version_tags(_mkdoc(label="v1.3"), version=_V12_KEY, tags=None) is False
    assert _matches_version_tags(_mkdoc(label=None), version=_V12_KEY, tags=None) is False


def test_matches_version_default_current_filters_old():
    """version=None + cur_labels 非空 → 旧 label 过滤，无 label 保留。"""
    from server.chat.pipeline.doc_utils import _matches_version_tags
    cur = {"v1.2", "v2.0"}
    assert _matches_version_tags(_mkdoc(label="v1.2"), version=None, tags=None, cur_labels=cur) is True
    assert _matches_version_tags(_mkdoc(label="v1.0"), version=None, tags=None, cur_labels=cur) is False
    assert _matches_version_tags(_mkdoc(label=None), version=None, tags=None, cur_labels=cur) is True


def test_matches_version_default_empty_no_filter():
    """version=None + cur_labels 空 → fail-open 不过滤。"""
    from server.chat.pipeline.doc_utils import _matches_version_tags
    assert _matches_version_tags(_mkdoc(label="v1.0"), version=None, tags=None, cur_labels=None) is True
    assert _matches_version_tags(_mkdoc(label="v1.0"), version=None, tags=None, cur_labels=set()) is True
    assert _matches_version_tags(_mkdoc(label=None), version=None, tags=None, cur_labels=None) is True


def test_matches_tags_or_semantics():
    """tags OR 语义：含至少一个 tag 即保留。"""
    from server.chat.pipeline.doc_utils import _matches_version_tags
    assert _matches_version_tags(_mkdoc(tags=["a", "b"]), version=None, tags=["b", "c"]) is True
    assert _matches_version_tags(_mkdoc(tags=["x"]), version=None, tags=["a", "b"]) is False
    assert _matches_version_tags(_mkdoc(tags=None), version=None, tags=["a"]) is False
    assert _matches_version_tags(_mkdoc(tags=None), version=None, tags=None) is True


def test_matches_version_and_tags_combined():
    """version + tags 叠加：同时满足。"""
    _V12_KEY = 'v1.2'
    from server.chat.pipeline.doc_utils import _matches_version_tags
    cur = {_V12_KEY}
    assert _matches_version_tags(_mkdoc(label=_V12_KEY, tags=["a"]), version=None, tags=["a"], cur_labels=cur) is True
    assert _matches_version_tags(_mkdoc(label=_V12_KEY, tags=["x"]), version=None, tags=["a"], cur_labels=cur) is False
    assert _matches_version_tags(_mkdoc(label="v1.0", tags=["a"]), version=None, tags=["a"], cur_labels=cur) is False


# ── _dual_mcp_search 客户端过滤集成测试 ──

def test_dual_mcp_search_filters_by_version(mocker):
    """_dual_mcp_search(version=...) → 客户端过滤掉非指定版本的 code 块。"""
    _V12_KEY = 'v1.2'
    import asyncio

    from server.chat.pipeline import dual_mcp

    docs_in = [
        _mkdoc(label=_V12_KEY, tags=None),
        _mkdoc(label="v1.0", tags=None),
        _mkdoc(label=None),
    ]
    mocker.patch.object(dual_mcp, "_get_dual_mcp_base_url", return_value="http://x")
    _patch_dual_mcp_http(mocker)
    mocker.patch.object(dual_mcp, "_resolve_project", return_value="proj")
    mocker.patch.object(dual_mcp, "_query_project", AsyncMock(return_value=docs_in))
    mocker.patch.object(dual_mcp, "_normalize_code_source_sync", return_value={})

    r = asyncio.run(dual_mcp._dual_mcp_search("q", 8, "kb", filter_opts=SearchFilter(version=_V12_KEY)))
    labels = [d.metadata.get("file_version_label") for d in r]
    assert labels == [_V12_KEY], f"应只保留 v1.2，实际 {labels}"


def test_dual_mcp_search_filters_by_tags(mocker):
    """_dual_mcp_search(tags=[...]) → 客户端过滤掉无指定 tag 的 code 块。"""
    _V12_KEY = 'v1.2'
    import asyncio

    from server.chat.pipeline import dual_mcp

    docs_in = [
        _mkdoc(label=_V12_KEY, tags=["a"]),
        _mkdoc(label=_V12_KEY, tags=["b"]),
        _mkdoc(label=_V12_KEY, tags=None),
    ]
    mocker.patch.object(dual_mcp, "_get_dual_mcp_base_url", return_value="http://x")
    _patch_dual_mcp_http(mocker)
    mocker.patch.object(dual_mcp, "_resolve_project", return_value="proj")
    mocker.patch.object(dual_mcp, "_query_project", AsyncMock(return_value=docs_in))
    mocker.patch.object(dual_mcp, "_normalize_code_source_sync", return_value={})

    r = asyncio.run(dual_mcp._dual_mcp_search("q", 8, "kb", filter_opts=SearchFilter(version=_V12_KEY, tags=["a"])))
    assert len(r) == 1 and r[0].metadata.get("tags") == ["a"]


def test_dual_mcp_search_default_current_keeps_unlabeled(mocker):
    """version=None + KB 有版本记录 → 无 label 的 code 块保留（不破坏代码路）。"""
    _V12_KEY = 'v1.2'
    import asyncio

    from server.chat.pipeline import dual_mcp

    docs_in = [
        _mkdoc(label=_V12_KEY),
        _mkdoc(label="v1.0"),
        _mkdoc(label=None),
    ]
    mocker.patch.object(dual_mcp, "_get_dual_mcp_base_url", return_value="http://x")
    _patch_dual_mcp_http(mocker)
    mocker.patch.object(dual_mcp, "_resolve_project", return_value="proj")
    mocker.patch.object(dual_mcp, "_query_project", AsyncMock(return_value=docs_in))
    mocker.patch.object(dual_mcp, "_normalize_code_source_sync", return_value={})
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": _V12_KEY})

    r = asyncio.run(dual_mcp._dual_mcp_search("q", 8, "kb"))
    labels = [d.metadata.get("file_version_label") for d in r]
    assert _V12_KEY in labels and None in labels and "v1.0" not in labels


# ── _search_mempalace_async 客户端过滤集成测试 ──

def test_search_mempalace_filters_by_version(mocker):
    """_search_mempalace_async(version=...) → 客户端过滤掉非指定版本。"""
    _V12_KEY = 'v1.2'
    import asyncio

    from server.chat.pipeline import mempalace_retrieval as mp

    docs_in = [
        _mkdoc(label=_V12_KEY),
        _mkdoc(label="v1.0"),
    ]
    mocker.patch.object(mp, "_mempalace_available", return_value=True)
    mocker.patch.object(mp, "_mempalace_record_success")
    mocker.patch.object(mp, "_hits_to_docs", return_value=docs_in)
    async def _fake_get(*args, **kwargs):
        return _FakeResponse(200, {"results": []})
    mocker.patch.object(mp._mp_client, "get", _fake_get)

    r = asyncio.run(mp._search_mempalace_async("q", "kb", filter_opts=SearchFilter(version=_V12_KEY)))
    labels = [d.metadata.get("file_version_label") for d in r]
    assert labels == [_V12_KEY]


def test_search_mempalace_filters_by_tags(mocker):
    """_search_mempalace_async(tags=[...]) → 客户端 OR 过滤。"""
    _V12_KEY = 'v1.2'
    import asyncio

    from server.chat.pipeline import mempalace_retrieval as mp

    docs_in = [
        _mkdoc(label=_V12_KEY, tags=["x"]),
        _mkdoc(label=_V12_KEY, tags=["y"]),
    ]
    mocker.patch.object(mp, "_mempalace_available", return_value=True)
    mocker.patch.object(mp, "_mempalace_record_success")
    mocker.patch.object(mp, "_hits_to_docs", return_value=docs_in)
    async def _fake_get(*args, **kwargs):
        return _FakeResponse(200, {"results": []})
    mocker.patch.object(mp._mp_client, "get", _fake_get)

    r = asyncio.run(mp._search_mempalace_async("q", "kb", filter_opts=SearchFilter(version=_V12_KEY, tags=["x"])))
    assert len(r) == 1 and r[0].metadata.get("tags") == ["x"]