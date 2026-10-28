#!/usr/bin/env python3
"""add_doc 文件级事务 UT：全成功/部分失败/回滚/校验。"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
from langchain.docstore.document import Document

from server.knowledge_base.kb_service.base import KBService, VectorizeError


class _StubKBService(KBService):
    @staticmethod
    def do_init():
        pass

    @staticmethod
    def do_create_kb():
        pass

    @staticmethod
    def do_drop_kb():
        pass

    @staticmethod
    def do_search(query, top_k, score_threshold):
        pass

    @staticmethod
    def do_add_doc(docs, **kwargs):
        pass

    @staticmethod
    def do_delete_doc(kb_file):
        pass

    @staticmethod
    def do_clear_vs():
        pass

    @staticmethod
    def vs_type():
        return "stub"


def _make_kb_file(filename="test.txt"):
    kb_file = MagicMock()
    kb_file.filename = filename
    kb_file.filepath = os.path.join("/tmp", filename)
    kb_file.file2text.return_value = [Document(page_content=f"chunk_{i}") for i in range(3)]
    return kb_file


def test_add_doc_all_success():
    """embedding + milvus 全成功 → return True + 不回滚。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(return_value=[[0.1]] * 3)
    svc._transactional_milvus_insert = MagicMock(return_value=[100, 200, 300])
    svc._rollback_pks = MagicMock()
    with patch('server.knowledge_base.kb_service.base.add_file_to_db'), \
         patch('server.knowledge_base.kb_service.base.VECTORIZE_VERIFY_INSERT_COUNT', True):
        result = svc.add_doc(_make_kb_file())
    assert result is True
    svc._rollback_pks.assert_not_called()


def test_add_doc_embedding_fail_raises_no_rollback():
    """embedding 失败 → 抛 VectorizeError(embedding)，无 PK 写入，不回滚。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(
        side_effect=VectorizeError("embedding", "test.txt", "API down", []))
    svc._transactional_milvus_insert = MagicMock()
    svc._rollback_pks = MagicMock()
    with pytest.raises(VectorizeError) as exc_info:
        svc.add_doc(_make_kb_file())
    assert exc_info.value.stage == "embedding"
    svc._transactional_milvus_insert.assert_not_called()
    svc._rollback_pks.assert_not_called()


def test_add_doc_milvus_partial_fail_rollback():
    """Milvus 写入中途失败 → 抛 VectorizeError(milvus_insert) + 回滚已写 PK。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(return_value=[[0.1]] * 3)
    svc._transactional_milvus_insert = MagicMock(
        side_effect=VectorizeError("milvus_insert", "test.txt", "timeout", [100, 200]))
    svc._rollback_pks = MagicMock()
    with patch('server.knowledge_base.kb_service.base.VECTORIZE_ROLLBACK_ON_FAILURE', True), \
         patch('server.knowledge_base.kb_service.base.add_file_to_db'):
        with pytest.raises(VectorizeError) as exc_info:
            svc.add_doc(_make_kb_file())
    assert exc_info.value.stage == "milvus_insert"
    svc._rollback_pks.assert_called_once_with([100, 200], "test.txt")


def test_add_doc_verify_count_mismatch():
    """PK 数 != chunk 数 → 抛 VectorizeError(verify) + 回滚。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(return_value=[[0.1]] * 3)  # 3 chunk
    svc._transactional_milvus_insert = MagicMock(return_value=[100, 200])  # 只 2 PK
    svc._rollback_pks = MagicMock()
    with patch('server.knowledge_base.kb_service.base.VECTORIZE_VERIFY_INSERT_COUNT', True), \
         patch('server.knowledge_base.kb_service.base.VECTORIZE_ROLLBACK_ON_FAILURE', True):
        with pytest.raises(VectorizeError) as exc_info:
            svc.add_doc(_make_kb_file())
    assert exc_info.value.stage == "verify"
    svc._rollback_pks.assert_called_once()


def test_add_doc_no_docs_returns_false():
    """文件解析无 docs → return False（不进事务）。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    kb_file = _make_kb_file()
    kb_file.file2text.return_value = []
    svc._batch_embed_with_retry = MagicMock()
    result = svc.add_doc(kb_file)
    assert result is False
    svc._batch_embed_with_retry.assert_not_called()


def test_add_doc_rollback_disabled():
    """ROLLBACK_ON_FAILURE=False → 失败不回滚。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(return_value=[[0.1]] * 3)
    svc._transactional_milvus_insert = MagicMock(
        side_effect=VectorizeError("milvus_insert", "test.txt", "err", [100]))
    svc._rollback_pks = MagicMock()
    with patch('server.knowledge_base.kb_service.base.VECTORIZE_ROLLBACK_ON_FAILURE', False):
        with pytest.raises(VectorizeError):
            svc.add_doc(_make_kb_file())
    svc._rollback_pks.assert_not_called()


def test_add_doc_filters_null_page_content():
    """docs 含 page_content='null'/空 → 过滤后只 embed 有效切片（根因 A/B 入口防御）。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock(return_value=[[0.1]] * 2)
    svc._transactional_milvus_insert = MagicMock(return_value=[100, 200])
    svc._rollback_pks = MagicMock()
    kb_file = MagicMock()
    kb_file.filename = "test.png"
    kb_file.filepath = "/tmp/test.png"
    docs = [
        Document(page_content="null"),        # 过滤
        Document(page_content=""),            # 过滤
        Document(page_content="   "),         # 过滤
        Document(page_content="有效图片描述"),  # 保留
        Document(page_content="另一段描述"),   # 保留
    ]
    with patch('server.knowledge_base.kb_service.base.add_file_to_db'), \
         patch('server.knowledge_base.kb_service.base.VECTORIZE_VERIFY_INSERT_COUNT', True):
        result = svc.add_doc(kb_file, docs=docs)
    assert result is True
    # _batch_embed_with_retry 收到 2 个有效 doc
    embedded_docs = svc._batch_embed_with_retry.call_args[0][0]
    assert len(embedded_docs) == 2
    assert embedded_docs[0].page_content == "有效图片描述"
    assert embedded_docs[1].page_content == "另一段描述"


def test_add_doc_all_null_returns_false():
    """docs 全是 'null'/空 → 过滤后为空，return False（不进 embed，根因 A/B 入口防御）。"""
    svc = _StubKBService.__new__(_StubKBService)
    svc.embed_model = "text-embedding-v3"
    svc._batch_embed_with_retry = MagicMock()
    svc._transactional_milvus_insert = MagicMock()
    kb_file = MagicMock()
    kb_file.filename = "small.png"
    kb_file.filepath = "/tmp/small.png"
    docs = [
        Document(page_content="null"),
        Document(page_content=""),
        Document(page_content="  "),
    ]
    result = svc.add_doc(kb_file, docs=docs)
    assert result is False
    svc._batch_embed_with_retry.assert_not_called()
    svc._transactional_milvus_insert.assert_not_called()
