"""kb_doc_api 遗漏端点 file_version_label+tags 透传 UT。

Task 17：补齐 upload_docs / batch_upload_docs / update_docs 三个入口端点的
file_version_label + tags 参数，并验证它们透传给下游已改好的函数：
  - update_docs → _update_docs_internal
  - upload_docs → update_docs
  - batch_upload_docs → update_docs_use_path

环境说明（test-only shim，不动生产代码）：
本机 venv 装的是 langchain 1.2.15，而项目按 langchain 0.2.x 编写
（`langchain.docstore`、`langchain.chat_models.ChatOpenAI` 等已移除）。
为让 `import server.knowledge_base.kb_doc_api` 通过，这里注册一个 meta_path
finder，对 `langchain.*` 子模块返回占位 module（PEP 562 `__getattr__` 返回
一个真实类，保证被继承的 Document/Embeddings/CharacterTextSplitter 等可用）。
不 shadow `langchain_core/community/text_splitters`（独立真实包）。
"""
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
    """占位 loader：模块为空命名空间，未知属性经 PEP 562 __getattr__ 返回真实类。"""

    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            # 返回真实类（不是 MagicMock）：langchain 的 Document/Embeddings/
            # CharacterTextSplitter 等在代码中被继承（class X(Doc)），
            # MagicMock 无法作为基类；真实类可以。
            # __init__ 接受 kwargs：Document(page_content=..., metadata=...) 需实例化
            return type(attr, (), {"__init__": lambda self, **kw: self.__dict__.update(kw)})
        module.__getattr__ = __getattr__


class _LangChainShimFinder(importlib.abc.MetaPathFinder):
    """对 langchain.* 子模块返回占位 spec；不处理 langchain_* 系独立包。"""

    def find_spec(self, fullname, path, target=None):
        if not fullname.startswith("langchain."):
            return None
        if fullname.startswith(("langchain_core", "langchain_community",
                                "langchain_text_splitters")):
            return None
        if fullname in sys.modules:
            return None
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

# Milvus 连接 shim：kb_doc_api.py L39 模块级 `client = MilvusClient(uri=...)`
# 在 import 时 eagerly 连 Milvus，dev 容器无 Milvus 会 import 崩溃。
# 把 pymilvus.MilvusClient 替换成返回 MagicMock 的工厂，避免真连；
# kb_doc_api L26 `from pymilvus import MilvusClient` 取到的是这里的 mock。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


def test_update_docs_endpoint_passes_label_tags_to_internal(mocker):
    """update_docs 端点把 file_version_label+tags 透传给 _update_docs_internal。"""
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import update_docs, UpdateDocsRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    mocker.patch.object(kb_doc_api, "_update_docs_internal",
                        return_value=({}, 1, {}))
    update_docs(request=UpdateDocsRequest(
        knowledge_base_name="kb1", file_names=["f.md"],
        file_version_label="rev-1", tags=["GPIO"]))
    args = kb_doc_api._update_docs_internal.call_args
    assert args.kwargs.get("file_version_label") == "rev-1"
    assert args.kwargs.get("tags") == ["GPIO"]


def test_upload_docs_endpoint_passes_label_tags_to_update_docs(mocker):
    """upload_docs 端点把 file_version_label+tags 透传给 update_docs。"""
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import upload_docs, UploadDocsRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    kb = MagicMock()
    mocker.patch.object(kb_doc_api, "KBServiceFactory")
    kb_doc_api.KBServiceFactory.get_service_by_name.return_value = kb
    mocker.patch.object(kb_doc_api, "_save_files_in_thread",
                        side_effect=lambda files, **kw: iter([]))
    inner_resp = MagicMock()
    inner_resp.data = {"failed_files": {}, "chunkCount": 0}
    mocker.patch.object(kb_doc_api, "update_docs", return_value=inner_resp)
    mocker.patch("server.knowledge_base.file_version.cleanup_async")

    files = [MagicMock()]
    files[0].filename = "f.md"
    # docs 显式传 {}：直接调用端点时 Form({}) 默认值是 FieldInfo 对象，无 .keys()；
    # 传 {} 使 file_names=[] 并跳过 is_code_file 分支。
    upload_docs(files=files, request=UploadDocsRequest(
        knowledge_base_name="kb1", docs={}, file_version_label="rev-1", tags=["GPIO"]))
    args = kb_doc_api.update_docs.call_args
    assert args.kwargs["request"].file_version_label == "rev-1"
    assert args.kwargs["request"].tags == ["GPIO"]


def test_batch_upload_docs_endpoint_passes_label_tags(mocker):
    """batch_upload_docs 端点把 file_version_label+tags 透传给 update_docs_use_path。"""
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import batch_upload_docs, BatchUploadDocsRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    kb = MagicMock()
    mocker.patch.object(kb_doc_api, "KBServiceFactory")
    kb_doc_api.KBServiceFactory.get_service_by_name.return_value = kb
    mocker.patch.object(kb_doc_api, "get_all_file_paths", return_value=["/p/f.md"])
    mocker.patch.object(kb_doc_api, "_checking_files_in_thread",
                        side_effect=lambda files, **kw: iter([]))
    inner_resp = MagicMock()
    inner_resp.data = {"failed_files": {}}
    mocker.patch.object(kb_doc_api, "update_docs_use_path", return_value=inner_resp)

    batch_upload_docs(request=BatchUploadDocsRequest(
        files_paths=["/p"], knowledge_base_name="kb1",
        file_version_label="rev-1", tags=["GPIO"]))
    args = kb_doc_api.update_docs_use_path.call_args
    assert args.kwargs["request"].file_version_label == "rev-1"
    assert args.kwargs["request"].tags == ["GPIO"]


def test_update_docs_internal_passes_tags_to_kb_update_doc(mocker):
    """_update_docs_internal 内部 kb.update_doc 调用接收 tags 透传（B1 回归防护）。

    覆盖 final review 发现的 Blocker：_update_docs_internal 把 tags 传给
    record_current/_store_docs_to_mempalace 但漏传给 kb.update_doc，导致
    Milvus metadata 永远不写 tags。本测试让 _update_docs_internal 真实执行
    到 kb.update_doc 调用，断言 tags 透传。
    """
    _FMD_KEY = 'f.md'
    from langchain.docstore.document import Document

    from server.knowledge_base import kb_doc_api

    kb = MagicMock()
    kb.update_doc.return_value = True
    mocker.patch.object(kb_doc_api, "KBServiceFactory")
    kb_doc_api.KBServiceFactory.get_service_by_name.return_value = kb
    mocker.patch.object(kb_doc_api, "get_file_detail", return_value={})
    mocker.patch.object(kb_doc_api, "KnowledgeFile", MagicMock())
    fake_docs = [Document(page_content="c", metadata={"source": _FMD_KEY})]
    mocker.patch.object(kb_doc_api, "files2docs_in_thread",
                        return_value=[(True, ("kb1", _FMD_KEY, fake_docs))])
    mocker.patch.object(kb_doc_api, "_store_docs_to_mempalace")
    mocker.patch("server.knowledge_base.file_version.record_current",
                 return_value=("rev-1", True))

    _failed, _total, _version_info = kb_doc_api._update_docs_internal(
        knowledge_base_name="kb1", file_names=[_FMD_KEY],
        not_refresh_vs_cache=True,
        file_version_label="rev-1", tags=["GPIO", "UART"])
    assert kb.update_doc.called, "kb.update_doc 未被调用"
    assert kb.update_doc.call_args.kwargs.get("tags") == ["GPIO", "UART"]
