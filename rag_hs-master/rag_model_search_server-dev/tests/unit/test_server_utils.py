"""
server/utils.py 纯逻辑白盒单元测试。

覆盖:
- BaseResponse / ListResponse / ChatMessage 数据模型默认值
- list_embed_models(): MODEL_PATH["embed_model"] 为 None 时不抛 TypeError 回归
- list_config_llm_models(): 返回 local/online/worker 三键
- get_model_path(): 绝对路径 / MODEL_ROOT_PATH 下 / 未命中 / 回退 path_str 分支
- api_address() / webui_address()
- get_prompt_template()
- detect_device() / llm_device() / embedding_device()
- run_async()
- get_temp_dir()
- list_online_embed_models()

全部用 monkeypatch + tmp_path，不依赖真实外部服务（Milvus/PG/Kafka/LLM/Embedding API）。
"""
# pylint: disable=no-use-sys-path-insert
import os
import sys
import types
from pathlib import Path

# 将项目根目录加入 sys.path，保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from server.utils import (
    BaseResponse,
    ChatMessage,
    ListResponse,
    api_address,
    detect_device,
    embedding_device,
    get_model_path,
    get_prompt_template,
    get_temp_dir,
    list_config_llm_models,
    list_embed_models,
    list_online_embed_models,
    llm_device,
    run_async,
    webui_address,
)

# ==================== 数据模型 ====================

class TestBaseResponse:
    @staticmethod
    def test_defaults():
        r = BaseResponse()
        assert r.code == 200
        assert r.msg == "success"
        assert r.data is None

    @staticmethod
    def test_custom():
        r = BaseResponse(code=500, msg="error", data={"k": "v"})
        assert r.code == 500
        assert r.msg == "error"
        assert r.data == {"k": "v"}

    @staticmethod
    def test_schema_extra():
        assert "example" in BaseResponse.Config.schema_extra
        assert BaseResponse.Config.schema_extra["example"]["code"] == 200


class TestListResponse:
    @staticmethod
    def test_data_required():
        with pytest.raises(Exception):
            ListResponse()  # data 是必填

    @staticmethod
    def test_data_list():
        r = ListResponse(data=["a.txt", "b.pdf"])
        assert r.code == 200
        assert r.data == ["a.txt", "b.pdf"]

    @staticmethod
    def test_schema_extra():
        assert "example" in ListResponse.Config.schema_extra
        assert isinstance(ListResponse.Config.schema_extra["example"]["data"], list)


class TestChatMessage:
    @staticmethod
    def test_fields():
        m = ChatMessage(
            question="q",
            response="r",
            history=[["h1", "h2"]],
            source_documents=["doc1"],
        )
        assert m.question == "q"
        assert m.response == "r"
        assert m.history == [["h1", "h2"]]
        assert m.source_documents == ["doc1"]

    @staticmethod
    def test_schema_extra():
        ex = ChatMessage.Config.schema_extra["example"]
        assert "question" in ex
        assert "response" in ex


# ==================== run_async ====================

class TestRunAsync:
    @staticmethod
    def test_returns_coroutine_result():
        async def _coro():
            return 42

        assert run_async(_coro()) == 42

    @staticmethod
    def test_returns_complex_value():
        async def _coro():
            return {"a": 1, "b": [1, 2, 3]}

        assert run_async(_coro()) == {"a": 1, "b": [1, 2, 3]}


# ==================== list_embed_models（含 None 回归）====================

class TestListEmbedModels:
    @staticmethod
    def test_none_returns_empty_list(monkeypatch):
        """MODEL_PATH['embed_model'] 为 None 时不抛 TypeError，返回空列表（已知 bug 回归）。"""
        monkeypatch.setattr("server.utils.MODEL_PATH", {"embed_model": None})
        result = list_embed_models()
        assert not result

    @staticmethod
    def test_empty_dict_returns_empty_list(monkeypatch):
        monkeypatch.setattr("server.utils.MODEL_PATH", {"embed_model": {}})
        assert not list_embed_models()

    @staticmethod
    def test_with_models(monkeypatch):
        monkeypatch.setattr("server.utils.MODEL_PATH", {"embed_model": {"m1": "p1", "m2": "p2"}})
        result = list_embed_models()
        assert set(result) == {"m1", "m2"}


