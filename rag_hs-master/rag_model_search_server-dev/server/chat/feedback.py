from fastapi import Body

from configs import log_verbose, logger
from server.db.repository import feedback_message_to_db
from server.utils import BaseResponse


def chat_feedback(message_id: str = Body("", max_length=32, description="聊天记录 ID（由对话接口返回的 message_id）"),
            user_id: str = Body(..., description="用户 ID（必填，UUID 格式，用于多租户隔离）"),
            score: int = Body(0, max=100, description="用户评分，满分 100，越大表示评价越高"),
            reason: str = Body("", description="用户评分理由，如：不符合芯片资料事实、答非所问等")
            ):
    try:
        feedback_message_to_db(message_id, score, reason, user_id=user_id)
    except Exception as e:
        msg = f"反馈聊天记录出错： {e}"
        logger.error(f'{e.__class__.__name__}: {msg}',
                     exc_info=e if log_verbose else None)
        return BaseResponse(code=500, msg=msg)

    return BaseResponse(code=200, msg=f"已反馈聊天记录 {message_id}")
