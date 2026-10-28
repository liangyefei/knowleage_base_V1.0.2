#!/usr/bin/env python3
"""端到端集成测试：update_docs_use_path → update_doc → add_doc →
_batch_embed_with_retry → _transactional_milvus_insert 全链路。

验证假成功已修复：embedding/milvus 失败 → failed_files 非空 → code=500；
全成功 → code=200。使用真实 MilvusKBService（绕过 __init__），仅在
embed_texts 与 milvus.add_documents 边界 mock。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
from langchain.docstore.document import Document

from server.knowledge_base.kb_service.base import VectorizeError
from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService


def _make_real_milvus_stub():
    """构造绕过 __init__ 的真实 MilvusKBService（保留真实 add_doc/事务链路）。"""
    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "test_kb"
    svc.embed_model = "text-embedding-v3"
    svc.milvus = MagicMock()
    svc.milvus._text_field = "text"
    svc.milvus._vector_field = "vector"
    svc.milvus.col = MagicMock()
    # langchain Milvus 内部用 embedding_func（非 embedding_function），
    # 替换 stub 时读的是这个属性，mock 要对齐属性名
    svc.milvus.embedding_func = MagicMock()
    svc.milvus.add_documents = MagicMock()
    # update_doc 会先调 delete_doc（含 DB），用 no-op 跳过
    svc.delete_doc = MagicMock()
    # _rollback_pks 是真实实例方法，__new__ 不会 mock 它，手动替换以便断言
    svc._rollback_pks = MagicMock()
    return svc


def _make_fake_kb_file(docs):
    fake = MagicMock()
    fake.filename = "test.txt"
    fake.filepath = "/tmp/test.txt"  # os.path.exists 会走 patch
    fake.file2text.return_value = docs
    return fake


def _common_patches(svc, fake_kb_file):
    return [
        patch('server.knowledge_base.kb_doc_api.KBServiceFactory.get_service_by_name',
              return_value=svc),
        patch('server.knowledge_base.kb_doc_api.validate_kb_name', return_value=True),
        patch('server.knowledge_base.kb_doc_api.files2docs_in_thread',
              return_value=iter([(True, ("test_kb", "test.txt",
                                         [Document(page_content="c1"),
                                          Document(page_content="c2")]))])),
        patch('server.knowledge_base.kb_doc_api.KnowledgeFile', return_value=fake_kb_file),
        patch('server.knowledge_base.kb_doc_api._store_docs_to_mempalace'),
        patch('server.knowledge_base.kb_doc_api.log_verbose', False),
        patch('server.knowledge_base.kb_service.base.add_file_to_db'),
        patch('server.knowledge_base.kb_service.base.os.path.exists', return_value=True),
        patch('server.knowledge_base.kb_service.base.time.sleep'),
    ]


def _call_update_docs_use_path():
    from server.knowledge_base import kb_doc_api
    from server.knowledge_base.kb_doc_api import UpdateDocsUsePathRequest
    return kb_doc_api.update_docs_use_path(
        request=UpdateDocsUsePathRequest(
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


def test_embedding_fail_end_to_end_returns_500():
    """embedding API 失败 → update_docs_use_path 返回 500 + failed_files。"""
    svc = _make_real_milvus_stub()
    docs = [Document(page_content="c1"), Document(page_content="c2")]
    fake_kb_file = _make_fake_kb_file(docs)

    fail_resp = MagicMock(code=500, data=None, msg="API down")
    patches = _common_patches(svc, fake_kb_file) + [
        patch('server.knowledge_base.kb_service.base.embed_texts', return_value=fail_resp),
        patch('server.knowledge_base.kb_service.base.EMBEDDING_MAX_RETRIES', 1),
    ]
    for p in patches:
        p.start()
    try:
        result = _call_update_docs_use_path()
    finally:
        for p in patches:
            p.stop()

    assert result.code == 500, f"预期 500，实际 {result.code}"
    assert "test.txt" in result.data["failed_files"], f"failed_files={result.data}"
    assert "向量化失败" in result.data["failed_files"]["test.txt"]
    # embedding 失败无 PK 写入，不应回滚
    svc._rollback_pks.assert_not_called()


def test_milvus_insert_fail_end_to_end_returns_500():
    """Milvus 写入抛异常 → update_docs_use_path 返回 500 + failed_files。"""
    svc = _make_real_milvus_stub()
    svc.milvus.add_documents.side_effect = Exception("milvus timeout")
    docs = [Document(page_content="c1"), Document(page_content="c2")]
    fake_kb_file = _make_fake_kb_file(docs)

    ok_resp = MagicMock(code=200, data=[[0.1] * 1024, [0.2] * 1024])
    patches = _common_patches(svc, fake_kb_file) + [
        patch('server.knowledge_base.kb_service.base.embed_texts', return_value=ok_resp),
    ]
    for p in patches:
        p.start()
    try:
        result = _call_update_docs_use_path()
    finally:
        for p in patches:
            p.stop()

    assert result.code == 500, f"预期 500，实际 {result.code}"
    assert "test.txt" in result.data["failed_files"]
    assert "向量化失败" in result.data["failed_files"]["test.txt"]


def test_all_success_end_to_end_returns_200():
    """全成功 → update_docs_use_path 返回 200 + failed_files 空。"""
    svc = _make_real_milvus_stub()
    svc.milvus.add_documents.return_value = [100, 200]
    docs = [Document(page_content="c1"), Document(page_content="c2")]
    fake_kb_file = _make_fake_kb_file(docs)

    ok_resp = MagicMock(code=200, data=[[0.1] * 1024, [0.2] * 1024])
    patches = _common_patches(svc, fake_kb_file) + [
        patch('server.knowledge_base.kb_service.base.embed_texts', return_value=ok_resp),
    ]
    for p in patches:
        p.start()
    try:
        result = _call_update_docs_use_path()
    finally:
        for p in patches:
            p.stop()

    assert result.code == 200, f"预期 200，实际 {result.code}，failed={result.data}"
    assert result.data.get("failed_files", {}) == {}
    assert result.data.get("chunkCount") == 2
    svc._rollback_pks.assert_not_called()


def test_mempalace_fail_does_not_break_success():
    """MemPalace 抛异常 → 文件仍成功（best-effort）→ code=200。"""
    svc = _make_real_milvus_stub()
    svc.milvus.add_documents.return_value = [100, 200]
    docs = [Document(page_content="c1"), Document(page_content="c2")]
    fake_kb_file = _make_fake_kb_file(docs)

    ok_resp = MagicMock(code=200, data=[[0.1] * 1024, [0.2] * 1024])
    patches = _common_patches(svc, fake_kb_file) + [
        patch('server.knowledge_base.kb_service.base.embed_texts', return_value=ok_resp),
    ]
    # 覆盖 _store_docs_to_mempalace 为抛异常
    patches[4] = patch('server.knowledge_base.kb_doc_api._store_docs_to_mempalace',
                       side_effect=Exception("mem fail"))
    for p in patches:
        p.start()
    try:
        result = _call_update_docs_use_path()
    finally:
        for p in patches:
            p.stop()

    assert result.code == 200, f"MemPalace 失败不应影响文件成功，code={result.code}"
    assert result.data.get("failed_files", {}) == {}
