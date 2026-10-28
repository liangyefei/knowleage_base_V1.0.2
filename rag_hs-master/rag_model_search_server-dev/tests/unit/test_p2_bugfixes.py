"""Task 6-9 / Bug F-G-H-I：P2 bug 修复 UT。

Bug F: verdiff 多库 fallback（version_diff._run_verdiff_for_kbs）
Bug G: elif 优先级（verdiff 提到 tech_keyword 前）
Bug H: verdiff kb_stats（hit_kbs 含 verdiff_doc 的 knowledge_base_name）
Bug I: in filter 转义（label 含双引号）

环境说明（复用 test_chat_version_filter 的 shim 模式）。
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


def _drain_asyncgen(gen):
    """消费 async generator（_phase_assemble 是 async generator，asyncio.run 需 coroutine）。"""
    async def _drain():
        async for _ in gen:
            pass
    asyncio.run(_drain())


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

# ── Bug F：verdiff 多库 fallback ──

_DIFF_FOUND = {
    "found": True, "file_name": "f.md", "from": "v1", "to": "v2",
    "from_sha": "aaa", "to_sha": "bbb", "from_size": 100, "to_size": 120,
    "identical": False, "added": 5, "removed": 2,
    "diff": "--- diff ---", "truncated": False,
}

_DIFF_NOT_FOUND = {"found": False, "file_name": "f.md"}

_DIFF_VERSION_UNAVAILABLE = {
    "found": True, "file_name": "f.md", "from": None, "to": None,
    "from_sha": None, "to_sha": None, "from_size": 0, "to_size": 0,
    "identical": False, "added": 0, "removed": 0, "diff": "", "truncated": False,
}


def test_run_verdiff_falls_back_to_next_kb_on_not_found(mocker):
    """Bug F: 第一个库 found=False → 继续第二个库。"""
    _KB2_KEY = 'kb2'
    from server.chat.pipeline import version_diff
    diff_calls = []

    def _fake_diff(kb, file, frm, to):
        diff_calls.append(kb)
        if kb == "kb1":
            return dict(_DIFF_NOT_FOUND)
        return dict(_DIFF_FOUND)
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                 side_effect=_fake_diff)
    mocker.patch.object(version_diff, "_search_version_chunks",
                        return_value=[])

    r = version_diff._run_verdiff_for_kbs(
        ["kb1", _KB2_KEY], {"file": "f.md", "from": "v1", "to": "v2"}, query="q")

    assert r is not None, "应在 kb2 命中并返回"
    assert r.get("knowledge_base_name") == _KB2_KEY
    assert diff_calls == ["kb1", _KB2_KEY], f"应遍历 kb1 和 kb2，实际 {diff_calls}"


def test_run_verdiff_falls_back_on_version_unavailable(mocker):
    """Bug F: 第一个库 found=True 但 from/to=None → 继续第二个库。"""
    from server.chat.pipeline import version_diff
    diff_calls = []

    def _fake_diff(kb, file, frm, to):
        diff_calls.append(kb)
        if kb == "kb1":
            return dict(_DIFF_VERSION_UNAVAILABLE)  # found=True 但版本不可用
        return dict(_DIFF_FOUND)
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                 side_effect=_fake_diff)
    mocker.patch.object(version_diff, "_search_version_chunks",
                        return_value=[])

    r = version_diff._run_verdiff_for_kbs(
        ["kb1", "kb2"], {"file": "f.md", "from": "v1", "to": "v2"}, query="q")

    assert r is not None, "kb1 版本不可用应 fallback 到 kb2"
    assert r.get("from") == "v1" and r.get("to") == "v2"  # kb2 的 _DIFF_FOUND
    assert diff_calls == ["kb1", "kb2"]


def test_run_verdiff_no_fallback_when_version_available(mocker):
    """Bug F 回归：第一个库版本可用 → 直接返回，不遍历后续库。"""
    _KB1_KEY = 'kb1'
    from server.chat.pipeline import version_diff
    diff_calls = []

    def _fake_diff(kb, file, frm, to):
        diff_calls.append(kb)
        return dict(_DIFF_FOUND)
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                 side_effect=_fake_diff)
    mocker.patch.object(version_diff, "_search_version_chunks",
                        return_value=[])

    r = version_diff._run_verdiff_for_kbs(
        [_KB1_KEY, "kb2"], {"file": "f.md", "from": "v1", "to": "v2"}, query="q")

    assert r is not None and r.get("knowledge_base_name") == _KB1_KEY
    assert diff_calls == [_KB1_KEY], "kb1 已命中不应再遍历 kb2"


# ── Bug G：elif 优先级 ──

def test_route_verdiff_priority_over_tech_keyword(mocker):
    """Bug G: query 同时命中 verdiff + tech_keyword → 走 verdiff 分支。"""
    from server.chat import multi_knowledge_base_chat as mod

    ctx = _make_route_ctx(query="v1.0 和 v2.0 的 GPIO 配置差异？")
    mocker.patch.object(mod, "_is_greeting", return_value=False)
    mocker.patch.object(mod, "_has_chitchat_keyword", return_value=False)
    mocker.patch.object(mod, "_has_tech_keyword", return_value=True)  # GPIO 命中
    mocker.patch.object(mod, "_detect_version_diff",
                        return_value={"file": "f.md", "from": "v1.0", "to": "v2.0"})
    mocker.patch.object(mod, "_detect_version_filter", return_value=None)
    mocker.patch.object(mod, "_extract_keywords_lac", AsyncMock(return_value=["GPIO"]))
    mocker.patch.object(mod, "_run_route", AsyncMock())

    asyncio.run(_collect_async_gen(mod._phase_route(ctx)))

    assert ctx.route_decision is not None or ctx.kb_stats.get("route_reason") == "version_diff", \
        f"应走 verdiff 分支，实际 route_reason={ctx.kb_stats.get('route_reason')}"
    assert ctx.kb_stats["route_reason"] == "version_diff"
    assert ctx.route_keywords == [], "verdiff 分支 route_keywords 应为空"


def test_route_tech_keyword_when_no_verdiff(mocker):
    """Bug G 回归：无 verdiff 意图 → 走 tech_keyword 分支。"""
    from server.chat import multi_knowledge_base_chat as mod

    ctx = _make_route_ctx(query="GPIO 配置方法")
    mocker.patch.object(mod, "_is_greeting", return_value=False)
    mocker.patch.object(mod, "_has_chitchat_keyword", return_value=False)
    mocker.patch.object(mod, "_has_tech_keyword", return_value=True)
    mocker.patch.object(mod, "_detect_version_diff", return_value=None)
    mocker.patch.object(mod, "_detect_version_filter", return_value=None)
    mocker.patch.object(mod, "_extract_keywords_lac", AsyncMock(return_value=["GPIO"]))
    mocker.patch.object(mod, "_run_route", AsyncMock())

    asyncio.run(_collect_async_gen(mod._phase_route(ctx)))

    assert ctx.kb_stats["route_reason"] == "tech_keyword"


# ── Bug H：verdiff kb_stats ──

def test_assemble_verdiff_hit_kbs_includes_verdiff_kb(mocker):
    """Bug H: verdiff 分支 docs=[] 但 verdiff_doc 命中 → hit_kbs 含 verdiff 的 kb。"""
    from server.chat import multi_knowledge_base_chat as mod

    ctx = _make_assemble_ctx()
    ctx.verdiff_ctx = "【版本差异】\n..."
    ctx.verdiff_doc = {
        "file": "f.md", "knowledge_base_name": "kb1",
        "doc_type": "version_diff", "content": "...",
    }
    ctx.docs = []  # verdiff 分支检索被跳过

    _drain_asyncgen(mod._phase_assemble(ctx))

    assert ctx.kb_stats["kb_hit"] is True
    assert "kb1" in ctx.kb_stats["hit_kbs"], \
        f"verdiff 命中时 hit_kbs 应含 kb1，实际 {ctx.kb_stats['hit_kbs']}"


def test_assemble_verdiff_unfound_hit_kbs_empty(mocker):
    """Bug H 回归：verdiff 未命中（doc=None）→ hit_kbs 为空，kb_hit=False。"""
    from server.chat import multi_knowledge_base_chat as mod

    ctx = _make_assemble_ctx()
    ctx.verdiff_ctx = "【版本差异】\n未找到文件"
    ctx.verdiff_doc = None
    ctx.docs = []

    _drain_asyncgen(mod._phase_assemble(ctx))

    assert ctx.kb_stats["kb_hit"] is False
    assert ctx.kb_stats["hit_kbs"] == []


# ── Bug I：in filter 转义 ──

def test_milvus_retrieval_in_filter_escapes_quotes(mocker):
    """Bug I: in [...] filter 对含双引号的 label 做转义。"""
    from server.chat.pipeline import milvus_retrieval as mr

    # mock build_files_version_map 返回含特殊字符的 label
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": 'v1.0"x'})
    fake_client = MagicMock()
    fake_client.search = MagicMock(return_value=[[]])
    mocker.patch.object(mr, "milvus_client", fake_client)
    mocker.patch.object(mr, "get_embeddings", return_value=MagicMock(tolist=lambda: [[0.1]]))

    mr._search_milvus_impl("q", 5, "kb", 0.0, precomputed_embedding=None, filter_opts=SearchFilter())

    call_kwargs = fake_client.search.call_args.kwargs
    filter_expr = call_kwargs.get("filter", "")
    # 转义后的 label 应含 \"
    assert 'in ["v1.0\\"x"]' in filter_expr, \
        f"in filter 应转义双引号，实际 filter={filter_expr}"


def test_milvus_kb_service_in_filter_escapes_quotes(mocker):
    """Bug I: milvus_kb_service.do_search 的 in filter 也转义。"""
    from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService

    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"f.md": 'v"x'})
    mocker.patch.object(MilvusKBService, "_load_milvus")  # 跳过真实 Milvus 初始化
    # mock embed_texts 返回带 .data 的对象（EmbeddingsFunAdapter.embed_query 内部调）
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

    call_kwargs = svc.milvus.similarity_search_with_score_by_vector.call_args.kwargs
    filter_expr = call_kwargs.get("filter", "")
    assert 'in ["v\\"x"]' in filter_expr, \
        f"in filter 应转义双引号，实际 filter={filter_expr}"


# ── 辅助函数 ──

async def _collect_async_gen(gen):
    """收集 async generator 的所有 yield，返回列表。"""
    out = []
    async for item in gen:
        out.append(item)
    return out


def _make_route_ctx(query="q"):
    """构造 _phase_route 所需的最小 ChatContext。"""
    from server.chat.multi_knowledge_base_chat import ChatContext
    ctx = ChatContext(
        query=query, history=[], valid_kbs=["kb"], top_k=5,
        score_threshold=0.5, model_name="m", prompt_name="default",
        deep_think=False, stream=False, temperature=0.1, max_tokens=None,
        message_id="", user_id="u", conversation_id="c",
    )
    ctx.kb_stats = {"rag_triggered": False, "no_hit_reason": None,
                    "route_reason": None}
    ctx.router_client = MagicMock()
    ctx.router_model_id = "router"
    return ctx


def _make_assemble_ctx():
    """构造 _phase_assemble 所需的最小 ChatContext。"""
    from server.chat.multi_knowledge_base_chat import ChatContext
    ctx = ChatContext(
        query="v1 和 v2 差异？", history=[], valid_kbs=["kb1"], top_k=5,
        score_threshold=0.5, model_name="m", prompt_name="default",
        deep_think=False, stream=False, temperature=0.1, max_tokens=None,
        message_id="", user_id="u", conversation_id="c",
    )
    ctx.kb_stats = {}
    ctx.docs = []
    ctx.verdiff_ctx = None
    ctx.verdiff_doc = None
    return ctx
