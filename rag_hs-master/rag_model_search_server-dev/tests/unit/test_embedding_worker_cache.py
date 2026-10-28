#!/usr/bin/env python3
"""_EMBEDDING_WORKER_CACHE 配置指纹 + 刷新接口 UT。

验证：配置变更 → 缓存键变 → 重建 worker（解决生产配置不刷新）；
      clear_embedding_worker_cache 能消除测试间污染。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from server.embeddings_api import (
    _EMBEDDING_WORKER_CACHE,
    _get_embedding_worker_cached,
    clear_embedding_worker_cache,
)


def _make_config(**overrides):
    base = dict(api_base_url="http://x", api_key="k", model_id="mid",
                embedding_dim=1024, dimensions=None, encoding_format="float",
                timeout=60, max_workers=4, batch_size=32)
    base.update(overrides)
    return base


def test_config_change_rebuilds_worker():
    """api_key 变更 → 配置指纹变 → 重建新 worker 实例。"""
    cfg1 = _make_config(api_key="key_v1")
    cfg2 = _make_config(api_key="key_v2")
    # side_effect 让两次实例化返回不同对象（MagicMock 默认 return_value 固定，会掩盖重建）
    w1_instance, w2_instance = MagicMock(), MagicMock()
    fake_worker_cls = MagicMock(side_effect=[w1_instance, w2_instance])
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=fake_worker_cls), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg1}):
        w1 = _get_embedding_worker_cached('m')
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=fake_worker_cls), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg2}):
        w2 = _get_embedding_worker_cached('m')
    assert w1 is w1_instance and w2 is w2_instance, "应通过 worker_class 实例化"
    assert w1 is not w2, "配置变更后应重建 worker 实例"
    assert fake_worker_cls.call_count == 2, "应实例化两次"


def test_same_config_reuses_worker():
    """配置不变 → 缓存命中 → 复用 worker 实例（保留连接池复用优势）。"""
    cfg = _make_config()
    fake_worker_cls = MagicMock()
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=fake_worker_cls), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg}):
        w1 = _get_embedding_worker_cached('m')
        w2 = _get_embedding_worker_cached('m')
    assert w1 is w2, "配置不变时应复用 worker"
    assert fake_worker_cls.call_count == 1, "应只实例化一次"


def test_clear_cache_by_model():
    """clear_embedding_worker_cache(model) 只清该模型所有指纹，保留其他模型。"""
    cfg = _make_config()
    fake_worker_cls = MagicMock()
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=fake_worker_cls), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG',
               {'m': cfg, 'other': _make_config(api_key='o')}):
        _get_embedding_worker_cached('m')
        _get_embedding_worker_cached('other')
        assert len(_EMBEDDING_WORKER_CACHE) == 2
        n = clear_embedding_worker_cache('m')
        assert n == 1
        assert len(_EMBEDDING_WORKER_CACHE) == 1


def test_clear_cache_all():
    """clear_embedding_worker_cache() 无参清空全部。"""
    cfg = _make_config()
    fake_worker_cls = MagicMock()
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=fake_worker_cls), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg}):
        _get_embedding_worker_cached('m')
        n = clear_embedding_worker_cache()
        assert n == 1
        assert len(_EMBEDDING_WORKER_CACHE) == 0


def test_no_cache_pollution_after_clear():
    """验证 clear_embedding_worker_cache 能消除测试间污染（既有失败测试根因）。"""
    cfg = _make_config()
    # 测试 1：worker 正常返回向量
    w_success = MagicMock()
    w_success.embed_texts_with_prompt.return_value = [[0.5, 0.6]]
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=lambda **kw: w_success), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg}):
        from server.embeddings_api import embed_texts
        r1 = embed_texts(texts=['hi'], embed_model='m')
        assert r1.code == 200 and r1.data == [[0.5, 0.6]]
    # 模拟"新测试"：手动清空缓存（conftest autouse fixture 在测试间自动做这件事）
    clear_embedding_worker_cache()
    # 测试 2：worker 抛异常 → 应返回 500（清空后用新 worker）
    w_fail = MagicMock()
    w_fail.embed_texts_with_prompt.side_effect = RuntimeError('network error')
    with patch('server.embedding_workers.get_embedding_worker',
               return_value=lambda **kw: w_fail), \
         patch('server.embeddings_api.EMBEDDING_API_CONFIG', {'m': cfg}):
        from server.embeddings_api import embed_texts
        r2 = embed_texts(texts=['hi'], embed_model='m')
        assert r2.code == 500, f"清空缓存后应用新 worker，预期 500，实际 {r2.code}"
        assert '文本向量化错误' in r2.msg
