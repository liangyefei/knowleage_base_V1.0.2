"""直接验证 faiss→milvus 迁移的正确性（绕过预存 Q1 embedding 路由缺口）。

测试对象：
- server/knowledge_base/kb_cache/milvus_cache.py 的 memo_milvus_pool / kb_milvus_pool
- server/chat/file_chat.py 的检索路径（score_threshold_process + similarity_search_with_score_by_vector）

不依赖 server/embeddings_api.py 的 embed_texts（存在 Q1 缺口不路由在线 worker），
改为 monkey-patch embed_texts 让其调用 Qwen3Embedding 在线 worker。

验证点：
1. memo_milvus_pool.load_vector_store(id) 创建 temp_{id} collection 并写入数据
2. similarity_search_with_score_by_vector + score_threshold_process 返回过滤后的结果
3. memo_milvus_pool.pop(id) drop 对应 collection
4. kb_milvus_pool.load_vector_store(kb_name, "summary") 创建 {kb_name}_summary collection
5. kb_milvus_pool.drop_vector_store drop 对应 collection
6. MemoMilvusPool._check_count 在 LRU 淘汰时 drop 被移除的 collection
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import logging
import os
import sys

# 把项目根目录加入 sys.path，便于直接运行
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import numpy as np

logger = logging.getLogger(__name__)
from langchain.docstore.document import Document

# ---- monkey-patch embed_texts 路由在线 embedding worker ----
import server.embeddings_api as emb_api
from configs import EMBEDDING_API_CONFIG
from server.embedding_workers import get_embedding_worker

_orig_embed_texts = emb_api.embed_texts


def _patched_embed_texts(texts, embed_model=emb_api.EMBEDDING_MODEL, to_query=False):
    """路由在线 embedding 模型到对应 worker（绕过 Q1 缺口）。"""
    try:
        if embed_model in EMBEDDING_API_CONFIG:
            worker_cls = get_embedding_worker(embed_model)
            if worker_cls is None:
                return emb_api.BaseResponse(
                    code=500, msg=f"找不到 worker: {embed_model}")
            cfg = EMBEDDING_API_CONFIG[embed_model]
            worker = worker_cls(
                api_base_url=cfg["api_base_url"],
                embedding_dim=cfg.get("embedding_dim", 2560),
                timeout=cfg.get("timeout", 60),
                max_workers=cfg.get("max_workers", 4),
                batch_size=cfg.get("batch_size", 32),
            )
            prompt_name = "query" if to_query else "document"
            embeddings = worker.embed_texts_with_prompt(texts, prompt_name)
            return emb_api.BaseResponse(data=embeddings)
        # 回退到原逻辑（本地模型）
        return _orig_embed_texts(texts, embed_model=embed_model, to_query=to_query)
    except Exception as e:
        emb_api.logger.error(f"patched embed_texts 失败: {e}")
        return emb_api.BaseResponse(code=500, msg=f"文本向量化错误：{e}")


emb_api.embed_texts = _patched_embed_texts
# 同时 patch 被 EmbeddingsFunAdapter 引用的入口
import server.knowledge_base.kb_service.base as kb_base

kb_base.embed_texts = _patched_embed_texts

# ---- 测试开始 ----
from pymilvus import Collection, connections, utility  # pylint: disable=wrong-import-order,ungrouped-imports

from server.knowledge_base.kb_cache.milvus_cache import _collection_name_from_key, kb_milvus_pool, memo_milvus_pool  # pylint: disable=wrong-import-order,ungrouped-imports
from server.knowledge_base.kb_service.base import score_threshold_process

MILVUS_HOST = "192.168.80.250"
MILVUS_PORT = 19530


def _connect_milvus():
    """建立 pymilvus 连接用于验证 collection 状态"""
    alias = "_test_verify"
    if not connections.has_connection(alias):
        connections.connect(alias=alias, host=MILVUS_HOST, port=MILVUS_PORT,
                            user="root", password="Milvus")
    return alias


def _collection_exists(name: str) -> bool:
    alias = _connect_milvus()
    try:
        return utility.has_collection(name, using=alias)
    except Exception:
        return False


def _row_count(name: str) -> int:
    alias = _connect_milvus()
    try:
        if not utility.has_collection(name, using=alias):
            return -1
        col = Collection(name, using=alias)
        col.load()
        n = col.num_entities
        col.release()
        return n
    except Exception as e:
        logger.warning("  [warn] num_entities 失败: %s", e)
        return -1


def _embed_query(text: str):
    """生成查询向量（用于 similarity_search_with_score_by_vector）"""
    r = _patched_embed_texts([text], to_query=True)
    return r.data[0]


def test_memo_milvus_pool_lifecycle():
    logger.info("\n=== Test 1: memo_milvus_pool 生命周期（temp_ collection） ===")
    test_id = "migration_test_001"
    # 先清理可能的残留
    memo_milvus_pool.pop(test_id)

    logger.info("  [step] load_vector_store(%s)", test_id)
    tso = memo_milvus_pool.load_vector_store(test_id)
    col_name = f"temp_{test_id}"
    assert col_name == _collection_name_from_key(test_id)

    logger.info("  [step] acquire + add_documents")
    with tso.acquire() as vs:
        docs = [
            Document(page_content="海思是一家半导体公司，专注于视频、安防、芯片设计。"),
            Document(page_content="Hi3798MV310 是海思的机顶盒芯片。"),
            Document(page_content="Hi3516CV610 是海思的安防摄像头芯片。"),
        ]
        vs.add_documents(docs)

    # Milvus 异步 flush/索引构建，给点时间再校验
    import time
    time.sleep(2)

    logger.info("  [verify] collection %s 存在", col_name)
    assert _collection_exists(col_name), f"collection {col_name} 未创建"

    logger.info("  [step] similarity_search_with_score_by_vector")
    with tso.acquire() as vs:
        # 检索前确保 collection 已 load（flush 后可能需 refresh）
        try:
            vs._load()
        except Exception as e:
            logger.debug("[milvus-migration] vs._load() skipped: %s", e)
            pass
        qv = _embed_query("海思的芯片")
        docs = vs.similarity_search_with_score_by_vector(qv, k=3)
        logger.info("  [verify] 返回 %s 条检索结果", len(docs))
        assert len(docs) > 0, "检索无结果"
        # 验证 score_threshold_process（operator.ge，相似度过滤，大=好）
        # 注意：调用方需保证 docs 的 score 为相似度（余弦/内积），非 L2 距离
        filtered = score_threshold_process(0.55, 3, docs)
        logger.info("  [verify] score_threshold_process(0.55) 过滤后剩 %s 条", len(filtered))

    logger.info("  [step] pop(%s) 应 drop collection", test_id)
    memo_milvus_pool.pop(test_id)
    import time
    time.sleep(1)
    assert not _collection_exists(col_name), f"pop 后 collection {col_name} 仍存在"
    logger.info("  [PASS] temp collection 已 drop")


def test_kb_milvus_pool_summary_lifecycle():
    logger.info("\n=== Test 2: kb_milvus_pool 摘要 collection 生命周期 ===")
    kb_name = "migration_test_kb"
    vec_name = "summary"
    # 先清理
    kb_milvus_pool.drop_vector_store(kb_name, vec_name)

    logger.info("  [step] load_vector_store(%s, %s)", kb_name, vec_name)
    tso = kb_milvus_pool.load_vector_store(kb_name, vec_name)
    col_name = f"{kb_name}_{vec_name}"
    assert col_name == _collection_name_from_key((kb_name, vec_name))
    # 验证不与正式 KB collection 冲突（正式 KB = {kb_name}，摘要 = {kb_name}_summary）
    assert col_name != kb_name

    logger.info("  [step] acquire + add_documents")
    with tso.acquire() as vs:
        docs = [
            Document(page_content="这是知识库摘要测试文档1", metadata={"source": "test"}),
            Document(page_content="这是知识库摘要测试文档2", metadata={"source": "test"}),
        ]
        vs.add_documents(docs)

    import time
    time.sleep(2)

    logger.info("  [verify] collection %s 存在", col_name)
    assert _collection_exists(col_name), f"collection {col_name} 未创建"

    logger.info("  [step] drop_vector_store(%s, %s)", kb_name, vec_name)
    kb_milvus_pool.drop_vector_store(kb_name, vec_name)
    import time
    time.sleep(1)
    assert not _collection_exists(col_name), f"drop 后 collection {col_name} 仍存在"
    logger.info("  [PASS] summary collection 已 drop")


def test_memo_lru_eviction_drops_collection():
    logger.info("\n=== Test 3: MemoMilvusPool._check_count LRU 淘汰 drop collection ===")
    # 临时把 cache_num 调小到 2，构造 3 个 temp collection 触发淘汰
    original_cache_num = memo_milvus_pool._cache_num
    memo_milvus_pool._cache_num = 2
    ids = ["lru_test_a", "lru_test_b", "lru_test_c"]
    try:
        # 先清理
        for i in ids:
            memo_milvus_pool.pop(i)
        import time
        time.sleep(0.5)

        # 依次 load 3 个，第 3 个会触发 LRU 淘汰第 1 个
        for i in ids:
            tso = memo_milvus_pool.load_vector_store(i)
            with tso.acquire() as vs:
                vs.add_documents([Document(page_content=f"lru test {i}")])

        col_a = f"temp_{ids[0]}"
        col_b = f"temp_{ids[1]}"
        col_c = f"temp_{ids[2]}"
        logger.info("  [verify] LRU 淘汰后 temp_%s 应被 drop，后两个保留", ids[0])
        # 淘汰是异步 flush，稍等
        time.sleep(1)
        assert not _collection_exists(col_a), \
            f"LRU 淘汰未 drop {col_a}（_check_count 重写失效）"
        assert _collection_exists(col_b), f"{col_b} 不应被淘汰"
        assert _collection_exists(col_c), f"{col_c} 不应被淘汰"
        logger.info("  [PASS] LRU 淘汰正确 drop 了被移除的 temp collection")
    finally:
        # 清理：drop 剩下的
        for i in ids:
            memo_milvus_pool.pop(i)
        memo_milvus_pool._cache_num = original_cache_num


if __name__ == "__main__":
    import traceback
    results = []
    for t in [test_memo_milvus_pool_lifecycle,
              test_kb_milvus_pool_summary_lifecycle,
              test_memo_lru_eviction_drops_collection]:
        try:
            t()
            results.append((t.__name__, "PASS"))
        except Exception as e:
            logger.info("  [FAIL] %s: %s", t.__name__, e)
            traceback.print_exc()
            results.append((t.__name__, f"FAIL: {e}"))

    logger.info("\n========== Summary ==========")
    for name, r in results:
        logger.info("  %s: %s", name, r)
    failed = [r for _, r in results if not r.startswith("PASS")]
    sys.exit(1 if failed else 0)