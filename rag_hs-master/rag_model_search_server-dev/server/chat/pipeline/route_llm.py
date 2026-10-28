"""路由 LLM：意图判定 + 问题改写 + 多query分解，流式输出思考过程。"""
import asyncio
import json
import re

from jinja2 import Template

from configs import logger
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope
from server.chat.utils import History
from server.utils import get_prompt_template

# Jinja2 模板编译缓存：route_chat.user 模板字符串热加载后内容不变时复用已编译的 Template 对象，
# 避免每请求重新 parse/compile 模板（Jinja2 编译开销在 0.1ms 级，高频路由下累积可观）。
# 以模板字符串本身为 key——yaml 热加载新字符串会 miss 旧缓存、重新编译并加入缓存。
_route_template_cache: dict = {}


def _get_route_template(template_str: str) -> Template:
    """编译并缓存 Jinja2 模板，避免每请求重新解析。"""
    tpl = _route_template_cache.get(template_str)
    if tpl is None:
        tpl = Template(template_str)
        _route_template_cache[template_str] = tpl
    return tpl


def _parse_route_decision(content: str) -> dict:
    """从 LLM 输出中解析 <json>...</json> 块。"""
    match = re.search(r'<json>\s*(.*?)\s*</json>', content, re.DOTALL)
    if not match:
        try:
            return json.loads(content.strip())
        except Exception:
            logger.warning(f"[route] 无法解析路由 JSON，fail-open 走 RAG。raw={content[:200]}")
            return {"need_rag": True}
    try:
        return json.loads(match.group(1))
    except Exception as e:
        logger.warning(f"[route] JSON 解析失败，fail-open 走 RAG: {e}。raw={match.group(1)[:200]}")
        return {"need_rag": True}


async def _route_and_rewrite(
    query: str,
    history: list[History],
    openai_client,
    model_name: str,
    on_reasoning=None,
) -> dict:
    """调用 LLM 完成意图判定 + 问题改写 + 多query分解，流式输出思考过程。

    Args:
        on_reasoning: 可选回调，每收到一段思考文本时调用 on_reasoning(text)
    
    Returns:
        dict: {"need_rag": bool, "rewritten_query": str|None, "sub_queries": list|None, ...}
    """
    _USER_KEY = 'user'
    # 构建 history 文本（取最近 3 轮 = 6 条消息）
    history_text = ""
    for h in (history or [])[-6:]:
        role_label = "用户" if h.role == _USER_KEY else "助手"
        history_text += f"{role_label}: {h.content}\n"
    if not history_text:
        history_text = "（无历史）"

    # 从 prompt_templates.yaml 加载路由 prompt（支持热加载）
    system_prompt = get_prompt_template("route_chat", "system")
    user_template = get_prompt_template("route_chat", _USER_KEY)
    if not system_prompt or not user_template:
        raise ValueError("路由 prompt 模板未配置，请检查 prompt_templates.yaml 中 route_chat.system / route_chat.user")

    # yaml 模板用 Jinja2 {{ }} 语法，用 Jinja2 Template 渲染（与现有 prompt 渲染方式一致）
    user_msg = _get_route_template(user_template).render(history=history_text, query=query)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": _USER_KEY, "content": user_msg},
    ]

    full_content = ""
    resp = None
    _req_id = None  # DashScope requestID，create 返回后立即填充（排队式限流排查关键）
    try:
        resp = await openai_client.chat.completions.create(
            model=model_name,
            messages=messages,
            temperature=0.1,
            max_tokens=1024,
            stream=True,
            timeout=10,
        )
        # create 返回即提取 req_id：排队式限流时 chunk 永不来但响应头已到，此时
        # req_id 可取；后续 chunk 超时/取消日志据此向阿里云客服提供 requestID 排查
        _req_id = extract_aliyun_request_id(resp)
        log_dashscope(model_name, "route_stream_start", _req_id, level="debug")

        # 状态机：分离思考文本和 <json> 块，只把思考部分通过回调推送
        # 用缓冲区处理 <json> 标签跨 chunk 的情况
        _in_json = False
        _buf = ""  # 待推送的文本缓冲（可能包含未完整的 <json> 标签前缀）
        _JSON_OPEN = "<json>"

        async for chunk in resp:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if hasattr(delta, _CONTENT_KEY) and delta.content:
                full_content += delta.content

                if _in_json:
                    # 已在 JSON 区域，只累积不推送
                    continue

                # 把新内容加入缓冲
                _buf += delta.content

                # 检查缓冲中是否出现了 <json>
                _pos = _buf.find(_JSON_OPEN)
                if _pos != -1:
                    # 找到 <json>，推送它之前的思考文本
                    _think = _buf[:_pos]
                    if _think and on_reasoning:
                        on_reasoning(_think)
                    _in_json = True
                    _buf = ""
                    continue

                # 没找到 <json>，但缓冲末尾可能包含 <json> 的不完整前缀
                # 保留可能的前缀，推送安全部分
                _safe_len = len(_buf)
                for _i in range(min(len(_JSON_OPEN) - 1, len(_buf)), 0, -1):
                    if _buf.endswith(_JSON_OPEN[:_i]):
                        _safe_len = len(_buf) - _i
                        break
                if _safe_len > 0 and on_reasoning:
                    on_reasoning(_buf[:_safe_len])
                    _buf = _buf[_safe_len:]

        # 流结束后，缓冲里可能还有残留的思考文本（没有 <json> 的情况）
        if not _in_json and _buf and on_reasoning:
            on_reasoning(_buf)

        # 从完整输出中解析 <json>...</json>
        return _parse_route_decision(full_content)

    except asyncio.CancelledError:
        # wait_for=15s 超时（外层 _run_route）或 B4 cancel。resp 若已建立则 req_id 已取到，
        # 这正是排队式限流的现场证据（连接已建、chunk 不来）；记录后重抛让 wait_for 转 TimeoutError
        log_dashscope(model_name, "route_timeout", _req_id, level="error",
                      reason="wait_for timeout or cancelled")
        raise
    except Exception as e:
        # 异常对象（如 APIStatusError）可能带 request_id，优先从异常取，回退到 create 时取到的
        _req_id = extract_aliyun_request_id(e) or _req_id
        logger.warning(f"[route] 融合调用失败，fail-open 走 RAG: {e}{fmt_req_id(_req_id)}")
        log_dashscope(model_name, "route_error", _req_id, level="warning", reason=str(e)[:200])
        return {"need_rag": True, "rewritten_query": None, "sub_queries": None,
                "route_type": "knowledge_qa", "reason": f"路由失败，fail-open: {e}"}
    finally:
        # B5：统一关闭流释放底层连接——正常结束 / 异常 / CancelledError（wait_for 超时 / B4 cancel）
        # CancelledError 是 BaseException 不被 except Exception 捕获，但触发 finally 执行
        if resp is not None:
            try:
                await resp.aclose()
            except Exception as _e:
                logger.debug(f"[route] stream aclose failed: {_e}")
