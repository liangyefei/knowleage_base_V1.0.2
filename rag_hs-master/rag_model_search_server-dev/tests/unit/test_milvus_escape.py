"""Milvus filter 表达式转义 helper UT。

_escape_milvus_str：== 比较的字符串字面量转义（反斜杠 + 双引号）
_escape_milvus_like：like 模式的字符串转义（额外转义 % 和 _ 通配符）

转义顺序至关重要：必须先转义反斜杠，再转义其他字符，否则会二次转义。
"""
# pylint: disable=no-use-sys-path-insert
import importlib.abc
import importlib.machinery
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock


class _LangChainShimLoader(importlib.abc.Loader):
    def create_module(self, spec):
        return types.ModuleType(spec.name)

    def exec_module(self, module):
        def __getattr__(attr):
            return type(attr, (), {"__init__": lambda self, **kw: self.__dict__.update(kw)})
        module.__getattr__ = __getattr__


class _LangChainShimFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path, target=None):
        if not fullname.startswith("langchain."):
            return None
        if fullname.startswith(("langchain_core", "langchain_community", "langchain_text_splitters")):
            return None
        if fullname in sys.modules:
            return None
        return importlib.machinery.ModuleSpec(fullname, _LangChainShimLoader())


sys.meta_path.insert(0, _LangChainShimFinder())

# Milvus 连接 shim：milvus_retrieval.py 模块级 MilvusClient eagerly 连接，
# dev 容器无 Milvus 会 import 崩。替换成返回 MagicMock 的工厂，避免真连。
import pymilvus


def _mock_milvus_client(*a, **kw):
    return MagicMock()


pymilvus.MilvusClient = _mock_milvus_client


from server.chat.pipeline.milvus_retrieval import _escape_milvus_like, _escape_milvus_str

# ---------- _escape_milvus_str ----------

def test_str_plain_unchanged():
    """普通字符串无特殊字符，原样返回。"""
    assert _escape_milvus_str("v1.0") == "v1.0"
    assert _escape_milvus_str("rev-2024-01") == "rev-2024-01"


def test_str_double_quote_escaped():
    """双引号转义为 \"。"""
    assert _escape_milvus_str('a"b') == 'a\\"b'
    assert _escape_milvus_str('"') == '\\"'


def test_str_backslash_escaped():
    """反斜杠转义为 \\（先于双引号转义，避免二次转义）。"""
    assert _escape_milvus_str("a\\b") == "a\\\\b"
    assert _escape_milvus_str("\\") == "\\\\"


def test_str_backslash_before_quote_no_double_escape():
    """关键：输入 a\" （反斜杠+双引号）应输出 a\\\\\\"（反斜杠转义 + 双引号转义），
    而非 a\\\\"（如果先转义双引号，反斜杠会把转义后的 \\" 的反斜杠再转义）。
    验证转义顺序：先反斜杠后双引号。
    """
    # 输入：a + \ + " （3 字符）
    # 正确：先转义 \ → \\，再转义 " → \"  → 结果 a\\" （a + \\ + \" = 4 字符）
    # 错误：先转义 " → \"，再转义 \ → \\ → 会把 \" 的 \ 也转义 → a\\\" （5 字符，错误）
    assert _escape_milvus_str('a\\"') == 'a\\\\\\"'


def test_str_non_string_coerced():
    """非字符串自动转 str。"""
    assert _escape_milvus_str(123) == "123"
    assert _escape_milvus_str(None) == "None"
    assert _escape_milvus_str(True) == "True"


def test_str_empty_string():
    """空字符串原样返回。"""
    assert _escape_milvus_str("") == ""


# ---------- _escape_milvus_like ----------

def test_like_plain_unchanged():
    """普通字符串无特殊字符，原样返回。"""
    assert _escape_milvus_like("src/main.py") == "src/main.py"
    assert _escape_milvus_like("path/to/file.c") == "path/to/file.c"


def test_like_percent_escaped():
    """% 通配符转义为 \\%。"""
    assert _escape_milvus_like("100%") == "100\\%"
    assert _escape_milvus_like("%") == "\\%"


def test_like_underscore_escaped():
    """_ 通配符转义为 \\_。

    rel 路径常含 _（文件名/目录名），未转义会被 like 当通配符误匹配多个 source。
    """
    assert _escape_milvus_like("my_file.c") == "my\\_file.c"
    assert _escape_milvus_like("_") == "\\_"


