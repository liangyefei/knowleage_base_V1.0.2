"""server/knowledge_base/kb_cache/milvus_cache.py 纯逻辑单测。

覆盖:
- _collection_name_from_key:tuple→{k0}_{k1} / 其他→temp_{key}
- ThreadSafeMilvus.docs_count:_obj+col 均真→num_entities / 异常→0 / None→0
- ThreadSafeMilvus.save:_obj+col 均真→flush / 异常吞 / None→直接 True
- ThreadSafeMilvus.drop:_obj+col 均真→release+drop(异常均吞) / 否则 no-op
- MemoMilvusPool._check_count:LRU 淘汰调 drop / drop 异常吞
- MemoMilvusPool.pop:key=None→popitem / 指定 key / drop 调用
"""
# pylint: disable=protected-access
# 阻断 pymilvus 等重依赖,避免 import 时连接真实 Milvus
import sys
from collections import OrderedDict
from unittest.mock import MagicMock, patch

import pytest

if "pymilvus" not in sys.modules:
    sys.modules["pymilvus"] = MagicMock()
if "langchain.vectorstores.milvus" not in sys.modules:
    _fake_milvus_mod = MagicMock()
    sys.modules["langchain.vectorstores.milvus"] = _fake_milvus_mod

from server.knowledge_base.kb_cache.milvus_cache import (
    MemoMilvusPool,
    ThreadSafeMilvus,
    _collection_name_from_key,
)


class TestCollectionNameFromKey:
    """_collection_name_from_key 纯函数。"""

    @staticmethod
    def test_tuple_key():
        assert _collection_name_from_key(("kb1", "vec1")) == "kb1_vec1"

    @staticmethod
    def test_str_key():
        assert _collection_name_from_key("abc123") == "temp_abc123"

    @staticmethod
    def test_int_key():
        assert _collection_name_from_key(42) == "temp_42"


class TestThreadSafeMilvusDocsCount:
    """docs_count 三个分支。"""

    @staticmethod
    def test_returns_num_entities():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = MagicMock()
        milvus._obj.col.num_entities = 100
        assert milvus.docs_count() == 100

    @staticmethod
    def test_exception_returns_zero():
        """num_entities 是 property 访问,异常需通过自定义 property 触发。"""
        milvus = ThreadSafeMilvus(("kb", "vec"))

        class _BoomCol:
            @property
            def num_entities(self):
                raise RuntimeError("boom")

        milvus._obj = MagicMock()
        milvus._obj.col = _BoomCol()
        assert milvus.docs_count() == 0

    @staticmethod
    def test_obj_or_col_none_returns_zero():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = None
        assert milvus.docs_count() == 0

        milvus._obj = MagicMock()
        milvus._obj.col = None
        assert milvus.docs_count() == 0


class TestThreadSafeMilvusSave:
    """save 三个分支。"""

    @staticmethod
    def test_flush_called():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = MagicMock()
        milvus._obj.col = MagicMock()
        assert milvus.save() is True
        milvus._obj.col.flush.assert_called_once()

    @staticmethod
    def test_flush_exception_swallowed():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = MagicMock()
        milvus._obj.col = MagicMock(flush=MagicMock(side_effect=RuntimeError("flush fail")))
        # 异常被吞,仍返回 True
        assert milvus.save() is True

    @staticmethod
    def test_obj_or_col_none_returns_true():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = None
        assert milvus.save() is True

        milvus._obj = MagicMock()
        milvus._obj.col = None
        assert milvus.save() is True


