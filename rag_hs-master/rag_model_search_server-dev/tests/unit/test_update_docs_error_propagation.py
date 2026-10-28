#!/usr/bin/env python3
"""update_docs_use_path 错误传播 UT：向量化失败进 failed_files + code=500；
MemPalace 失败不影响文件成功（best-effort）。"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
from langchain.docstore.document import Document

from server.knowledge_base.kb_service.base import VectorizeError


def _common_patches(mock_kb):
    """返回 update_docs_use_path 直接调用所需的公共 patch 上下文管理器列表。"""
    fake_kb_file = MagicMock()
    return [
        patch('server.knowledge_base.kb_doc_api.KBServiceFactory.get_service_by_name',
              return_value=mock_kb),
        patch('server.knowledge_base.kb_doc_api.validate_kb_name', return_value=True),
        patch('server.knowledge_base.kb_doc_api.files2docs_in_thread',
              return_value=iter([(True, ("test_kb", "test.txt",
                                         [Document(page_content="c1")]))])),
        patch('server.knowledge_base.kb_doc_api.KnowledgeFile', return_value=fake_kb_file),
        patch('server.knowledge_base.kb_doc_api._store_docs_to_mempalace'),
        patch('server.knowledge_base.kb_doc_api.log_verbose', False),
        # P2：record_current 返回 (label, is_new) 二元组；mock 为新内容（is_new=True）
        # 让 update_doc 被调用，从而测试 VectorizeError / False / mempalace 失败传播
        patch('server.knowledge_base.file_version.record_current',
              return_value=("rev-1", True)),
    ]


def test_update_docs_use_path_vectorize_fail_goes_to_failed_files():
    """kb.update_doc 抛 VectorizeError → 进 failed_files + code=500。"""
    _TESTTXT_KEY = 'test.txt'
    from server.knowledge_base import kb_doc_api

    mock_kb = MagicMock()
    mock_kb.update_doc.side_effect = VectorizeError(
        "embedding", _TESTTXT_KEY, "API down", [])
    mock_kb.save_vector_store = MagicMock()

    patches = _common_patches(mock_kb)
    for p in patches:
        p.start()
    try:
        result = kb_doc_api.update_docs_use_path(
            request=kb_doc_api.UpdateDocsUsePathRequest(
                knowledge_base_name="test_kb",
                file_paths=[_TESTTXT_KEY],
                chunk_size=500,
                chunk_overlap=50,
                zh_title_enhance=False,
                override_custom_docs=True,
                docs={},
                not_refresh_vs_cache=True,
            ),
        )
    finally:
        for p in patches:
            p.stop()

    assert result.code == 500, f"预期 500，实际 {result.code}"
    assert _TESTTXT_KEY in result.data["failed_files"], f"failed_files={result.data}"
    assert "向量化失败" in result.data["failed_files"][_TESTTXT_KEY]


def test_update_docs_use_path_vectorize_false_return_goes_to_failed_files():
    """kb.update_doc 返回 False（旧式失败）→ 进 failed_files + code=500。"""
    from server.knowledge_base import kb_doc_api

    mock_kb = MagicMock()
    mock_kb.update_doc.return_value = False
    mock_kb.save_vector_store = MagicMock()

    patches = _common_patches(mock_kb)
    for p in patches:
        p.start()
    try:
        result = kb_doc_api.update_docs_use_path(
            request=kb_doc_api.UpdateDocsUsePathRequest(
                knowledge_base_name="test_kb",
                file_paths=["test.txt"],
                chunk_size=500,
                chunk_overlap=50,
                zh_title_enhance=False,
                override_custom_docs=True,
                docs={},
                not_refresh_vs_cache=True,
            ),
        )
    finally:
        for p in patches:
            p.stop()

    assert result.code == 500
    assert "test.txt" in result.data["failed_files"]


def test_mempalace_failure_does_not_affect_success():
    """MemPalace 抛异常 → 文件仍标记成功（best-effort），code=200。"""
    _TESTTXT_KEY = 'test.txt'
    from server.knowledge_base import kb_doc_api

    mock_kb = MagicMock()
    mock_kb.update_doc.return_value = True
    mock_kb.save_vector_store = MagicMock()

    fake_kb_file = MagicMock()
    with patch('server.knowledge_base.kb_doc_api.KBServiceFactory.get_service_by_name',
               return_value=mock_kb), \
         patch('server.knowledge_base.kb_doc_api.validate_kb_name', return_value=True), \
         patch('server.knowledge_base.kb_doc_api.files2docs_in_thread',
               return_value=iter([(True, ("test_kb", _TESTTXT_KEY,
                                           [Document(page_content="c1")]))])), \
         patch('server.knowledge_base.kb_doc_api.KnowledgeFile', return_value=fake_kb_file), \
         patch('server.knowledge_base.kb_doc_api._store_docs_to_mempalace',
               side_effect=Exception("mem fail")), \
         patch('server.knowledge_base.kb_doc_api.log_verbose', False), \
         patch('server.knowledge_base.file_version.record_current',
               return_value=("rev-1", True)):
        result = kb_doc_api.update_docs_use_path(
            request=kb_doc_api.UpdateDocsUsePathRequest(
                knowledge_base_name="test_kb",
                file_paths=[_TESTTXT_KEY],
                chunk_size=500,
                chunk_overlap=50,
                zh_title_enhance=False,
                override_custom_docs=True,
                docs={},
                not_refresh_vs_cache=True,
            ),
        )

    assert result.code == 200, f"MemPalace 失败不应影响文件成功，code={result.code}"
    assert _TESTTXT_KEY not in result.data.get("failed_files", {})


def test_update_docs_vectorize_fail_goes_to_failed_files():
    """update_docs（非 use_path 版本）同样：向量化失败进 failed_files。"""
    _TESTTXT_KEY = 'test.txt'
    from server.knowledge_base import kb_doc_api

    mock_kb = MagicMock()
    mock_kb.update_doc.side_effect = VectorizeError(
        "milvus_insert", _TESTTXT_KEY, "timeout", [])
    mock_kb.save_vector_store = MagicMock()

    fake_kb_file = MagicMock()
    # update_docs 用 get_file_detail，需 mock 返回非 custom_docs
    with patch('server.knowledge_base.kb_doc_api.KBServiceFactory.get_service_by_name',
               return_value=mock_kb), \
         patch('server.knowledge_base.kb_doc_api.validate_kb_name', return_value=True), \
         patch('server.knowledge_base.kb_doc_api.get_file_detail',
               return_value={"custom_docs": False}), \
         patch('server.knowledge_base.kb_doc_api.files2docs_in_thread',
               return_value=iter([(True, ("test_kb", _TESTTXT_KEY,
                                           [Document(page_content="c1")]))])), \
         patch('server.knowledge_base.kb_doc_api.KnowledgeFile', return_value=fake_kb_file), \
         patch('server.knowledge_base.kb_doc_api._store_docs_to_mempalace'), \
         patch('server.knowledge_base.kb_doc_api.log_verbose', False), \
         patch('server.knowledge_base.file_version.record_current',
               return_value=("rev-1", True)):
        result = kb_doc_api.update_docs(
            request=kb_doc_api.UpdateDocsRequest(
                knowledge_base_name="test_kb",
                file_names=[_TESTTXT_KEY],
                chunk_size=500,
                chunk_overlap=50,
                zh_title_enhance=False,
                override_custom_docs=True,
                docs={},
                not_refresh_vs_cache=True,
            ),
        )

    assert result.code == 500, f"预期 500，实际 {result.code}"
    assert _TESTTXT_KEY in result.data["failed_files"]