# ==================== list_config_llm_models ====================

class TestListConfigLlmModels:
    @staticmethod
    def test_returns_three_keys(monkeypatch):
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {"m1": "p1"}})
        monkeypatch.setattr("server.utils.ONLINE_LLM_MODEL", {"om1": {"k": "v"}})
        monkeypatch.setattr("server.utils.FSCHAT_MODEL_WORKERS", {"default": {}, "w1": {}})

        result = list_config_llm_models()
        assert set(result.keys()) == {"local", "online", "worker"}

    @staticmethod
    def test_default_popped_from_worker(monkeypatch):
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {}})
        monkeypatch.setattr("server.utils.ONLINE_LLM_MODEL", {})
        monkeypatch.setattr("server.utils.FSCHAT_MODEL_WORKERS", {"default": {"x": 1}, "w1": {}})

        result = list_config_llm_models()
        assert "default" not in result["worker"]
        assert "w1" in result["worker"]

    @staticmethod
    def test_local_none_returns_empty(monkeypatch):
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": None})
        monkeypatch.setattr("server.utils.ONLINE_LLM_MODEL", {})
        monkeypatch.setattr("server.utils.FSCHAT_MODEL_WORKERS", {})

        result = list_config_llm_models()
        assert result["local"] == {}


# ==================== get_model_path ====================

class TestGetModelPath:
    @staticmethod
    def test_absolute_dir(monkeypatch, tmp_path):
        """path_str 为已存在的绝对目录时直接返回。"""
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {"m1": str(tmp_path)}})
        assert get_model_path("m1") == str(tmp_path)

    @staticmethod
    def test_under_root_by_key(monkeypatch, tmp_path):
        """MODEL_ROOT_PATH/m1 存在时返回该路径。"""
        _M1_KEY = 'm1'
        (tmp_path / _M1_KEY).mkdir()
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {_M1_KEY: _M1_KEY}})
        monkeypatch.setattr("server.utils.MODEL_ROOT_PATH", str(tmp_path))
        assert get_model_path(_M1_KEY) == str(tmp_path / _M1_KEY)

    @staticmethod
    def test_under_root_by_value(monkeypatch, tmp_path):
        """MODEL_ROOT_PATH/THUDM/m1 存在时返回该路径（按 value 拼接）。"""
        _MODEL_NAME_KEY = 'm1'
        (tmp_path / "THUDM" / _MODEL_NAME_KEY).mkdir(parents=True)
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {_MODEL_NAME_KEY: "THUDM/m1"}})
        monkeypatch.setattr("server.utils.MODEL_ROOT_PATH", str(tmp_path))
        assert get_model_path(_MODEL_NAME_KEY) == str(tmp_path / "THUDM" / _MODEL_NAME_KEY)

    @staticmethod
    def test_not_in_paths_returns_none(monkeypatch, tmp_path):
        """model_name 不在 paths 中时隐式返回 None。"""
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {"other": "p"}})
        monkeypatch.setattr("server.utils.MODEL_ROOT_PATH", str(tmp_path))
        assert get_model_path("nonexistent") is None

    @staticmethod
    def test_fallback_to_path_str(monkeypatch, tmp_path):
        """MODEL_ROOT_PATH 不存在且 path_str 非绝对目录时，回退返回 path_str。"""
        monkeypatch.setattr("server.utils.MODEL_PATH", {"llm_model": {"m1": "THUDM/m1"}})
        monkeypatch.setattr("server.utils.MODEL_ROOT_PATH", str(tmp_path / "nonexistent_root"))
        assert get_model_path("m1") == "THUDM/m1"


# ==================== api_address / webui_address ====================

class TestApiAddress:
    @staticmethod
    def test_zero_host_replaced(monkeypatch):
        monkeypatch.setattr("configs.API_SERVER", {"host": "0.0.0.0", "port": 8080})
        assert api_address() == "http://127.0.0.1:8080/kw"

    @staticmethod
    def test_normal_host(monkeypatch):
        monkeypatch.setattr("configs.API_SERVER", {"host": "1.2.3.4", "port": 9000})
        assert api_address() == "http://1.2.3.4:9000/kw"

    @staticmethod
    def test_public(monkeypatch):
        monkeypatch.setattr("configs.API_SERVER", {
            "host": "x", "port": 1,
            "public_host": "pub.host", "public_port": 1234,
        })
        assert api_address(is_public=True) == "http://pub.host:1234/kw"