def test_like_backslash_and_quote_escaped():
    """like 模式也转义反斜杠和双引号（继承 _escape_milvus_str）。"""
    assert _escape_milvus_like("a\\b") == "a\\\\b"
    assert _escape_milvus_like('a"b') == 'a\\"b'


def test_like_all_special_chars_combined():
    """组合：反斜杠 + 双引号 + % + _ 全转义。

    用 chr() 构造避免 Python 字符串转义混淆。
    输入 4 字符 → 输出 8 字符（每个特殊符变 2 字符）。
    """
    # 输入：\(92) "(34) %(37) _(95)
    inp = chr(92) + chr(34) + chr(37) + chr(95)
    # 期望：\(92) \(92) \(92) "(34) \(92) %(37) \(92) _(95)
    expected = chr(92) + chr(92) + chr(92) + chr(34) + chr(92) + chr(37) + chr(92) + chr(95)
    assert _escape_milvus_like(inp) == expected
    assert len(_escape_milvus_like(inp)) == 8  # 4 字符 → 8 字符（每个翻倍）


def test_like_path_with_underscore():
    """真实场景：代码路径含下划线，转义后 like 精确匹配字面 _。"""
    rel = "src/dual_mcp/handler.c"
    escaped = _escape_milvus_like(rel)
    assert "\\_" in escaped  # _ 被转义
    assert "%" not in escaped.replace("\\%", "")  # 无未转义 %
    # 拼进 like 表达式
    filter_expr = f'source like "%{escaped}"'
    assert filter_expr == 'source like "%src/dual\\_mcp/handler.c"'


# ---------- 注入防御验证 ----------

def _count_unescaped_dquotes(s: str) -> int:
    """数未转义双引号数量（不被奇数个反斜杠前导的 ")。

    用于验证 filter 表达式注入防御：恶意输入含双引号时，转义后所有内嵌双引号
    都被 \" 化，只剩字符串边界的 2 个未转义双引号。
    """
    n = 0
    i = 0
    while i < len(s):
        if s[i] == '"':
            j = i - 1
            bs = 0
            while j >= 0 and s[j] == '\\':
                bs += 1
                j -= 1
            if bs % 2 == 0:
                n += 1
        i += 1
    return n


def test_filter_version_eq_injection_blocked():
    """version 含双引号，转义后 filter 字符串边界不被破坏。

    恶意 version = a" or 1==1 or "b：转义后双引号变 \"，Milvus 会把它当成
    完整的 version 字面值 "a\" or 1==1 or \"b"（一个字符串），or 1==1 是字符串
    内容而非表达式操作符——注入失败。验证：esc 本身无未转义双引号；
    拼进 filter 后 value 部分只有首尾 2 个边界双引号。
    """
    version = 'a" or 1==1 or "b'
    esc = _escape_milvus_str(version)
    assert _count_unescaped_dquotes(esc) == 0  # esc 本身无未转义双引号
    filter_expr = f'metadata["file_version_label"] == "{esc}"'
    value_part = filter_expr.split(' == ', 1)[1]  # '"a\" or 1==1 or \"b"'
    assert _count_unescaped_dquotes(value_part) == 2  # 只有首尾边界


def test_filter_tags_injection_blocked():
    """tags 含双引号和表达式片段，json_contains 不会逃逸。"""
    tag = 'evil"); metadata[.x == 1 or json_contains(metadata["tags", "'
    esc = _escape_milvus_str(tag)
    assert _count_unescaped_dquotes(esc) == 0  # esc 本身无未转义双引号
    clause = f'json_contains(metadata["tags"], "{esc}")'
    # 拼进 clause 后，esc 部分仍无未转义双引号（只有首尾边界 2 个）
    # 用 ", " 定位 tag 值起止（json_contains 的第 2 参数）
    prefix = 'json_contains(metadata["tags"], "'
    value_part = clause[len(prefix):]  # '{esc}")' 含尾边界 + )
    inner = value_part[:-2]  # 去掉尾 '")' 的边界双引号和右括号
    assert _count_unescaped_dquotes(inner) == 0  # esc 部分无未转义双引号
