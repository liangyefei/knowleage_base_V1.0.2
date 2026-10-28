"""language_detect 单元测试：CJK 占优快速通道 / 代码主导识别 / 极短文本 / kill-switch。"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))  # pylint: disable=no-use-sys-path-insert

import pytest

try:
    from server.chat.pipeline.language_detect import detect_language, resolve_language
    _IMPORT_ERROR = None
except Exception as _e:  # noqa: BLE001
    detect_language = None
    resolve_language = None
    _IMPORT_ERROR = _e

_SKIP = f"无法导入 language_detect 模块: {_IMPORT_ERROR}"

_LANG_ZH = "zh"
_LANG_EN = "en"


@pytest.mark.skipif(detect_language is None, reason=_SKIP)
class TestDetectLanguage:
    @pytest.mark.parametrize("query,expected", [
        ("Hi3798MV310 的启动流程是什么？", _LANG_ZH),
        ("What is the boot process of Hi3798MV310?", _LANG_EN),
        ("hi", _LANG_EN),
        ("你好", _LANG_ZH),
        ("GPIO 配置", _LANG_ZH),
        ("How to configure GPIO?", _LANG_EN),
        ("", _LANG_ZH),
        ("12345", _LANG_ZH),
        ("OK", _LANG_EN),
        ("Thanks a lot", _LANG_EN),
        # 代码主导识别（核心）：纯代码不切英文
        ("def foo():\n    return 42", _LANG_ZH),
        ("int main() { return 0; }", _LANG_ZH),
        ("GPIO_CFG = 0x1234;", _LANG_ZH),
        ("x=1", _LANG_ZH),
        ("How to use return statement?", _LANG_EN),
        ("why error?\n```python\ndef foo():\n    return 1\n```", _LANG_EN),
        ("这段代码报错了\n```python\ndef foo(): pass\n```", _LANG_ZH),
    ])
    @staticmethod
    def test_detect(query, expected):
        assert detect_language(query) == expected

    @staticmethod
    def test_resolve_explicit_override():
        assert resolve_language("你好", _LANG_EN) == _LANG_EN
        assert resolve_language("hello", _LANG_ZH) == _LANG_ZH
        assert resolve_language("你好", "auto") == _LANG_ZH
        assert resolve_language("hello", "auto") == _LANG_EN

    @staticmethod
    def test_kill_switch(monkeypatch):
        monkeypatch.setenv("HAISI_LANG_AUTO_SWITCH", "0")
        assert resolve_language("hello", "en") == "zh"
        monkeypatch.setenv("HAISI_LANG_AUTO_SWITCH", "1")
        assert resolve_language("hello", "auto") == "en"
