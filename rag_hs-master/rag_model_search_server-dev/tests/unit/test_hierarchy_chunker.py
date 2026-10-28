"""HierarchyChunker 超大块拆分 UT（根因：大 md 文件产生超大 chunk 导致 milvus/embedding 失败）。

覆盖：
- _split_text_by_char：按字符拆分，尽量在换行处切
- _split_by_paragraph：单段超大块（无 \n\n 分隔的超长段落）按字符强制拆分
- chunk_markdown：65535 字符硬保护兜底
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from document_loaders.base_loader import HierarchyChunker


def test_split_text_by_char_short():
    """短文本不拆分。"""
    chunker = HierarchyChunker()
    result = chunker._split_text_by_char("短文本", 100)
    assert result == ["短文本"]


def test_split_text_by_char_exact_limit():
    """恰好等于 max_chars 不拆分。"""
    chunker = HierarchyChunker()
    text = "a" * 100
    result = chunker._split_text_by_char(text, 100)
    assert result == [text]


def test_split_text_by_char_long_all_chunks_within_limit():
    """长文本拆分后每块 <= max_chars。"""
    chunker = HierarchyChunker()
    text = "a" * 250
    result = chunker._split_text_by_char(text, 100)
    assert len(result) == 3
    for part in result:
        assert len(part) <= 100
    assert "".join(result) == text


def test_split_text_by_char_prefers_newline():
    """尽量在换行符处切分。"""
    chunker = HierarchyChunker()
    # 90 字符 + 换行 + 20 字符，max_chars=100 → 应在换行处切
    text = "a" * 90 + "\n" + "b" * 20
    result = chunker._split_text_by_char(text, 100)
    assert len(result) == 2
    assert result[0].endswith("\n")
    assert "".join(result) == text


def test_split_by_paragraph_normal_accumulation():
    """正常段落按 max_tokens 累积合并。"""
    _CONTENT_KEY = 'content'
    chunker = HierarchyChunker()
    block = {
        "title": "test",
        _CONTENT_KEY: "段落1\n\n段落2\n\n段落3",
        "level": "L2",
        "hierarchy": [],
        "page_numbers": [],
    }
    result = chunker._split_by_paragraph(block, max_tokens=2048)
    # 三个段落都小于 max_tokens，合并为一个块
    assert len(result) == 1
    assert "段落1" in result[0][_CONTENT_KEY]
    assert "段落3" in result[0][_CONTENT_KEY]


def test_split_by_paragraph_oversized_single_para():
    """单段超大块（无 \\n\\n 分隔，自身超 max_tokens）→ 按字符强制拆分（核心修复）。"""
    _CONTENT_KEY = 'content'
    chunker = HierarchyChunker()
    # 单段 5000 字符，无 \n\n，_estimate_tokens 远超 max_tokens
    huge_para = "中" * 5000
    block = {
        "title": "test",
        _CONTENT_KEY: huge_para,
        "level": "L2",
        "hierarchy": [],
        "page_numbers": [],
    }
    result = chunker._split_by_paragraph(block, max_tokens=2048)
    # 必须被拆分，不再返回单个原样超大块
    assert len(result) > 1
    # 每个子块字符数 <= char_limit(1024)
    for sub in result:
        assert len(sub[_CONTENT_KEY]) <= 1024
    # 拼接后内容长度不变（不丢内容）
    joined = "".join(sub[_CONTENT_KEY] for sub in result)
    assert len(joined) == 5000


def test_split_by_paragraph_mixed_normal_and_oversized():
    """混合：正常段 + 超大段 + 正常段 → 超大段拆分，正常段保留。"""
    _CONTENT_KEY = 'content'
    chunker = HierarchyChunker()
    content = "短段1\n\n" + "中" * 5000 + "\n\n短段2"
    block = {
        "title": "test",
        _CONTENT_KEY: content,
        "level": "L2",
        "hierarchy": [],
        "page_numbers": [],
    }
    result = chunker._split_by_paragraph(block, max_tokens=2048)
    # 超大段被拆成多块，短段1 和 短段2 保留
    assert len(result) > 2
    contents = [sub[_CONTENT_KEY] for sub in result]
    assert any("短段1" in c for c in contents)
    assert any("短段2" in c for c in contents)
    # 无任何子块超 65535 字符
    for sub in result:
        assert len(sub[_CONTENT_KEY]) < 65535


def test_chunk_markdown_hard_limit_65535():
    """chunk 超 65535 字符 或 2048 token → 硬保护强制按字符拆分（双层防御）。"""
    chunker = HierarchyChunker()
    # 构造一个无标题的超大文档，触发整文档单块兜底
    huge_text = "内容" * 50000  # 100000 字符 > 65535，token 估算远超 2048
    chunks = chunker.chunk_markdown(huge_text, "huge.md", source_type="markdown")
    # 必须被拆分，无单个 chunk 超 milvus 65535 字符 或 embedding 2048 token
    assert len(chunks) > 1
    for chunk in chunks:
        assert len(chunk["content"]) <= 65535
        assert chunker._estimate_tokens(chunk["content"]) <= chunker.max_tokens_per_chunk


def test_chunk_markdown_normal_not_affected():
    """正常文档不受硬保护影响。"""
    chunker = HierarchyChunker()
    # 用 L1+L2 标题 + 足够长正文（>50 字符避免被 _has_real_content 过滤）
    text = ("[L1] 标题1\n[L2] 子标题1\n\n"
            + "正文内容。" * 30
            + "\n[L2] 子标题2\n\n"
            + "更多正文。" * 30)
    chunks = chunker.chunk_markdown(text, "normal.md", source_type="markdown")
    assert len(chunks) >= 1
    for chunk in chunks:
        assert len(chunk["content"]) <= 65535
        assert chunker._estimate_tokens(chunk["content"]) <= chunker.max_tokens_per_chunk
