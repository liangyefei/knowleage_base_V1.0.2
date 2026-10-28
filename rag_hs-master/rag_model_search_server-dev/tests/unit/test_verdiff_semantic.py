"""Task 21：verdiff 语义增强 UT：双材料（blob diff + Milvus 两版本 chunk）。

_run_verdiff_for_kbs 在 blob difflib（行级精确）基础上，按 query 分别检索
两版本 chunk（语义聚焦）。_build_verdiff_context 拼三段：行级 diff + 两版本
语义片段。fail-open：Milvus 检索失败退化为纯 blob diff。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

_DIFF_RETURN = {
    "found": True, "file_name": "f.md", "from": "v1", "to": "v2",
    "from_sha": "aaa", "to_sha": "bbb", "from_size": 100, "to_size": 120,
    "identical": False, "added": 5, "removed": 2,
    "diff": "--- diff ---", "truncated": False,
}


def test_run_verdiff_returns_diff_and_semantic_chunks(mocker):
    """_run_verdiff_for_kbs 返回 {diff, from_chunks, to_chunks, from_label, to_label}。"""
    from server.chat.pipeline import version_diff
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                  return_value=dict(_DIFF_RETURN))
    fake_search = MagicMock(return_value=[{"content": "chunk A", "source": "f.md"}])
    mocker.patch.object(version_diff, "_search_version_chunks", fake_search)

    r = version_diff._run_verdiff_for_kbs(
        ["kb"], {"file": "f.md", "from": "v1", "to": "v2"}, query="HDMI 配置")

    assert r["from_label"] == "v1" and r["to_label"] == "v2"
    assert r["diff"] == "--- diff ---"
    assert len(r["from_chunks"]) == 1 and len(r["to_chunks"]) == 1
    # 两版本各检索一次
    assert fake_search.call_count == 2


def test_build_verdiff_context_includes_semantic_sections():
    """_build_verdiff_context 输出含【版本差异】+ 两版本语义片段三段。"""
    _FROM_CHUNKS_KEY = 'from_chunks'
    _FILE_NAME_KEY = 'f.md'
    _TO_CHUNKS_KEY = 'to_chunks'
    from server.chat.pipeline.version_diff import _build_verdiff_context
    vres = dict(_DIFF_RETURN)
    vres[_FROM_CHUNKS_KEY] = [{"content": "chunk A1", "source": _FILE_NAME_KEY}]
    vres[_TO_CHUNKS_KEY] = [{"content": "chunk B1", "source": _FILE_NAME_KEY}]
    vres["from_label"] = "v1"
    vres["to_label"] = "v2"

    ctx, doc = _build_verdiff_context(
        {"file": _FILE_NAME_KEY, "from": "v1", "to": "v2"}, vres)

    assert "【版本差异】" in ctx
    assert "【版本 v1 语义相关片段】" in ctx
    assert "【版本 v2 语义相关片段】" in ctx
    assert "chunk A1" in ctx and "chunk B1" in ctx
    assert doc is not None and doc["doc_type"] == "version_diff"
    assert doc[_FROM_CHUNKS_KEY] == vres[_FROM_CHUNKS_KEY]
    assert doc[_TO_CHUNKS_KEY] == vres[_TO_CHUNKS_KEY]


def test_run_verdiff_failopen_on_milvus_error(mocker):
    """Milvus 语义检索失败 → 退化为纯 blob diff，不阻塞。"""
    from server.chat.pipeline import version_diff
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                  return_value=dict(_DIFF_RETURN))
    mocker.patch.object(version_diff, "_search_version_chunks",
                        side_effect=Exception("milvus down"))

    r = version_diff._run_verdiff_for_kbs(["kb"], {"file": "f.md"}, query="q")

    assert r["diff"] == "--- diff ---"  # blob diff 仍可用
    assert r["from_chunks"] == [] and r["to_chunks"] == []  # 语义降级为空


def test_run_verdiff_no_query_skips_semantic(mocker):
    """无 query → 不检索语义 chunk（from_chunks/to_chunks 均为空）。"""
    from server.chat.pipeline import version_diff
    mocker.patch("server.knowledge_base.file_version.diff_versions",
                  return_value=dict(_DIFF_RETURN))
    fake_search = MagicMock(return_value=[])
    mocker.patch.object(version_diff, "_search_version_chunks", fake_search)

    r = version_diff._run_verdiff_for_kbs(
        ["kb"], {"file": "f.md", "from": "v1", "to": "v2"}, query=None)

    assert r["from_chunks"] == [] and r["to_chunks"] == []
    assert fake_search.call_count == 0  # 无 query 不检索
