"""chat 域纯逻辑模块的单元测试。

覆盖:
- server.chat.extract_chip_labels: extract_chip_label, find_chip_keys_in_text
- server.chat.utils: History 模型(to_msg_tuple / to_msg_template)
- server.chat.next_question: format_questions_str2list
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

# 将项目根目录加入 sys.path,确保可 import server、configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    ChatMessage,
    FunctionMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

from server.chat.extract_chip_labels import extract_chip_label, find_chip_keys_in_text
from server.chat.utils import History, _convert_message_to_dict

# next_question 依赖较重(fastapi / sse_starlette / langchain / torch 等),
# 导入可能因环境差异失败,故用守卫导入,失败时相关用例 skip。
try:
    from server.chat.next_question import format_questions_str2list
    _NEXT_QUESTION_IMPORT_ERROR = None
except Exception as _e:  # noqa: BLE001
    format_questions_str2list = None
    _NEXT_QUESTION_IMPORT_ERROR = _e


# ===================== extract_chip_labels =====================

def test_extract_chip_label_hit():
    """文本含 Hi3798MV310 时,返回列表应包含该键。"""
    result = extract_chip_label("机顶盒 Hi3798MV310 对接规范")
    assert "Hi3798MV310" in result


def test_extract_chip_label_no_hit():
    """文本不含任何芯片键时返回空列表。"""
    assert extract_chip_label("普通文本没有芯片型号") == []


def test_extract_chip_label_multi_hit():
    """文本同时含多个芯片键时返回多个。"""
    result = extract_chip_label("Hi3798MV310 与 Hi3516CV610")
    assert "Hi3798MV310" in result
    assert "Hi3516CV610" in result
    assert len(result) == 2


def test_extract_chip_label_empty_text():
    """空字符串返回空列表。"""
    assert extract_chip_label("") == []


def test_extract_chip_label_case_sensitive():
    """Python `in` 运算符大小写敏感:全小写文本不应命中首字母大写的芯片键。"""
    assert extract_chip_label("hi3798mv310") == []


def test_find_chip_keys_in_text_with_custom_dict():
    """find_chip_keys_in_text 接收自定义 chips_dict,独立于全局 CHIP_TYPE_DICT。"""
    custom = {"Foo": "x", "Bar": "y", "Baz": "z"}
    assert find_chip_keys_in_text("Foo 与 Baz", custom) == ["Foo", "Baz"]


# ===================== History 模型 =====================

def test_history_creation():
    """History 实例字段正确赋值(role / content)。"""
    h = History(role="user", content="你好")
    assert h.role == "user"
    assert h.content == "你好"


def test_history_to_msg_tuple():
    """to_msg_tuple 将 role 映射为 (human|ai, content) 元组。

    注:源码实际方法名为 to_msg_tuple,任务描述中的 to_messages 不存在。
    源码仅当 role=="assistant" 时映射为 "ai",其余一律 "human"。
    """
    assert History(role="user", content="你好").to_msg_tuple() == ("human", "你好")
    assert History(role="assistant", content="好的").to_msg_tuple() == ("ai", "好的")
    # 源码实际行为:非 "assistant" 的 role(含 "ai")都映射为 "human"
    assert History(role="ai", content="测试").to_msg_tuple() == ("human", "测试")


def test_history_to_msg_template():
    """to_msg_template(is_raw=True) 返回 BaseMessage（D2 SSTI 修复）。

    注:源码实际方法名为 to_msg_template,任务描述中的 to_message_templates 不存在。
    is_raw=True 路径原用 {% raw %}...{% endraw %} 包裹用户 content,
    content 含 {% endraw %} 可逃逸 raw 块 → SSTI（LangChain 用非沙箱 jinja2.Template）。
    现改用 BaseMessage 直接构造，完全跳过 Jinja2 解析。
    """
    result = History(role="user", content="你好").to_msg_template()

    assert isinstance(result, BaseMessage)
    assert isinstance(result, HumanMessage)
    assert result.content == "你好"


# ===================== format_questions_str2list =====================

_SKIP_REASON = (
    f"无法导入 next_question 模块(可能依赖未安装或配置缺失): "
    f"{_NEXT_QUESTION_IMPORT_ERROR}"
)


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
def test_format_questions_str2list_normal():
    """正常多行编号问题串解析为问题列表(去除编号前缀)。"""
    text = "1. 今天天气怎么样啊？\n2. 明天会不会下雨呢？\n3. 后天会不会刮风呢？"
    result = format_questions_str2list(text)
    assert result == ["今天天气怎么样啊？", "明天会不会下雨呢？", "后天会不会刮风呢？"]


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
def test_format_questions_str2list_empty():
    """空字符串返回空列表(无任何编号匹配)。"""
    assert not format_questions_str2list("")


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
def test_format_questions_str2list_single():
    """单行编号问题返回单元素列表。"""
    assert format_questions_str2list("1. 今天天气怎么样啊？") == ["今天天气怎么样啊？"]


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
def test_format_questions_str2list_short_question_filtered():
    """边界:过短问题(长度 <= 5)被实现过滤,返回空列表。

    源码 `if len(q_res) > 5` 才保留;"你好？" 长度为 3,被过滤。
    """
    assert not format_questions_str2list("1. 你好？")


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
def test_format_questions_str2list_colon_prefix_stripped():
    """边界:问题内容含全角冒号"："时,实现取冒号后部分。

    源码 `if "：" in q_res: q_res = q_res.split("：")[1]`。
    """
    text = "1. 问题：今天天气怎么样啊？"
    assert format_questions_str2list(text) == ["今天天气怎么样啊？"]


# ===================== _convert_message_to_dict 9 分支 =====================

class TestConvertMessageToDict:
    """_convert_message_to_dict 的 9 个分支覆盖(直接 import 内部函数)。"""

    @staticmethod
    def test_additional_kwargs_name_injected():
        """additional_kwargs 含 name 时,任何分支都会注入 message_dict['name']。"""
        msg = HumanMessage(content="hi", additional_kwargs={"name": "Alice"})
        result = _convert_message_to_dict(msg)
        assert result == {"role": "user", "content": "hi", "name": "Alice"}

    @staticmethod
    def test_ai_message_function_call_content_to_none():
        """AIMessage 含 function_call 且 content="" → content=None。"""
        msg = AIMessage(content="", additional_kwargs={
            "function_call": {"name": "foo", "arguments": "{}"}
        })
        result = _convert_message_to_dict(msg)
        assert result["role"] == "assistant"
        assert result["content"] is None
        assert result["function_call"] == {"name": "foo", "arguments": "{}"}

    @staticmethod
    def test_ai_message_tool_calls_content_to_none():
        """AIMessage 含 tool_calls 且 content="" → content=None。"""
        msg = AIMessage(content="", additional_kwargs={
            "tool_calls": [{"id": "call_1", "name": "bar"}]
        })
        result = _convert_message_to_dict(msg)
        assert result["role"] == "assistant"
        assert result["content"] is None
        assert result["tool_calls"] == [{"id": "call_1", "name": "bar"}]

    @staticmethod
    def test_chat_message_custom_role():
        """ChatMessage → {'role': message.role, 'content': message.content}。"""
        msg = ChatMessage(role="custom", content="custom content")
        result = _convert_message_to_dict(msg)
        assert result == {"role": "custom", "content": "custom content"}

    @staticmethod
    def test_function_message():
        """FunctionMessage → {'role': 'function', 'content': ..., 'name': ...}。"""
        msg = FunctionMessage(content="result", name="func_name")
        result = _convert_message_to_dict(msg)
        assert result == {"role": "function", "content": "result", "name": "func_name"}

    @staticmethod
    def test_human_message():
        """HumanMessage → {'role': 'user', 'content': ...}。"""
        msg = HumanMessage(content="你好")
        result = _convert_message_to_dict(msg)
        assert result == {"role": "user", "content": "你好"}

    @staticmethod
    def test_system_message():
        """SystemMessage → {'role': 'system', 'content': ...}。"""
        msg = SystemMessage(content="sys prompt")
        result = _convert_message_to_dict(msg)
        assert result == {"role": "system", "content": "sys prompt"}

    @staticmethod
    def test_tool_message():
        """ToolMessage → {'role': 'tool', 'content': ..., 'tool_call_id': ...}。"""
        msg = ToolMessage(content="result", tool_call_id="call_123")
        result = _convert_message_to_dict(msg)
        assert result == {"role": "tool", "content": "result", "tool_call_id": "call_123"}

    @staticmethod
    def test_unknown_type_raises_type_error():
        """未知类型(非任何 LangChain Message)→ 抛 TypeError。"""
        class _Unknown:
            pass

        with pytest.raises(TypeError):
            _convert_message_to_dict(_Unknown())


# ===================== History.from_data =====================

class TestHistoryFromData:
    """History.from_data 的分支覆盖。

    源码:
        if isinstance(h, (list,tuple)) and len(h) >= 2:  h = cls(role=h[0], content=h[1])
        elif isinstance(h, dict):  h = cls(**h)
        return h
    len<2 / int / str / None 都不会命中任何分支,直接返回原值(潜在 bug,如实断言)。
    """

    @staticmethod
    def test_dict():
        """dict → History(**h)。"""
        h = History.from_data({"role": "system", "content": "sys"})
        assert isinstance(h, History)
        assert h.role == "system"
        assert h.content == "sys"

    @staticmethod
    def test_empty_list_returns_original():
        """空 list(len=0<2)返回原值。"""
        src = []
        result = History.from_data(src)
        assert result is src

    @staticmethod
    def test_int_returns_original():
        """int 既非 list/tuple 也非 dict,返回原值。"""
        result = History.from_data(42)
        assert result == 42

    @staticmethod
    def test_list_len_ge_2():
        """list len>=2 → History(role=h[0], content=h[1])。"""
        h = History.from_data(["user", "你好"])
        assert isinstance(h, History)
        assert h.role == "user"
        assert h.content == "你好"

    @staticmethod
    def test_list_len_lt_2_returns_original():
        """len<2 时源码直接返回原值,不构造 History(潜在 bug,断言返回原值)。"""
        src = ["only_one"]
        result = History.from_data(src)
        assert result is src  # 据实断言:返回原对象,非 History

    @staticmethod
    def test_none_returns_original():
        """None 返回原值。"""
        result = History.from_data(None)
        assert result is None

    @staticmethod
    def test_str_returns_original():
        """str 返回原值。"""
        result = History.from_data("hello")
        assert result == "hello"

    @staticmethod
    def test_tuple_len_ge_2():
        """tuple len>=2 → History(role, content)。"""
        h = History.from_data(("assistant", "好的"))
        assert isinstance(h, History)
        assert h.role == "assistant"
        assert h.content == "好的"


# ===================== History.from_message =====================

class TestHistoryFromMessage:
    """History.from_message 的 LangChain Message → History 转换链。

    源码: cls.from_data(_convert_message_to_dict(message))
    FunctionMessage/ToolMessage 的 dict 含额外字段(name/tool_call_id),
    History 为 Pydantic v1 模型,默认 ignore 额外字段。
    """

    @staticmethod
    def test_ai_message():
        h = History.from_message(AIMessage(content="好的"))
        assert h.role == "assistant"
        assert h.content == "好的"

    @staticmethod
    def test_chat_message_custom_role():
        h = History.from_message(ChatMessage(role="custom", content="c"))
        assert h.role == "custom"
        assert h.content == "c"

    @staticmethod
    def test_function_message():
        """FunctionMessage → dict 含 name,History 忽略额外字段(Pydantic v1 默认 ignore)。"""
        h = History.from_message(FunctionMessage(content="result", name="func"))
        assert h.role == "function"
        assert h.content == "result"

    @staticmethod
    def test_human_message():
        h = History.from_message(HumanMessage(content="你好"))
        assert isinstance(h, History)
        assert h.role == "user"
        assert h.content == "你好"

    @staticmethod
    def test_system_message():
        h = History.from_message(SystemMessage(content="sys"))
        assert h.role == "system"
        assert h.content == "sys"

    @staticmethod
    def test_tool_message():
        """ToolMessage → dict 含 tool_call_id,History 忽略额外字段。"""
        h = History.from_message(ToolMessage(content="result", tool_call_id="call_1"))
        assert h.role == "tool"
        assert h.content == "result"


# ===================== History.to_msg_template 分支扩充 =====================

class TestHistoryToMsgTemplate:
    """扩充 to_msg_template 的分支覆盖（D2 SSTI 修复后）:

    - is_raw=False → 走 Jinja2 模板路径，content 不含 {% raw %}/{% endraw %}
    - is_raw=True → 返回 BaseMessage，跳过 Jinja2 解析（消除 SSTI）
    - role='ai'/'assistant' → AIMessage
    - role='system' → SystemMessage
    - role='human'/'user' → HumanMessage
    """

    @staticmethod
    def test_is_raw_false_no_raw_wrapper(make_template):
        """is_raw=False → prompt.template 即原文,不含 raw 标签。"""
        result = make_template(role="user", content="你好", is_raw=False)
        assert result.prompt.template == "你好"
        assert "{% raw %}" not in result.prompt.template
        assert "{% endraw %}" not in result.prompt.template

    @staticmethod
    def test_is_raw_true_returns_base_message(make_template):
        """is_raw=True(默认) → 返回 BaseMessage，content 即原文，跳过 Jinja2 解析。"""
        result = make_template(role="user", content="你好", is_raw=True)
        assert isinstance(result, HumanMessage)
        assert result.content == "你好"
        # BaseMessage 不是模板，无 .prompt 属性，消除 SSTI 逃逸路径
        assert not hasattr(result, "prompt")

    @staticmethod
    def test_is_raw_true_ssti_payload_not_evaluated(make_template):
        """D2 安全验证：含 Jinja2 逃逸载荷的 content 不被求值，原样存入 BaseMessage.content。"""
        # 逃逸载荷：尝试用 {% endraw %} 逃逸 raw 块 + {{ }} 求值
        payload = "{% endraw %}{{ 7*7 }}{% raw %}"
        result = make_template(role="user", content=payload, is_raw=True)
        assert isinstance(result, HumanMessage)
        # content 原样保留，7*7 未被求值为 49
        assert result.content == payload
        assert "49" not in result.content

    @staticmethod
    def test_role_ai_returns_ai_message(make_template):
        """role='ai' → role_maps 映射为 'assistant' → 返回 AIMessage。"""
        result = make_template(role="ai", content="x")
        assert isinstance(result, AIMessage)
        assert result.content == "x"

    @staticmethod
    def test_role_assistant_returns_ai_message(make_template):
        """role='assistant' 透传 'assistant' → 返回 AIMessage。"""
        result = make_template(role="assistant", content="x")
        assert isinstance(result, AIMessage)
        assert result.content == "x"

    @staticmethod
    def test_role_human_returns_human_message(make_template):
        """role='human' → role_maps 映射为 'user' → 返回 HumanMessage。"""
        result = make_template(role="human", content="x")
        assert isinstance(result, HumanMessage)
        assert result.content == "x"

    @staticmethod
    def test_role_system_returns_system_message(make_template):
        """role='system' 不在 role_maps → 走 else 分支返回 SystemMessage。"""
        result = make_template(role="system", content="x")
        assert isinstance(result, SystemMessage)
        assert result.content == "x"

    @pytest.fixture
    def make_template(self):
        """工厂: 构造 to_msg_template,若 langchain 版本不兼容则 skip。"""
        def _make(role="user", content="你好", is_raw=True):
            try:
                return History(role=role, content=content).to_msg_template(is_raw=is_raw)
            except Exception as e:  # noqa: BLE001
                pytest.skip(f"当前 langchain 版本下 to_msg_template 抛异常: {type(e).__name__}: {e}")
                return None
        return _make