class TestWebuiAddress:
    @staticmethod
    def test_returns(monkeypatch):
        monkeypatch.setattr("configs.WEBUI_SERVER", {"host": "1.2.3.4", "port": 7861})
        assert webui_address() == "http://1.2.3.4:7861"


# ==================== get_prompt_template ====================

class TestGetPromptTemplate:
    @staticmethod
    def test_returns_template(monkeypatch):
        fake_loader = types.SimpleNamespace(load=lambda: {"llm_chat": {"default": "hello prompt"}})
        monkeypatch.setattr("configs.prompt_loader.get_loader", lambda: fake_loader)
        assert get_prompt_template("llm_chat", "default") == "hello prompt"

    @staticmethod
    def test_missing_name_returns_none(monkeypatch):
        fake_loader = types.SimpleNamespace(load=lambda: {"llm_chat": {"default": "x"}})
        monkeypatch.setattr("configs.prompt_loader.get_loader", lambda: fake_loader)
        assert get_prompt_template("llm_chat", "nonexistent") is None


# ==================== detect_device / llm_device / embedding_device ====================

class TestDetectDevice:
    @staticmethod
    def test_returns_valid_value():
        assert detect_device() in ("cuda", "mps", "cpu")


class TestLlmDevice:
    @staticmethod
    def test_valid_cuda():
        assert llm_device("cuda") == "cuda"

    @staticmethod
    def test_valid_cpu():
        assert llm_device("cpu") == "cpu"

    @staticmethod
    def test_valid_mps():
        assert llm_device("mps") == "mps"

    @staticmethod
    def test_default_from_config(monkeypatch):
        monkeypatch.setattr("server.utils.LLM_DEVICE", "cpu")
        assert llm_device(None) == "cpu"

    @staticmethod
    def test_invalid_falls_to_detect(monkeypatch):
        monkeypatch.setattr("server.utils.detect_device", lambda: "cpu")
        assert llm_device("invalid_device") == "cpu"


class TestEmbeddingDevice:
    @staticmethod
    def test_valid_cuda():
        assert embedding_device("cuda") == "cuda"

    @staticmethod
    def test_valid_cpu():
        assert embedding_device("cpu") == "cpu"

    @staticmethod
    def test_default_from_config(monkeypatch):
        monkeypatch.setattr("server.utils.EMBEDDING_DEVICE", "cpu")
        assert embedding_device(None) == "cpu"

    @staticmethod
    def test_invalid_falls_to_detect(monkeypatch):
        monkeypatch.setattr("server.utils.detect_device", lambda: "cpu")
        assert embedding_device("invalid_device") == "cpu"


# ==================== get_temp_dir ====================

class TestGetTempDir:
    @staticmethod
    def test_creates_new_dir(monkeypatch, tmp_path):
        monkeypatch.setattr("configs.BASE_TEMP_DIR", str(tmp_path))
        path, name = get_temp_dir()
        assert os.path.isdir(path)
        assert name == os.path.basename(path)
        assert path.startswith(str(tmp_path))

    @staticmethod
    def test_existing_id(monkeypatch, tmp_path):
        _ID_KEY = 'myid'
        existing = tmp_path / _ID_KEY
        existing.mkdir()
        monkeypatch.setattr("configs.BASE_TEMP_DIR", str(tmp_path))
        path, name = get_temp_dir(_ID_KEY)
        assert path == str(existing)
        assert name == _ID_KEY

    @staticmethod
    def test_none_base_temp_dir(monkeypatch):
        """BASE_TEMP_DIR 为 None 时回退到系统默认临时目录。"""
        monkeypatch.setattr("configs.BASE_TEMP_DIR", None)
        path, name = get_temp_dir()
        assert os.path.isdir(path)
        assert name == os.path.basename(path)


# ==================== list_online_embed_models ====================

class TestListOnlineEmbedModels:
    @staticmethod
    def test_returns_empty_list():
        assert not list_online_embed_models()
