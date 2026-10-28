"""服务器配置与 prompt 模板连通性测试（Task 2）。

覆盖路由：
- POST /server/configs：获取服务器原始配置信息（返回 dict，非 BaseResponse）
- POST /server/get_prompt_template：获取配置的 prompt 模板（返回纯字符串）
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from tests.mst.conftest import BASE_URL, assert_base_response, assert_http_ok, get_json, post_json


def test_server_configs():
    """POST /server/configs：返回配置 dict（非 BaseResponse），应包含 api_address 字段。

    get_server_configs() 直接返回 dict（含 DEFAULT_KNOWLEDGE_BASE、api_address 等），
    不包裹 BaseResponse，故不能用 assert_base_response，只断言 HTTP 200 且
    返回 dict 含 api_address 键。
    """
    r = post_json("/server/configs", json={})
    assert_http_ok(r)
    data = r.json()
    assert isinstance(data, dict), f"返回非 dict: {r.text[:200]}"
    assert "api_address" in data, (
        f"返回 dict 缺少 api_address 键: {list(data.keys())[:10]}"
    )


def test_get_prompt_template():
    """POST /server/get_prompt_template：返回纯字符串模板（非 BaseResponse），内容非空。

    该接口直接返回 str（prompt 模板内容，text/plain），故仅断言 HTTP 200
    且响应体文本非空（用 r.text 而非 r.json）。
    """
    r = post_json("/server/get_prompt_template",
                  json={"type": "llm_chat", "name": "default"})
    assert_http_ok(r)
    assert len(r.text) > 0, f"响应为空: {r.text[:200]}"
