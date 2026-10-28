#!/usr/bin/env python3
"""cbm_sync.py — CBM 本地热数据定期回传 EFS（本地盘方案配套）。

dual-mcp 的 CBM db 迁 pod 本地盘（emptyDir，env CBM_CACHE_DIR 指向本地）后，
EFS（/var/lib/codebase-memory-mcp）降级为持久源：启动时 deployment command
全量复制 EFS→本地，本脚本每 CBM_SYNC_INTERVAL 秒反向回传——
  *.db        → sqlite3 backup API（在线安全，防 WAL 撕裂；mtime/size
                未变时跳过，避免 127 库每轮全量 backup）
  其它文件    → mtime+size 变化才 copy2
  不回删      （EFS 上旧文件/备份保留）
pod 异常重启丢未回传写入（≤间隔窗口）时，Phase 1 检出 db 缺失/过期自动
重索引自愈。用法：nohup python3 cbm_sync.py &
"""
import os
import shutil
import sqlite3
import time

LOCAL = os.environ.get("CBM_CACHE_DIR", "/var/lib/cbm-local/codebase-memory-mcp")
EFS = os.environ.get("CBM_SYNC_DEST", "/var/lib/codebase-memory-mcp")
INTERVAL = int(os.environ.get("CBM_SYNC_INTERVAL", "600"))
LOG = os.environ.get("CBM_SYNC_LOG", "/var/log/cbm-sync.log")


def log(msg: str):
    try:
        with open(LOG, "a") as f:
            f.write(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")
    except OSError:
        pass


def _changed(st, dst_st) -> bool:
    return dst_st is None or dst_st.st_mtime < st.st_mtime or dst_st.st_size != st.st_size


def sync_db(src: str, dst: str) -> bool:
    tmp = dst + ".sync.tmp"
    s = sqlite3.connect(f"file:{src}?mode=ro", uri=True, timeout=60)
    try:
        d = sqlite3.connect(tmp)
        try:
            s.backup(d)
        finally:
            d.close()
    finally:
        s.close()
    os.replace(tmp, dst)
    return True


def sync_plain(src: str, dst: str) -> bool:
    tmp = dst + ".sync.tmp"
    shutil.copy2(src, tmp)
    os.replace(tmp, dst)
    return True


def round_sync() -> tuple:
    n_db = n_file = 0
    for root, _dirs, files in os.walk(LOCAL):
        rel = os.path.relpath(root, LOCAL)
        droot = EFS if rel == "." else os.path.join(EFS, rel)
        os.makedirs(droot, exist_ok=True)
        for fn in files:
            if fn.endswith((".sync.tmp", ".db-wal", ".db-shm", ".tmp")):
                continue
            src = os.path.join(root, fn)
            dst = os.path.join(droot, fn)
            try:
                st = os.stat(src)
                if st.st_size == 0:
                    continue
                dst_st = os.stat(dst) if os.path.exists(dst) else None
                if not _changed(st, dst_st):
                    continue
                if fn.endswith(".db"):
                    if sync_db(src, dst):
                        n_db += 1
                elif sync_plain(src, dst):
                    n_file += 1
            except Exception as e:
                log(f"WARN skip {fn}: {e}")
    return n_db, n_file


def main():
    log(f"cbm_sync start: local={LOCAL} dest={EFS} interval={INTERVAL}s")
    while True:
        t0 = time.time()
        try:
            n_db, n_file = round_sync()
            if n_db or n_file:
                log(f"round ok: {time.time()-t0:.1f}s, db={n_db}, files={n_file}")
        except Exception as e:
            log(f"round error: {e}")
        time.sleep(max(30, INTERVAL - int(time.time() - t0)))


if __name__ == "__main__":
    main()
