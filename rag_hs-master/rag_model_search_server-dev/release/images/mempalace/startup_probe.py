#!/usr/bin/env python3
"""启动时一次性跑 HNSW 容量探针，暖 NFS 页缓存。

探针本身进程级缓存后，每个服务进程的首次检索仍要跑一次探针；
先在本进程跑一遍让 chroma.sqlite3 的页进 NFS 客户端页缓存，
服务进程的首次探针即从 50-70s 降到秒级。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access

import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import os
import sys

sys.path.insert(0, "/app/.venv/lib/python3.12/site-packages")
os.environ["MEMPALACE_PALACE_PATH"] = "/data/palaces/kb61ece94b2c094fefb3b6b34ad983ba7a"

import mempalace.mcp_server as m  # noqa: E402

m._refresh_vector_disabled_flag()
# 同步跑一次完整性 quick_check（写 /tmp/mempalace-integrity-ok.ts，
# 服务进程见 marker 即跳过后台全库扫描，避免与查询抢 NFS I/O）
m._refresh_sqlite_integrity_status()
# 完整 dummy 检索：把 52MB HNSW 段 + 集合打开 + embedding 全走一遍，
# 页缓存暖起来后服务进程的首查不再付 EFS 冷读（实测 56s）
res = m.tool_search("startup warmup dummy", limit=1)
n = len(res.get("results", [])) if isinstance(res, dict) else -1
# 预热 tools/call 预检门（SQLite 完整性 quick_check）：服务进程自己的
# 首次 tools/call 会重跑一次，NFS 页缓存已暖 + quick_check(1) 快速模式
m._mcp_sqlite_integrity_refusal(None, "mempalace_search")
logger.info('[startup-probe] done results=%s', n)
