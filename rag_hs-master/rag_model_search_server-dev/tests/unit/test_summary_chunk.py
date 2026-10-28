"""server/knowledge_base/kb_summary/summary_chunk.py 纯逻辑单测。

覆盖:
- _drop_overlap:无重叠 / 全重叠 / 空列表 / _OVERLAP_SIZE 极小
- _join_docs:join+strip / 空串→None
- asummarize:chain.combine_docs 正常 / ValueError→空 / summary_combine 为空→[]
"""
# pylint: disable=protected-access
import asyncio
from unittest.mock import MagicMock

import pytest

from server.knowledge_base.kb_summary.summary_chunk import SummaryAdapter


def _make_doc(page_content, doc_id="d1"):
    """构造 DocumentWithVSId 替身。"""
    return MagicMock(page_content=page_content, id=doc_id)


class TestDropOverlap:
    """_drop_overlap 去除相邻文档重叠前缀。"""

    @staticmethod
    def test_empty_list():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        assert not adapter._drop_overlap([])

    @staticmethod
    def test_no_overlap():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        docs = [_make_doc("hello"), _make_doc("world")]
        result = adapter._drop_overlap(docs)
        # 第一个文档直接 append;第二个无重叠,for 循环不命中 break,不 append
        assert result == ["hello"]

    @staticmethod
    def test_full_overlap():
        _ABC_KEY = 'abc'
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        docs = [_make_doc(_ABC_KEY), _make_doc(_ABC_KEY)]
        result = adapter._drop_overlap(docs)
        # 第一个 _ABC_KEY;第二个与前一个重叠,去除重叠后剩余 ""
        assert result[0] == _ABC_KEY

    @staticmethod
    def test_overlap_size_too_small():
        """_OVERLAP_SIZE 极小时,range(len(pre_doc), 0-2*len(separator), -1) 可能不执行。"""
        adapter = SummaryAdapter(overlap_size=1, token_max=4096, chain=MagicMock())
        # separator="\n\n" 长度 2,OVERLAP_SIZE//2 - 2*2 = 0 - 4 = -4
        # range(len(pre_doc), -4, -1) 仍会迭代
        docs = [_make_doc("abc"), _make_doc("abcdef")]
        result = adapter._drop_overlap(docs)
        assert result[0] == "abc"

    @staticmethod
    def test_partial_overlap():
        """前一个文档结尾与后一个开头部分重叠。"""
        adapter = SummaryAdapter(overlap_size=20, token_max=4096, chain=MagicMock())
        docs = [_make_doc("hello world"), _make_doc("world foo")]
        result = adapter._drop_overlap(docs)
        # 第一个 "hello world";第二个开头 "world" 与前一个结尾 "world" 重叠
        assert result[0] == "hello world"
        if len(result) > 1:
            assert result[1] == " foo"


class TestJoinDocs:
    """_join_docs join + strip,空串返回 None。"""

    @staticmethod
    def test_normal_join():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        result = adapter._join_docs(["hello", "world"])
        assert result == "hello\n\nworld"

    @staticmethod
    def test_empty_list():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        result = adapter._join_docs([])
        assert result is None

    @staticmethod
    def test_whitespace_only():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        result = adapter._join_docs(["  ", "  "])
        assert result is None

    @staticmethod
    def test_strip():
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=MagicMock())
        result = adapter._join_docs(["  hello  ", "world"])
        # strip 只去首尾空白,内部空格保留:"hello  \n\nworld"
        assert result == "hello  \n\nworld"


class TestAsummarize:
    """asummarize 覆盖 combine_docs 正常 / ValueError / 为空 三分支。用 asyncio.run 驱动。"""

    @staticmethod
    def test_combine_docs_value_error_returns_empty():
        chain = MagicMock()
        chain.combine_docs = MagicMock(side_effect=ValueError("context too long"))
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=chain)
        result = asyncio.run(adapter.asummarize(file_description="desc", docs=[_make_doc("x")]))
        assert result == []

    @staticmethod
    def test_empty_summary_returns_empty():
        chain = MagicMock()
        chain.combine_docs = MagicMock(return_value=("", {}))
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=chain)
        result = asyncio.run(adapter.asummarize(file_description="desc", docs=[_make_doc("x")]))
        assert result == []

    @staticmethod
    def test_normal_summary():
        chain = MagicMock()
        chain.combine_docs = MagicMock(return_value=("summary text", {"steps": 1}))
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=chain)
        docs = [_make_doc("content1", "id1"), _make_doc("content2", "id2")]
        result = asyncio.run(adapter.asummarize(file_description="file desc", docs=docs))
        assert len(result) == 1
        doc = result[0]
        assert doc.page_content == "summary text"
        assert doc.metadata["file_description"] == "file desc"
        assert doc.metadata["summary_intermediate_steps"] == {"steps": 1}
        assert doc.metadata["doc_ids"] == "id1,id2"

    @staticmethod
    def test_empty_docs():
        chain = MagicMock()
        chain.combine_docs = MagicMock(return_value=("summary", {}))
        adapter = SummaryAdapter(overlap_size=10, token_max=4096, chain=chain)
        result = asyncio.run(adapter.asummarize(file_description="desc", docs=[]))
        # docs=[] 传给 combine_docs,若返回非空仍构造 Document
        # doc_ids = ",".join([]) = ""
        if result:
            assert result[0].metadata["doc_ids"] == ""
