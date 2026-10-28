"""PDF 孤儿批次（缺口后已缓存）处理集成测试。

回归场景：缓存有缺口（batch 2 缺失，但 batch 1 + batch 3 已缓存）时，
旧实现 remaining 含 batch 2 和 batch 3 → batch 3 被重复调用 VLM。
修复后 remaining 只含 batch 2，batch 3 从缓存读，不重复调用 VLM。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import base64
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import fitz
import pytest

from document_loaders.base_loader import PDFBatchCache
from document_loaders.mypdfloader import PDFMarkdownExtractor


def _make_test_pdf(tmp_path, num_pages=9):
    """用 PyMuPDF 创建测试 PDF，每页写页码文本。"""
    pdf_path = tmp_path / "test.pdf"
    doc = fitz.open()
    for i in range(num_pages):
        page = doc.new_page()
        page.insert_text((50, 50), f"Page {i + 1}")
    doc.save(str(pdf_path))
    doc.close()
    return pdf_path


def _make_extractor(tmp_path):
    """构造 extractor，batch_size=3 让 9 页 = 3 批次。"""
    with patch("document_loaders.mypdfloader.get_vlm_config") as mock_cfg:
        mock_cfg.return_value = {
            "api_key": "fake_key",
            "model_id": "fake_model",
            "api_base_url": "http://fake",
            "max_retries": 1,
        }
        return PDFMarkdownExtractor(
            model_name="fake_model",
            batch_size=3,
            max_workers=2,
        )


def _prepopulate_cache(cache_dir, doc_hash, batches, content_tmpl="batch_{} content"):
    """预填缓存：写入指定批次的 .md 文件 + meta.json。"""
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache = PDFBatchCache.__new__(PDFBatchCache)
    cache.cache_dir = cache_dir
    for idx in batches:
        cache.save_batch(idx, content_tmpl.format(idx))
    # 写 meta.json（参数兼容）
    import json
    meta = {
        "pdf_path": "fake",
        "batch_size": 3,
        "dpi": 200,
        "model": "fake_model",
        "total_pages": 9,
    }
    with open(cache_dir / "meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f)


def test_orphan_batch_not_reprocessed(tmp_path, monkeypatch):
    """缺口后已缓存的孤儿批次不应被重复调用 VLM。

    缓存状态：batch 1 ✓, batch 2 ✗（缺口）, batch 3 ✓（孤儿）
    期望：VLM 只对 batch 2 调用一次，batch 1/3 从缓存读。
    """
    pdf_path = _make_test_pdf(tmp_path, num_pages=9)
    extractor = _make_extractor(tmp_path)

    # 重定向 KW_ROOT_PATH 到 tmp_path
    import document_loaders.base_loader as bl
    cache_root = tmp_path / "pdf_cache_root"
    monkeypatch.setattr(bl, "KW_ROOT_PATH", str(cache_root))

    # 预填缓存：batch 1 和 batch 3（batch 2 缺失）
    # _doc_hash 需要 pdf_path 的真实内容
    doc_hash = PDFBatchCache._doc_hash(str(pdf_path))
    cache_dir = cache_root / "pdf_cache" / doc_hash
    _prepopulate_cache(cache_dir, doc_hash, [1, 3])

    # mock call_vlm_model，记录调用
    call_log = []

    def fake_call_vlm(*args, **kwargs):
        # 从 messages 里推断是哪个 batch（通过 page_start）
        messages = kwargs.get("messages")
        text_content = messages[1]["content"][0]["text"]
        call_log.append(text_content)
        # 从 page_start 推断 batch_idx
        # page_start=1 → batch 1, page_start=4 → batch 2, page_start=7 → batch 3
        return f"VLM output for {text_content}"

    with patch("document_loaders.mypdfloader.call_vlm_model", side_effect=fake_call_vlm):
        result = extractor.extract_full_markdown(str(pdf_path))

    # VLM 只被调用 1 次（batch 2），batch 1/3 从缓存读
    assert len(call_log) == 1, f"VLM 应只调用 1 次，实际 {len(call_log)} 次"

    # 最终结果应含 3 个批次的内容，按序拼接
    assert "batch_1 content" in result
    assert "VLM output" in result  # batch 2 的 VLM 输出
    assert "batch_3 content" in result

    # 验证顺序：batch 1 < batch 2(VLM) < batch 3
    idx1 = result.index("batch_1 content")
    idx2 = result.index("VLM output")
    idx3 = result.index("batch_3 content")
    assert idx1 < idx2 < idx3, "批次顺序应保持 1→2→3"


def test_all_cached_no_vlm_call(tmp_path, monkeypatch):
    """全部批次已缓存时，VLM 不应被调用。"""
    pdf_path = _make_test_pdf(tmp_path, num_pages=9)
    extractor = _make_extractor(tmp_path)

    import document_loaders.base_loader as bl
    cache_root = tmp_path / "pdf_cache_root"
    monkeypatch.setattr(bl, "KW_ROOT_PATH", str(cache_root))

    doc_hash = PDFBatchCache._doc_hash(str(pdf_path))
    cache_dir = cache_root / "pdf_cache" / doc_hash
    _prepopulate_cache(cache_dir, doc_hash, [1, 2, 3])  # 全部缓存

    call_log = []
    with patch("document_loaders.mypdfloader.call_vlm_model",
               side_effect=lambda *a, **k: call_log.append(1) or "fake"):
        result = extractor.extract_full_markdown(str(pdf_path))

    assert len(call_log) == 0, "全部缓存时 VLM 不应被调用"
    assert "batch_1 content" in result
    assert "batch_2 content" in result
    assert "batch_3 content" in result


def test_no_cache_process_all(tmp_path, monkeypatch):
    """无缓存时，所有批次都调用 VLM。"""
    pdf_path = _make_test_pdf(tmp_path, num_pages=9)
    extractor = _make_extractor(tmp_path)

    import document_loaders.base_loader as bl
    cache_root = tmp_path / "pdf_cache_root"
    monkeypatch.setattr(bl, "KW_ROOT_PATH", str(cache_root))

    call_count = [0]

    def fake_call_vlm(*args, **kwargs):
        call_count[0] += 1
        return f"VLM batch {call_count[0]}"

    with patch("document_loaders.mypdfloader.call_vlm_model", side_effect=fake_call_vlm):
        extractor.extract_full_markdown(str(pdf_path))

    assert call_count[0] == 3, f"无缓存时应调用 3 次 VLM，实际 {call_count[0]}"
