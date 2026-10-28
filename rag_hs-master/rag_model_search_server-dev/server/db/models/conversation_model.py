from sqlalchemy import Column, DateTime, String, func

from server.db.base import Base


class ConversationModel(Base):
    """
    聊天记录模型
    """
    __tablename__ = 'conversation'
    id = Column(String(32), primary_key=True, comment='对话框ID')
    user_id = Column(String(64), nullable=False, index=True, comment='用户ID')
    name = Column(String(50), comment='对话框名称')
    # chat/agent_chat等
    chat_type = Column(String(50), comment='聊天类型')
    create_time = Column(DateTime, default=func.now(), comment='创建时间')

    def __repr__(self):
        return f"<Conversation(id='{self.id}', name='{self.name}', chat_type='{self.chat_type}', create_time='{self.create_time}')>"  # pylint: disable=line-too-long
