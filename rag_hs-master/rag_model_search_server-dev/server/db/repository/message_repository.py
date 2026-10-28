import uuid

from server.db.models.message_model import MessageModel
from server.db.session import with_session


@with_session
def add_message_to_db(session, conversation_id: str, chat_type, query, **kwargs):
    """新增聊天记录。

    后 4 个参数（通过 kwargs 传入，避免签名参数过多）:
        response: str - 回复内容（默认 ""）
        message_id: str | None - 消息 ID（默认 None，未传时自动 uuid4().hex 生成）
        metadata: dict | None - 元数据（默认 None）
        user_id: str | None - 用户 ID（必传，未传抛 ValueError）
    """
    response = kwargs.get("response", "")
    message_id = kwargs.get("message_id")
    metadata = kwargs.get("metadata")
    user_id = kwargs.get("user_id")
    if not user_id:
        raise ValueError("user_id is required")
    metadata = metadata or {}
    if not message_id:
        message_id = uuid.uuid4().hex
    m = MessageModel(id=message_id, chat_type=chat_type, query=query, response=response,
                     conversation_id=conversation_id, user_id=user_id,
                     meta_data=metadata)
    session.add(m)
    session.commit()
    return m.id


@with_session
def update_message(session, message_id, response: str = None, metadata: dict | None = None, user_id: str = None):
    """
    更新已有的聊天记录
    """
    metadata = metadata or {}
    m = session.query(MessageModel).filter_by(id=message_id, user_id=user_id).first()
    if m is not None:
        if response is not None:
            m.response = response
        if metadata:
            m.meta_data = {**(m.meta_data or {}), **metadata}
        session.add(m)
        session.commit()
        return m.id
    return None


@with_session
def get_message_by_id(session, message_id) -> MessageModel:
    """
    查询聊天记录
    """
    m = session.query(MessageModel).filter_by(id=message_id).first()
    return m


@with_session
def feedback_message_to_db(session, message_id, feedback_score, feedback_reason, user_id: str = None):
    """
    反馈聊天记录
    """
    m = session.query(MessageModel).filter_by(id=message_id, user_id=user_id).first()
    if m:
        m.feedback_score = feedback_score
        m.feedback_reason = feedback_reason
    session.commit()
    # message_id 不存在时 m 为 None，直接 m.id 会抛 AttributeError，此处兜底返回 None
    return m.id if m else None


@with_session
def filter_message(session, conversation_id: str, limit: int = 10, user_id: str = None):
    messages = (session.query(MessageModel).filter_by(conversation_id=conversation_id, user_id=user_id).
                # 用户最新的query 也会插入到db，忽略这个message record
                filter(MessageModel.response != '').
                # 返回最近的limit 条记录
                order_by(MessageModel.create_time.desc()).limit(limit).all())
    # 直接返回 List[MessageModel] 报错
    data = []
    for m in messages:
        data.append({"query": m.query, "response": m.response, "metadata": m.meta_data, "user_id": m.user_id})
    return data
