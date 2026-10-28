#!/usr/bin/env python3
"""MilvusKBService 事务化写入 + 回滚 UT。"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.knowledge_base.kb_service.base import VectorizeError
from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService


def _make_milvus_service():
    """构造一个不调 __init__ 的 MilvusKBService stub。"""
    svc = MilvusKBService.__new__(MilvusKBService)
    svc.kb_name = "test_kb"
    svc.embed_model = "text-embedding-v3"
    svc.milvus = MagicMock()
    svc.milvus._text_field = "text"
    svc.milvus._vector_field = "vector"
    svc.milvus.col = MagicMock()
    return svc


def test_transactional_insert_partial_fail_raises():
    """milvus.add_documents 中途抛异常 → 抛 VectorizeError(milvus_insert, partial_pks=已写)。"""
    from langchain.docstore.document import Document
    svc = _make_milvus_service()
    docs = [Document(page_content="a"), Document(page_content="b")]
    embeddings = [[0.1] * 1024, [0.2] * 1024]
    svc.milvus.add_documents.side_effect = Exception("milvus timeout")
    with pytest.raises(VectorizeError) as exc_info:
        svc.do_add_doc_with_embeddings(docs, embeddings)
    assert exc_info.value.stage == "milvus_insert"


def test_rollback_pks_calls_milvus_delete():
    """_rollback_pks 调用 milvus.col.delete(expr=pk in [...])。"""
    svc = _make_milvus_service()
    svc._rollback_pks([100, 200], "test.txt")
    svc.milvus.col.delete.assert_called_once()
    call_args = svc.milvus.col.delete.call_args
    assert "pk in" in call_args.kwargs.get("expr", "") or "pk in" in str(call_args)


def test_rollback_fail_logs_critical():
    """_rollback_pks delete 失败 → 记 CRITICAL 日志，不抛异常。"""
    svc = _make_milvus_service()
    svc.milvus.col.delete.side_effect = Exception("delete failed")
    with patch('server.knowledge_base.kb_service.milvus_kb_service.logger') as mock_logger:
        svc._rollback_pks([100, 200], "test.txt")
        mock_logger.critical.assert_called_once()
