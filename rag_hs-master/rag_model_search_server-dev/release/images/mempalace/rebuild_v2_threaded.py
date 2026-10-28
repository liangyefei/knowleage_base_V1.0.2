#!/usr/bin/env python3
"""把 mempalace_drawers 全部重嵌入（百炼 v4/1024 维）到 mempalace_drawers_v2。

- 旧集合不动（回滚即改回配置）
- 断点续跑：状态文件记录已完成条数
- 批 100 行，API 调用 4 线程并发（每线程 10 条/调用）
"""

# pylint: disable=no-use-sys-path-insert
import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "/app/.venv/lib/python3.12/site-packages")
os.environ["MEMPALACE_PALACE_PATH"] = "/data/palaces/kb61ece94b2c094fefb3b6b34ad983ba7a"
os.environ["MEMPALACE_EMBEDDING_MODEL"] = "dashscope"

import chromadb  # noqa: E402
from mempalace.embedding import get_embedding_function  # noqa: E402

PALACE = os.environ["MEMPALACE_PALACE_PATH"]
OLD = "mempalace_drawers"
NEW = "mempalace_drawers_v2"
STATE = "/tmp/rebuild_v2_state.json"
BATCH = 100
WORKERS = 4

client = chromadb.PersistentClient(path=PALACE)
old = client.get_collection(OLD)
total = old.count()
ef = get_embedding_function()

new = client.get_or_create_collection(
    NEW, embedding_function=ef, metadata=dict(old.metadata or {})
)

done = 0
if os.path.isfile(STATE):
    try:
        with open(STATE, 'r', encoding='utf-8') as f:
            done = int(json.load(f).get("done", 0))
    except Exception:
        done = 0
if done > 0:
    cur = new.count()
    if cur < done:
        logger.info("[rebuild] state=%s but new.count=%s — 从头续跑 cur", cur)
        done = cur
logger.info('[rebuild] old=%s new_existing=%s resume_from=%s', total, new.count(), done)

t0 = time.time()
last = time.time()
for offset in range(done, total, BATCH):
    data = old.get(limit=BATCH, offset=offset,
                   include=["documents", "metadatas"])
    ids = data["ids"]
    docs = data["documents"]
    metas = data["metadatas"]
    # 4 线程并发嵌：100 条切成 10 个子批（每子批 10 条一次 API 调用）
    sub_batches = [docs[i:i + 10] for i in range(0, len(docs), 10)]
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        chunk_embs = list(ex.map(ef, sub_batches))
    embs = [e for chunk in chunk_embs for e in chunk]
    new.upsert(ids=ids, documents=docs, metadatas=metas, embeddings=embs)
    done = offset + len(ids)
    with open(STATE, "w") as _state_f:
        json.dump({"done": done, "total": total}, _state_f)
    now = time.time()
    rate = done / max(now - t0, 1e-6)
    logger.info('[rebuild] %s/%s (%s%%) rate=%.0f/s eta=%.1fmin batch=%.1fs', done, total, done * 100 // total, rate, (total - done) / max(rate, 1e-06) / 60, now - last)
    last = now

logger.info('[rebuild] DONE %s/%s in %.0fs', new.count(), total, time.time() - t0)

if new.count() >= total:
    reg_path = os.path.join(PALACE, "mempalace_embedder.json")
    try:
        with open(reg_path, encoding="utf-8") as f:
            reg = json.load(f)
    except Exception:
        reg = {}
    reg[NEW] = {"model_name": "dashscope", "dimension": 1024}
    with open(reg_path, "w", encoding="utf-8") as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
    logger.info('[rebuild] SWITCHED: %s -> %s', reg_path, list(reg))
else:
    logger.info('[rebuild] count mismatch (%s < %s) — 不切换，检查状态文件重跑', new.count(), total)
