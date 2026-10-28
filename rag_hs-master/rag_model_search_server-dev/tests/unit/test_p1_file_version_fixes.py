"""P1-2 / P1-3 / P1-4 修复回归测试。

P1-2: ilike → _ci_eq 精确匹配（_ / % 不再当通配符误匹配近名文件）
P1-3: mark_history_deleted 加 max_id 上界防并发误标新行
P1-4: register_superseded 不重插全 deleted 的 sha 行（archive+同内容重传漏入库）

用 SQLite in-memory 做真实 DB 语义测试（不走 MagicMock，验证 SQL 层行为）。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import os
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from server.db.base import Base
from server.db.models.file_version_history_model import FileVersionHistoryModel

_KB1_KEY = 'kb1'
_FILE_NAME_KEY = 'f.md'
_VERSION_KEY = 'v1.0'


@pytest.fixture
def real_db(mocker, tmp_path):
    """SQLite in-memory DB + patch _db & session_scope 指向它。

    同时 patch _version_area_root 到 tmp_path，避免 register_superseded 写
    blob 到真实 KW_ROOT_PATH。
    """
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine, tables=[FileVersionHistoryModel.__table__])

    @contextmanager
    def _mock_db():
        session = Session(engine)
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    @contextmanager
    def _mock_session_scope():
        session = Session(engine)
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    from server.knowledge_base import file_version as fv
    mocker.patch.object(fv, "_db", _mock_db)
    mocker.patch("server.db.session.session_scope", _mock_session_scope)
    mocker.patch.object(fv, "_version_area_root",
                        return_value=str(tmp_path / "file-versions"))
    return engine


def _add_row(s, kb_name, file_name, sha, **kwargs):
    """插入一行 history（在 with Session(engine) as s 块内调）。

    可选参数 label/status/id 通过 kwargs 传入，避免签名参数过多。
    """
    _DEFAULT_LABEL = ""
    _DEFAULT_STATUS = "superseded"
    _TMP_DIR = "/tmp"
    _DEFAULT_FILE_SIZE = 100
    _EMPTY_STR = ""
    label = kwargs.get("label", _DEFAULT_LABEL)
    status = kwargs.get("status", _DEFAULT_STATUS)
    row_id = kwargs.get("id")
    row = FileVersionHistoryModel(
        kb_name=kb_name, file_name=file_name, file_path=os.path.join(_TMP_DIR, file_name),
        content_sha256=sha, size=_DEFAULT_FILE_SIZE, file_version_label=label, tags=_EMPTY_STR,
        note=_EMPTY_STR, status=status)
    if row_id is not None:
        row.id = row_id
    s.add(row)
    return row


# ── P1-2: _ci_eq 不把 _ / % 当通配符 ──

def test_get_current_version_no_underscore_wildcard(real_db):
    """P1-2: 文件名 rev-1_0 不匹配 rev-1X0（ilike 的 _ 会匹配任意单字符）。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, "rev-1_0.md", "sha_a", label="v_a")
        _add_row(s, _KB1_KEY, "rev-1X0.md", "sha_b", label="v_b")
        s.commit()

    label = fv.get_current_version(_KB1_KEY, "rev-1_0.md")
    assert label == "v_a", f"应只匹配 rev-1_0.md，实际 {label}"


def test_list_versions_no_percent_wildcard(real_db):
    """P1-2: 文件名 rev%1 不匹配 revX1（ilike 的 % 匹配任意序列）。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, "rev%1.md", "sha_a", label="v_a")
        _add_row(s, _KB1_KEY, "revX1.md", "sha_b", label="v_b")
        s.commit()

    versions = fv.list_versions(_KB1_KEY, "rev%1.md")
    assert len(versions) == 1, f"应只匹配 rev%1.md，实际 {len(versions)} 行"
    assert versions[0]["content_sha256"] == "sha_a"


def test_ci_eq_case_insensitive_exact(real_db):
    """P1-2 回归：_ci_eq 保持大小写不敏感（KB1 匹配 kb1）。"""
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, "kb1", "file.md", "sha_a", label="v_a")
        s.commit()

    label = fv.get_current_version("KB1", "FILE.md")
    assert label == "v_a", f"_ci_eq 应大小写不敏感，实际 {label}"


# ── P1-3: mark_history_deleted max_id 上界 ──

def test_mark_history_deleted_respects_max_id(real_db):
    """P1-3: max_id 上界 → id > max_id 的行不被标 deleted（并发新行保护）。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha1", label="v1", id=1)
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha2", label="v2", id=2)
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha3", label="v3", id=3)  # 模拟并发新行
        s.commit()

    cnt = fv.mark_history_deleted(_KB1_KEY, _FILE_NAME_KEY, max_id=2)
    assert cnt == 2, f"应只标 id<=2 的 2 行，实际 {cnt}"

    with Session(real_db) as s:
        rows = (s.query(FileVersionHistoryModel)
                .filter(FileVersionHistoryModel.kb_name == _KB1_KEY,
                        FileVersionHistoryModel.file_name == _FILE_NAME_KEY)
                .order_by(FileVersionHistoryModel.id).all())
        assert rows[0].status == "deleted"
        assert rows[1].status == "deleted"
        assert rows[2].status == "superseded", "id=3 应未被标 deleted（max_id 上界保护）"


