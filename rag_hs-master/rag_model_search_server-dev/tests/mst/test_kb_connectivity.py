"""知识库管理接口连通性测试（Task 4）。

覆盖路由：
- GET /knowledge_base/list_knowledge_bases：获取知识库列表（ListResponse）
- POST /knowledge_base/create_knowledge_base：创建知识库
- POST /knowledge_base/delete_knowledge_base：删除知识库
- GET /knowledge_base/list_files：列出知识库内文件
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
from tests.mst.conftest import BASE_URL, _delete_kb_and_wait, assert_base_response, assert_http_ok, get_json, post_json


def test_list_knowledge_bases():
    """GET /knowledge_base/list_knowledge_bases：返回 ListResponse，data 为 list。"""
    r = get_json("/knowledge_base/list_knowledge_bases")
    assert_base_response(r)
    assert isinstance(r.json()["data"], list)


def test_create_and_delete_kb():
    """创建→重复创建(404)→删除→重复删除(404) 的完整生命周期。

    delete_kb 是异步的（内部起线程，仅 join 5s），返回 200 时 KB 可能
    仍存在。为确保生命周期断言稳定：
    - 起始先清理同名残留 KB（删除并等待消失）；
    - 删除后再等待 KB 真正消失，才进行“重复删除”断言。
    """
    kb_name = "kb_api_test_tmp_create"

    # 0. 预清理：删除可能残留的同名 KB（跨测试运行残留）
    _delete_kb_and_wait(kb_name, timeout=30)

    # 1. 首次创建：成功
    r = post_json("/knowledge_base/create_knowledge_base",
                  json={"knowledge_base_name": kb_name})
    assert_base_response(r)

    # 2. 重复创建同名：已存在，code=404
    r = post_json("/knowledge_base/create_knowledge_base",
                  json={"knowledge_base_name": kb_name})
    assert r.json()["code"] == 404

    # 3. 删除：成功（异步，返回 200 但可能尚未真正删除）
    # delete_kb 单 Body 参数，请求体为纯字符串
    r = post_json("/knowledge_base/delete_knowledge_base", json=kb_name)
    assert_base_response(r)

    # 3.5 等待 KB 真正从列表消失，避免重复删除仍命中
    _delete_kb_and_wait(kb_name, timeout=30)

    # 4. 重复删除：未找到，code=404
    r = post_json("/knowledge_base/delete_knowledge_base", json=kb_name)
    assert r.json()["code"] == 404


def test_list_files(test_kb):
    """GET /knowledge_base/list_files：列出 test_kb fixture 中的文件。"""
    r = get_json("/knowledge_base/list_files",
                 params={"knowledge_base_name": test_kb})
    assert_base_response(r)
