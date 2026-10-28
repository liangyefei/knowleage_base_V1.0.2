"""规则快速路由闸门：问候/技术/闲聊关键词命中即跳过 LLM route。"""
from server.chat.pipeline.constants import _CHITCHAT_KEYWORDS, _GREETING_WORDS, _TECH_KEYWORDS


def _is_greeting(query: str) -> bool:
    """问候语闸门：query 较短且命中问候词表 → True，跳过检索直走 LLM。

    放宽长度限制至 ≤10 字符（原 ≤4），保持完全匹配避免子串误判
    （如 "ok" 误匹配 "token"、"hi" 误匹配 "this"）。
    """
    if not query:
        return False
    q = query.strip()
    if not q:
        return False
    if len(q) > 10:
        return False
    return q.lower() in _GREETING_WORDS


def _has_tech_keyword(query: str) -> bool:
    """技术关键词闸门：query 含技术术语 → True，直接走 RAG 跳过 route LLM。

    用大小写不敏感的包含匹配。误判风险可控：即使非技术问题误判走 RAG，
    检索结果为空时 LLM 仍可正常回答。
    """
    if not query:
        return False
    q_upper = query.upper()
    return any(k.upper() in q_upper for k in _TECH_KEYWORDS)


def _has_chitchat_keyword(query: str) -> bool:
    """闲聊关键词闸门：query 含明显闲聊意图 → True，跳过 route LLM 直走闲聊。

    用包含匹配（闲聊关键词均为多字短语，子串误判风险低）。
    """
    if not query:
        return False
    return any(k in query for k in _CHITCHAT_KEYWORDS)
