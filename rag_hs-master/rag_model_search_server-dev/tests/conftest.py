"""tests 顶层共享配置：将项目根目录注入 sys.path，供所有子目录用例 import server/configs。"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pytest


@pytest.fixture(autouse=True)
def _clear_embedding_worker_cache():
    """每个测试后清空 embedding worker 全局缓存。

    避免模块级 _EMBEDDING_WORKER_CACHE 导致测试间污染：
    例如 test_online_model_worker_success 写入的 worker 被
    test_online_model_worker_embed_raises_returns_500 误读导致假成功。
    """
    yield
    try:
        from server.embeddings_api import clear_embedding_worker_cache
        clear_embedding_worker_cache()
    except Exception as e:
        import logging
        logging.getLogger(__name__).debug(f"[tests] failed to clear embedding worker cache: {e}")
        pass
