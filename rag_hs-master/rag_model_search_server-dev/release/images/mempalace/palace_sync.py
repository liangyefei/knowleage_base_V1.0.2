#!/usr/bin/env python3
"""palace 本地盘(emptyDir) → NFS 单向回传同步。

每 PALACE_SYNC_INTERVAL 秒（默认 600）把 /data-local/palaces 同步回
/data/palaces（NFS）。sqlite 走 sqlite3 backup API（避免拷到撕裂文件），
其它文件按 mtime/size 差异增量 copy2；不回删（NFS 只增不改删，重启用新副本）。
"""

# pylint: disable=redefined-outer-name
import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import os
import shutil
import sqlite3
import time

SRC = "/data-local/palaces"
DST = "/data/palaces"
INTERVAL = int(os.environ.get("PALACE_SYNC_INTERVAL", "600"))


def sync_once():
    n = 0
    for root, _, files in os.walk(SRC):
        rel = os.path.relpath(root, SRC)
        dstdir = DST if rel == "." else os.path.join(DST, rel)
        os.makedirs(dstdir, exist_ok=True)
        for fn in files:
            if fn.endswith(("-wal", "-shm")):
                continue
            s = os.path.join(root, fn)
            d = os.path.join(dstdir, fn)
            try:
                st_s = os.stat(s)
                st_d = os.stat(d)
                if st_s.st_size == st_d.st_size and abs(st_s.st_mtime - st_d.st_mtime) < 2:
                    continue
            except OSError:
                pass
            try:
                if fn.endswith(".sqlite3"):
                    sc = sqlite3.connect(s)
                    dc = sqlite3.connect(d)
                    sc.backup(dc)
                    dc.close()
                    sc.close()
                else:
                    shutil.copy2(s, d)
                n += 1
            except Exception as err:
                logger.info("[palace-sync] skip %s: %s", s, err)
    logger.info('[palace-sync] synced %d files at %s', n, time.strftime('%Y-%m-%d %H:%M:%S'))


while True:
    time.sleep(INTERVAL)
    try:
        sync_once()
    except Exception as e:
        logger.info('[palace-sync] error: %s', e)
