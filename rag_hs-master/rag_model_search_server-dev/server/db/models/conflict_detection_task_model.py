from sqlalchemy import Column, Integer, String, DateTime, Text, Boolean, func

from server.db.base import Base


class ConflictDetectionTaskModel(Base):
    """冲突检测任务（手工触发：指定文件 vs 知识库既有内容）。

    对外 task_id 用 task_uuid（uuid4().hex），自增 id 仅作内部主键。
    status: QUEUED / RUNNING / SUCCEEDED / FAILED
    结果明细落盘到 KW_ROOT_PATH/conflict-detections/<task_uuid>.json，
    conflict_count 为冗余汇总列，便于列表页展示。
    """
    __tablename__ = 'conflict_detection_task'
    id = Column(Integer, primary_key=True, autoincrement=True, comment='ID')
    task_uuid = Column(String(36), index=True, unique=True, comment='对外任务ID（uuid4 hex）')
    kb_name = Column(String(100), index=True, comment='比对目标知识库（MemPalace palace）')
    file_path = Column(String(512), comment='待检测文件的服务器本地绝对路径（批量任务=首个文件，完整列表在 file_paths_json）')
    file_paths_json = Column(Text, comment='批量任务的文件路径列表 JSON（单文件任务也存单元素列表）')
    file_count = Column(Integer, default=1, comment='批量任务的文件数')
    status = Column(String(16), default='QUEUED', index=True, comment='QUEUED/RUNNING/SUCCEEDED/FAILED')
    total_chunks = Column(Integer, default=0, comment='文件切分后的总 chunk 数')
    done_chunks = Column(Integer, default=0, comment='已检测 chunk 数（进度）')
    conflict_count = Column(Integer, default=0, comment='检出冲突总数')
    result_file = Column(String(512), comment='结果 JSON 文件绝对路径')
    error = Column(Text, comment='失败原因（FAILED 时非空）')
    create_time = Column(DateTime, default=func.now(), comment='创建时间')
    started_at = Column(DateTime, comment='开始执行时间')
    finished_at = Column(DateTime, comment='结束时间')

    def __repr__(self):
        return (f"<ConflictDetectionTask(uuid='{self.task_uuid}', kb='{self.kb_name}', "
                f"file='{self.file_path}', status='{self.status}', "
                f"conflicts={self.conflict_count})>")


class ConflictDetectionSwitchModel(Base):
    """入库自动冲突检测开关（按知识库，前端页面可设置）。

    无行 = 默认关（False）。行存在后按 enabled 走 upsert。
    """
    __tablename__ = 'conflict_detection_switch'
    kb_name = Column(String(100), primary_key=True, comment='知识库名（kbId 或注册名）')
    enabled = Column(Boolean, default=False, comment='入库自动冲突检测开关，默认关')
    update_time = Column(DateTime, default=func.now(), onupdate=func.now(), comment='更新时间')

    def __repr__(self):
        return (f"<ConflictDetectionSwitch(kb='{self.kb_name}', "
                f"enabled={self.enabled})>")
