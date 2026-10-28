from sqlalchemy import Column, DateTime, Integer, String, func

from server.db.base import Base


class FileVersionHistoryModel(Base):
    """
    文件版本历史（文件级多版本管理，纯 String 版本标签方案）。

    版本标识 = file_version_label(String，后端约定，格式任意不递增）；
    内容寻址 = content_sha256（blob 寻址键，同内容天然去重）；
    时序依据 = id 自增主键 + create_time（替代原 INT version 的排序/切尾）。
    同 file_version_label 可有多行历史（每次上传一条），is_current 取
    id desc 第一行，回滚按 (kb_name, file_name, file_version_label, max(id)) 定位。
    当前版本也会有一行（上传成功后 best-effort 登记），status 区分：
    - superseded: 已被更新的版本取代（可回滚目标）
    - deleted:    已被保留策略清理（blob 已删，不可回滚）
    """
    __tablename__ = 'file_version_history'
    id = Column(Integer, primary_key=True, autoincrement=True, comment='ID')
    kb_name = Column(String(50), index=True, comment='知识库名称')
    file_name = Column(String(255), index=True, comment='文件逻辑名（文档=文件名，代码=code 目录下相对路径）')
    file_path = Column(String(512), default='', comment='文件绝对路径（回滚时写回目标）')
    content_sha256 = Column(String(64), comment='内容 sha256（版本区 blob 寻址键）')
    size = Column(Integer, default=0, comment='字节数')
    file_version_label = Column(String(64), default='', comment='后端约定的版本标签（任意格式字符串，不递增；空则模型端用 sha[:8] 兜底）')
    tags = Column(String(255), default='', comment='文件标签（逗号分隔串，如 GPIO,UART；已去空白/去重/去空）')
    note = Column(String(255), default='', comment='备注（如 restored from v2）')
    status = Column(String(16), default='superseded', comment='superseded/deleted')
    create_time = Column(DateTime, default=func.now(), comment='登记时间')

    def __repr__(self):
        return (f"<FileVersionHistory(kb='{self.kb_name}', file='{self.file_name}', "
                f"label='{self.file_version_label}', sha='{self.content_sha256[:12]}...', "
                f"status='{self.status}')>")
