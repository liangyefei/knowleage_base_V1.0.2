#!/usr/bin/env python3
"""
embeddings_api 域纯逻辑白盒单元测试。

覆盖:
- server.embeddings_api.embed_texts 的全部三分支 + 空/异常路径

所有外部依赖（本地 embeddings 池、在线 embedding worker、list_embed_models、
EMBEDDING_API_CONFIG）均通过 mock 注入，不依赖真实模型文件或在线 API。

注: 源码中无 embed_texts_stream 函数（实际为 embed_texts / aembed_texts /
embed_texts_endpoint / embed_documents），按源码实际行为对 embed_texts 做白盒断言。

embed_texts 内部分支:
  1) embed_model in list_embed_models()  -> 本地 embeddings (load_local_embeddings)
  2) embed_model in EMBEDDING_API_CONFIG -> 在线 API worker (get_embedding_worker)
  3) fall-through                        -> 500 "不支持" (question.md Q1 已知缺口)
- 外层 try/except 兜底 -> 500 "文本向量化过程中出现错误"
- 在线分支内层 try/except -> 500 "文本向量化错误"
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.embeddings_api import embed_texts
from server.utils import BaseResponse


class TestEmbedTexts:
    # ==================== 本地模型分支 ====================

    @staticmethod
    def test_local_model_returns_embeddings_vector_list():
        """本地模型命中 list_embed_models() 时，返回 BaseResponse(code=200, data=向量列表)。

        源码调用 embeddings.embed_documents(texts)（非 embed_query），此处按源码实际行为 mock。
        """
        _QWEN3EMBEDDINGAPI_KEY = 'qwen3-embedding-api'
        fake_embeddings = MagicMock()
        fake_embeddings.embed_documents.return_value = [[0.1, 0.2], [0.3, 0.4]]

        with patch('server.utils.load_local_embeddings', return_value=fake_embeddings) as mock_load, \
             patch('server.embeddings_api.list_embed_models', return_value=[_QWEN3EMBEDDINGAPI_KEY]), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG', {}):
            resp = embed_texts(texts=['Hi3798MV310', '海思机顶盒芯片'],
                               embed_model=_QWEN3EMBEDDINGAPI_KEY)

        assert isinstance(resp, BaseResponse)
        assert resp.code == 200
        assert resp.data == [[0.1, 0.2], [0.3, 0.4]]
        mock_load.assert_called_once_with(model=_QWEN3EMBEDDINGAPI_KEY)
        fake_embeddings.embed_documents.assert_called_once_with(['Hi3798MV310', '海思机顶盒芯片'])

    @staticmethod
    def test_local_model_empty_texts_returns_empty_data():
        """空 texts 列表：源码不做特判，直接把 [] 透传给 embed_documents，
        返回 mock 给出的空 data（BaseResponse(code=200, data=[])）。"""
        fake_embeddings = MagicMock()
        fake_embeddings.embed_documents.return_value = []

        with patch('server.utils.load_local_embeddings', return_value=fake_embeddings), \
             patch('server.embeddings_api.list_embed_models', return_value=['m']), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG', {}):
            resp = embed_texts(texts=[], embed_model='m')

        assert resp.code == 200
        assert resp.data == []
        fake_embeddings.embed_documents.assert_called_once_with([])

    @staticmethod
    def test_local_model_embed_documents_raises_returns_500():
        """本地 embed_documents 抛异常时，被外层 try/except 兜底，返回 500 且不向上传播。"""
        fake_embeddings = MagicMock()
        fake_embeddings.embed_documents.side_effect = RuntimeError('model load failed')

        with patch('server.utils.load_local_embeddings', return_value=fake_embeddings), \
             patch('server.embeddings_api.list_embed_models', return_value=['m']), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG', {}):
            resp = embed_texts(texts=['x'], embed_model='m')

        assert resp.code == 500
        assert '文本向量化过程中出现错误' in resp.msg
        assert 'model load failed' in resp.msg

    # ==================== 在线 API 模型分支 ====================

    @staticmethod
    def test_online_model_worker_not_found_returns_500():
        """模型在 EMBEDDING_API_CONFIG 中但 get_embedding_worker 返回 None -> 500 找不到 worker。"""
        _MODEL_NAME_KEY = 'online-emb'
        with patch('server.embeddings_api.list_embed_models', return_value=[]), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG',
                   {_MODEL_NAME_KEY: {'api_base_url': 'http://x', 'api_key': 'k'}}), \
             patch('server.embedding_workers.get_embedding_worker', return_value=None):
            resp = embed_texts(texts=['x'], embed_model=_MODEL_NAME_KEY)

        assert resp.code == 500
        assert '找不到 embedding worker' in resp.msg
        assert _MODEL_NAME_KEY in resp.msg

    @staticmethod
    def test_online_model_worker_success():
        """模型在 EMBEDDING_API_CONFIG 中且 worker 正常 -> 200 + worker 返回向量；
        to_query=True 时 prompt_name 应为 'query'。"""
        fake_worker = MagicMock()
        fake_worker.embed_texts_with_prompt.return_value = [[0.5, 0.6]]

        with patch('server.embeddings_api.list_embed_models', return_value=[]), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG',
                   {'online-emb': {'api_base_url': 'http://x', 'api_key': 'k', 'model_id': 'mid'}}), \
             patch('server.embedding_workers.get_embedding_worker',
                   return_value=lambda **kwargs: fake_worker):
            resp = embed_texts(texts=['hi'], embed_model='online-emb', to_query=True)

        assert resp.code == 200
        assert resp.data == [[0.5, 0.6]]
        fake_worker.embed_texts_with_prompt.assert_called_once_with(['hi'], 'query')

    @staticmethod
    def test_online_model_worker_embed_raises_returns_500():
        """在线 worker.embed_texts_with_prompt 抛异常 -> 内层 except 返回 500 文本向量化错误。"""
        fake_worker = MagicMock()
        fake_worker.embed_texts_with_prompt.side_effect = RuntimeError('network error')

        with patch('server.embeddings_api.list_embed_models', return_value=[]), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG',
                   {'online-emb': {'api_base_url': 'http://x'}}), \
             patch('server.embedding_workers.get_embedding_worker',
                   return_value=lambda **kwargs: fake_worker):
            resp = embed_texts(texts=['hi'], embed_model='online-emb')

        assert resp.code == 500
        assert '文本向量化错误' in resp.msg
        assert 'network error' in resp.msg

    # ==================== 未命中分支（question.md Q1 已知缺口，断言行为而非修复）====================

    @staticmethod
    def test_model_neither_local_nor_online_returns_500_unsupported():
        """模型既不在本地 list_embed_models() 也不在 EMBEDDING_API_CONFIG -> 500 不支持。

        这是 question.md Q1 标记的已知缺口：未配置的（在线）模型名会落入 fall-through，
        返回"指定的模型 ... 不支持 Embeddings 功能。"，此处仅做行为回归断言，不修复。
        """
        with patch('server.embeddings_api.list_embed_models', return_value=['local-only']), \
             patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'online-emb': {}}):
            resp = embed_texts(texts=['x'], embed_model='mystery-online-model')

        assert resp.code == 500
        assert '不支持' in resp.msg
        assert 'mystery-online-model' in resp.msg
