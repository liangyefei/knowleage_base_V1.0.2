"""Bug B 修复测试：HTTP /search_docs 的 _search_milvus 加 version/tags filter。

验证 _search_milvus 传 version/tags 时构造正确 filter，不再旁路版本过滤。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from server.knowledge_base.search_filter import SearchFilter

# Milvus 连接 shim：kb_doc_api 模块级 client = MilvusClient(...) eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


def _setup_mocks(mocker):
    """mock _search_milvus 的外部依赖：get_embeddings / client / build_files_version_map。"""
    mocker.patch("server.knowledge_base.kb_doc_api.get_embeddings",
                  return_value=MagicMock(tolist=lambda: [[0.1, 0.2, 0.3]]))
    mock_client = MagicMock()
    mock_client.search.return_value = [[]]
    mocker.patch("server.knowledge_base.kb_doc_api.client", mock_client)
    return mock_client


def test_search_milvus_version_filter(mocker):
    """version 传值时 filter 含 file_version_label == label。"""
    mock_client = _setup_mocks(mocker)
    from server.knowledge_base.kb_doc_api import _search_milvus

    _search_milvus("query", 10, "kb1", 0.0, filter_opts=SearchFilter(version="v1.2"))

    _, kwargs = mock_client.search.call_args
    flt = kwargs.get("filter") or ""
    assert 'file_version_label' in flt
    assert '"v1.2"' in flt
    assert '==' in flt


def test_search_milvus_tags_filter(mocker):
    """tags 传值时 filter 含 json_contains。"""
    mock_client = _setup_mocks(mocker)
    from server.knowledge_base.kb_doc_api import _search_milvus

    _search_milvus("query", 10, "kb1", 0.0, filter_opts=SearchFilter(tags=["GPIO", "UART"]))

    _, kwargs = mock_client.search.call_args
    flt = kwargs.get("filter") or ""
    assert 'json_contains' in flt
    assert '"GPIO"' in flt
    assert '"UART"' in flt
    assert ' or ' in flt


def test_search_milvus_default_current_version_filter(mocker):
    """version=None 时走默认当前版本 filter（build_files_version_map）。"""
    mock_client = _setup_mocks(mocker)
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                  return_value={"file.md": "v1.0"})
    from server.knowledge_base.kb_doc_api import _search_milvus

    _search_milvus("query", 10, "kb1", 0.0)

    _, kwargs = mock_client.search.call_args
    flt = kwargs.get("filter") or ""
    assert 'file_version_label' in flt
    assert 'in [' in flt
    assert '"v1.0"' in flt


def test_search_milvus_version_and_tags_combined(mocker):
    """version + tags 同时传：filter 用 and 叠加。"""
    mock_client = _setup_mocks(mocker)
    from server.knowledge_base.kb_doc_api import _search_milvus

    _search_milvus("query", 10, "kb1", 0.0, filter_opts=SearchFilter(version="v1.2", tags=["GPIO"]))

    _, kwargs = mock_client.search.call_args
    flt = kwargs.get("filter") or ""
    assert '==' in flt
    assert 'json_contains' in flt
    assert ' and ' in flt


def test_search_milvus_no_filter_when_empty_kb(mocker):
    """空库（build_files_version_map 返回空）+ 无 version/tags：filter=None（fail-open）。"""
    mock_client = _setup_mocks(mocker)
    mocker.patch("server.knowledge_base.file_version.build_files_version_map",
                  return_value={})
    from server.knowledge_base.kb_doc_api import _search_milvus

    _search_milvus("query", 10, "kb1", 0.0)

    _, kwargs = mock_client.search.call_args
    flt = kwargs.get("filter")
    assert flt is None
