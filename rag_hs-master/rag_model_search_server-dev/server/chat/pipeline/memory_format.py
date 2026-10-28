"""长期记忆格式化：用户画像与相关历史对话转 prompt 段。"""
import asyncio
import difflib


async def _search_memory_both(mm, user_id: str, conversation_id: str, query: str):
    """并行检索画像+对话记忆（gather 并发，省一个 RTT 的等待）。

    返回 (user_memories, conversation_memories) 元组，与调用方解构一致。
    mm 方法已为 async（httpx.AsyncClient），直接 await + gather 并发（1×RTT）。
    """
    return await asyncio.gather(
        mm.search_user_memories(user_id, query),
        mm.search_conversation_memories(user_id, conversation_id, query),
    )


def _format_conversation(memories, history=None) -> str | None:
    """把召回的历史对话格式化为 prompt 段；空/与前端 history 重复则 None。"""
    if not memories:
        return None
    _hist_text = " ".join((getattr(h, "content", "") or "") for h in (history or []))
    # 循环外预计算历史尾部切片（原代码每条记忆重复切片 _hist_text[-2000:]）
    # _hist_text 为空时跳过所有去重检查（无历史可比对，difflib 无意义）
    _hist_tail = _hist_text[-2000:] if _hist_text else ""
    lines = []
    for m in memories:
        content = (m.get("content") or "").strip()
        if not content:
            continue
        # 与前端已传 history 重复的（前端 3 轮原文已在上下文）跳过
        q_part = content.split("\n答：", 1)[0].replace("问：", "").strip()
        if q_part and q_part in _hist_text:
            continue
        # 模糊匹配仅在精确未命中且有历史可比时执行（O(n*m)，q_part 截 200 字符）
        if q_part and _hist_tail and difflib.SequenceMatcher(
                None, q_part[:200], _hist_tail).ratio() >= 0.9:
            continue
        lines.append(f"- {content}")
    if not lines:
        return None
    return ("【相关历史对话】（更早对话中的真实记录；用户询问\"之前说过/聊过\"类问题时，优先以此段为准）\n"
            + "\n".join(lines) + "\n\n")


def _format_profile(memories) -> str | None:
    """把用户画像记忆格式化为 prompt 前缀；空返回 None（不注入）。"""
    if not memories:
        return None
    lines = [f"- {m.get('content', '')}" for m in memories if m.get("content")]
    if not lines:
        return None
    return ("【用户画像】（仅供参考，回答时可结合但不强制）\n"
            + "\n".join(lines) + "\n\n")
