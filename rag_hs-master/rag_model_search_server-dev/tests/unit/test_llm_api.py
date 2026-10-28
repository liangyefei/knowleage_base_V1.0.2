"""server/llm_api.py 纯逻辑单测。

覆盖:
- stop_llm_model / change_llm_model 纯返回
- get_model_config 敏感字段过滤(worker_class/含 key/含 secret/以 id 结尾)
- list_running_models 按 api_base_url 过滤 + 异常 500
- list_config_models 按 types 过滤
"""
from unittest.mock import MagicMock, patch

import pytest

from server.llm_api import (
    change_llm_model,
    get_model_config,
    list_config_models,
    list_running_models,
    stop_llm_model,
)
from server.utils import BaseResponse


class TestStopLlmModel:
    """在线 API 模型无需停止,纯返回 200。"""

    @staticmethod
    def test_returns_friendly_msg():
        resp = stop_llm_model("doubao-1.5-pro-32k-api")
        assert resp.code == 200
        assert "无需停止" in resp.msg
        assert "doubao-1.5-pro-32k-api" in resp.msg


class TestChangeLlmModel:
    """在线 API 模型无需切换,纯返回 200。"""

    @staticmethod
    def test_returns_friendly_msg():
        resp = change_llm_model("old-model", "new-model")
        assert resp.code == 200
        assert "无需切换" in resp.msg
        assert "old-model" in resp.msg
        assert "new-model" in resp.msg


class TestGetModelConfig:
    """get_model_config 过滤敏感字段(worker_class / 含 key / 含 secret / 以 id 结尾)。"""

    @patch("server.llm_api.get_model_worker_config")
    def test_filters_sensitive_fields(self, mock_get_config):
        mock_get_config.return_value = {
            "api_base_url": "http://x",
            "api_key": "sk-xxx",
            "secret_key": "sec-xxx",
            "worker_class": "SomeWorker",
            "group_id": "g1",
            "normal_field": "ok",
            "model_id": "m1",
            "api_secret": "s2",
        }
        resp = get_model_config("test-model")
        assert resp.code == 200
        data = resp.data
        assert "api_base_url" in data
        assert "normal_field" in data
        # 敏感字段被过滤
        assert "api_key" not in data
        assert "secret_key" not in data
        assert "worker_class" not in data
        assert "group_id" not in data  # 以 id 结尾
        assert "model_id" not in data  # 以 id 结尾
        assert "api_secret" not in data  # 含 secret

    @patch("server.llm_api.get_model_worker_config")
    def test_empty_config(self, mock_get_config):
        mock_get_config.return_value = {}
        resp = get_model_config("empty-model")
        assert resp.code == 200
        assert resp.data == {}


class TestListRunningModels:
    """list_running_models 只返回有 api_base_url 的在线模型。"""

    @patch("server.llm_api.get_model_config")
    @patch("server.llm_api.ONLINE_LLM_MODEL")
    def test_filters_by_api_base_url(self, mock_online, mock_get_model_config):
        mock_online.items.return_value = [
            ("model-a", {"api_base_url": "http://a"}),
            ("model-b", {"other": "x"}),  # 无 api_base_url
            ("model-c", {"api_base_url": "http://c"}),
        ]
        def _make_model_response(name):
            return BaseResponse(data={"model": name})

        mock_get_model_config.side_effect = _make_model_response
        resp = list_running_models()
        assert resp.code == 200
        assert "model-a" in resp.data
        assert "model-c" in resp.data
        assert "model-b" not in resp.data

    @patch("server.llm_api.ONLINE_LLM_MODEL")
    def test_exception_returns_500(self, mock_online):
        mock_online.items.side_effect = RuntimeError("boom")
        resp = list_running_models()
        assert resp.code == 500
        assert "failed to get available models" in resp.msg


class TestListConfigModels:
    """list_config_models 按 types 过滤本地配置的模型列表。"""

    @patch("server.llm_api.get_model_config")
    @patch("server.llm_api.list_config_llm_models")
    def test_filters_by_types(self, mock_list, mock_get_model_config):
        _LOCAL_KEY = 'local'
        _ONLINE_KEY = 'online'
        mock_list.return_value = {
            _LOCAL_KEY: ["local-a", "local-b"],
            _ONLINE_KEY: ["online-a"],
            "worker": ["worker-a"],
        }

        def _make_model_response(name):
            return BaseResponse(data={"m": name})

        mock_get_model_config.side_effect = _make_model_response
        resp = list_config_models([_LOCAL_KEY, _ONLINE_KEY])
        assert resp.code == 200
        assert _LOCAL_KEY in resp.data
        assert _ONLINE_KEY in resp.data
        assert "worker" not in resp.data
        assert set(resp.data[_LOCAL_KEY].keys()) == {"local-a", "local-b"}

    @patch("server.llm_api.get_model_config")
    @patch("server.llm_api.list_config_llm_models")
    def test_all_types(self, mock_list, mock_get_model_config):
        _LOCAL_KEY = 'local'
        _ONLINE_KEY = 'online'
        _WORKER_KEY = 'worker'
        mock_list.return_value = {_LOCAL_KEY: ["a"], _ONLINE_KEY: ["b"], _WORKER_KEY: ["c"]}

        def _make_model_response(name):
            return BaseResponse(data={"m": name})

        mock_get_model_config.side_effect = _make_model_response
        resp = list_config_models([_LOCAL_KEY, _ONLINE_KEY, _WORKER_KEY])
        assert resp.code == 200
        assert set(resp.data.keys()) == {_LOCAL_KEY, _ONLINE_KEY, _WORKER_KEY}
