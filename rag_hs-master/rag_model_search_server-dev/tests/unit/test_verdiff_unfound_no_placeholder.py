"""Task 4 / Bug C：verdiff 未命中不触发"无法回答"占位 UT。

_build_verdiff_context 在版本未命中（vres=None 或 from/to 不可用）时返回
(提示文本, None)。此时 source_documents=[]，但 context 已含对比提示文本，
应交由 LLM 基于 context 回答，而非塞红色"未找到相关文档"占位。

修复：L754 占位条件加 `and _verdiff_ctx is None`。

环境说明（复用 test_chat_version_filter 的 shim 模式）：
- langchain meta_path finder：优先真实 langchain，仅占位不可提供的子模块。
- milvus_retrieval 模块级 MilvusClient eagerly 连接，替换为 MagicMock 工厂。
- openai_clients 模块级 AsyncOpenAI eagerly 构造，替换为 MagicMock 工厂。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import asyncio
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

from server.chat.multi_knowledge_base_chat import ChatContext


def _make_ctx(verdiff_ctx=None, source_documents=None):
    """构造 _phase_generate 所需的最小 ChatContext。"""
    ctx = ChatContext(
        query="v1 和 v2 的差异？", history=[], valid_kbs=["kb"], top_k=5,
        score_threshold=0.5, model_name="m", prompt_name="default",
        deep_think=False, stream=True, temperature=0.1, max_tokens=None,
        message_id="", user_id="u", conversation_id="c",
    )
    ctx.need_rag = True
    ctx.verdiff_ctx = verdiff_ctx
    ctx.verdiff_doc = None
    ctx.docs = []
    ctx.context = verdiff_ctx or ""
    ctx.source_documents = source_documents if source_documents is not None else []
    ctx.answer_query = ctx.query
    ctx.search_queries = [ctx.query]
    ctx.kb_stats = {"no_hit_reason": None, "degraded": False, "retrieved_count": 0,
                    "used_count": 0, "kb_hit": False, "hit_kbs": [], "top_score": 0.0}
    ctx.openai_client = MagicMock()
    ctx.config = {}
    ctx.profile_prefix = ""
    return ctx


async def _run_phase_generate(ctx, captured):
    """跑 _phase_generate，捕获传给 _stream_llm_response 的 source_documents。"""
    from server.chat import multi_knowledge_base_chat as mod

    async def _fake_stream(**kw):
        captured["source_documents"] = kw.get("source_documents")
        if False:  # 使其成为 async generator
            yield ""

    # patch _finish_mem（避免真实记忆检索）+ _stream_llm_response（捕获参数）
    mod._finish_mem = AsyncMock(return_value="")
    # _stream_llm_response 是 async generator，用 side_effect 返回 async gen
    import unittest.mock as _um
    _um.patch.object(mod, "_stream_llm_response", side_effect=_fake_stream).start()

    async for _ in mod._phase_generate(ctx):
        pass


def test_verdiff_unfound_no_placeholder():
    """verdiff_ctx 非 None + source_documents=[] → 不触发占位。"""
    captured = {}
    ctx = _make_ctx(verdiff_ctx="【版本差异】\n未找到文件 f.md 的版本记录。",
                    source_documents=[])

    asyncio.run(_run_phase_generate(ctx, captured))

    sd = captured.get("source_documents", [])
    # 不应含"未找到相关文档"占位
    assert not any("未找到相关文档" in (d.get("text", "") if isinstance(d, dict) else "")
                   for d in sd), f"verdiff 分支不应触发占位，实际 source_documents={sd}"


def test_normal_rag_unfound_triggers_placeholder():
    """verdiff_ctx=None + source_documents=[] → 触发占位（回归保护）。"""
    captured = {}
    ctx = _make_ctx(verdiff_ctx=None, source_documents=[])

    asyncio.run(_run_phase_generate(ctx, captured))

    sd = captured.get("source_documents", [])
    assert any("未找到相关文档" in (d.get("text", "") if isinstance(d, dict) else "")
               for d in sd), f"普通 RAG 分支应触发占位，实际 source_documents={sd}"


def test_verdiff_with_docs_no_placeholder():
    """verdiff_ctx 非 None + source_documents 非空 → 不触发占位（有命中时本就不该占位）。"""
    captured = {}
    ctx = _make_ctx(verdiff_ctx="【版本差异】\n...",
                    source_documents=[{"filename": "f.md", "page_content": "...",
                                       "text": "...", "num": 1, "url": ""}])

    asyncio.run(_run_phase_generate(ctx, captured))

    sd = captured.get("source_documents", [])
    assert not any("未找到相关文档" in (d.get("text", "") if isinstance(d, dict) else "")
                   for d in sd), f"有命中时不应触发占位，实际 source_documents={sd}"
