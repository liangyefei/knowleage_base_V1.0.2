"""LLM 模型管理接口连通性测试（Task 3）。

覆盖路由：
- POST /llm_model/list_running_models：列出当前已加载的在线模型
- POST /llm_model/list_config_models：按类型列出 configs 中已配置模型
- POST /llm_model/get_model_config：获取指定模型的合并配置
- POST /llm_model/stop：停止指定模型（在线 API 返回无需停止）
- POST /llm_model/change：切换指定模型（在线 API 返回无需切换）
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from tests.mst.conftest import BASE_URL, assert_base_response, assert_http_ok, get_json, post_json


def test_list_running_models():
    """POST /llm_model/list_running_models：data 中应含 doubao-1.5-pro-32k-api。"""
    r = post_json("/llm_model/list_running_models", json={})
    assert_base_response(r)
    assert "doubao-1.5-pro-32k-api" in r.json()["data"]


def test_list_config_models():
    """POST /llm_model/list_config_models：按 local/online 列出，data 中应含 online 键。"""
    r = post_json("/llm_model/list_config_models",
                  json={"types": ["local", "online"]})
    assert_base_response(r)
    assert "online" in r.json()["data"]


def test_get_model_config():
    """POST /llm_model/get_model_config：获取 doubao 模型配置，data 应为非空 dict。"""
    r = post_json("/llm_model/get_model_config",
                  json={"model_name": "doubao-1.5-pro-32k-api"})
    assert_base_response(r)
    data = r.json()["data"]
    assert isinstance(data, dict) and len(data) > 0


def test_stop_llm_model():
    """POST /llm_model/stop：在线 API 模型无需停止，msg 应含"无需停止"。"""
    r = post_json("/llm_model/stop",
                  json={"model_name": "doubao-1.5-pro-32k-api"})
    assert_base_response(r)
    assert "无需停止" in r.json()["msg"]


def test_change_llm_model():
    """POST /llm_model/change：在线 API 模型无需切换，msg 应含"无需切换"。"""
    r = post_json("/llm_model/change",
                  json={"model_name": "doubao-1.5-pro-32k-api",
                        "new_model_name": "doubao-1.5-pro-32k-api"})
    assert_base_response(r)
    assert "无需切换" in r.json()["msg"]
