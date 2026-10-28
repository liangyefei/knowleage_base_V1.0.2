"""_dedupe_docs 单元测试：验证预计算 + 长度分区重写后逻辑等价。

覆盖：完全相同/包含/被包含/替换/空块/空白归一/替换链/顺序保持等边界。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

import pytest
from langchain.docstore.document import Document

# pipeline 包在 server/chat/pipeline，测试需能 import
_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_SERVER_SRC = _PROJECT_ROOT / "server"
if str(_SERVER_SRC) not in sys.path:
    sys.path.insert(0, str(_SERVER_SRC))

from chat.pipeline.doc_utils import _dedupe_docs, _norm_text  # noqa: E402


def _doc(text: str, meta: dict = None) -> Document:
    return Document(page_content=text, metadata=meta or {})


class TestDedupeDocsBasic:
    """基础去重逻辑：完全相同/包含/被包含/无关。"""

    @staticmethod
    def test_empty_input():
        assert not _dedupe_docs([])

    @staticmethod
    def test_single_block_kept():
        d = _doc("唯一块")
        assert _dedupe_docs([d]) == [d]

    @staticmethod
    def test_identical_blocks_deduped():
        """完全相同：保留先出现的，丢弃后出现的。"""
        a, b = _doc("GPIO 配置"), _doc("GPIO 配置")
        assert _dedupe_docs([a, b]) == [a]

    @staticmethod
    def test_identical_with_whitespace_diff():
        """空白差异：_norm_text 去所有空白后相同 → 去重。"""
        a = _doc("GPIO 配置 寄存器")
        b = _doc("GPIO配置寄存器")
        # norm 后都是 "GPIO配置寄存器"，完全相同 → 保留 a
        assert _dedupe_docs([a, b]) == [a]

    @staticmethod
    def test_d_is_substring_of_kept_dropped():
        """d 是 kept 的子串（d 被 kept 包含）→ 丢弃 d。"""
        kept = _doc("GPIO 配置寄存器 0x1000 详细说明")
        sub = _doc("GPIO 配置寄存器")
        assert _dedupe_docs([kept, sub]) == [kept]

    @staticmethod
    def test_d_contains_kept_replaced():
        """d 包含 kept（kept 是 d 子串）→ 用 d 替换 kept。"""
        kept = _doc("GPIO 配置")
        full = _doc("GPIO 配置寄存器 0x1000 详细说明")
        result = _dedupe_docs([kept, full])
        assert len(result) == 1
        assert result[0].page_content == full.page_content

    @staticmethod
    def test_unrelated_blocks_all_kept():
        """无关块都保留。"""
        a, b, c = _doc("GPIO"), _doc("I2C"), _doc("UART")
        assert _dedupe_docs([a, b, c]) == [a, b, c]


class TestDedupeDocsEdgeCases:
    """边界：空块/纯空白块/替换链/顺序。"""

    @staticmethod
    def test_empty_blocks_skipped():
        """空 page_content 块跳过。"""
        empty = _doc("")
        real = _doc("GPIO 配置")
        assert _dedupe_docs([empty, real]) == [real]

    @staticmethod
    def test_whitespace_only_blocks_skipped():
        """纯空白块（norm 后为空）跳过。"""
        ws = _doc("   \n\t  ")
        real = _doc("GPIO")
        assert _dedupe_docs([ws, real]) == [real]

    @staticmethod
    def test_replacement_chain():
        """替换链：短→中→长，最终保留最长的。

        顺序：短块先入 out，中块替换短块，长块替换中块。
        """
        short = _doc("GPIO")
        mid = _doc("GPIO 配置")
        long = _doc("GPIO 配置 寄存器 0x1000")
        result = _dedupe_docs([short, mid, long])
        assert len(result) == 1
        assert result[0].page_content == long.page_content

    @staticmethod
    def test_replacement_then_unrelated_kept():
        """替换后，新块继续与后续无关块比较（无关则都保留）。"""
        short = _doc("GPIO")
        full = _doc("GPIO 配置 寄存器")  # 替换 short
        unrelated = _doc("I2C 总线")     # 与 full 无关，应保留
        result = _dedupe_docs([short, full, unrelated])
        assert len(result) == 2
        assert result[0].page_content == full.page_content
        assert result[1].page_content == unrelated.page_content

    @staticmethod
    def test_order_preserved_for_unrelated():
        """无关块保持输入顺序。"""
        blocks = [_doc(f"块{i}") for i in range(5)]
        assert _dedupe_docs(blocks) == blocks

    @staticmethod
    def test_substring_after_replacement():
        """替换后，后续块若是新 kept 的子串，应被丢弃。"""
        short = _doc("GPIO")               # 先入 out
        full = _doc("GPIO 配置 寄存器")    # 替换 short → out=[full]
        sub = _doc("GPIO 配置")           # 是 full 子串 → 丢弃
        result = _dedupe_docs([short, full, sub])
        assert len(result) == 1
        assert result[0].page_content == full.page_content


class TestDedupeDocsLengthPartition:
    """长度分区逻辑：验证三区间互斥、每对最多 1 次子串匹配的等价性。"""

    @staticmethod
    def test_equal_length_identical():
        """长度相等 + 内容相同 → 去重（t_len == kt_len 分支）。"""
        a, b = _doc("abc"), _doc("abc")
        assert _dedupe_docs([a, b]) == [a]

    @staticmethod
    def test_equal_length_different_kept():
        """长度相等但内容不同 → 都保留（长度相等分支不命中子串）。"""
        a, b = _doc("abc"), _doc("xyz")
        assert len(_dedupe_docs([a, b])) == 2

    @staticmethod
    def test_shorter_is_substring_dropped():
        """d 更短且是 kept 子串 → 丢弃（t_len < kt_len 分支）。"""
        kept = _doc("abcdefgh")
        sub = _doc("cdef")
        assert _dedupe_docs([kept, sub]) == [kept]

    @staticmethod
    def test_shorter_not_substring_kept():
        """d 更短但非 kept 子串 → 都保留（t_len < kt_len 分支不命中）。"""
        kept = _doc("abcdefgh")
        other = _doc("xyz1")  # 更短但不是子串
        assert len(_dedupe_docs([kept, other])) == 2

    @staticmethod
    def test_longer_contains_kept_replaced():
        """d 更长且包含 kept → 替换（t_len > kt_len 分支）。"""
        kept = _doc("cdef")
        full = _doc("abcdefgh")
        result = _dedupe_docs([kept, full])
        assert len(result) == 1
        assert result[0].page_content == full.page_content

    @staticmethod
    def test_longer_not_contains_kept_both_kept():
        """d 更长但不包含 kept → 都保留（t_len > kt_len 分支不命中）。"""
        kept = _doc("abcd")
        other = _doc("xyz12345")  # 更长但不包含 abcd
        assert len(_dedupe_docs([kept, other])) == 2


class TestNormTextConsistency:
    """验证 _norm_text 行为（去重比较的基础）。"""

    @staticmethod
    def test_norm_removes_all_whitespace():
        assert _norm_text("a b\tc\nd") == "abcd"

    @staticmethod
    def test_norm_empty_and_none():
        assert _norm_text("") == ""
        assert _norm_text(None) == ""
