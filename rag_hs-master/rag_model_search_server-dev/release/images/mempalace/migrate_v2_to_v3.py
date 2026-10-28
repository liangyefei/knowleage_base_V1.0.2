import logging
import os
import time

import chromadb
from mempalace.embedding import DashScopeEF

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

PALACE = "/data/palaces/kbfccb654a43f241cbb520174f30951e81"
V3 = "mempalace_drawers_v3"
BATCH = 500

ef = DashScopeEF(api_key=os.environ.get("DASHSCOPE_API_KEY", ""))
c = chromadb.PersistentClient(path=PALACE)
old = c.get_collection("mempalace_drawers_v2", embedding_function=ef)
total = old.count()
logger.info("source v2 count: %s", total)

try:
    c.delete_collection(V3)
    logger.info("%s", 'stale v3 removed')
except Exception as e:
    logger.info('remove stale v3 skipped: %s', e)
new = c.create_collection(
    V3,
    metadata={"hnsw:space": "cosine", "hnsw:num_threads": 1,
              "hnsw:batch_size": 2, "hnsw:sync_threshold": 2},
    embedding_function=ef)
logger.info("%s", 'v3 created')

t0 = time.time()
off, moved = 0, 0
while off < total:
    d = old.get(include=["embeddings", "documents", "metadatas"],
                limit=BATCH, offset=off)
    if not d["ids"]:
        break
    new.add(ids=d["ids"], embeddings=d["embeddings"],
            documents=d["documents"], metadatas=d["metadatas"])
    moved += len(d["ids"])
    off += len(d["ids"])
    if moved % 2500 < BATCH:
        logger.info('progress %d/%d (%.0f/s)', moved, total, moved / (time.time() - t0))
logger.info('DONE moved=%d v3 count=%d elapsed=%.0fs', moved, new.count(), time.time() - t0)
