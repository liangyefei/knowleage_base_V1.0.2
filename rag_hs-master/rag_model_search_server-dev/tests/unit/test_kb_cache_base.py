"""server/knowledge_base/kb_cache/base.py 纯逻辑单测。

覆盖:
- ThreadSafeObject:acquire 上下文(owner 空→thread id / _pool move_to_end)、
  obj getter/setter、start/finish/wait_for_loading
- CachePool:keys / get(命中/未命中) / set(触发 _check_count) / pop(None/指定) /
  acquire(空抛 RuntimeError / ThreadSafeObject move_to_end / 非 ThreadSafeObject 直接返回) /
  _check_count LRU
"""
# pylint: disable=protected-access
import threading
from collections import OrderedDict
from typing import Tuple, Union
from unittest.mock import MagicMock

import pytest

from server.knowledge_base.kb_cache.base import CachePool, ThreadSafeObject


class TestThreadSafeObject:
    """ThreadSafeObject 纯逻辑。"""

    @staticmethod
    def test_key_property():
        obj = ThreadSafeObject("my-key")
        assert obj.key == "my-key"

    @staticmethod
    def test_obj_getter_setter():
        obj = ThreadSafeObject("k")
        assert obj.obj is None
        obj.obj = "value"
        assert obj.obj == "value"

    @staticmethod
    def test_acquire_default_owner():
        pool = MagicMock()
        pool._cache = MagicMock()
        obj = ThreadSafeObject("k", pool=pool)
        with obj.acquire() as inner:
            # 上下文 yield self._obj
            assert inner is None  # _obj 默认 None
        # pool move_to_end 被调用
        pool._cache.move_to_end.assert_called_once_with("k")

    @staticmethod
    def test_acquire_explicit_owner():
        obj = ThreadSafeObject("k", pool=None)
        with obj.acquire(owner="my-owner", msg="test") as _:
            pass
        # 无 pool 时不调 move_to_end,不抛异常

    @staticmethod
    def test_acquire_no_pool_no_move_to_end():
        obj = ThreadSafeObject("k", pool=None)
        with obj.acquire() as inner:
            assert inner is None

    @staticmethod
    def test_start_finish_wait_for_loading():
        obj = ThreadSafeObject("k")
        # start_loading 后 wait_for_loading 会阻塞,所以用短超时
        obj.start_loading()
        # 在另一线程 finish_loading
        t = threading.Thread(target=lambda: (obj.finish_loading(),))
        t.start()
        obj.wait_for_loading()  # 不会阻塞,因为 finish 已在另线程调用
        t.join()


class TestCachePoolKeys:
    """CachePool.keys 返回键列表。"""

    @staticmethod
    def test_keys():
        pool = CachePool()
        pool._cache = OrderedDict([("a", 1), ("b", 2)])
        assert pool.keys() == ["a", "b"]


class TestCachePoolGet:
    """CachePool.get 命中/未命中。"""

    @staticmethod
    def test_hit():
        pool = CachePool()
        cache_obj = MagicMock()
        cache_obj.wait_for_loading = MagicMock()
        pool._cache["k"] = cache_obj
        result = pool.get("k")
        assert result is cache_obj
        cache_obj.wait_for_loading.assert_called_once()

    @staticmethod
    def test_miss():
        pool = CachePool()
        assert pool.get("nonexistent") is None


class TestCachePoolSet:
    """CachePool.set 触发 _check_count。"""

    @staticmethod
    def test_set_triggers_check_count():
        pool = CachePool(cache_num=2)
        pool._cache = OrderedDict([("k1", MagicMock()), ("k2", MagicMock())])
        obj = MagicMock()
        pool.set("k3", obj)
        assert pool._cache["k3"] is obj
        # _check_count 淘汰 k1(cache_num=2)
        assert "k1" not in pool._cache
        assert len(pool._cache) == 2


class TestCachePoolPop:
    """CachePool.pop None→popitem / 指定 key。"""

    @staticmethod
    def test_pop_none():
        """pop(None) 调用 popitem(last=False),返回 (key, value) 元组。"""
        _K1_KEY = 'k1'
        pool = CachePool()
        c1, c2 = MagicMock(), MagicMock()
        pool._cache = OrderedDict([(_K1_KEY, c1), ("k2", c2)])
        result = pool.pop(None)
        # popitem 返回元组,不是 value 本身
        assert isinstance(result, tuple)
        assert result[0] == _K1_KEY
        assert result[1] is c1
        assert _K1_KEY not in pool._cache

    @staticmethod
    def test_pop_specific():
        _KB_NAME_KEY = 'k2'
        pool = CachePool()
        c1, c2 = MagicMock(), MagicMock()
        pool._cache = OrderedDict([("k1", c1), (_KB_NAME_KEY, c2)])
        result = pool.pop(_KB_NAME_KEY)
        assert result is c2
        assert _KB_NAME_KEY not in pool._cache

    @staticmethod
    def test_pop_missing():
        pool = CachePool()
        assert pool.pop("missing") is None


class TestCachePoolAcquire:
    """CachePool.acquire 三分支。"""

    @staticmethod
    def test_missing_raises_runtime_error():
        pool = CachePool()
        with pytest.raises(RuntimeError, match="不存在"):
            pool.acquire("missing")

    @staticmethod
    def test_thread_safe_object():
        pool = CachePool()
        ts_obj = ThreadSafeObject("k", pool=pool)
        ts_obj.finish_loading()  # 必须 set _loaded,否则 get→wait_for_loading 死锁
        pool._cache["k"] = ts_obj
        with pool.acquire("k") as inner:
            assert inner is None
        # move_to_end 被调用
        assert list(pool._cache.keys())[-1] == "k"

    @staticmethod
    def test_non_thread_safe_object():
        pool = CachePool()
        plain_obj = MagicMock()
        pool._cache["k"] = plain_obj
        result = pool.acquire("k")
        # 非 ThreadSafeObject 直接返回 cache 本身
        assert result is plain_obj


class TestCachePoolCheckCount:
    """CachePool._check_count LRU 淘汰。"""

    @staticmethod
    def test_lru_eviction():
        pool = CachePool(cache_num=2)
        c1, c2, c3 = MagicMock(), MagicMock(), MagicMock()
        pool._cache = OrderedDict([("k1", c1), ("k2", c2), ("k3", c3)])
        pool._check_count()
        assert "k1" not in pool._cache
        assert len(pool._cache) == 2

    @staticmethod
    def test_no_eviction_when_under_limit():
        pool = CachePool(cache_num=5)
        pool._cache = OrderedDict([("k1", 1), ("k2", 2)])
        pool._check_count()
        assert len(pool._cache) == 2

    @staticmethod
    def test_zero_or_negative_cache_num_no_eviction():
        pool = CachePool(cache_num=-1)
        for i in range(10):
            pool._cache[f"k{i}"] = i
        pool._check_count()
        assert len(pool._cache) == 10
