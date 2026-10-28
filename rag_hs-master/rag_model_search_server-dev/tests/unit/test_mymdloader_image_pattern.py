"""Markdown 图片引用正则 + 路径解析 UT。

回归 bug：带 title 的图片引用 `![](path "title")` 被 group(2) `[^)]+` 整体吃掉，
title 混入路径经 sanitize 删双引号后变成 `path title`，os.path.isfile 失败，
exists=False 导致路径不替换。
修复：group(2) 改 `[^)\\s]+` 排除空白，title 走 group(3) 可选。
"""
# pylint: disable=no-use-sys-path-insert
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from document_loaders.mymdloader import (
    _MD_IMAGE_PATTERN,
    _replace_path_in_ref,
    _sanitize_image_path,
)

# 真实 sample markdown（含 51 个图片引用，44 个带 title）
_SAMPLE_DIR = Path(__file__).resolve().parent.parent / "samples" / "行车记录仪和无人机应用图像调试FAQ"
_SAMPLE_MD = _SAMPLE_DIR / "行车记录仪和无人机应用图像调试FAQ.md"


def test_pattern_with_title_extracts_pure_path():
    """带 title 的引用：group(2) 是纯 path（不含 title）。回归 bug 核心。"""
    ref = '![](figures/xxx.png "图片标题")'
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m is not None
    assert m.group(2) == "figures/xxx.png"
    assert m.group(3) == '"图片标题"'


def test_pattern_without_title():
    """无 title 的引用：group(2) 是 path，group(3) 是 None。"""
    ref = '![](figures/xxx.png)'
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m is not None
    assert m.group(2) == "figures/xxx.png"
    assert m.group(3) is None


def test_pattern_single_quote_title():
    """单引号 title 也能匹配。"""
    ref = "![](figures/xxx.png '标题')"
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m is not None
    assert m.group(2) == "figures/xxx.png"
    assert m.group(3) == "'标题'"


def test_pattern_with_alt_text():
    """带 alt 文本的引用：group(1) 是 alt。"""
    ref = '![alt 文本](figures/xxx.png "标题")'
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m is not None
    assert m.group(1) == "alt 文本"
    assert m.group(2) == "figures/xxx.png"


def test_pattern_path_with_no_space_keeps_full():
    """path 含中文但不含空格：完整匹配到 .png 后停（空格分隔 title）。"""
    ref = '![](figures/行车记录仪场景.png "标题")'
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m.group(2) == "figures/行车记录仪场景.png"


def test_replace_path_preserves_title():
    """_replace_path_in_ref 替换路径保留 title。"""
    ref = '![](figures/old.png "原标题")'
    new = _replace_path_in_ref(ref, "/abs/path/new.png")
    assert new == '![](/abs/path/new.png "原标题")'


def test_replace_path_without_title():
    """无 title 的引用替换路径后不多余加 title。"""
    ref = '![](figures/old.png)'
    new = _replace_path_in_ref(ref, "/abs/path/new.png")
    assert new == '![](/abs/path/new.png)'


def test_replace_path_with_alt():
    """带 alt 的引用替换路径后保留 alt。"""
    ref = '![图表](figures/old.png "标题")'
    new = _replace_path_in_ref(ref, "/abs/path/new.png")
    assert new == '![图表](/abs/path/new.png "标题")'


def test_sanitize_image_path_strips_quotes():
    """路径清洗：双引号被删（与后端 sanitizeFileName 对齐）。"""
    # 模拟 bug 路径：group(2) 如果吃了 title，sanitize 会删双引号留下 title 文字
    buggy_path = 'figures/xxx.png "标题"'
    cleaned = _sanitize_image_path(buggy_path)
    # 双引号被删，但 title 文字留下 → 路径污染（这就是 bug 的表现）
    assert '"' not in cleaned
    assert "标题" in cleaned  # title 文字残留


def test_real_md_all_images_resolve_to_existing_files():
    """端到端回归：真实 sample markdown 的所有图片引用解析后都能找到文件。

    修复前：51 个引用只有 7 个（无 title 的）能找到文件，44 个带 title 的全失败。
    修复后：51 个全部能找到文件。
    """
    if not _SAMPLE_MD.exists():
        pytest.skip(f"sample markdown 不存在：{_SAMPLE_MD}")
    content = _SAMPLE_MD.read_bytes().decode("utf-8-sig")
    md_dir = str(_SAMPLE_MD.parent)

    matches = list(_MD_IMAGE_PATTERN.finditer(content))
    assert len(matches) == 51, f"期望 51 个图片引用，实际 {len(matches)}"

    not_found = []
    for m in matches:
        raw_path = m.group(2)
        # 模拟 _resolve_image_path 的清洗流程
        cleaned = _sanitize_image_path(raw_path.strip().split('?')[0].split('#')[0].strip())
        full = os.path.normpath(os.path.join(md_dir, cleaned))
        if not os.path.isfile(full):
            not_found.append((raw_path, full))

    assert not not_found, f"{len(not_found)} 个图片引用解析后找不到文件：{not_found[:3]}"
