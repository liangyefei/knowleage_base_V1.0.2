
from langchain.vectorstores.milvus import Milvus

from configs import CACHED_MEMO_VS_NUM, CACHED_VS_NUM, EMBEDDING_MODEL, kbs_config, log_verbose, logger
from server.knowledge_base.kb_cache.base import CachePool, ThreadSafeObject
from server.knowledge_base.kb_service.base import EmbeddingsFunAdapter
from server.utils import embedding_device


def _build_milvus(collection_name: str, embed_model: str = EMBEDDING_MODEL) -> Milvus:
    """创建/加载一个 Milvus collection（langchain Milvus 实例）

    collection 不存在时，langchain Milvus 会在首次 add_documents 时自动创建。
    """
    embeddings = EmbeddingsFunAdapter(embed_model)
    milvus_kwargs = kbs_config.get("milvus_kwargs", {}) or {}
    return Milvus(
        embedding_function=embeddings,
        collection_name=collection_name,
        connection_args=kbs_config.get("milvus"),
        auto_id=True,
        index_params=milvus_kwargs.get("index_params"),
        search_params=milvus_kwargs.get("search_params"),
    )


def _collection_name_from_key(key: str | tuple) -> str:
    """把 pool 的 key 转成 Milvus collection 名"""
    if isinstance(key, tuple):
        return f"{key[0]}_{key[1]}"
    return f"temp_{key}"


class ThreadSafeMilvus(ThreadSafeObject):
    def __repr__(self) -> str:
        cls = type(self).__name__
        return f"<{cls}: key: {self.key}, obj: {self._obj}, docs_count: {self.docs_count()}>"

    def docs_count(self) -> int:
        if self._obj and getattr(self._obj, "col", None):
            try:
                return self._obj.col.num_entities
            except Exception as e:
                if log_verbose:
                    logger.warning(f"milvus num_entities 失败: {e}")
                return 0
        return 0

    def save(self, path: str = None, create_path: bool = True):
        """Milvus 自动持久化，这里仅做 flush 保证写入可见"""
        if self._obj and getattr(self._obj, "col", None):
            try:
                self._obj.col.flush()
            except Exception as e:
                logger.warning(f"milvus flush 失败: {e}")
        return True

    def drop(self):
        """drop 对应的 Milvus collection"""
        if self._obj and getattr(self._obj, "col", None):
            try:
                self._obj.col.release()
            except Exception as e:
                if log_verbose:
                    logger.warning(f"milvus release 失败: {e}")
            try:
                self._obj.col.drop()
                logger.info(f"已 drop milvus collection: {_collection_name_from_key(self.key)}")
            except Exception as e:
                logger.warning(f"milvus drop collection 失败: {e}")

    def clear(self):
        """清空 collection：drop 后重建空实例"""
        self.drop()
        self._obj = _build_milvus(_collection_name_from_key(self.key),
                                  self._embed_model if hasattr(self, "_embed_model") else EMBEDDING_MODEL)


class _MilvusPool(CachePool):
    @staticmethod
    def new_vector_store(collection_name: str, embed_model: str = EMBEDDING_MODEL) -> Milvus:
        return _build_milvus(collection_name, embed_model)

    def save_vector_store(self, kb_name: str, path: str = None):
        cache = self.get(kb_name)
        if cache:
            return cache.save(path)
        return None

    def unload_vector_store(self, kb_name: str):
        cache = self.get(kb_name)
        if cache:
            self.pop(kb_name)
            logger.info(f"成功释放向量库：{kb_name}")


class KBMilvusPool(_MilvusPool):
    """知识库摘要等持久化向量库的 pool

    key 为 (kb_name, vector_name) 元组；collection 名为 {kb_name}_{vector_name}。
    pop 只移除内存缓存，不 drop collection（持久化数据由上层显式管理）。
    若要 drop collection，调用 drop_vector_store。
    """

    def load_vector_store(
            self,
            kb_name: str,
            vector_name: str = None,
            create: bool = True,
            embed_model: str = EMBEDDING_MODEL,
            embed_device: str = embedding_device(),
    ) -> ThreadSafeMilvus:
        self.atomic.acquire()
        vector_name = vector_name or embed_model
        cache_key = (kb_name, vector_name)
        cache = self.get(cache_key)
        if cache is None:
            item = ThreadSafeMilvus(cache_key, pool=self)
            item._embed_model = embed_model  # pylint: disable=protected-access
            self.set(cache_key, item)
            with item.acquire(msg="初始化"):
                self.atomic.release()
                collection_name = _collection_name_from_key(cache_key)
                logger.info(f"loading milvus collection '{collection_name}'")
                try:
                    vector_store = self.new_vector_store(collection_name, embed_model)
                    item.obj = vector_store
                except Exception as e:
                    logger.error(f"创建/加载 milvus collection {collection_name} 失败: {e}")
                    raise
                item.finish_loading()
            return self.get(cache_key)
        self.atomic.release()
        return self.get(cache_key)

    def drop_vector_store(self, kb_name: str, vector_name: str = None):
        """drop collection 并移除缓存"""
        self.atomic.acquire()
        try:
            cache_key = (kb_name, vector_name or kb_name)
            cache = self._cache.pop(cache_key, None)
            if cache is not None:
                cache.drop()
        finally:
            self.atomic.release()


class MemoMilvusPool(_MilvusPool):
    """临时向量库 pool（用于文件对话）

    key 为字符串 id；collection 名为 temp_{id}。
    pop 时会 drop collection，避免残留临时数据。
    """

    def load_vector_store(
            self,
            kb_name: str,
            embed_model: str = EMBEDDING_MODEL,
            embed_device: str = embedding_device(),
    ) -> ThreadSafeMilvus:
        self.atomic.acquire()
        cache = self.get(kb_name)
        if cache is None:
            item = ThreadSafeMilvus(kb_name, pool=self)
            item._embed_model = embed_model  # pylint: disable=protected-access
            self.set(kb_name, item)
            with item.acquire(msg="初始化"):
                self.atomic.release()
                logger.info(f"loading milvus collection 'temp_{kb_name}' to memory.")
                try:
                    vector_store = self.new_vector_store(f"temp_{kb_name}", embed_model)
                    item.obj = vector_store
                except Exception as e:
                    logger.error(f"创建/加载 milvus temp collection temp_{kb_name} 失败: {e}")
                    raise
                item.finish_loading()
            return self.get(kb_name)
        self.atomic.release()
        return self.get(kb_name)

    def pop(self, key: str = None) -> ThreadSafeMilvus:
        """移除缓存并 drop 对应 collection"""
        self.atomic.acquire()
        try:
            if key is None:
                cache = self._cache.popitem(last=False)
            else:
                cache = self._cache.pop(key, None)
            if cache is not None:
                cache.drop()
            return cache
        finally:
            self.atomic.release()

    def _check_count(self):
        """LRU 淘汰时 drop 被移除的临时 collection，避免残留 temp_* 数据。

        基类 CachePool._check_count 直接 popitem 丢弃，不经过 pop，
        所以这里重写以保证淘汰路径也 drop collection。
        """
        if isinstance(self._cache_num, int) and self._cache_num > 0:
            while len(self._cache) > self._cache_num:
                _, cache = self._cache.popitem(last=False)
                if cache is not None:
                    try:
                        cache.drop()
                    except Exception as e:
                        logger.warning(f"LRU 淘汰临时 collection 时 drop 失败: {e}")


kb_milvus_pool = KBMilvusPool(cache_num=CACHED_VS_NUM)
memo_milvus_pool = MemoMilvusPool(cache_num=CACHED_MEMO_VS_NUM)
