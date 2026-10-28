"""Milvus filter 表达式转义工具。

被三层复用，避免跨层依赖（kb_doc_api / milvus_kb_service / milvus_retrieval）：
- milvus_retrieval.py（RAG 主检索路，chat/pipeline 层）import 并 re-export
- milvus_kb_service.py（KBService 路，kb_service 层）直接 import
- kb_doc_api.py（文档管理 API 路，knowledge_base 层）直接 import

Milvus filter 表达式字符串用双引号包裹，反斜杠是转义前缀，双引号是边界。
like 模式里 % 和 _ 是通配符。未转义会导致 filter 语法错误（400 崩溃）或
表达式逃逸（数据异常/注入）。
"""


def _escape_milvus_str(s) -> str:
    """转义 Milvus filter 表达式字符串字面量（== 比较）里的反斜杠和双引号。

    转义顺序：先反斜杠后双引号，避免二次转义把转义符自身再次转义。
    """
    return str(s).replace("\\", "\\\\").replace('"', '\\"')


def _escape_milvus_like(s) -> str:
    """转义 Milvus like 模式字符串里的特殊字符（用于字面后缀匹配）。

    在 _escape_milvus_str 基础上额外转义 like 通配符 % 和 _，
    用于 source like "%xxx" 的精确后缀匹配场景——rel 路径常含 _（文件名/目录），
    未转义会被当成通配符误匹配多个 source。
    """
    return _escape_milvus_str(s).replace("%", "\\%").replace("_", "\\_")
