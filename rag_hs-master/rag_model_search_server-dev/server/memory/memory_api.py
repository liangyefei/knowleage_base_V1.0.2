"""用户长期记忆管理端点（MemMachine 用户画像的查看/删除）。"""

from fastapi import Body, Query

from configs import logger
from server.memory.memmachine_client import get_memmachine, memmachine_enabled
from server.utils import BaseResponse


def list_user_memories(
        user_id: str = Query(..., description="用户 ID（与对话接口的 user_id 一致）"),
        limit: int = Query(50, ge=1, le=200),
        type: str = Query("", description="过滤类型：空=全部 / profile=用户画像 / conversation=对话记忆"),
) -> BaseResponse:
    """列出该用户的记忆（画像：习惯/爱好/角色；对话：不限轮次历史问答）。"""
    logger.info(f"/kw/memory/list request user_id={user_id} type={type}")
    if not memmachine_enabled():
        return BaseResponse(code=400, msg="长期记忆功能未启用（MEMMACHINE_ENABLED=false）")
    memories = get_memmachine().list_user_memories(user_id, limit=limit,
                                                   mem_type=type)
    return BaseResponse(data={"user_id": user_id, "count": len(memories),
                              "memories": memories})


async def delete_user_memories(
        user_id: str = Body(..., description="用户 ID（校验归属，防越权删除）"),
        memory_type: str = Body(..., description="episodic | semantic"),
        memory_ids: list[str] = Body(..., description="要删除的记忆 ID 列表"),
) -> BaseResponse:
    """删除该用户的指定记忆（删前校验归属）。"""
    logger.info(f"/kw/memory/delete request user_id={user_id} type={memory_type} ids={len(memory_ids)}")
    if not memmachine_enabled():
        return BaseResponse(code=400, msg="长期记忆功能未启用（MEMMACHINE_ENABLED=false）")
    if memory_type not in ("episodic", "semantic"):
        return BaseResponse(code=400, msg="memory_type 只能是 episodic 或 semantic")
    deleted = await get_memmachine().delete_memories(user_id, memory_type, memory_ids)
    return BaseResponse(msg=f"已删除 {deleted} 条记忆",
                        data={"user_id": user_id, "deleted": deleted})
