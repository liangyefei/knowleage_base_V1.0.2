"""Task 20：chat 指定版本检索 UT。

端点加 version 参数 → ChatContext.version → _retrieve_one_kb_one_query →
_search_milvus_sync(version=...) → filter metadata["file_version_label"] == 指定label
（覆盖 Task 19 默认当前版本过滤）。

环境说明（test-only shim，复用 test_chat_tags_pipeline 的 shim 模式）：
- langchain meta_path finder：优先真实 langchain，仅在真实包不可提供某子模块时
  （venv langchain 1.2.15 已移除 langchain.docstore 等）才回落到占位类。
- milvus_retrieval 模块级 MilvusClient eagerly 连接，替换为 MagicMock 工厂。
- openai_clients 模块级 AsyncOpenAI eagerly 构造，替换为 MagicMock 工厂。
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
    """对 langchain.* 子模块：优先真实 langchain，仅在其不可提供时返回占位 spec。"""

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


from server.chat.multi_knowledge_base_chat import ChatContext


def test_chat_context_has_version_field():
    """ChatContext 支持 version 字段，默认 None。"""
    ctx = ChatContext(
        query="q", history=[], valid_kbs=["kb"], top_k=5,
        score_threshold=0.5, model_name="m", prompt_name="default",
        deep_think=False, stream=False, temperature=0.1, max_tokens=None,
        message_id="", user_id="u", conversation_id="c",
    )
    assert hasattr(ctx, "version")
    assert ctx.version is None
    ctx.version = "v1.0"
    assert ctx.version == "v1.0"


def test_retrieve_one_kb_one_query_passes_version_to_milvus(mocker):
    """ctx.version 非空 → _retrieve_one_kb_one_query 把 version 传给 _search_milvus_sync。"""
    from server.chat import multi_knowledge_base_chat as mod
    fake_milvus = MagicMock(return_value=[])
    mocker.patch.object(mod, "_search_milvus_sync", fake_milvus)
    mocker.patch.object(mod, "_dual_mcp_search", AsyncMock(return_value=[]))
    mocker.patch.object(mod, "_search_mempalace_async", AsyncMock(return_value=[]))

    import asyncio
    asyncio.run(mod._retrieve_one_kb_one_query(
        "kb", "q", [0.1], 5,
        route_keywords=[], score_threshold=0.5,
        filter_opts=SearchFilter(version="v1.0")))

    _flt = fake_milvus.call_args.kwargs.get("filter_opts")
    assert _flt is not None and _flt.version == "v1.0"


def test_endpoint_accepts_version_param():
    """chat 端点签名含 version 参数，Body(None) 可选默认 None。"""
    import inspect

    from server.chat.multi_knowledge_base_chat import multi_knowledge_base_chat
    sig = inspect.signature(multi_knowledge_base_chat)
    assert "version" in sig.parameters
    # Body(None, ...) 的 default 是 Body 对象（非 Python None），非 ... 即可选
    assert sig.parameters["version"].default is not ...
