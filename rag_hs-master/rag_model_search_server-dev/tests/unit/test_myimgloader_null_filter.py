"""VLLMOCRLoader 有效描述 + 小图片过滤 UT。

覆盖：
- 有效描述 → _get_elements 返回 1 个 element，page_content 为描述原文
- 小图片 → extract_image 返回空串（占位 Document 由 _get_elements 内部处理）
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest

from document_loaders.myimgloader import VLLMOCRLoader


def _make_loader(file_path: str = "/tmp/test.png") -> VLLMOCRLoader:
    """构造不调真实 VLM 的 VLLMOCRLoader stub（绕过 __init__ 的 get_vlm_config）。"""
    loader = VLLMOCRLoader.__new__(VLLMOCRLoader)
    loader.file_path = file_path
    loader.extractor = MagicMock()
    return loader


def test_valid_description_returns_elements():
    """有效描述 → _get_elements 返回 1 个 element，page_content 为描述原文。"""
    loader = _make_loader()
    loader.extractor.extract_image.return_value = "这是一张芯片架构图"

    with patch('document_loaders.myimgloader._save_chunks_to_json'):
        elements = loader._get_elements()
    assert len(elements) == 1
    assert elements[0].page_content == "这是一张芯片架构图"
    assert elements[0].metadata["source_type"] == "image"


def test_extract_image_small_returns_empty_string():
    """小图片 → extract_image 返回空串（不再返回 'null'）。"""
    from document_loaders.myimgloader import VLLMImageExtractor
    extractor = VLLMImageExtractor.__new__(VLLMImageExtractor)
    extractor.min_image_pixels = 10000
    extractor.api_key = "fake"
    extractor.model = "fake"
    extractor.endpoint = "http://fake"
    extractor.headers = {}
    extractor.max_retries = 1
    extractor.retry_delay = 0

    with patch('document_loaders.myimgloader._get_image_pixel_count', return_value=5000):
        result = extractor.extract_image("/tmp/small.png")
    assert result == ""
