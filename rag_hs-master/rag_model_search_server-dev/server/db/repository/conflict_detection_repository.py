import json
from typing import Dict, List, Optional, Tuple

from configs import logger
from server.db.base import Base, engine
from server.db.models.conflict_detection_task_model import (ConflictDetectionTaskModel,
                                                            ConflictDetectionSwitchModel)
from server.db.session import with_session


def _ensure_table():
    """部署环境无 init_db 步骤，导入时自愈建表（幂等）。

    create_all 只建缺失的表，不改已存在表的列；新增列走在线 ALTER
    （幂等：先 inspect 查列再 ADD COLUMN），兼容 DEV 已有旧表。
    """
    try:
        Base.metadata.create_all(engine, tables=[ConflictDetectionTaskModel.__table__,
                                                 ConflictDetectionSwitchModel.__table__])
        from sqlalchemy import inspect, text
        insp = inspect(engine)
        cols = {c["name"] for c in insp.get_columns("conflict_detection_task")}
        with engine.begin() as conn:
            if "file_paths_json" not in cols:
                conn.execute(text("ALTER TABLE conflict_detection_task ADD COLUMN file_paths_json TEXT"))
            if "file_count" not in cols:
                conn.execute(text("ALTER TABLE conflict_detection_task ADD COLUMN file_count INTEGER DEFAULT 1"))
    except Exception as e:
        logger.warning(f"[conflict-detection] 建表自检失败: {e}")


_ensure_table()


def _to_dict(t: ConflictDetectionTaskModel) -> Dict:
    file_paths = None
    if t.file_paths_json:
        try:
            file_paths = json.loads(t.file_paths_json)
        except (TypeError, json.JSONDecodeError):
            file_paths = None
    return {
        "task_id": t.task_uuid,
        "knowledge_base_name": t.kb_name,
        "file_path": t.file_path,
        "file_paths": file_paths,
        "file_count": t.file_count or 1,
        "status": t.status,
        "total_chunks": t.total_chunks,
        "done_chunks": t.done_chunks,
        "conflict_count": t.conflict_count,
        "result_file": t.result_file,
        "error": t.error,
        "created_at": str(t.create_time) if t.create_time else None,
        "started_at": str(t.started_at) if t.started_at else None,
        "finished_at": str(t.finished_at) if t.finished_at else None,
    }


@with_session
def create_task(session, kb_name: str, file_path: str,
                file_paths: Optional[List[str]] = None) -> str:
    import uuid
    task = ConflictDetectionTaskModel(
        task_uuid=uuid.uuid4().hex,
        kb_name=kb_name,
        file_path=file_path,
        file_paths_json=json.dumps(file_paths, ensure_ascii=False) if file_paths else None,
        file_count=len(file_paths) if file_paths else 1,
        status="QUEUED",
    )
    session.add(task)
    session.flush()
    return task.task_uuid


@with_session
def update_task(session, task_uuid: str, **fields) -> None:
    session.query(ConflictDetectionTaskModel).filter_by(task_uuid=task_uuid).update(fields)


@with_session
def get_task(session, task_uuid: str) -> Optional[Dict]:
    t = session.query(ConflictDetectionTaskModel).filter_by(task_uuid=task_uuid).first()
    return _to_dict(t) if t else None


@with_session
def list_tasks(session, page: int = 1, page_size: int = 10,
               status: Optional[str] = None) -> Tuple[List[Dict], int]:
    q = session.query(ConflictDetectionTaskModel)
    if status:
        q = q.filter(ConflictDetectionTaskModel.status == status)
    total = q.count()
    rows = (q.order_by(ConflictDetectionTaskModel.id.desc())
             .offset((page - 1) * page_size)
             .limit(page_size).all())
    return [_to_dict(t) for t in rows], total


# ==================== 入库自动检测开关（按知识库，默认关） ====================

@with_session
def get_switch(session, kb_name: str) -> bool:
    """开关状态；无行/异常一律返回 False（默认不检测）。"""
    row = session.query(ConflictDetectionSwitchModel).filter_by(kb_name=kb_name).first()
    return bool(row.enabled) if row else False


@with_session
def set_switch(session, kb_name: str, enabled: bool) -> None:
    row = session.query(ConflictDetectionSwitchModel).filter_by(kb_name=kb_name).first()
    if row:
        row.enabled = bool(enabled)
    else:
        session.add(ConflictDetectionSwitchModel(kb_name=kb_name, enabled=bool(enabled)))


@with_session
def list_switches(session) -> List[Dict]:
    rows = session.query(ConflictDetectionSwitchModel).order_by(ConflictDetectionSwitchModel.kb_name).all()
    return [{"knowledge_base_name": r.kb_name, "enabled": bool(r.enabled),
             "update_time": str(r.update_time) if r.update_time else None}
            for r in rows]
