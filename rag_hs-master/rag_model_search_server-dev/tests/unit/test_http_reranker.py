"""HttpReranker 白盒单元测试。

覆盖 server.reranker_workers.base.HttpReranker 的核心逻辑:
- TestScore: score() 的空入参短路(0.0) + 各类 requests 异常→ValueError(消息含关键字)
- TestCompressDocuments: compress_documents() 的空入参短路、并发评分、降序排序、
  top_n 截断、metadata 拷贝与 relevance_score 写入、全失败→[]、单文档失败跳过
- TestHealthCheck: health_check() 的 200/非200/超时/异常分支

所有外部 HTTP 调用均通过 mock self._session(.post/.get) 注入,禁止真实网络请求。
源码中 self._session 为 requests.Session() 实例,这里保留真实 Session(不连网),
仅 patch 其 post/get 方法返回受控响应或抛受控异常。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
import requests
from langchain_core.documents import Document

from server.reranker_workers.base import HttpReranker


def _make_reranker(top_n=3, max_workers=8):
    """构造一个 HttpReranker 实例(真实 requests.Session 不连网)。"""
    return HttpReranker(
        api_base_url="http://reranker.example.com",
        default_task_type="TEXT_TO_CODE",
        timeout=30,
        top_n=top_n,
        max_workers=max_workers,
    )


# ==================== TestScore ====================
class TestScore:
    """score() 的空入参短路 + 各类 requests 异常分支。"""

    @staticmethod
    def test_empty_query_returns_zero():
        """空 query → 0.0,不发起 HTTP 请求。"""
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            assert r.score("", "doc content") == 0.0
            mock_session.post.assert_not_called()

    @staticmethod
    def test_empty_document_returns_zero():
        """空 document → 0.0,不发起 HTTP 请求。"""
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            assert r.score("query", "") == 0.0
            mock_session.post.assert_not_called()

    @staticmethod
    def test_timeout_raises_value_error():
        """requests.Timeout → ValueError,消息含"超时"。"""
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.post.side_effect = requests.exceptions.Timeout("timed out")
            with pytest.raises(ValueError) as exc:
                r.score("q", "d")
            assert "超时" in str(exc.value)

    @staticmethod
    def test_connection_error_raises_value_error():
        """requests.ConnectionError → ValueError,消息含"连接失败"。"""
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.post.side_effect = requests.exceptions.ConnectionError("refused")
            with pytest.raises(ValueError) as exc:
                r.score("q", "d")
            assert "连接失败" in str(exc.value)

    @staticmethod
    def test_http_error_raises_value_error():
        """requests.HTTPError(由 raise_for_status 抛出) → ValueError,消息含"HTTP"。"""
        r = _make_reranker()
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError("server error")
        with patch.object(r, "_session") as mock_session:
            mock_session.post.return_value = mock_response
            with pytest.raises(ValueError) as exc:
                r.score("q", "d")
            assert "HTTP" in str(exc.value)

    @staticmethod
    def test_other_exception_raises_value_error():
        """其他未预期异常 → ValueError,消息含"未知错误"。"""
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.post.side_effect = RuntimeError("boom")
            with pytest.raises(ValueError) as exc:
                r.score("q", "d")
            assert "未知错误" in str(exc.value)

    @staticmethod
    def test_success_returns_score():
        """200 + {"score": 0.88} → 返回 0.88。"""
        r = _make_reranker()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"score": 0.88}
        with patch.object(r, "_session") as mock_session:
            mock_session.post.return_value = mock_response
            assert r.score("q", "d") == 0.88


# ==================== TestCompressDocuments ====================
class TestCompressDocuments:
    """compress_documents() 的空入参短路 + 并发评分/排序/top_n/metadata 拷贝。"""

    @staticmethod
    def test_empty_documents_returns_empty():
        r = _make_reranker()
        assert not r.compress_documents([], "query")

    @staticmethod
    def test_empty_query_returns_empty():
        r = _make_reranker()
        docs = [Document(page_content="x", metadata={})]
        assert not r.compress_documents(docs, "")

    @staticmethod
    def test_sorted_desc_top_n_with_metadata_copy():
        """mock self.score 返回不同分数:验证降序、top_n 截断、metadata 拷贝、relevance_score 写入。

        构造 3 个文档,score 分别 0.1/0.9/0.5,top_n=2:
        - 排序后选 0.9(idx1)、0.5(idx2) 两个
        - 降序:0.9 在前
        - metadata 是 copy(不修改原 doc)
        - relevance_score 写入对应分数
        """
        _C1_KEY = 'c1'
        _DOC_ID_KEY = 'c2'
        _SCORE_KEY = 'relevance_score'
        r = _make_reranker(top_n=2)
        docs = [
            Document(page_content="c0", metadata={"k": "v0"}),
            Document(page_content=_C1_KEY, metadata={"k": "v1"}),
            Document(page_content=_DOC_ID_KEY, metadata={"k": "v2"}),
        ]
        scores = {"c0": 0.1, _C1_KEY: 0.9, _DOC_ID_KEY: 0.5}
        with patch.object(r, "score", side_effect=lambda q, d: scores.get(d, 0.0)):
            result = r.compress_documents(docs, "query")

        assert len(result) == 2
        # 降序:0.9 在前,0.5 在后
        assert result[0].page_content == _C1_KEY
        assert result[0].metadata[_SCORE_KEY] == 0.9
        assert result[1].page_content == _DOC_ID_KEY
        assert result[1].metadata[_SCORE_KEY] == 0.5
        # metadata 是 copy:原 doc 未被写入 relevance_score
        assert _SCORE_KEY not in docs[1].metadata
        # 原 metadata 字段保留
        assert result[0].metadata["k"] == "v1"
        assert result[1].metadata["k"] == "v2"

    @staticmethod
    def test_all_scores_fail_returns_empty():
        """self.score 全部抛异常 → _score_one 返回 None → 全部跳过 → []。"""
        r = _make_reranker()
        docs = [
            Document(page_content="c0", metadata={}),
            Document(page_content="c1", metadata={}),
        ]
        with patch.object(r, "score", side_effect=ValueError("fail")):
            result = r.compress_documents(docs, "query")
        assert not result

    @staticmethod
    def test_partial_failure_skips_failing_doc():
        """部分文档 score 抛异常 → 跳过,仅返回成功的文档。

        3 个文档:idx0 抛异常, idx1=0.9, idx2=0.5,top_n=3 → 返回 2 个(降序)。
        """
        r = _make_reranker(top_n=3)

        def fake_score(q, d):
            if d == "c0":
                raise ValueError("fail c0")
            return 0.9 if d == "c1" else 0.5

        docs = [
            Document(page_content="c0", metadata={}),
            Document(page_content="c1", metadata={}),
            Document(page_content="c2", metadata={}),
        ]
        with patch.object(r, "score", side_effect=fake_score):
            result = r.compress_documents(docs, "query")

        # c0 被跳过,剩 c1(0.9)、c2(0.5)
        assert len(result) == 2
        assert result[0].page_content == "c1"
        assert result[1].page_content == "c2"


# ==================== TestHealthCheck ====================
class TestHealthCheck:
    """health_check() 的 200/非200/超时/异常分支。"""

    @staticmethod
    def test_200_returns_true():
        r = _make_reranker()
        mock_response = MagicMock()
        mock_response.status_code = 200
        with patch.object(r, "_session") as mock_session:
            mock_session.get.return_value = mock_response
            assert r.health_check() is True

    @staticmethod
    def test_non_200_returns_false():
        r = _make_reranker()
        mock_response = MagicMock()
        mock_response.status_code = 503
        with patch.object(r, "_session") as mock_session:
            mock_session.get.return_value = mock_response
            assert r.health_check() is False

    @staticmethod
    def test_timeout_returns_false():
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.get.side_effect = requests.exceptions.Timeout("timed out")
            assert r.health_check() is False

    @staticmethod
    def test_connection_error_returns_false():
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.get.side_effect = requests.exceptions.ConnectionError("refused")
            assert r.health_check() is False

    @staticmethod
    def test_other_exception_returns_false():
        r = _make_reranker()
        with patch.object(r, "_session") as mock_session:
            mock_session.get.side_effect = RuntimeError("boom")
            assert r.health_check() is False
