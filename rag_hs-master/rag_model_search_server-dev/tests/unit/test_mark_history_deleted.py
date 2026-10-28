"""Bug A 修复测试：mark_history_deleted 标 history 行 status=deleted。

验证 delete/archive/recreate 路径调用后，record_current 的 sha 去重
不再命中已删文件（否则 is_new=False 跳过 Milvus add → "已入库但搜不到"）。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))


def _patch_db(mocker, session):
    """patch file_version._db 为返回 session 的 contextmanager。"""
    from server.knowledge_base import file_version as fv

    @contextmanager
    def _mock_db():
        yield session

    mocker.patch.object(fv, "_db", _mock_db)


def test_mark_history_deleted_single_file(mocker):
    """标单文件 history deleted：query + filter kb/file + update status。"""
    from server.knowledge_base import file_version as fv
    session = MagicMock()
    q = MagicMock()
    session.query.return_value = q
    q.filter.return_value = q
    q.update.return_value = 2
    _patch_db(mocker, session)

    cnt = fv.mark_history_deleted("kb1", "file.md")

    assert cnt == 2
    session.query.assert_called_once_with(fv.FileVersionHistoryModel)
    # filter 被调用（kb_name + status + file_name）
    assert q.filter.call_count >= 2
    q.update.assert_called_once_with({"status": "deleted"}, synchronize_session=False)


def test_mark_history_deleted_whole_kb(mocker):
    """file_name=None 标整库：不 filter file_name。"""
    from server.knowledge_base import file_version as fv
    session = MagicMock()
    q = MagicMock()
    session.query.return_value = q
    q.filter.return_value = q
    q.update.return_value = 5
    _patch_db(mocker, session)

    cnt = fv.mark_history_deleted("kb1")

    assert cnt == 5
    # file_name=None 时只 filter kb_name + status，不 filter file_name
    assert q.filter.call_count == 1


def test_mark_history_deleted_skips_already_deleted(mocker):
    """filter status != 'deleted'：query 链含 status 过滤条件（已删行不重复标记）。"""
    from server.knowledge_base import file_version as fv
    session = MagicMock()
    q = MagicMock()
    session.query.return_value = q
    q.filter.return_value = q
    q.update.return_value = 0
    _patch_db(mocker, session)

    cnt = fv.mark_history_deleted("kb1", "file.md")

    assert cnt == 0
    # filter 至少调用了 kb_name + status + file_name 条件
    assert q.filter.call_count >= 2
    # update 只更新 status 字段
    q.update.assert_called_once_with({"status": "deleted"}, synchronize_session=False)


def test_mark_history_deleted_failopen(mocker):
    """DB 异常时返回 0，不抛。"""
    from server.knowledge_base import file_version as fv

    @contextmanager
    def _mock_db():
        raise RuntimeError("db down")

    mocker.patch.object(fv, "_db", _mock_db)

    cnt = fv.mark_history_deleted("kb1", "file.md")
    assert cnt == 0


def test_record_current_not_skip_after_mark_deleted(mocker):
    """Bug A 核心场景：标 deleted 后 record_current 不命中旧 top 行 → is_new=True。

    模拟 mark_history_deleted 后，record_current 查 status != deleted 的 top 行
    返回 None（旧行已标 deleted），应新增行返回 (label, True)。
    """
    import os
    import tempfile

    from server.knowledge_base import file_version as fv

    # 真实临时文件（record_current 需算 sha）
    with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as tmp:
        tmp.write("# test content for sha")

    session = MagicMock()
    # top 查询返回 None（旧行已标 deleted，查不到非 deleted 行）
    session.query.return_value.filter.return_value.order_by.return_value.first.return_value = None
    session.query.return_value.filter.return_value.filter.return_value = session.query.return_value.filter.return_value
    _patch_db(mocker, session)

    try:
        v = fv.record_current("kb1", "file.md", tmp.name, file_version_label="v1.0")
        assert v is not None
        label, is_new = v
        assert label == "v1.0"
        assert is_new is True  # 标 deleted 后应新增行
    finally:
        os.unlink(tmp.name)
