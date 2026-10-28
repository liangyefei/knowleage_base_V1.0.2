import uuid

from server.db.models.conversation_model import ConversationModel
from server.db.models.message_model import MessageModel
from server.db.session import with_session


@with_session
def add_conversation_to_db(session, chat_type, name="", conversation_id=None, user_id: str = None):
    """
    新增聊天记录
    """
    if not user_id:
        raise ValueError("user_id is required")
    if not conversation_id:
        conversation_id = uuid.uuid4().hex
    c = ConversationModel(id=conversation_id, chat_type=chat_type, name=name, user_id=user_id)

    session.add(c)
    return c.id


@with_session
def list_conversation(session, chat_type: str | None = None,
                       user_id: str | None = None) -> list[dict]:
    """
    查询会话列表，按 create_time 倒序。
    chat_type / user_id 为可选过滤条件。
    """
    q = session.query(ConversationModel)
    if chat_type is not None:
        q = q.filter(ConversationModel.chat_type == chat_type)
    if user_id is not None:
        q = q.filter(ConversationModel.user_id == user_id)
    conversations = q.order_by(ConversationModel.create_time.desc()).all()
    data = []
    for c in conversations:
        data.append({
            "conversation_id": c.id,
            "name": c.name,
            "chat_type": c.chat_type,
            "create_time": c.create_time,
            "user_id": c.user_id,
        })
    return data


@with_session
def get_conversation(session, conversation_id: str, user_id: str = None) -> dict | None:
    """
    查询单条会话，返回 Dict 或 None。
    """
    c = session.query(ConversationModel).filter_by(id=conversation_id, user_id=user_id).first()
    if not c:
        return None
    return {
        "conversation_id": c.id,
        "name": c.name,
        "chat_type": c.chat_type,
        "create_time": c.create_time,
        "user_id": c.user_id,
    }


@with_session
def update_conversation(session, conversation_id: str, name: str, user_id: str = None):
    """
    更新会话名称。在自身 session 内查询以避免 detached 实例 bug（参考 Task 1 修复模式）。
    """
    c = session.query(ConversationModel).filter_by(id=conversation_id, user_id=user_id).first()
    if c is not None:
        c.name = name
        return c.id
    return None


@with_session
def delete_conversation(session, conversation_id: str, user_id: str = None):
    """
    删除会话，级联删该会话下所有 message。
    使用批量 delete 避免加载 ORM 对象到内存。
    """
    # 先校验会话归属，不匹配则返回 None
    c = session.query(ConversationModel).filter_by(id=conversation_id, user_id=user_id).first()
    if c is None:
        return None
    # 先删该会话下的所有 message（外键关联，按 user_id 过滤）
    session.query(MessageModel).filter(
        MessageModel.conversation_id == conversation_id,
        MessageModel.user_id == user_id,
    ).delete(synchronize_session=False)
    # 再删 conversation 本身
    session.query(ConversationModel).filter(
        ConversationModel.id == conversation_id
    ).delete(synchronize_session=False)
    return None
