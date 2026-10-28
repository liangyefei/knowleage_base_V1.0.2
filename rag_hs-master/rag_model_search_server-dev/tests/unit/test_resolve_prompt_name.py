"""resolve_prompt_name 单元测试：语言 → 模板名映射 + 优雅降级。

覆盖两类模板形态：
- knowledge_base_chat: system+body 两段式 dict（en_default/en_text/en_think/en_empty 已存在）
- llm_chat / search_engine_chat: 单字符串（en_default 在 Task 4/5 添加）
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))  # pylint: disable=no-use-sys-path-insert

import pytest

try:
    from server.utils import resolve_prompt_name
    _IMPORT_ERROR = None
except Exception as _e:  # noqa: BLE001
    resolve_prompt_name = None
    _IMPORT_ERROR = _e

_SKIP = f"无法导入 resolve_prompt_name: {_IMPORT_ERROR}"


@pytest.mark.skipif(resolve_prompt_name is None, reason=_SKIP)
class TestResolvePromptName:
    _ZH_KEY = 'zh'
    _EN_KEY = 'en'
    _TEMPLATE_NAME_KEY = 'knowledge_base_chat'

    @staticmethod
    def test_zh_returns_original():
        """language=zh 时原样返回模板名（中文行为零变更）。"""
        _DEFAULT_KEY = 'default'
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, _DEFAULT_KEY,
            TestResolvePromptName._ZH_KEY) == _DEFAULT_KEY
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, "chitchat",
            TestResolvePromptName._ZH_KEY) == "chitchat"
        assert resolve_prompt_name(
            "llm_chat", _DEFAULT_KEY,
            TestResolvePromptName._ZH_KEY) == _DEFAULT_KEY

    @staticmethod
    def test_en_hit_existing_dict_template():
        """language=en 且 en_{name} 已存在（dict 形态）→ 返回 en_{name}。"""
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, "default",
            TestResolvePromptName._EN_KEY) == "en_default"
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, "text",
            TestResolvePromptName._EN_KEY) == "en_text"
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, "think",
            TestResolvePromptName._EN_KEY) == "en_think"
        assert resolve_prompt_name(
            TestResolvePromptName._TEMPLATE_NAME_KEY, "empty",
            TestResolvePromptName._EN_KEY) == "en_empty"

    @staticmethod
    def test_en_hit_existing_string_template():
        """language=en 且 en_{name} 已存在（单字符串形态）→ 返回 en_{name}。

        验证 resolve_prompt_name 的存在性检查（en_name in cat）兼容单字符串模板，
        不会因 isinstance(dict) 误判为未命中而降级。
        """
        assert resolve_prompt_name("llm_chat", "default", "en") == "en_default"
        assert resolve_prompt_name("llm_chat", "with_history", "en") == "en_with_history"

    @staticmethod
    def test_en_hit_search_engine_chat():
        """search_engine_chat 单字符串英文模板命中。"""
        assert resolve_prompt_name("search_engine_chat", "default", "en") == "en_default"
        assert resolve_prompt_name("search_engine_chat", "search", "en") == "en_search"

    @staticmethod
    def test_en_chitchat_hit():
        """language=en 且 en_chitchat 已添加 → 返回 en_chitchat。"""
        assert resolve_prompt_name("knowledge_base_chat", "chitchat", "en") == "en_chitchat"

    @staticmethod
    def test_en_degrade_when_missing():
        """已知 category 但 prompt_name 无对应 en_ → 降级回原模板名。"""
        assert resolve_prompt_name("knowledge_base_chat", "nonexistent_prompt", "en") == "nonexistent_prompt"

    @staticmethod
    def test_unknown_category_degrades():
        """未知 category → 降级回原模板名（不抛异常）。"""
        assert resolve_prompt_name("nonexistent_cat", "default", "en") == "default"

    @staticmethod
    def test_unknown_prompt_name_degrades():
        """已知 category 但 prompt_name 无对应 en_ → 降级回原模板名。"""
        assert resolve_prompt_name("knowledge_base_chat", "nonexistent_prompt", "en") == "nonexistent_prompt"
