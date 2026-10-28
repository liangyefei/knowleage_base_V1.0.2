"""Task 18：响应体透出 version_info UT。

让入库调用方立即知道每个文件的版本 label + tags，不必事后查 list_versions。

覆盖：
  - _update_docs_internal 返回三元组 (failed, chunks, version_info)
  - update_docs 端点响应 data 含 version_info
  - update_docs_use_path 端点响应 data 含 version_info
  - upload_docs 从 update_docs 响应取 version_info 透传

环境说明（test-only shim，不动生产代码；复制自 test_kb_doc_api_tags.py）：
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

_FMD_KEY = 'f.md'
_FILE_NAME_KEY = 'f.md'
_REV1_KEY = 'rev-1'
_REV2_KEY = 'rev-2'


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


def test_update_docs_internal_returns_version_info(mocker):
    """_update_docs_internal 返回三元组，version_info 含 label+tags。"""
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
                 return_value=(_REV1_KEY, True))

    _failed, _total, version_info = kb_doc_api._update_docs_internal(
        knowledge_base_name="kb1", file_names=[_FMD_KEY],
        not_refresh_vs_cache=True,
        file_version_label=_REV1_KEY, tags=["GPIO", "UART"])
    assert _FMD_KEY in version_info
    assert version_info[_FMD_KEY]["file_version_label"] == _REV1_KEY
    assert version_info[_FMD_KEY]["tags"] == ["GPIO", "UART"]


def test_update_docs_endpoint_response_has_version_info(mocker):
    """update_docs 端点响应 data 含 version_info。"""
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import UpdateDocsRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    mocker.patch.object(kb_doc_api, "_update_docs_internal",
                        return_value=({}, 1, {_FMD_KEY: {"file_version_label": _REV1_KEY, "tags": ["GPIO"]}}))
    resp = kb_doc_api.update_docs(request=UpdateDocsRequest(
        knowledge_base_name="kb1", file_names=[_FMD_KEY],
        file_version_label=_REV1_KEY, tags=["GPIO"]))
    assert "version_info" in resp.data
    assert resp.data["version_info"][_FMD_KEY]["file_version_label"] == _REV1_KEY


def test_update_docs_use_path_response_has_version_info(mocker):
    """update_docs_use_path 端点响应 data 含 version_info（kb.update_doc 成功后收集）。"""
    from langchain.docstore.document import Document

    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import UpdateDocsUsePathRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    kb = MagicMock()
    kb.update_doc.return_value = True
    mocker.patch.object(kb_doc_api, "KBServiceFactory")
    kb_doc_api.KBServiceFactory.get_service_by_name.return_value = kb
    mocker.patch.object(kb_doc_api, "KnowledgeFile", MagicMock())
    mocker.patch.object(kb_doc_api, "files2docs_in_thread",
                        return_value=[(True, ("kb1", _FMD_KEY, [Document(page_content="c", metadata={"source": _FMD_KEY})]))])
    mocker.patch.object(kb_doc_api, "_store_docs_to_mempalace")
    mocker.patch("server.knowledge_base.file_version.record_current",
                 return_value=(_REV2_KEY, True))
    resp = kb_doc_api.update_docs_use_path(request=UpdateDocsUsePathRequest(
        knowledge_base_name="kb1", file_paths=["/p/f.md"],
        docs={}, not_refresh_vs_cache=True,
        file_version_label=_REV2_KEY, tags=["UART"]))
    assert "version_info" in resp.data
    assert resp.data["version_info"][_FMD_KEY]["file_version_label"] == _REV2_KEY


def test_upload_docs_propagates_version_info(mocker):
    """upload_docs 从 update_docs 响应取 version_info 透传。"""
    _VERSION_INFO_KEY = 'version_info'
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import UploadDocsRequest
    mocker.patch.object(kb_doc_api, "validate_kb_name", return_value=True)
    mocker.patch.object(kb_doc_api, "KBServiceFactory")
    kb_doc_api.KBServiceFactory.get_service_by_name.return_value = MagicMock()
    mocker.patch.object(kb_doc_api, "_save_files_in_thread",
                        side_effect=lambda files, **kw: iter([]))
    inner_resp = MagicMock()
    inner_resp.code = 200
    inner_resp.data = {"failed_files": {}, "chunkCount": 1,
                       _VERSION_INFO_KEY: {_FILE_NAME_KEY: {"file_version_label": _REV1_KEY, "tags": ["GPIO"]}}}
    mocker.patch.object(kb_doc_api, "update_docs", return_value=inner_resp)
    mocker.patch("server.knowledge_base.file_version.cleanup_async")
    files = [MagicMock()]
    files[0].filename = _FILE_NAME_KEY
    resp = kb_doc_api.upload_docs(files=files, request=UploadDocsRequest(
        knowledge_base_name="kb1", docs={}, file_version_label=_REV1_KEY, tags=["GPIO"]))
    assert _VERSION_INFO_KEY in resp.data
    assert resp.data[_VERSION_INFO_KEY][_FILE_NAME_KEY]["file_version_label"] == _REV1_KEY
