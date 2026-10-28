"""server/minx_chat_openai.py 纯逻辑单测。

覆盖:
- import_tiktoken:成功返回模块 / ImportError → ValueError
- get_encoding_model:tiktoken_model_name 非空 / gpt-3.5-turbo→0301 / gpt-4→0314 / encoding_for_model 异常→cl100k_base
"""
import sys
import types
from unittest.mock import MagicMock

import pytest

from server.minx_chat_openai import MinxChatOpenAI


def _make_fake_tiktoken(encoding_for_model=None, get_encoding=None, raise_import=False):
    """构造假 tiktoken 模块。"""
    mod = types.ModuleType("tiktoken")
    if raise_import:
        del mod
        raise ImportError("no tiktoken")
    mod.encoding_for_model = encoding_for_model or MagicMock(return_value="enc")
    mod.get_encoding = get_encoding or MagicMock(return_value="fallback_enc")
    return mod


class TestImportTiktoken:
    """import_tiktoken 成功返回模块 / 失败抛 ValueError。"""

    @staticmethod
    def test_success():
        _TIKTOKEN_KEY = 'tiktoken'
        fake = _make_fake_tiktoken()
        original = sys.modules.get(_TIKTOKEN_KEY)
        sys.modules[_TIKTOKEN_KEY] = fake
        try:
            result = MinxChatOpenAI.import_tiktoken()
            assert result is fake
        finally:
            if original is not None:
                sys.modules[_TIKTOKEN_KEY] = original
            else:
                sys.modules.pop(_TIKTOKEN_KEY, None)

    @staticmethod
    def test_import_error_raises_value_error():
        sys.modules.pop("tiktoken", None)
        # 确保 import tiktoken 抛 ImportError
        import builtins
        original_import = builtins.__import__

        def fake_import(name, *args, **kwargs):
            if name == "tiktoken":
                raise ImportError("no tiktoken")
            return original_import(name, *args, **kwargs)

        builtins.__import__ = fake_import
        try:
            with pytest.raises(ValueError, match="Could not import tiktoken"):
                MinxChatOpenAI.import_tiktoken()
        finally:
            builtins.__import__ = original_import


class TestGetEncodingModel:
    """get_encoding_model 4 个分支。"""

    @staticmethod
    def test_tiktoken_model_name_non_empty():
        _MODEL_NAME_KEY = 'my-model'
        fake = _make_fake_tiktoken()
        sys.modules["tiktoken"] = fake
        self_obj = MagicMock(tiktoken_model_name=_MODEL_NAME_KEY, model_name="gpt-4")
        try:
            model, _encoding = MinxChatOpenAI.get_encoding_model(self_obj)
            assert model == _MODEL_NAME_KEY
            fake.encoding_for_model.assert_called_once_with(_MODEL_NAME_KEY)
        finally:
            sys.modules.pop("tiktoken", None)

    @staticmethod
    def test_gpt35_turbo_maps_to_0301():
        fake = _make_fake_tiktoken()
        sys.modules["tiktoken"] = fake
        self_obj = MagicMock(tiktoken_model_name=None, model_name="gpt-3.5-turbo")
        try:
            model, _encoding = MinxChatOpenAI.get_encoding_model(self_obj)
            assert model == "gpt-3.5-turbo-0301"
            fake.encoding_for_model.assert_called_once_with("gpt-3.5-turbo-0301")
        finally:
            sys.modules.pop("tiktoken", None)

    @staticmethod
    def test_gpt4_maps_to_0314():
        fake = _make_fake_tiktoken()
        sys.modules["tiktoken"] = fake
        self_obj = MagicMock(tiktoken_model_name=None, model_name="gpt-4")
        try:
            model, _encoding = MinxChatOpenAI.get_encoding_model(self_obj)
            assert model == "gpt-4-0314"
            fake.encoding_for_model.assert_called_once_with("gpt-4-0314")
        finally:
            sys.modules.pop("tiktoken", None)

    @staticmethod
    def test_encoding_for_model_exception_falls_back_to_cl100k():
        fake = _make_fake_tiktoken(
            encoding_for_model=MagicMock(side_effect=Exception("not found"))
        )
        sys.modules["tiktoken"] = fake
        self_obj = MagicMock(tiktoken_model_name=None, model_name="unknown-model")
        try:
            model, _encoding = MinxChatOpenAI.get_encoding_model(self_obj)
            assert model == "cl100k_base"
            fake.get_encoding.assert_called_once_with("cl100k_base")
        finally:
            sys.modules.pop("tiktoken", None)
