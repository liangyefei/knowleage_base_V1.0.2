"""m2 UT：base.py search_docs 补 tags 参数透传给 do_search。

final review Minor m2：chat 路绕过 search_docs 直调 _search_milvus_sync，
但 search_docs 是管理端 / 单测用路，应与 do_search 签名对齐支持 tags。

环境说明（test-only shim，不动生产代码）：
本机 venv 装的是 langchain 1.2.15，而项目按 langchain 0.2.x 编写
（`langchain.docstore`、`langchain.embeddings.base.Embeddings` 等已移除）。
为让 `import server.knowledge_base.kb_service.base` 通过，这里注册一个
meta_path finder，对 `langchain.*` 子模块返回占位 module（PEP 562
`__getattr__` 返回真实类，保证被继承的 Document/Embeddings 可用）。
"""
# pylint: disable=no-use-sys-path-insert
import importlib.abc
import importlib.machinery
import logging
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

logger = logging.getLogger(__name__)

from unittest.mock import MagicMock

import pytest


class _LangChainShimLoader(importlib.abc.Loader):
    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            return type(attr, (), {"__init__": lambda self, **kw: self.__dict__.update(kw)})
        module.__getattr__ = __getattr__


class _LangChainShimFinder(importlib.abc.MetaPathFinder):
    """优先真实 langchain：真实可 import 时不介入，不可用才返回占位。

    避免收集阶段 shim 污染 sys.modules（占位 Document 无 metadata 属性，
    会让后续依赖真实 Document 的测试如 test_vectorize_transaction 失败）。
    """
    def find_spec(self, fullname, path, target=None):
        if not fullname.startswith("langchain."):
            return None
        if fullname.startswith(("langchain_core", "langchain_community",
                                "langchain_text_splitters")):
            return None
        if fullname in sys.modules:
            return None
        # 用其他 finder 检查真实是否可用；可用则不介入（返回 None 让默认 import 处理）
        for finder in sys.meta_path:
            if finder is self:
                continue
            try:
                spec = finder.find_spec(fullname, path, target)
            except Exception as e:
                logger.debug("[langchain-shim] finder.find_spec(%s) failed: %s", fullname, e)
                continue
            if spec is not None:
                return None
        # 真实不可用（如 venv langchain 1.2.15），返回占位
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())


def _make_svc():
    """构造 KBService 实例（ABC 不可直接 __new__，用 MagicMock(spec) 绑定真实 search_docs）。"""
    from server.knowledge_base.kb_service.base import KBService
    svc = MagicMock(spec=KBService)
    svc.search_docs = KBService.search_docs.__get__(svc)
    return svc


def test_search_docs_passes_tags_to_do_search(mocker):
    """search_docs(query, ..., tags=xs) → do_search(..., tags=xs)。"""
    svc = _make_svc()

    svc.search_docs("q", top_k=5, score_threshold=0.5, tags=["GPIO", "UART"])

    _, kwargs = svc.do_search.call_args
    # 签名 do_search(query, top_k, score_threshold, version=, tags=)
    assert kwargs.get("tags") == ["GPIO", "UART"]


def test_search_docs_tags_default_none(mocker):
    """不传 tags → do_search 收到 tags=None（向后兼容）。"""
    svc = _make_svc()

    svc.search_docs("q", top_k=5, score_threshold=0.5)

    kwargs = svc.do_search.call_args.kwargs
    assert kwargs.get("tags") is None
    assert kwargs.get("version") is None
