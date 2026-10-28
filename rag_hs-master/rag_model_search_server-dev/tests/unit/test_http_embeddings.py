"""server/embedding_workers/base.py (HttpEmbeddings) 纯逻辑单测。

覆盖:
- embed_documents:空 texts→[] / embeddings 空→[] / Timeout/ConnectionError/HTTPError/其他→ValueError
- embed_query:空 text→[] / 同上异常分支
- health_check:200→True / 其他→False / Timeout/Exception→False
- _aembed_batch:空 texts→[] / 分片 + 合并顺序 / 异常传播
"""
# pylint: disable=protected-access
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import requests

from server.embedding_workers.base import HttpEmbeddings


def _make_embeddings():
    """构造 HttpEmbeddings 实例(不连真实 HTTP)。"""
    return HttpEmbeddings(
        api_base_url="http://fake:8080",
        embedding_dim=128,
        timeout=5,
        max_workers=2,
        batch_size=2,
    )


class TestEmbedDocuments:
    """embed_documents 各分支。"""

    @staticmethod
    def test_empty_texts():
        emb = _make_embeddings()
        assert emb.embed_documents([]) == []

    @staticmethod
    def test_empty_embeddings_returned():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.json.return_value = {"embeddings": []}
        emb._session.post.return_value = resp
        assert emb.embed_documents(["text"]) == []

    @staticmethod
    def test_success():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.json.return_value = {"embeddings": [[0.1, 0.2], [0.3, 0.4]]}
        emb._session.post.return_value = resp
        result = emb.embed_documents(["a", "b"])
        assert result == [[0.1, 0.2], [0.3, 0.4]]

    @staticmethod
    def test_timeout_raises_value_error():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.post.side_effect = requests.exceptions.Timeout("timeout")
        with pytest.raises(ValueError, match="请求超时"):
            emb.embed_documents(["a"])

    @staticmethod
    def test_connection_error_raises_value_error():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.post.side_effect = requests.exceptions.ConnectionError("conn fail")
        with pytest.raises(ValueError, match="连接失败"):
            emb.embed_documents(["a"])

    @staticmethod
    def test_http_error_raises_value_error():
        """raise_for_status() 抛 HTTPError(response 已赋值)→ValueError。"""
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.status_code = 500
        resp.raise_for_status = MagicMock(side_effect=requests.exceptions.HTTPError("http err"))
        emb._session.post.return_value = resp
        with pytest.raises(ValueError, match="HTTP 错误"):
            emb.embed_documents(["a"])

    @staticmethod
    def test_unknown_error_raises_value_error():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.post.side_effect = RuntimeError("boom")
        with pytest.raises(ValueError, match="未知错误"):
            emb.embed_documents(["a"])


class TestEmbedQuery:
    """embed_query 各分支。"""

    @staticmethod
    def test_empty_text():
        emb = _make_embeddings()
        assert emb.embed_query("") == []

    @staticmethod
    def test_success():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.json.return_value = {"embeddings": [[0.1, 0.2]]}
        emb._session.post.return_value = resp
        assert emb.embed_query("query") == [0.1, 0.2]

    @staticmethod
    def test_empty_embeddings():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.json.return_value = {"embeddings": []}
        emb._session.post.return_value = resp
        assert emb.embed_query("query") == []

    @staticmethod
    def test_timeout_raises_value_error():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.post.side_effect = requests.exceptions.Timeout("timeout")
        with pytest.raises(ValueError, match="请求超时"):
            emb.embed_query("query")

    @staticmethod
    def test_connection_error_raises_value_error():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.post.side_effect = requests.exceptions.ConnectionError("conn")
        with pytest.raises(ValueError, match="连接失败"):
            emb.embed_query("query")


class TestHealthCheck:
    """health_check 各分支。"""

    @staticmethod
    def test_200_returns_true():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.status_code = 200
        emb._session.get.return_value = resp
        assert emb.health_check() is True

    @staticmethod
    def test_non_200_returns_false():
        emb = _make_embeddings()
        emb._session = MagicMock()
        resp = MagicMock()
        resp.status_code = 500
        emb._session.get.return_value = resp
        assert emb.health_check() is False

    @staticmethod
    def test_timeout_returns_false():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.get.side_effect = requests.exceptions.Timeout("timeout")
        assert emb.health_check() is False

    @staticmethod
    def test_connection_error_returns_false():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.get.side_effect = requests.exceptions.ConnectionError("conn")
        assert emb.health_check() is False

    @staticmethod
    def test_unknown_exception_returns_false():
        emb = _make_embeddings()
        emb._session = MagicMock()
        emb._session.get.side_effect = RuntimeError("boom")
        assert emb.health_check() is False


class TestAembedBatch:
    """_aembed_batch 分片 + 合并 + 异常传播。"""

    @staticmethod
    def test_empty_texts():
        emb = _make_embeddings()
        import asyncio
        result = asyncio.run(emb._aembed_batch([]))
        assert result == []

    @staticmethod
    def test_single_chunk():
        emb = _make_embeddings()
        emb._aembed_request = AsyncMock(return_value=[[0.1, 0.2]])
        import asyncio
        result = asyncio.run(emb._aembed_batch(["text"]))
        assert result == [[0.1, 0.2]]

    @staticmethod
    def test_multi_chunks_merge():
        emb = _make_embeddings()
        emb._aembed_request = AsyncMock(side_effect=[
            [[0.1, 0.2], [0.3, 0.4]],  # chunk 1
            [[0.5, 0.6], [0.7, 0.8]],  # chunk 2
        ])
        import asyncio
        result = asyncio.run(emb._aembed_batch(["a", "b", "c", "d"]))
        assert result == [[0.1, 0.2], [0.3, 0.4], [0.5, 0.6], [0.7, 0.8]]

    @staticmethod
    def test_exception_propagation():
        emb = _make_embeddings()
        emb._aembed_request = AsyncMock(side_effect=ValueError("chunk fail"))
        import asyncio
        with pytest.raises(ValueError, match="chunk fail"):
            asyncio.run(emb._aembed_batch(["a", "b"]))

    @staticmethod
    def test_aembed_documents():
        emb = _make_embeddings()
        emb._aembed_batch = AsyncMock(return_value=[[0.1, 0.2]])
        import asyncio
        result = asyncio.run(emb.aembed_documents(["a"]))
        assert result == [[0.1, 0.2]]

    @staticmethod
    def test_aembed_query_empty():
        emb = _make_embeddings()
        import asyncio
        result = asyncio.run(emb.aembed_query(""))
        assert result == []