class TestThreadSafeMilvusDrop:
    """drop 两个分支(有 col / 无 col)。"""

    @staticmethod
    def test_drop_called():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = MagicMock()
        milvus._obj.col = MagicMock()
        milvus.drop()
        milvus._obj.col.release.assert_called_once()
        milvus._obj.col.drop.assert_called_once()

    @staticmethod
    def test_release_exception_swallowed():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = MagicMock()
        milvus._obj.col = MagicMock(
            release=MagicMock(side_effect=RuntimeError("release fail")),
            drop=MagicMock(side_effect=RuntimeError("drop fail")),
        )
        # 两个异常均被吞,不抛
        milvus.drop()

    @staticmethod
    def test_obj_or_col_none_noop():
        milvus = ThreadSafeMilvus(("kb", "vec"))
        milvus._obj = None
        milvus.drop()  # no-op,不抛

        milvus._obj = MagicMock()
        milvus._obj.col = None
        milvus.drop()


class TestMemoMilvusPoolCheckCount:
    """_check_count LRU 淘汰调 drop,drop 异常被吞。"""

    @staticmethod
    def test_lru_eviction_calls_drop():
        pool = MemoMilvusPool(cache_num=2)
        pool._cache = OrderedDict()
        c1 = MagicMock()
        c2 = MagicMock()
        c3 = MagicMock()
        pool._cache["k1"] = c1
        pool._cache["k2"] = c2
        # 超限:cache_num=2, 加入第三个
        pool._cache["k3"] = c3
        # 手动触发 _check_count
        pool._check_count()
        # k1 被淘汰,其 drop 被调用
        c1.drop.assert_called_once()
        c2.drop.assert_not_called()
        c3.drop.assert_not_called()
        assert len(pool._cache) == 2

    @staticmethod
    def test_drop_exception_swallowed():
        """cache_num=1 + 2 项 → 淘汰 k1,drop 异常被吞,剩 c2。"""
        pool = MemoMilvusPool(cache_num=1)
        pool._cache = OrderedDict()
        c1 = MagicMock()
        c1.drop = MagicMock(side_effect=RuntimeError("drop fail"))
        c2 = MagicMock()
        pool._cache["k1"] = c1
        pool._cache["k2"] = c2
        # 超限触发淘汰,drop 异常被吞
        pool._check_count()
        # 不抛异常,k1 被淘汰,drop 被调用一次
        c1.drop.assert_called_once()
        assert len(pool._cache) == 1

    @staticmethod
    def test_cache_num_zero_no_eviction():
        pool = MemoMilvusPool(cache_num=-1)
        pool._cache = OrderedDict()
        for i in range(5):
            pool._cache[f"k{i}"] = MagicMock()
        pool._check_count()
        # cache_num=-1 不触发淘汰
        assert len(pool._cache) == 5


class TestMemoMilvusPoolPop:
    """pop 两个分支 + drop 调用。"""

    @staticmethod
    def test_pop_none_popitem():
        """MemoMilvusPool.pop(None) 调 popitem 返回 (key, value) 元组。
        源码 cache.drop() 对元组调用会抛 AttributeError(已知源码 bug,此处断言行为)。
        """
        pool = MemoMilvusPool(cache_num=2)
        pool._cache = OrderedDict()
        c1 = MagicMock()
        c2 = MagicMock()
        pool._cache["k1"] = c1
        pool._cache["k2"] = c2
        # pop(None) 走 popitem 分支,cache 是 (key, value) 元组
        # cache.drop() 对元组调用 → AttributeError(源码未解包 popitem)
        with pytest.raises(AttributeError, match="tuple"):
            pool.pop(None)

    @staticmethod
    def test_pop_specific_key_works():
        """pop(指定 key) 走 _cache.pop(key, None),返回 ThreadSafeMilvus 且 drop 被调用。"""
        pool = MemoMilvusPool(cache_num=2)
        pool._cache = OrderedDict()
        c1 = MagicMock()
        c2 = MagicMock()
        pool._cache["k1"] = c1
        pool._cache["k2"] = c2
        result = pool.pop("k2")
        assert result is c2
        c2.drop.assert_called_once()
        c1.drop.assert_not_called()

    @staticmethod
    def test_pop_missing_key():
        pool = MemoMilvusPool(cache_num=2)
        pool._cache = OrderedDict()
        result = pool.pop("nonexistent")
        assert result is None
