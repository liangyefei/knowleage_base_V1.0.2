"""用户记忆抽取：每轮问答后后台抽取习惯/爱好/角色类事实写入 MemMachine。

设计约束：
- fire-and-forget 主 loop task（调用方均在 async chat handler 里，
  create_task 调度，无 daemon 线程无跨 loop 问题，共享 httpx.AsyncClient
  可复用），任何失败只告警，绝不影响聊天主流程与延迟。
- LLM 每次调用新建独立 AsyncOpenAI 客户端（30s 超时，用完即关），
  fail-open 模式照抄 _route_and_rewrite。
- 抽取门槛：confidence≥0.7、每轮≤3 条、prompt 限定"稳定特征"；
  写入前按 fact 检索查重，相似命中则跳过。
"""
import asyncio
import json
import re

from jinja2 import Template

from configs import logger
from configs.server_loader import MEMMACHINE_EXTRACT_ENABLED
from server.aliyun_trace import extract_aliyun_request_id, log_dashscope
from server.utils import get_prompt_template

_MIN_CONFIDENCE = 0.7
_MAX_FACTS_PER_TURN = 3
_DEDUP_TOP_K = 3


def _parse_facts(content: str) -> list[dict]:
    """从 LLM 输出解析 <json>[...]</json>（或裸 JSON 数组）。"""
    m = re.search(r"<json>\s*(.*?)\s*</json>", content, re.DOTALL)
    raw = m.group(1) if m else content.strip()
    try:
        data = json.loads(raw)
    except Exception:
        return []
    if not isinstance(data, list):
        return []
    out = []
    for it in data[:_MAX_FACTS_PER_TURN]:
        if not isinstance(it, dict):
            continue
        fact = str(it.get("fact", "")).strip()
        if not fact or len(fact) > 200:
            continue
        try:
            conf = float(it.get("confidence", 0))
        except (TypeError, ValueError):
            conf = 0.0
        if conf < _MIN_CONFIDENCE:
            continue
        out.append({
            "fact": fact,
            "topic": str(it.get("topic", "")).strip()[:50],
            "confidence": conf,
        })
    return out


async def _extract_facts(query: str, answer: str, history) -> list[dict]:
    """qwen-flash 抽取用户画像事实；模板缺失/调用失败返回 []。

    每次新建 AsyncOpenAI 客户端用完即关（30s 超时），fail-open 隔离。
    """
    _USER_KEY = 'user'
    _QWEN_FLASH_KEY = 'qwen-flash'
    _ROLE_KEY = 'role'
    _MODEL_ID_KEY = 'model_id'
    system_prompt = get_prompt_template("memory_extraction", "system")
    user_template = get_prompt_template("memory_extraction", _USER_KEY)
    if not system_prompt or not user_template:
        logger.warning("[memory] memory_extraction 模板未配置，跳过抽取")
        return []

    history_text = ""
    for h in (history or [])[-6:]:
        role_label = "用户" if getattr(h, _ROLE_KEY, "") == _USER_KEY else "助手"
        history_text += f"{role_label}: {getattr(h, 'content', '')}\n"

    from openai import AsyncOpenAI

    from server.chat.pipeline.openai_clients import _router_model_cfg
    user_msg = Template(user_template).render(history=history_text or "（无历史）",
                                              query=query, answer=answer[:2000])
    client = AsyncOpenAI(api_key=_router_model_cfg.get("api_key"),
                         base_url=_router_model_cfg.get("api_base_url"))
    try:
        # 用 with_raw_response 拿到 httpx 响应头，从中提取 DashScope x-request-id
        # （openai 1.14.3 普通 create 返回的 ChatCompletion 已被 pydantic 剥离 raw headers）
        raw_resp = await client.chat.completions.with_raw_response.create(
            model=_router_model_cfg.get(_MODEL_ID_KEY, _QWEN_FLASH_KEY),
            messages=[{_ROLE_KEY: "system", "content": system_prompt},
                      {_ROLE_KEY: _USER_KEY, "content": user_msg}],
            temperature=0.1,
            max_tokens=512,
            timeout=30,
        )
        # create 成功返回即提取 req_id（阿里云限流排查用）
        _req_id = extract_aliyun_request_id(raw_resp)
        log_dashscope(_router_model_cfg.get(_MODEL_ID_KEY, _QWEN_FLASH_KEY),
                      "memory_extract_done", _req_id, level="debug")
        resp = raw_resp.parse()
        content = resp.choices[0].message.content or ""
        return _parse_facts(content)
    except Exception as e:
        # 记 req_id 后重抛，保持原 fail-open 冒泡到 _run_async 统一处理的语义
        _req_id = extract_aliyun_request_id(e)
        log_dashscope(_router_model_cfg.get(_MODEL_ID_KEY, _QWEN_FLASH_KEY),
                      "memory_extract_error", _req_id, level="warning", reason=str(e)[:200])
        raise
    finally:
        await client.close()


