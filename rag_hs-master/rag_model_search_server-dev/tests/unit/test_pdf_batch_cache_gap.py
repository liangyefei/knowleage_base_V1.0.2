"""PDF 批次缓存缺口（gap）处理 UT。

回归 bug：缓存中间批次缺失（如 batch_0091.md 不存在但 batch_0092.md 存在）时，
find_last_completed_batch 旧实现返回最大 idx（92），加载循环 load_batch(91)
直接 FileNotFoundError，导致整个 PDF 处理失败。

修复：
1. find_last_completed_batch 返回从 1 开始的最大连续 idx（遇缺口即停）
2. 加载循环 has_batch 检查，缺口处回退 last_completed，让 remaining 接管重处理
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from document_loaders.base_loader import PDFBatchCache


def _make_cache(tmp_path, batch_size=5, dpi=150, model="qwen3-vl", total_pages=100):
    """构造一个 PDFBatchCache 实例，cache_dir 重定向到 tmp_path 隔离。"""
    # 构造真实 PDF 文件（_doc_hash 需读取内容算 sha256）
    pdf_file = tmp_path / "fake.pdf"
    pdf_file.write_bytes(b"%PDF-1.4 fake content for hashing")
    cache = PDFBatchCache(
        pdf_path=str(pdf_file),
        batch_size=batch_size,
        dpi=dpi,
        model=model,
    )
    # 重定向 cache_dir 到 tmp_path 子目录，避免污染真实 /kb-files/pdf_cache
    cache.cache_dir = tmp_path / "pdf_cache_test"
    cache.meta["total_pages"] = total_pages
    return cache


def _save_batches(cache, indices, content_tmpl="batch_{} content"):
    """批量写入指定 idx 的 batch 文件。"""
    cache.cache_dir.mkdir(parents=True, exist_ok=True)
    for i in indices:
        cache.save_batch(i, content_tmpl.format(i))
    cache._save_meta()


def test_find_last_no_cache(tmp_path):
    """无缓存目录时返回 0。"""
    cache = _make_cache(tmp_path)
    assert cache.find_last_completed_batch() == 0


def test_find_last_empty_cache_dir(tmp_path):
    """缓存目录存在但无 batch 文件时返回 0。"""
    cache = _make_cache(tmp_path)
    cache.cache_dir.mkdir(parents=True, exist_ok=True)
    assert cache.find_last_completed_batch() == 0


def test_find_last_contiguous(tmp_path):
    """连续批次 1..5 返回 5。"""
    cache = _make_cache(tmp_path)
    _save_batches(cache, range(1, 6))
    assert cache.find_last_completed_batch() == 5


def test_find_last_gap_returns_contiguous(tmp_path):
    """缺口场景：batch 1..90 和 92 存在但 91 缺失 → 返回 90。

    回归 bug 核心：旧实现返回 92，加载循环 load_batch(91) 报 FileNotFoundError。
    """
    cache = _make_cache(tmp_path)
    _save_batches(cache, list(range(1, 91)) + [92])
    assert cache.find_last_completed_batch() == 90


def test_find_last_first_missing(tmp_path):
    """batch 1 缺失但 2..5 存在 → 返回 0（从 1 就断了）。"""
    cache = _make_cache(tmp_path)
    _save_batches(cache, range(2, 6))
    assert cache.find_last_completed_batch() == 0


def test_find_last_ignores_tmp(tmp_path):
    """.tmp 半成品不算已完成。"""
    cache = _make_cache(tmp_path)
    cache.cache_dir.mkdir(parents=True, exist_ok=True)
    # 写一个正常的 batch_0001.md
    cache.save_batch(1, "done")
    # 写一个半成品 batch_0002.md.tmp
    (cache.cache_dir / "batch_0002.md.tmp").write_text("half")
    cache._save_meta()
    assert cache.find_last_completed_batch() == 1


def test_load_batch_gap_does_not_raise(tmp_path):
    """缺口场景下 load_batch 不再被无条件调用，has_batch 先检查。"""
    cache = _make_cache(tmp_path)
    _save_batches(cache, list(range(1, 91)) + [92])
    # batch 91 应该 has_batch=False
    assert not cache.has_batch(91)
    assert cache.has_batch(90)
    assert cache.has_batch(92)


def test_init_with_gap_keeps_cache(tmp_path):
    """init 不应因缺口删除缓存（参数兼容时保留，缺口由 find+load 处理）。"""
    cache = _make_cache(tmp_path)
    _save_batches(cache, list(range(1, 91)) + [92])
    # 重新 init，参数一致 → 命中缓存，不删除
    cache.init(total_pages=100)
    assert cache.has_batch(90)
    assert cache.has_batch(92)
    assert not cache.has_batch(91)  # 缺口仍在，由 find 返回 90 触发重处理
