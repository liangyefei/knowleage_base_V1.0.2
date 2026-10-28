"""multi_knowledge_base_chat tags 全链路 UT。

Task 16：端到端贯通 chat 端点 tags 参数 → ChatContext → _retrieve_one_kb_one_query
→ _search_milvus_sync 过滤 → source_documents 透出 tags/file_version_label。

环境说明（test-only shim，不动生产代码）：
dev 容器已装 langchain 0.2.1 / langchain_core 0.2.3 / llama_index 0.9.34 / pymilvus 2.4.3。
- langchain meta_path finder：优先用真实 langchain，仅在真实包不可提供某子模块时
  （如本机 venv langchain 1.2.15 已移除 langchain.docstore 等）才回落到占位类。
  这样在 dev 容器下不 shadow 真实 langchain，避免污染同会话其他 UT 的 import。
- milvus_retrieval.py L15 模块级 MilvusClient eagerly 连接，dev 容器无 Milvus 会 import 崩，
  故把 pymilvus.MilvusClient 替换成返回 MagicMock 的工厂。
- openai_clients.py L30 模块级 eagerly 构造 AsyncOpenAI，dev 容器 openai/httpx 版本不匹配
  （httpx 移除 proxies 参数）会 TypeError 崩，故把 openai.AsyncOpenAI/OpenAI 替换为工厂。
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
    """占位 loader：模块为空命名空间，未知属性经 PEP 562 __getattr__ 返回真实类。"""

    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            # 占位类须可被继承（如 EmbeddingsFunAdapter(Embeddings)）且可接受
            # kwargs 实例化（如 Document(page_content=..., metadata=...)）。
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
        # 临时摘除自身避免递归，用剩余 finder 探测真实 langchain 是否可提供该子模块。
        sys.meta_path.remove(self)
        try:
            try:
                real_spec = importlib.util.find_spec(fullname)
            except Exception:
                real_spec = None
        finally:
            sys.meta_path.insert(0, self)
        if real_spec is not None:
            return None  # 真实 langchain 可提供，交给正常 import 链路
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

# Milvus 连接 shim：milvus_retrieval.py L15 模块级 MilvusClient(uri=...) eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_client

# OpenAI 客户端 shim：openai_clients.py L30 模块级 eagerly 构造 AsyncOpenAI(...)，
# dev 容器 openai/httpx 版本不匹配（httpx 移除 proxies 参数）会 TypeError 崩。
# 替换成返回 MagicMock 的工厂，避免 import 时真建客户端。
import openai

openai.AsyncOpenAI = _mock_client
openai.OpenAI = _mock_client

from server.knowledge_base.search_filter import SearchFilter


from server.chat.multi_knowledge_base_chat import ChatContext


def test_chat_context_has_tags_field():
    """ChatContext 支持 tags 字段，默认 None。"""
    ctx = ChatContext(
        query="q", history=[], valid_kbs=["kb"], top_k=5,
        score_threshold=0.5, model_name="m", prompt_name="default",
        deep_think=False, stream=False, temperature=0.1, max_tokens=None,
        message_id="", user_id="u", conversation_id="c",
    )
    assert hasattr(ctx, "tags")
    assert ctx.tags is None
    ctx.tags = ["GPIO"]
    assert ctx.tags == ["GPIO"]


def test_retrieve_one_kb_one_query_passes_tags_to_milvus(mocker):
    """ctx.tags 非空 → _retrieve_one_kb_one_query 把 tags 传给 _search_milvus_sync。"""
    from server.chat import multi_knowledge_base_chat as mod
    fake_milvus = MagicMock(return_value=[])
    mocker.patch.object(mod, "_search_milvus_sync", fake_milvus)
    mocker.patch.object(mod, "_dual_mcp_search", AsyncMock(return_value=[]))
    mocker.patch.object(mod, "_search_mempalace_async", AsyncMock(return_value=[]))

    import asyncio
    asyncio.run(mod._retrieve_one_kb_one_query(
        "kb", "q", [0.1], 5,
        route_keywords=[], score_threshold=0.5,
        filter_opts=SearchFilter(tags=["GPIO", "UART"])))

    _flt = fake_milvus.call_args.kwargs.get("filter_opts")
    assert _flt is not None and _flt.tags == ["GPIO", "UART"]


def test_source_documents_output_tags_and_version_label():
    """组装阶段：doc.metadata 含 tags/file_version_label → source_documents 输出含两字段。"""
    _TAGS_KEY = 'tags'
    _QUERY_KEY = 'GPIO'
    from langchain.docstore.document import Document

    from server.chat.multi_knowledge_base_chat import _build_source_documents
    docs = [Document(page_content="c", metadata={
        "source": "a.md", "doc_type": "milvus",
        "file_version_label": "rev-1", _TAGS_KEY: [_QUERY_KEY],
        "relevance_score": 0.9, "chunk_index": 0, "total_chunks": 1,
    })]
    out = _build_source_documents(docs, used_chunk_ids={id(docs[0])})
    assert out[0][_TAGS_KEY] == [_QUERY_KEY]
    assert out[0]["file_version_label"] == "rev-1"
    assert out[0]["chunks"][0][_TAGS_KEY] == [_QUERY_KEY]


def test_endpoint_accepts_tags_param():
    """multi_knowledge_base_chat 端点签名含 tags，Body(None) 可选默认 None。"""
    import inspect

    from server.chat.multi_knowledge_base_chat import multi_knowledge_base_chat
    sig = inspect.signature(multi_knowledge_base_chat)
    assert "tags" in sig.parameters
    # Body(None, ...) 的 default 是 Body 对象（非 Python None），非 ... 即可选
    assert sig.parameters["tags"].default is not ...