async def _dedup_skip(mm, user_id: str, fact: str) -> bool:
    """写入前查重：本地索引（可靠）+ MemMachine 语义检索（双保险）。"""
    try:
        from server.memory.memmachine_client import _index_search
        if _index_search(user_id, fact):
            return True
        for h in await mm.search_user_memories(user_id, fact, top_k=_DEDUP_TOP_K):
            existing = h.get("content", "")
            if not existing:
                continue
            if fact in existing or existing in fact:
                return True
            import difflib
            if difflib.SequenceMatcher(None, fact, existing).ratio() >= 0.8:
                return True
    except Exception:
        return False  # 查重失败不阻塞写入（宁可重复不可丢失）
    return False


async def _run_async(user_id: str, query: str, answer: str, history,
                    conversation_id: str = "") -> None:
    try:
        from server.memory.memmachine_client import get_memmachine, memmachine_enabled
        if not memmachine_enabled():
            return
        mm = get_memmachine()
        # 对话记忆直存：只受总开关控制（不受抽取开关约束——它无 LLM 成本）
        await _store_conversation(mm, user_id, conversation_id, query, answer)
        if not MEMMACHINE_EXTRACT_ENABLED:
            return
        facts = await _extract_facts(query, answer, history)
        for f in facts:
            if await _dedup_skip(mm, user_id, f["fact"]):
                logger.info(f"[memory] 查重跳过: {f['fact'][:50]}")
                continue
            if await mm.add_memory(user_id, f["fact"], topic=f["topic"],
                                   confidence=f["confidence"]):
                logger.info(f"[memory] 写入: {f['fact'][:60]} (conf={f['confidence']})")
    except Exception as e:
        logger.warning(f"[memory] 抽取/写入失败（不影响聊天）: {e}")


async def _store_conversation(mm, user_id: str, conversation_id: str,
                              query: str, answer: str) -> None:
    """直存本轮问答为对话记忆（不限轮次历史的沉淀侧）。"""
    try:
        if await mm.add_conversation_memory(user_id, conversation_id, query, answer):
            logger.info(f"[memory] 对话记忆已存: {query[:40]}")
    except Exception as e:
        logger.warning(f"[memory] 对话记忆存入异常（不影响聊天）: {e}")


def extract_and_store_async(user_id: str, query: str, answer: str,
                            history=None, conversation_id: str = "") -> None:
    """问答完成后触发后台写入（fire-and-forget，主 loop task）。

    调用方均在 async chat handler 里（主 loop 上），create_task 调度——
    无 daemon 线程，无跨 loop 问题，共享 httpx.AsyncClient 可复用。
    两件事：①直存本轮问答为对话记忆（无 LLM，供不限轮次历史召回）；
    ②qwen-flash 抽取用户画像事实（习惯/爱好/角色）。
    """
    if not user_id or not answer:
        return
    asyncio.get_running_loop().create_task(
        _run_async(user_id, query, answer, history, conversation_id))
