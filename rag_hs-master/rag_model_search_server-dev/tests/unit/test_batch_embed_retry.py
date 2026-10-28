#!/usr/bin/env python3
"""_batch_embed_with_retry UT：分批 + 重试 + 校验返回数。"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.knowledge_base.kb_service.base import KBService, VectorizeError


class _StubKBService(KBService):
    """绕过 KBService 抽象类的 stub。"""
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


def _make_docs(n):
    from langchain.docstore.document import Document
    return [Document(page_content=f"chunk_{i}") for i in range(n)]


def test_batch_embed_all_success():
    """50 chunk，batch=10，全部成功 → 返回 50 个 embedding。"""
    with patch('server.knowledge_base.kb_service.base.EMBEDDING_BATCH_SIZE', 0), \
         patch('configs.kb_loader.KB_CONFIG', {"VECTORIZE_CONFIG": {"EMBEDDING_BATCH_SIZE": 10}}):
        # 绕过 __init__ 直接构造实例
        svc = _StubKBService.__new__(_StubKBService)
        svc.embed_model = "text-embedding-v3"
        fake_resp = MagicMock()
        fake_resp.code = 200
        fake_resp.data = [[0.1] * 1024] * 10
        with patch('server.knowledge_base.kb_service.base.embed_texts', return_value=fake_resp):
            result = svc._batch_embed_with_retry(_make_docs(50), "test.txt")
        assert len(result) == 50, f"预期 50，实际 {len(result)}"


def test_batch_embed_retry_then_success():
    """第 1 批第 1 次失败、第 2 次成功 → 重试后全成功。"""
    with patch('server.knowledge_base.kb_service.base.EMBEDDING_BATCH_SIZE', 10), \
         patch('server.knowledge_base.kb_service.base.EMBEDDING_MAX_RETRIES', 3), \
         patch('server.knowledge_base.kb_service.base.EMBEDDING_RETRY_BACKOFF', 0.01):
        svc = _StubKBService.__new__(_StubKBService)
        svc.embed_model = "text-embedding-v3"
        ok_resp = MagicMock(code=200, data=[[0.1] * 1024] * 10)
        fail_resp = MagicMock(code=500, data=None, msg="API error")
        # 第 1 批第 1 次 fail，第 1 批第 2 次 ok，后续全 ok
        responses = [fail_resp, ok_resp] + [ok_resp] * 4  # 5 批，第 1 批 2 次 + 后 4 批各 1 次
        with patch('server.knowledge_base.kb_service.base.embed_texts', side_effect=responses), \
             patch('server.knowledge_base.kb_service.base.time.sleep'):
            result = svc._batch_embed_with_retry(_make_docs(50), "test.txt")
        assert len(result) == 50


def test_batch_embed_all_retries_exhausted():
    """第 3 批重试 3 次仍失败 → 抛 VectorizeError(stage=embedding)。"""
    with patch('server.knowledge_base.kb_service.base.EMBEDDING_BATCH_SIZE', 10), \
         patch('server.knowledge_base.kb_service.base.EMBEDDING_MAX_RETRIES', 3), \
         patch('server.knowledge_base.kb_service.base.EMBEDDING_RETRY_BACKOFF', 0.01):
        svc = _StubKBService.__new__(_StubKBService)
        svc.embed_model = "text-embedding-v3"
        ok_resp = MagicMock(code=200, data=[[0.1] * 1024] * 10)
        fail_resp = MagicMock(code=500, data=None, msg="API error")
        # 前 2 批 ok，第 3 批 3 次全 fail
        responses = [ok_resp, ok_resp, fail_resp, fail_resp, fail_resp]
        with patch('server.knowledge_base.kb_service.base.embed_texts', side_effect=responses), \
             patch('server.knowledge_base.kb_service.base.time.sleep'):
            with pytest.raises(VectorizeError) as exc_info:
                svc._batch_embed_with_retry(_make_docs(50), "test.txt")
        assert exc_info.value.stage == "embedding"
        assert exc_info.value.file_name == "test.txt"


def test_batch_embed_count_mismatch():
    """API 返回向量数 != 输入数 → 抛 RuntimeError（被包装为 VectorizeError embedding）。"""
    with patch('server.knowledge_base.kb_service.base.EMBEDDING_BATCH_SIZE', 10), \
         patch('server.knowledge_base.kb_service.base.EMBEDDING_MAX_RETRIES', 1):
        svc = _StubKBService.__new__(_StubKBService)
        svc.embed_model = "text-embedding-v3"
        mismatch_resp = MagicMock(code=200, data=[[0.1] * 1024] * 5)  # 输入 10 返回 5
        with patch('server.knowledge_base.kb_service.base.embed_texts', return_value=mismatch_resp), \
             patch('server.knowledge_base.kb_service.base.time.sleep'):
            with pytest.raises(VectorizeError) as exc_info:
                svc._batch_embed_with_retry(_make_docs(10), "test.txt")
        assert exc_info.value.stage == "embedding"


def test_batch_embed_size_auto_resolve():
    """EMBEDDING_BATCH_SIZE=0 → 自动读 EMBEDDING_API_CONFIG per-model batch_size=10。"""
    with patch('server.knowledge_base.kb_service.base.EMBEDDING_BATCH_SIZE', 0):
        svc = _StubKBService.__new__(_StubKBService)
        svc.embed_model = "text-embedding-v3"
        ok_resp = MagicMock(code=200, data=[[0.1] * 1024] * 10)
        call_count = {"n": 0}
        def _track(texts, **kw):
            call_count["n"] += 1
            assert len(texts) == 10, f"预期每批 10，实际 {len(texts)}"
            return ok_resp
        with patch('server.knowledge_base.kb_service.base.embed_texts', side_effect=_track):
            svc._batch_embed_with_retry(_make_docs(50), "test.txt")
        assert call_count["n"] == 5, f"预期 5 批，实际 {call_count['n']}"
