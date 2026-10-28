"""LangchainReranker 与 get_reranker 白盒单元测试。

覆盖 server.reranker.reranker:
- TestGetReranker: USE_RERANKER=False→None;model_name in RERANKER_API_CONFIG→调
  get_reranker_worker 构造 HttpReranker 子类(透传 config);否则从 MODEL_PATH["reranker"]
  构造 LangchainReranker。
- TestLangchainRerankerCompressDocuments: 绕过 __init__(用 __new__),
  手动 instance._model=MagicMock(),_model.predict 返回 MagicMock 其 topk 返回
  (values, indices);覆盖空 documents→[]、非空按 indices 取 doc+写
  metadata["relevance_score"]=value+数量不超 top_n。

sentence_transformers 未安装,在 import 前向 sys.modules 注入假模块(含 CrossEncoder),
避免 LangchainReranker.__init__ 真实加载模型。所有外部依赖均 mock,禁止真实模型/网络。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import asyncio
import sys
import types
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

# 注入假 sentence_transformers(必须在 import server.reranker.reranker 之前)
_fake_st = types.ModuleType("sentence_transformers")


class _FakeCrossEncoder:
    """假 CrossEncoder:不加载真实模型,仅记录构造参数。"""

    def __init__(self, *args, **kwargs):
        self.model_name = kwargs.get("model_name")
        self.max_length = kwargs.get("max_length")
        self.device = kwargs.get("device")


_fake_st.CrossEncoder = _FakeCrossEncoder
sys.modules["sentence_transformers"] = _fake_st

from langchain_core.documents import Document

from server.reranker.reranker import (
    LangchainReranker,
    acompress_documents_bounded,
    get_reranker,
    get_reranker_singleton,
)


class TestGetReranker:
    """get_reranker: USE_RERANKER=False/API 模型/本地模型三分支。"""

    @staticmethod
    def test_api_model_uses_reranker_worker():
        _MYAPIRERANKER_KEY = 'my-api-reranker'
        config = {
            "api_base_url": "http://reranker.api",
            "api_key": "ak",
            "model_id": "mid",
            "instruct": "inst",
            "return_documents": False,
            "default_task_type": "CODE_REVIEW",
            "timeout": 45,
            "top_n": 5,
            "max_workers": 4,
        }
        fake_worker_class = MagicMock(name="FakeWorker")

        with patch("configs.USE_RERANKER", True), \
             patch("configs.RERANKER_API_CONFIG", {_MYAPIRERANKER_KEY: config}), \
             patch("server.reranker_workers.get_reranker_worker",
                   return_value=fake_worker_class) as mock_get_worker:
            result = get_reranker(_MYAPIRERANKER_KEY, top_n=3)

        mock_get_worker.assert_called_once_with(_MYAPIRERANKER_KEY)
        fake_worker_class.assert_called_once_with(
            api_base_url="http://reranker.api",
            api_key="ak",
            model_id="mid",
            instruct="inst",
            return_documents=False,
            default_task_type="CODE_REVIEW",
            timeout=45,
            top_n=5,
            max_workers=4,
        )
        assert result is fake_worker_class.return_value

    @staticmethod
    def test_local_model_constructs_langchain_reranker():
        with patch("configs.USE_RERANKER", True), \
             patch("configs.RERANKER_API_CONFIG", {}), \
             patch("configs.MODEL_PATH",
                   {"reranker": {"bge-reranker-large": "/models/bge-reranker-large"}}):
            result = get_reranker("bge-reranker-large", top_n=3, device="cpu", max_length=512)

        assert isinstance(result, LangchainReranker)
        assert result.model_name_or_path == "/models/bge-reranker-large"
        assert result.top_n == 3
        assert result.device == "cpu"
        assert result.max_length == 512
        assert isinstance(result._model, _FakeCrossEncoder)

    @staticmethod
    def test_local_model_falls_back_to_baai_path():
        with patch("configs.USE_RERANKER", True), \
             patch("configs.RERANKER_API_CONFIG", {}), \
             patch("configs.MODEL_PATH", {"reranker": {}}):
            result = get_reranker("some-reranker")

        assert isinstance(result, LangchainReranker)
        assert result.model_name_or_path == "BAAI/some-reranker"

    @staticmethod
    def test_use_reranker_false_returns_none():
        with patch("configs.USE_RERANKER", False):
            result = get_reranker()
        assert result is None


class TestLangchainRerankerCompressDocuments:
    """compress_documents: 用假 CrossEncoder 构造实例后,替换 _model=MagicMock()。

    sentence_transformers 已通过 sys.modules 注入假模块,LangchainReranker.__init__
    会用假 CrossEncoder(不真实加载模型),构造完成后替换 _model 为 MagicMock 以控制 predict。
    """

    @staticmethod
    def _make_instance(top_n=2):
        instance = LangchainReranker(
            model_name_or_path="fake-model",
            top_n=top_n,
            device="cpu",
            max_length=1024,
        )
        instance._model = MagicMock()
        return instance

    def test_empty_documents_returns_empty(self):
        instance = self._make_instance()
        assert not instance.compress_documents([], "query")

    def test_non_empty_takes_by_indices_and_writes_score(self):
        _RELEVANCE_SCORE_KEY = 'relevance_score'
        docs = [
            Document(page_content="c0", metadata={"k": "v0"}),
            Document(page_content="c1", metadata={"k": "v1"}),
            Document(page_content="c2", metadata={"k": "v2"}),
        ]
        instance = self._make_instance(top_n=2)

        mock_results = MagicMock()
        mock_results.topk.return_value = ([0.9, 0.8], [2, 0])
        mock_results.__len__ = MagicMock(return_value=5)
        instance._model.predict.return_value = mock_results

        result = instance.compress_documents(docs, "query")

        assert len(result) == 2
        assert result[0].page_content == "c2"
        assert result[0].metadata[_RELEVANCE_SCORE_KEY] == 0.9
        assert result[1].page_content == "c0"
        assert result[1].metadata[_RELEVANCE_SCORE_KEY] == 0.8
        assert docs[2].metadata[_RELEVANCE_SCORE_KEY] == 0.9
        assert docs[0].metadata[_RELEVANCE_SCORE_KEY] == 0.8
        instance._model.predict.assert_called_once()
        call_kwargs = instance._model.predict.call_args.kwargs
        assert call_kwargs.get("batch_size") == 32
        assert call_kwargs.get("num_workers") == 0
        assert call_kwargs.get("convert_to_tensor") is True
        mock_results.topk.assert_called_once_with(2)

    def test_top_n_exceeds_results_returns_all(self):
        docs = [
            Document(page_content="c0", metadata={}),
            Document(page_content="c1", metadata={}),
        ]
        instance = self._make_instance(top_n=5)

        mock_results = MagicMock()
        mock_results.topk.return_value = ([0.95], [1])
        mock_results.__len__ = MagicMock(return_value=1)
        instance._model.predict.return_value = mock_results

        result = instance.compress_documents(docs, "query")

        assert len(result) == 1
        assert result[0].page_content == "c1"
        assert result[0].metadata["relevance_score"] == 0.95
        mock_results.topk.assert_called_once_with(1)


class TestGetRerankerSingleton:
    """get_reranker_singleton: 模块级单例（B2）。"""

    @staticmethod
    def test_singleton_returns_same_instance():
        with patch("configs.USE_RERANKER", True), \
             patch("configs.RERANKER_API_CONFIG", {}), \
             patch("configs.MODEL_PATH",
                   {"reranker": {"bge-reranker-large": "/models/bge-reranker-large"}}):
            r1 = get_reranker_singleton()
            r2 = get_reranker_singleton()
        assert r1 is r2
        assert isinstance(r1, LangchainReranker)
        import server.reranker.reranker as mod
        assert r1.top_n == mod._RERANKER_SINGLETON_TOP_N

    @staticmethod
    def test_singleton_use_reranker_false_returns_none():
        with patch("configs.USE_RERANKER", False):
            result = get_reranker_singleton()
        assert result is None

    @staticmethod
    def _reset_singleton():
        import server.reranker.reranker as mod
        mod._reranker_singleton = None

    def setup_method(self):
        self._reset_singleton()

    def teardown_method(self):
        self._reset_singleton()


class TestAcompressDocumentsBounded:
    """acompress_documents_bounded: 信号量 + wait_for + top_n 截取（B1）。

    用 asyncio.run 跑 async 函数（项目不用 pytest-asyncio）。模块级 _RERANKER_SEMAPHORE
    在 Python 3.10+ 不绑定 loop，每次 asyncio.run 新循环里 acquire/release 正常。
    """

    @staticmethod
    def test_exception_propagates():
        reranker = MagicMock()
        reranker.acompress_documents = AsyncMock(side_effect=RuntimeError("boom"))
        with pytest.raises(RuntimeError, match="boom"):
            asyncio.run(acompress_documents_bounded(reranker, [Document(page_content="c")], "q"))

    @staticmethod
    def test_timeout_raises_timeout_error():
        async def _slow(*a, **kw):
            await asyncio.sleep(100)
        reranker = MagicMock()
        reranker.acompress_documents = AsyncMock(side_effect=_slow)
        with patch("server.reranker.reranker._RERANKER_TIMEOUT", 0.1):
            with pytest.raises(asyncio.TimeoutError):
                asyncio.run(acompress_documents_bounded(
                    reranker, [Document(page_content="c")], "q"))

    @staticmethod
    def test_top_n_exceeds_result_no_truncation():
        docs = [Document(page_content="c0"), Document(page_content="c1")]
        reranker = MagicMock()
        reranker.acompress_documents = AsyncMock(return_value=docs)
        result = asyncio.run(acompress_documents_bounded(reranker, docs, "q", top_n=10))
        assert len(result) == 2

    @staticmethod
    def test_top_n_none_no_truncation():
        docs = [Document(page_content=f"c{i}") for i in range(5)]
        reranker = MagicMock()
        reranker.acompress_documents = AsyncMock(return_value=docs)
        result = asyncio.run(acompress_documents_bounded(reranker, docs, "q", top_n=None))
        assert len(result) == 5

    @staticmethod
    def test_top_n_truncation():
        docs = [Document(page_content=f"c{i}") for i in range(10)]
        reranker = MagicMock()
        reranker.acompress_documents = AsyncMock(return_value=docs)
        result = asyncio.run(acompress_documents_bounded(reranker, docs, "q", top_n=3))
        assert len(result) == 3
        assert [d.page_content for d in result] == ["c0", "c1", "c2"]