"""format_questions_str2list 补充分支单元测试。

覆盖 server.chat.next_question.format_questions_str2list 的额外分支(与
test_chat_utils.py 中已有用例互补,不重复覆盖):
- 引号 strip(strip("\"")): 问题被双引号包裹时去除引号
- 多冒号只取第一段(split("：")[1]): 多个全角冒号时取第二段(index 1)
- 非编号文本(无 \\d. 前缀)返回空
- 长度=6 边界(>5 保留) 与 长度=5 边界(not >5 过滤)
- 中英文问号混合(? 与 ？)都能匹配

源码:
    pattern = r'(\\d+\\.\\s*[^(\\？|\\?)]+[\\？|\\?])'
    res_list = re.findall(pattern, answer_str)
    for que_str in res_list:
        sub_pattern = r'([^\\.]*[\\？|\\?])'
        q_res = re.search(sub_pattern, que_str).group().strip().strip("\\"")
        if "：" in q_res:
            q_res = q_res.split("：")[1]
        if len(q_res) > 5:
            questions_str_list.append(q_res)
    return questions_str_list
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

# 将项目根目录加入 sys.path,确保可 import server、configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

# next_question 依赖较重(fastapi / sse_starlette / langchain 等),
# 导入可能因环境差异失败,故用守卫导入,失败时相关用例 skip。
try:
    from server.chat.next_question import format_questions_str2list
    _NEXT_QUESTION_IMPORT_ERROR = None
except Exception as _e:  # noqa: BLE001
    format_questions_str2list = None
    _NEXT_QUESTION_IMPORT_ERROR = _e


_SKIP_REASON = (
    f"无法导入 next_question 模块(可能依赖未安装或配置缺失): "
    f"{_NEXT_QUESTION_IMPORT_ERROR}"
)


@pytest.mark.skipif(format_questions_str2list is None, reason=_SKIP_REASON)
class TestFormatQuestionsStr2ListBranches:
    """format_questions_str2list 的补充分支覆盖(与 test_chat_utils.py 互补)。"""

    @staticmethod
    def test_quote_stripped():
        """引号 strip: 问题被双引号包裹时,strip("\\"") 去除引号。"""
        text = '1. "今天天气怎么样啊？"'
        assert format_questions_str2list(text) == ["今天天气怎么样啊？"]

    @staticmethod
    def test_multiple_colons_takes_second_segment():
        """多冒号: split("：")[1] 取第二段(首尾冒号间的片段)。

        源码 `q_res.split("：")[1]` 取 index 1(第二段),多个冒号时只取
        第一个冒号后、第二个冒号前的片段。
        """
        text = "1. 问题：今天天气怎么样啊：好吗？"
        assert format_questions_str2list(text) == ["今天天气怎么样啊"]

    @staticmethod
    def test_non_numbered_text_returns_empty():
        """非编号文本(无 \\d. 前缀)不匹配 pattern,返回空列表。"""
        text = "今天天气怎么样？明天会不会下雨？"
        assert not format_questions_str2list(text)

    @staticmethod
    def test_length_six_kept():
        """长度=6 (>5) 保留。

        q_res = "今天天气好？" 长度 6(含问号),>5 通过。
        """
        text = "1. 今天天气好？"
        result = format_questions_str2list(text)
        assert result == ["今天天气好？"]
        assert len(result[0]) == 6

    @staticmethod
    def test_length_five_filtered():
        """长度=5 (not >5) 过滤。

        q_res = "今天天气？" 长度 5(含问号),源码 `if len(q_res) > 5` 不满足,过滤。
        """
        text = "1. 今天天气？"
        assert not format_questions_str2list(text)

    @staticmethod
    def test_mixed_question_marks():
        """中英文问号混合: ？(全角)与 ?(半角)都能被 [\\？|\\?] 匹配。"""
        text = "1. 今天天气怎么样？\n2. 明天会不会下雨?"
        result = format_questions_str2list(text)
        assert result == ["今天天气怎么样？", "明天会不会下雨?"]
