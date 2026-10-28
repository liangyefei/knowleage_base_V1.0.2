"""P0 回归测试：_retrieve_one_kb_one_query 三路并发检索。

回归场景：Bug D 修复时给 _search_milvus_sync 加 version=version 关键字参数，
但遗留了位置参数 None（映射到 version），导致
`TypeError: _search_milvus_sync() got multiple values for argument 'version'`。
因 asyncio.gather(return_exceptions=True)，TypeError 被包成 Exception 对象，
Milvus 文档检索路 100% 失效，kb_stats["degraded"] 恒为 True。

关键设计：mock 用 MagicMock + side_effect=同签名真函数。
MagicMock 接受任意参数（不报错），但调用 side_effect 时 Python 按真签名绑定，
重复 version 会真实抛 TypeError，从而回归此 bug。

环境说明（test-only shim，不动生产代码）：
- langchain meta_path finder：优先用真实 langchain，仅在其不可提供某子模块时占位。
- milvus_retrieval.py L15 模块级 MilvusClient eagerly 连接，dev 容器无 Milvus 会 import 崩，
  故把 pymilvus.MilvusClient 替换成返回 MagicMock 的工厂。
- openai_clients.py L30 模块级 eagerly 构造 AsyncOpenAI，dev 容器 openai/httpx 版本不匹配
  会 TypeError 崩，故把 openai.AsyncOpenAI/OpenAI 替换为工厂。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import importlib.abc
import importlib.machinery
import importlib.util
import sys
import types
from pathlib import Path
from typing import List, Optional
from unittest.mock import AsyncMock, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))


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

# Milvus 连接 shim
import pymilvus


def _mock_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_client

# OpenAI 客户端 shim
import openai

openai.AsyncOpenAI = _mock_client
openai.OpenAI = _mock_client

from server.knowledge_base.search_filter import SearchFilter


def _make_doc(label):
    from langchain.schema import Document
    return Document(page_content=f"doc-{label}", metadata={
        "file_version_label": label,
        "knowledge_base_name": "kb1",
    })


def _milvus_real_sig(query: str, top_k: int, knowledge_base_name: str,
                     score_threshold: float, **kwargs):
    """与 _search_milvus_sync 同签名；作为 side_effect 用以触发签名检查。

    H3403 重构后 _search_milvus_sync 用 filter_opts 替代 version/tags/cur_labels，
    此函数同步更新签名保持一致。
    precomputed_embedding/filter_opts 通过 kwargs 传入（H3403 后续去 too-many-arguments）。
    """
    return _milvus_real_sig._docs  # type: ignore[attr-defined]


def test_retrieve_one_kb_milvus_not_typeerror(mocker):
    """P0 回归：Milvus 路不应抛 TypeError，结果应是 mock 返回值。"""
    _V1_KEY = 'v1'
    _KB_NAME_KEY = 't1'
    _FILTER_KEY = 'filter_opts'
    import asyncio

    from server.chat import multi_knowledge_base_chat as mod

    milvus_docs = [_make_doc(_V1_KEY), _make_doc("v2")]
    dual_docs = [_make_doc(_V1_KEY)]
    mem_docs = [_make_doc("v2")]

    _milvus_real_sig._docs = milvus_docs  # type: ignore[attr-defined]
    milvus_mock = MagicMock(side_effect=_milvus_real_sig)
    mocker.patch.object(mod, "_search_milvus_sync", milvus_mock)
    dual_mock = AsyncMock(return_value=dual_docs)
    mocker.patch.object(mod, "_dual_mcp_search", dual_mock)
    mem_mock = AsyncMock(return_value=mem_docs)
    mocker.patch.object(mod, "_search_mempalace_async", mem_mock)

    results = asyncio.run(mod._retrieve_one_kb_one_query(
        "kb1", "hello", [0.1, 0.2], 8,
        route_keywords=[], score_threshold=0.3,
        filter_opts=SearchFilter(tags=[_KB_NAME_KEY], version=_V1_KEY),
    ))

    # 核心断言：Milvus 路结果应是 docs，不是 TypeError exception
    assert results[0] == milvus_docs, (
        f"Milvus 路返回异常（P0 bug 复现）：{results[0]!r}"
    )
    assert results[1] == dual_docs
    assert results[2] == mem_docs

    # 验证 _search_milvus_sync 调用参数：位置参数 4 个 + precomputed_embedding/filter_opts
    milvus_mock.assert_called_once()
    _args, _kwargs = milvus_mock.call_args
    assert len(_args) == 4, f"位置参数异常（可能有冗余 None）: {_args!r}"
    _flt = _kwargs.get(_FILTER_KEY)
    assert _flt is not None and _flt.version == _V1_KEY and _flt.tags == [_KB_NAME_KEY]

    # dual/mem 也收到 filter_opts
    dual_mock.assert_awaited_once()
    _, d_kwargs = dual_mock.call_args
    _d_flt = d_kwargs.get(_FILTER_KEY)
    assert _d_flt is not None and _d_flt.version == _V1_KEY and _d_flt.tags == [_KB_NAME_KEY]

    mem_mock.assert_awaited_once()
    _, m_kwargs = mem_mock.call_args
    _m_flt = m_kwargs.get(_FILTER_KEY)
    assert _m_flt is not None and _m_flt.version == _V1_KEY and _m_flt.tags == [_KB_NAME_KEY]


def test_retrieve_one_kb_default_version_no_conflict(mocker):
    """P0 回归：version=None 默认时也不应有 TypeError。"""
    import asyncio

    from server.chat import multi_knowledge_base_chat as mod

    milvus_docs = [_make_doc("v1")]
    _milvus_real_sig._docs = milvus_docs  # type: ignore[attr-defined]
    mocker.patch.object(mod, "_search_milvus_sync",
                        MagicMock(side_effect=_milvus_real_sig))
    mocker.patch.object(mod, "_dual_mcp_search", AsyncMock(return_value=[]))
    mocker.patch.object(mod, "_search_mempalace_async", AsyncMock(return_value=[]))

    results = asyncio.run(mod._retrieve_one_kb_one_query(
        "kb1", "hello", [0.1, 0.2], 8,
        route_keywords=[], score_threshold=0.3,
    ))
    assert results[0] == milvus_docs
