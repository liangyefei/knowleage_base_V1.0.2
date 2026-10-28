"""markdown 标题行号缓存：用于 source_documents.page 字段定位。"""
import os
import threading
from collections import OrderedDict

from configs import logger

# 原文件行号缓存：path -> (mtime, lines)，用于 markdown 参考材料行号定位
# LRU 淘汰：超限时淘汰最久未访问的项（popitem(last=False)），避免全量 clear 抖动
_file_cache: OrderedDict = OrderedDict()
_file_cache_lock = threading.Lock()
_FILE_CACHE_MAX = 500


def _find_heading_line(source_path: str, titles: list) -> int | None:
    """在原 markdown 文件里搜索 titles[-1]（最后一个标题）所在行号。

    用于 source_documents.page 字段（markdown 约定为标题行号）。
    所有异常/降级返回 None，page 字段填空字符串。
    """
    if not titles or not source_path:
        return None
    try:
        if not os.path.exists(source_path):
            return None
        mtime = os.path.getmtime(source_path)

        # C3：to_thread 后多线程并发访问 _file_cache，加锁保护 OrderedDict 操作
        # 锁内：cache 查 + hit 时 move_to_end（快速，不持文件 IO）
        with _file_cache_lock:
            cached = _file_cache.get(source_path)
            if cached and cached[0] == mtime:
                lines = cached[1]
                _file_cache.move_to_end(source_path)  # LRU：标记为最近访问

        # 锁外：miss 时文件读取（慢 IO，不阻塞其他 cache 访问）
        if not cached or cached[0] != mtime:
            with open(source_path, encoding='utf-8') as f:
                lines = f.readlines()
            # 锁内：cache 写 + LRU 淘汰
            with _file_cache_lock:
                _file_cache[source_path] = (mtime, lines)
                if len(_file_cache) > _FILE_CACHE_MAX:
                    _file_cache.popitem(last=False)  # LRU：淘汰最久未访问

        # 锁外：标题搜索（CPU，无共享状态）
        last_title = titles[-1]
        for i, line in enumerate(lines, 1):
            if last_title in line:
                return i
        return None
    except Exception as e:
        logger.debug(f"[md_index] {source_path} 定位失败: {e}")
        return None
