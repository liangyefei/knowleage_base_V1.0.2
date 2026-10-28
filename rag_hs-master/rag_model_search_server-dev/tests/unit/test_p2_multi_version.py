"""P2 版本共存 UT：update_doc 追加不删 + 默认检索当前版本过滤。

环境说明（test-only shim，不动生产代码）：
本机 venv 装的是 langchain 1.2.15，而项目按 langchain 0.2.x 编写
（`langchain.docstore`、`langchain.embeddings.base.Embeddings` 等已移除）。
为让 `import server.knowledge_base.kb_service.base` 通过，这里注册一个
meta_path finder，对 `langchain.*` 子模块返回占位 module（PEP 562
`__getattr__` 返回真实类，保证被继承的 Document/Embeddings 可用）。
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
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

# Milvus 连接 shim：milvus_retrieval.py 模块级 MilvusClient eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client

from server.knowledge_base.search_filter import SearchFilter


def test_update_doc_keeps_old_version_chunks(mocker):
    """update_doc 后，旧版本 chunk 仍在 Milvus（不 delete）。

    KBService 是 ABC，不可直接 __new__（有抽象方法 do_add_doc/do_clear_vs/
    do_create_kb/do_delete_doc/do_drop_kb/do_init/do_search/vs_type，__new__
    也触发检查）。用 MagicMock(spec=KBService) + 绑定真实 update_doc，
    delete_doc/add_doc 用 MagicMock 桩（不调真实实现）。
    """
    from server.knowledge_base.kb_service.base import KBService
    svc = MagicMock(spec=KBService)
    svc.update_doc = KBService.update_doc.__get__(svc)  # 绑定真实方法
    svc.delete_doc = MagicMock()  # 不应被调（P2 不删旧 chunk）
    svc.add_doc = MagicMock(return_value=True)
    mocker.patch("os.path.exists", return_value=True)
    kb_file = MagicMock()
    kb_file.filepath = "/p/f.md"

    svc.update_doc(kb_file, docs=[], file_version_label="v2")

    svc.delete_doc.assert_not_called()  # P2：不删旧 chunk
    svc.add_doc.assert_called_once()


def test_search_milvus_default_filters_current_version(mocker):
    """_search_milvus_impl 默认加 filter：file_version_label in [当前所有 label]。"""
    from server.chat.pipeline import milvus_retrieval
    fake_client = MagicMock()
    fake_client.search.return_value = [[]]
    mocker.patch.object(milvus_retrieval, "milvus_client", fake_client)
    mocker.patch.object(milvus_retrieval, "get_embeddings",
                        lambda qs: MagicMock(to_list=lambda: [[0.1]]))
    # 当前版本映射：file_a → v2, file_b → v1
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                 return_value={"file_a.md": "v2", "file_b.md": "v1"})

    milvus_retrieval._search_milvus_sync("q", 5, "kb", 0.5)

    kw = fake_client.search.call_args[1]
    filt = kw.get("filter", "")
    assert 'metadata["file_version_label"] in' in filt
    assert '"v2"' in filt and '"v1"' in filt


def test_search_milvus_version_param_overrides_default(mocker):
    """传 version=xxx → filter == xxx（覆盖默认当前版本过滤）。"""
    from server.chat.pipeline import milvus_retrieval
    fake_client = MagicMock()
    fake_client.search.return_value = [[]]
    mocker.patch.object(milvus_retrieval, "milvus_client", fake_client)
    mocker.patch.object(milvus_retrieval, "get_embeddings",
                        lambda qs: MagicMock(to_list=lambda: [[0.1]]))

    milvus_retrieval._search_milvus_sync("q", 5, "kb", 0.5, filter_opts=SearchFilter(version="v1.0"))

    kw = fake_client.search.call_args[1]
    filt = kw.get("filter", "")
    assert 'metadata["file_version_label"] == "v1.0"' in filt


def test_record_current_returns_label_and_is_new(mocker, tmp_path):
    """record_current 返回 (label, is_new)；同 sha 重传 is_new=False。

    实际函数内部用 _sha256_file 算 sha + _db() 上下文 + query 链
    (query→filter→order_by→first) 查 top，mock 链依赖此内部写法。
    """
    _FMD_KEY = 'f.md'
    _VERSION_KEY = 'v1'
    from server.knowledge_base import file_version as fv
    p = tmp_path / _FMD_KEY
    p.write_text("hello")
    mocker.patch.object(fv, "_sha256_file", return_value="abc123")
    mocker.patch.object(fv, "_insert_history")  # 不真实写库
    # mock _db 上下文 + query 链（query→filter→order_by→first）
    fake_top = [None]
    fake_db = MagicMock()

    def _get_fake_top():
        return fake_top[0]

    (fake_db.query.return_value.filter.return_value
        .order_by.return_value.first) = _get_fake_top
    fake_ctx = MagicMock()
    fake_ctx.__enter__.return_value = fake_db
    fake_ctx.__exit__.return_value = False
    mocker.patch.object(fv, "_db", return_value=fake_ctx)
    # 首次：top=None → 新增行 → (v1, True)
    fake_top[0] = None
    label, is_new = fv.record_current("kb", _FMD_KEY, str(p), file_version_label=_VERSION_KEY)
    assert (label, is_new) == (_VERSION_KEY, True)
    # 重传同 sha：top 存在且 sha 相同 → (v1, False)，不新增行
    fake_top[0] = MagicMock(content_sha256="abc123", file_version_label=_VERSION_KEY)
    label, is_new = fv.record_current("kb", _FMD_KEY, str(p), file_version_label=_VERSION_KEY)
    assert (label, is_new) == (_VERSION_KEY, False)


def test_record_current_returns_none_on_failure():
    """文件不存在 → 返回 None（失败语义，非 Tuple）；调用方必须先判 None。"""
    from server.knowledge_base import file_version as fv
    assert fv.record_current("kb", "f.md", "/no/such/file.md") is None