def test_mark_history_deleted_no_max_id_marks_all(real_db):
    """P1-3 回归：max_id=None 标全部（旧行为保持）。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha1", label="v1", id=1)
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha2", label="v2", id=2)
        s.commit()

    cnt = fv.mark_history_deleted(_KB1_KEY, _FILE_NAME_KEY)
    assert cnt == 2, f"max_id=None 应标全部，实际 {cnt}"


def test_get_history_max_id_returns_max(real_db):
    """P1-3: get_history_max_id 返回当前最大 id（非 deleted 行）。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha1", label="v1", id=5)
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha2", label="v2", id=10)
        _add_row(s, _KB1_KEY, "other.md", "sha3", label="v3", id=15)
        s.commit()

    assert fv.get_history_max_id(_KB1_KEY, _FILE_NAME_KEY) == 10
    assert fv.get_history_max_id(_KB1_KEY) == 15


def test_get_history_max_id_excludes_deleted(real_db):
    """P1-3: get_history_max_id 只统计 status != deleted 的行。"""
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv
    with Session(real_db) as s:
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha1", label="v1", id=1, status="deleted")
        _add_row(s, _KB1_KEY, _FILE_NAME_KEY, "sha2", label="v2", id=5)
        s.commit()

    assert fv.get_history_max_id(_KB1_KEY, _FILE_NAME_KEY) == 5, "应排除 deleted 行（id=1）"


def test_get_history_max_id_zero_when_empty(real_db):
    """P1-3: 无行时返回 0。"""
    from server.knowledge_base import file_version as fv
    assert fv.get_history_max_id("kb1", "f.md") == 0
    assert fv.get_history_max_id("kb1") == 0


# ── P1-4: register_superseded 不重插全 deleted 的 sha 行 ──

def test_register_superseded_no_reinsert_after_archive(real_db):
    """P1-4: archive 标 deleted 后，同内容重传 register_superseded 不重插行。

    场景：archive_docs 标 history deleted → 同内容重传 → register_superseded
    查非 deleted 无行 → 查 deleted 同 sha 行 → 存在则不插，返回 label。
    """
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv

    with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as tmp:
        tmp.write("# same content")

    try:
        sha = fv._sha256_file(tmp.name)
        with Session(real_db) as s:
            _add_row(s, _KB1_KEY, _FILE_NAME_KEY, sha, label="v_old", status="deleted", id=1)
            s.commit()

        label = fv.register_superseded(_KB1_KEY, _FILE_NAME_KEY, tmp.name)
        assert label is not None, "应返回 label（deleted 行的）"
        assert label == "v_old", f"应返回 deleted 行的 label v_old，实际 {label}"

        with Session(real_db) as s:
            cnt = (s.query(FileVersionHistoryModel)
                   .filter(FileVersionHistoryModel.kb_name == _KB1_KEY,
                           FileVersionHistoryModel.file_name == _FILE_NAME_KEY)
                   .count())
            assert cnt == 1, f"不应新增行（仍 1 行），实际 {cnt} 行"
    finally:
        os.unlink(tmp.name)


def test_register_superseded_inserts_when_no_deleted_row(real_db):
    """P1-4 回归：无 deleted 行时正常插新行（存量文件首次覆盖）。"""
    from server.knowledge_base import file_version as fv

    with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as tmp:
        tmp.write("# brand new content")

    try:
        sha = fv._sha256_file(tmp.name)
        label = fv.register_superseded("kb1", "f.md", tmp.name)
        assert label is not None
        assert label == sha[:8], f"应返回兜底 sha[:8]，实际 {label}"

        with Session(real_db) as s:
            cnt = (s.query(FileVersionHistoryModel)
                   .filter(FileVersionHistoryModel.kb_name == "kb1",
                           FileVersionHistoryModel.file_name == "f.md")
                   .count())
            assert cnt == 1, f"应新增 1 行，实际 {cnt}"
    finally:
        os.unlink(tmp.name)


def test_p1_4_full_scenario_archive_reupload_is_new_true(real_db):
    """P1-4 完整场景：archive → 同内容重传 → record_current is_new=True。

    修复前：register_superseded 重插行 → record_current 命中此行 is_new=False
    → 跳过 Milvus add → "已入库但搜不到"。
    修复后：register_superseded 不重插 → record_current 查不到非 deleted top
    → is_new=True → 正常 add。
    """
    _KB1_KEY = 'kb1'
    from server.knowledge_base import file_version as fv

    with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as tmp:
        tmp.write("# same content for full scenario")

    try:
        # Step 1: 原始上传，record_current 登记一行
        v1 = fv.record_current(_KB1_KEY, _FILE_NAME_KEY, tmp.name, file_version_label=_VERSION_KEY)
        assert v1 is not None
        assert v1[0] == _VERSION_KEY and v1[1] is True, "首次应 is_new=True"

        # Step 2: archive 标 history deleted
        cnt = fv.mark_history_deleted(_KB1_KEY, _FILE_NAME_KEY)
        assert cnt == 1

        # Step 3: 同内容重传 → register_superseded 先调（应不重插）
        label = fv.register_superseded(_KB1_KEY, _FILE_NAME_KEY, tmp.name)
        assert label == _VERSION_KEY, f"应返回 deleted 行的 label v1.0，实际 {label}"

        # Step 4: record_current → 应 is_new=True（不命中 deleted 行）
        v2 = fv.record_current(_KB1_KEY, _FILE_NAME_KEY, tmp.name, file_version_label=_VERSION_KEY)
        assert v2 is not None
        assert v2[1] is True, (
            f"archive+同内容重传后 record_current 应 is_new=True（正常 add），"
            f"实际 is_new={v2[1]}")
    finally:
        os.unlink(tmp.name)
