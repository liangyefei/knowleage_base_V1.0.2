"""Markdown 图片路径 URL 编码（%20）解码 UT。

回归 bug：markdown 里图片路径用 %20 表示空格（如 ./images/CV610%20DevBoard%20Top.png），
但磁盘上真实文件名是带空格的（CV610 DevBoard Top.png）。
_resolve_image_path 没有 URL 解码，导致 os.path.isfile 找不到文件，
exists=False，路径不被替换成 /kb-files/... 绝对路径。

修复：_resolve_image_path 在 sanitize 前加 urllib.parse.unquote 解码，
与 sfs_path.py / kb_doc_api.py 的解码逻辑保持一致。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from document_loaders.mymdloader import (
    _MD_IMAGE_PATTERN,
    VLLMMarkdownLoader,
    _encode_path_for_md,
    _replace_path_in_ref,
)

_SAMPLE_DIR = Path(__file__).resolve().parent.parent / "samples" / "hardware-design-guide"
_SAMPLE_MD = _SAMPLE_DIR / "product-instroduction.md"
_SAMPLE_MD_2 = _SAMPLE_DIR / "sunniwell-cv610-dev-board.md"


def _make_loader(md_path: str = "test.md") -> VLLMMarkdownLoader:
    """构造 loader 实例但不触发 __init__（避免 VLM 网络）。"""
    loader = VLLMMarkdownLoader.__new__(VLLMMarkdownLoader)
    loader.file_path = md_path
    return loader


def test_pattern_extracts_url_encoded_path():
    """正则能正确提取 URL 编码的路径（%20 不被当空白截断）。"""
    ref = '![](./images/CV610%20DevBoard%20Top.png)'
    m = _MD_IMAGE_PATTERN.match(ref)
    assert m is not None
    assert m.group(2) == './images/CV610%20DevBoard%20Top.png'


def test_resolve_image_path_decodes_url_encoded(tmp_path):
    """_resolve_image_path 对 %20 编码的路径解码后能匹配磁盘文件。"""
    # 构造 md 目录和带空格的真实图片文件
    md_dir = tmp_path
    img_dir = tmp_path / "images"
    img_dir.mkdir()
    img_file = img_dir / "CV610 DevBoard Top.png"
    img_file.write_bytes(b"\x89PNG\r\n\x1a\n")  # PNG 文件头

    loader = _make_loader(str(tmp_path / "test.md"))

    # 编码路径：./images/CV610%20DevBoard%20Top.png
    encoded_path = "./images/CV610%20DevBoard%20Top.png"
    resolved = loader._resolve_image_path(encoded_path, str(md_dir))

    # 解析后应该是带真实空格的绝对路径
    assert resolved, "路径应为非空（文件存在）"
    assert "%20" not in resolved, "解析后的路径不应再含 %20"
    assert "CV610 DevBoard Top.png" in resolved
    assert os.path.isfile(resolved), f"解析后文件应存在：{resolved}"


def test_resolve_image_path_url_encoded_not_exist(tmp_path):
    """文件不存在时，URL 编码路径解析后 exists 仍为 False。"""
    loader = _make_loader(str(tmp_path / "test.md"))
    encoded_path = "./images/not_exist%20file.png"
    resolved = loader._resolve_image_path(encoded_path, str(tmp_path))
    # 路径仍会解析，但 isfile 会是 False
    assert resolved
    assert not os.path.isfile(resolved)


def test_resolve_all_images_finds_url_encoded_files(tmp_path):
    """_resolve_all_images 对 URL 编码路径的引用能正确判定 exists=True。"""
    md_dir = tmp_path
    img_dir = tmp_path / "images"
    img_dir.mkdir()
    (img_dir / "CV610 DevBoard Top.png").write_bytes(b"\x89PNG\r\n\x1a\n")
    (img_dir / "WS73 ExtBoard Top.png").write_bytes(b"\x89PNG\r\n\x1a\n")

    content = (
        "| 正面 | 背面 |\n"
        "|---|---|\n"
        "| ![](./images/CV610%20DevBoard%20Top.png) | ![](./images/WS73%20ExtBoard%20Top.png) |\n"
    )
    loader = _make_loader(str(md_dir / "test.md"))
    images = loader._resolve_all_images(content, str(md_dir))

    assert len(images) == 2
    assert all(img["exists"] for img in images), \
        f"所有 URL 编码路径的图片应存在：{[img['resolved_path'] for img in images]}"


def test_replace_image_paths_replaces_url_encoded(tmp_path):
    """端到端：URL 编码路径的图片引用，路径被替换为带 %20 的绝对路径。"""
    md_dir = tmp_path
    img_dir = tmp_path / "images"
    img_dir.mkdir()
    (img_dir / "CV610 DevBoard Top.png").write_bytes(b"\x89PNG\r\n\x1a\n")

    content = "| ![](./images/CV610%20DevBoard%20Top.png) |\n"
    loader = _make_loader(str(md_dir / "test.md"))
    images = loader._resolve_all_images(content, str(md_dir))
    assert images[0]["exists"], "前置条件：图片应存在"

    result = loader._replace_image_paths(content, images)
    # 替换后应包含绝对路径（/... 或盘符），且 %20 被重新编码
    assert "./images/" not in result, "相对路径应被替换"
    assert "CV610%20DevBoard%20Top.png" in result, \
        "输出路径应重新编码空格为 %20"


def test_real_md_all_url_encoded_images_resolve():
    """端到端回归：真实 sample markdown 中所有 %20 编码路径都能找到文件。

    product-instroduction.md 有 26 处 %20 编码的图片引用，修复前全部 exists=False，
    路径不替换；修复后应全部能找到文件。
    """
    if not _SAMPLE_MD.exists():
        pytest.skip(f"sample markdown 不存在：{_SAMPLE_MD}")
    content = _SAMPLE_MD.read_bytes().decode("utf-8-sig")
    md_dir = str(_SAMPLE_MD.parent)

    loader = _make_loader(str(_SAMPLE_MD))
    images = loader._resolve_all_images(content, md_dir)

    # 找出所有原始路径含 %20 的引用
    encoded_imgs = [img for img in images if "%20" in img["img_path"]]
    assert len(encoded_imgs) > 0, "前置条件：sample 中应有 URL 编码路径"

    not_found = [img for img in encoded_imgs if not img["exists"]]
    assert not not_found, \
        f"{len(not_found)} 个 URL 编码路径的图片找不到文件：{[i['img_path'] for i in not_found[:3]]}"


def test_real_md_replace_all_url_encoded_paths():
    """端到端回归：真实 sample markdown 中所有图片路径都被替换为绝对路径。"""
    if not _SAMPLE_MD.exists():
        pytest.skip(f"sample markdown 不存在：{_SAMPLE_MD}")
    content = _SAMPLE_MD.read_bytes().decode("utf-8-sig")
    md_dir = str(_SAMPLE_MD.parent)

    loader = _make_loader(str(_SAMPLE_MD))
    images = loader._resolve_all_images(content, md_dir)
    result = loader._replace_image_paths(content, images)

    # 检查所有存在的图片引用都被替换成了绝对路径
    not_replaced = []
    for img in images:
        if not img["exists"]:
            continue
        # 在替换后的内容中找原路径片段（./images/...）
        if "./images/" in result[img["start"]:img["end"]]:
            not_replaced.append(img["img_path"])
    assert not not_replaced, \
        f"{len(not_replaced)} 个图片路径未被替换：{not_replaced[:3]}"


def test_second_sample_md_all_url_encoded_images_resolve():
    """端到端回归：第二个 sample markdown（sunniwell-cv610-dev-board.md）
    中所有 %20 编码路径都能找到文件。该文件有 3 处 URL 编码引用。"""
    if not _SAMPLE_MD_2.exists():
        pytest.skip(f"sample markdown 不存在：{_SAMPLE_MD_2}")
    content = _SAMPLE_MD_2.read_bytes().decode("utf-8-sig")
    md_dir = str(_SAMPLE_MD_2.parent)

    loader = _make_loader(str(_SAMPLE_MD_2))
    images = loader._resolve_all_images(content, md_dir)

    encoded_imgs = [img for img in images if "%20" in img["img_path"]]
    assert len(encoded_imgs) > 0, "前置条件：sample 中应有 URL 编码路径"

    not_found = [img for img in encoded_imgs if not img["exists"]]
    assert not not_found, \
        f"{len(not_found)} 个 URL 编码路径的图片找不到文件：{[i['img_path'] for i in not_found]}"


def test_second_sample_md_replace_all_url_encoded_paths():
    """端到端回归：第二个 sample markdown 中所有图片路径都被替换为绝对路径。"""
    if not _SAMPLE_MD_2.exists():
        pytest.skip(f"sample markdown 不存在：{_SAMPLE_MD_2}")
    content = _SAMPLE_MD_2.read_bytes().decode("utf-8-sig")
    md_dir = str(_SAMPLE_MD_2.parent)

    loader = _make_loader(str(_SAMPLE_MD_2))
    images = loader._resolve_all_images(content, md_dir)
    result = loader._replace_image_paths(content, images)

    not_replaced = []
    for img in images:
        if not img["exists"]:
            continue
        if "./images/" in result[img["start"]:img["end"]]:
            not_replaced.append(img["img_path"])
    assert not not_replaced, \
        f"{len(not_replaced)} 个图片路径未被替换：{not_replaced[:3]}"
