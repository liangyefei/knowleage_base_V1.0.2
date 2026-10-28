from typing import Any

from langchain.prompts.chat import ChatMessagePromptTemplate
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    ChatMessage,
    FunctionMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from pydantic import BaseModel, Field


def _convert_message_to_dict(message: BaseMessage) -> dict:
    """Convert a LangChain message to a dictionary.

    Args:
        message: The LangChain message.

    Returns:
        The dictionary.
    """
    _ROLE_KEY = 'role'
    _CONTENT_KEY = 'content'
    _NAME_KEY = 'name'
    _FUNCTION_CALL_KEY = 'function_call'
    _TOOL_CALLS_KEY = 'tool_calls'
    message_dict: dict[str, Any]
    if isinstance(message, ChatMessage):
        message_dict = {_ROLE_KEY: message.role, _CONTENT_KEY: message.content}
    elif isinstance(message, HumanMessage):
        message_dict = {_ROLE_KEY: "user", _CONTENT_KEY: message.content}
    elif isinstance(message, AIMessage):
        message_dict = {_ROLE_KEY: "assistant", _CONTENT_KEY: message.content}
        if _FUNCTION_CALL_KEY in message.additional_kwargs:
            message_dict[_FUNCTION_CALL_KEY] = message.additional_kwargs[_FUNCTION_CALL_KEY]
            # If function call only, content is None not empty string
            if message_dict[_CONTENT_KEY] == "":
                message_dict[_CONTENT_KEY] = None
        if _TOOL_CALLS_KEY in message.additional_kwargs:
            message_dict[_TOOL_CALLS_KEY] = message.additional_kwargs[_TOOL_CALLS_KEY]
            # If tool calls only, content is None not empty string
            if message_dict[_CONTENT_KEY] == "":
                message_dict[_CONTENT_KEY] = None
    elif isinstance(message, SystemMessage):
        message_dict = {_ROLE_KEY: "system", _CONTENT_KEY: message.content}
    elif isinstance(message, FunctionMessage):
        message_dict = {
            _ROLE_KEY: "function",
            _CONTENT_KEY: message.content,
            _NAME_KEY: message.name,
        }
    elif isinstance(message, ToolMessage):
        message_dict = {
            _ROLE_KEY: "tool",
            _CONTENT_KEY: message.content,
            "tool_call_id": message.tool_call_id,
        }
    else:
        raise TypeError(f"Got unknown type {message}")
    if _NAME_KEY in message.additional_kwargs:
        message_dict[_NAME_KEY] = message.additional_kwargs[_NAME_KEY]
    return message_dict


class History(BaseModel):
    """
    对话历史
    可从dict生成，如
    h = History(**{"role":"user","content":"你好"})
    也可转换为tuple，如
    h.to_msy_tuple = ("human", "你好")
    """
    role: str = Field(...)
    content: Any = Field(...)

    @classmethod
    def from_data(cls, h: list | tuple | dict) -> "History":
        if isinstance(h, (list,tuple)) and len(h) >= 2:
            h = cls(role=h[0], content=h[1])
        elif isinstance(h, dict):
            h = cls(**h)

        return h

    @classmethod
    def from_message(cls, message: BaseMessage) -> "History":
        """从 LangChain BaseMessage 构造 History"""
        return cls.from_data(_convert_message_to_dict(message=message))

    def to_msg_tuple(self):
        return "ai" if self.role=="assistant" else "human", self.content

    def to_msg_template(self, is_raw=True) -> ChatMessagePromptTemplate | BaseMessage:
        role_maps = {
            "ai": "assistant",
            "human": "user",
        }
        role = role_maps.get(self.role, self.role)
        if is_raw:  # 当前默认历史消息都是没有input_variable的文本。
            # D2 修复：原方案用 {% raw %} 包裹用户内容，但 content 含 {% endraw %}
            # 可逃逸 raw 块 → SSTI（LangChain 用非沙箱 jinja2.Template）。
            # 历史消息是静态内容无模板变量，改用 BaseMessage 直接构造，完全跳过 Jinja2 解析。
            if role == "assistant":
                return AIMessage(content=self.content)
            if role == "user":
                return HumanMessage(content=self.content)
            return SystemMessage(content=self.content)

        content = self.content

        return ChatMessagePromptTemplate.from_template(
            content,
            "jinja2",
            role=role,
        )


def _extract_answer_from_sse_string(s: str) -> str:
    """从多个拼接的 SSE chunk JSON 字符串中提取所有 answer 字段并拼接。"""
    import json
    parts = []
    decoder = json.JSONDecoder()
    idx = 0
    while idx < len(s):
        # 跳过非 JSON 字符
        brace_idx = s.find('{', idx)
        if brace_idx == -1:
            break
        try:
            obj, end_idx = decoder.raw_decode(s, brace_idx)
            if isinstance(obj, dict):
                answer = obj.get("answer", "")
                if answer:
                    parts.append(answer)
            idx = end_idx
        except json.JSONDecodeError:
            idx = brace_idx + 1
    return "".join(parts)


def history_from_backend(items: list[dict]) -> list[History]:
    """适配后端传入的 history 格式。

    后端 assistant 项的 content 可能是：
    1. dict 对象 {"answer": ..., "type": "answer", ...}
    2. 多个 SSE chunk 拼接的字符串 '{"answer": "..."}{"answer": "..."}...'
    3. 普通字符串
    需统一提取 answer 文本；user 项的 content 直接是字符串。
    """
    result: list[History] = []
    for h in items or []:
        # 兼容 dict 和 History 对象
        if isinstance(h, dict):
            role = h.get("role", "")
            content = h.get("content", "")
        elif hasattr(h, "role") and hasattr(h, "content"):
            role = h.role
            content = h.content
        else:
            continue
        if isinstance(content, dict):
            content = content.get("answer", "") or ""
        elif content is None:
            content = ""
        elif isinstance(content, str) and '"answer"' in content:
            # 多个 SSE chunk 拼接的字符串，提取 answer
            content = _extract_answer_from_sse_string(content)
        elif not isinstance(content, str):
            content = str(content)
        if not content:
            continue
        result.append(History(role=role, content=content))
    return result
