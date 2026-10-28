"""ApiConfigParams 白盒单元测试。

覆盖 server.model_workers.base.ApiConfigParams 的配置加载逻辑:
- TestValidateConfig: root_validator(pre=True) 调 get_model_worker_config(worker_name),
  将 config 中与 cls.__fields__ 同名的键填入 v;__fields__ 之外的键(如 unrelated)被忽略。
- TestLoadConfig: 先构造 ApiConfigParams()(worker_name=None,不填字段),
  再 load_config("foo") 走 setattr 路径填入字段。

get_model_worker_config 通过 patch server.model_workers.base.get_model_worker_config 注入,
禁止真实读取 ONLINE_LLM_MODEL / FSCHAT_MODEL_WORKERS 配置。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import patch

import pytest

from server.model_workers.base import ApiConfigParams


class TestValidateConfig:
    """root_validator: 从 get_model_worker_config 填入已知字段,忽略未知键。"""

    @staticmethod
    def test_fills_known_fields_ignores_unknown():
        """config 含 api_base_url/api_key/unrelated → 仅前两者被填入,unrelated 被忽略。"""
        _UNRELATED_KEY = 'unrelated'
        config = {
            "api_base_url": "http://api.example.com",
            "api_key": "secret-key",
            _UNRELATED_KEY: "should-be-ignored",
        }
        with patch("server.model_workers.base.get_model_worker_config",
                   return_value=config) as mock_get:
            params = ApiConfigParams(worker_name="foo")

        # get_model_worker_config 以 worker_name="foo" 调用一次
        mock_get.assert_called_once_with("foo")
        # 已知字段被填入
        assert params.api_base_url == "http://api.example.com"
        assert params.api_key == "secret-key"
        # unrelated 不在 __fields__ 中
        assert _UNRELATED_KEY not in ApiConfigParams.__fields__
        # 实例上不存在 unrelated 字段(未被 setattr)
        assert _UNRELATED_KEY not in params.dict()

    @staticmethod
    def test_worker_name_none_skips_when_config_empty():
        """worker_name=None 且 get_model_worker_config 返回 {} → 不填字段,全为默认值。"""
        with patch("server.model_workers.base.get_model_worker_config",
                   return_value={}):
            params = ApiConfigParams(worker_name=None)
        assert params.api_base_url is None
        assert params.api_key is None
        assert params.worker_name is None


class TestLoadConfig:
    """load_config: 显式调用,走 setattr 路径填入字段。"""

    @staticmethod
    def test_load_config_setattr_path():
        """先 ApiConfigParams()(worker_name=None,无字段),再 load_config("foo") 填入字段。"""
        _KEY_NAME_KEY = 'foo'
        config = {
            "api_base_url": "http://loaded.example.com",
            "api_key": "loaded-key",
            "unrelated": "ignored-again",
        }

        def fake_get(name):
            if name == _KEY_NAME_KEY:
                return config
            return {}

        with patch("server.model_workers.base.get_model_worker_config",
                   side_effect=fake_get) as mock_get:
            params = ApiConfigParams()
            # 构造时 worker_name=None → get_model_worker_config(None) 返回 {} → 不填字段
            assert params.api_base_url is None
            assert params.api_key is None
            # 调用 load_config 走 setattr 路径
            returned = params.load_config(_KEY_NAME_KEY)

        # load_config 返回 self
        assert returned is params
        # 字段已填入
        assert params.api_base_url == "http://loaded.example.com"
        assert params.api_key == "loaded-key"
        assert params.worker_name == _KEY_NAME_KEY
        # get_model_worker_config 调用两次:一次 None,一次 "foo"
        assert mock_get.call_count == 2
        assert mock_get.call_args_list[0][0] == (None,)
        assert mock_get.call_args_list[1][0] == (_KEY_NAME_KEY,)
        # unrelated 仍被忽略
        assert "unrelated" not in params.dict()

    @staticmethod
    def test_load_config_no_config_does_nothing():
        """get_model_worker_config 返回 {} → load_config 仅设 worker_name,不填其他字段。"""
        with patch("server.model_workers.base.get_model_worker_config",
                   return_value={}):
            params = ApiConfigParams()
            params.load_config("nonexistent")
        assert params.worker_name == "nonexistent"
        assert params.api_base_url is None
        assert params.api_key is None
