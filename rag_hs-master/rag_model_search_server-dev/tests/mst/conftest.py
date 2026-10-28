"""tests/mst 共享测试设施。

提供：
- BASE_URL: API 服务基础地址（含 /kw 前缀，由 server.utils.api_address() 返回）
- ensure_server_up: session 级 autouse fixture，服务不可达时 skip 整个测试会话
- require_llm: 通过 --run-llm 控制，未启用时跳过 LLM 依赖测试
- test_kb: session 级 fixture，创建/清理临时知识库 kb_api_test_tmp
- post_json / get_json / assert_base_response / assert_http_ok: 辅助函数
"""
# pylint: disable=no-use-sys-path-insert
import logging
import sys
import tempfile
import time
from pathlib import Path

import pytest
import requests

logger = logging.getLogger(__name__)

# 将项目根目录加入 sys.path，确保能 import server.utils.api_address
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from server.utils import api_address

BASE_URL = api_address()  # http://127.0.0.1:7861/kw

# 临时知识库名（test_kb fixture 使用）
_TEST_KB_NAME = "kb_api_test_tmp"


def pytest_addoption(parser):
    parser.addoption(
        "--run-llm",
        action="store_true",
        default=False,
        help="运行依赖 LLM 的测试（会调用火山引擎 doubao）",
    )


def _delete_kb_and_wait(kb_name: str, timeout: int = 30) -> bool:
    """删除知识库并轮询直到它真正从 list_knowledge_bases 中消失。

    delete_kb 的签名是 knowledge_base_name: str = Body(...)（单 Body 参数），
    FastAPI 期望整个请求体为纯字符串（如 "kb_name"），而非 JSON 对象
    {"knowledge_base_name": ...}，否则返回 422。delete_kb 内部异步删除
    （起线程，仅 join 5s），返回 200 时 KB 可能仍存在，故需轮询确认。
    返回 True 表示已消失，False 表示超时仍存在。
    """
    try:
        requests.post(
            f"{BASE_URL}/knowledge_base/delete_knowledge_base",
            json=kb_name,  # 单 Body 参数：请求体为纯字符串
            timeout=30,
        )
    except requests.RequestException:
        pass
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            lr = requests.get(
                f"{BASE_URL}/knowledge_base/list_knowledge_bases",
                timeout=15,
            )
            if lr.status_code == 200:
                data = lr.json().get("data") or []
                if kb_name not in data:
                    return True
        except requests.RequestException:
            pass
        time.sleep(1)
    return False


@pytest.fixture(scope="session", autouse=True)
def ensure_server_up():
    """探测 API 服务是否可达；不可达时跳过整个测试会话。

    探测 {BASE_URL}/server/configs（POST {}），timeout=5。
    """
    url = f"{BASE_URL}/server/configs"
    try:
        r = requests.post(url, json={}, timeout=5)
    except (requests.ConnectionError, requests.Timeout) as e:
        pytest.skip(f"API 服务未启动: {BASE_URL} ({e})")
    if r.status_code != 200:
        pytest.skip(f"API 服务未启动: {BASE_URL} (HTTP {r.status_code})")


@pytest.fixture
def require_llm(request):
    """依赖 LLM 的测试在签名中请求此 fixture；未传 --run-llm 时自动跳过。"""
    if not request.config.getoption("--run-llm"):
        pytest.skip("需 --run-llm 才运行 LLM 依赖测试")


@pytest.fixture(scope="session")
def test_kb(ensure_server_up):
    """创建临时知识库并上传一个 txt 文档；yield 知识库名；teardown 删除。"""
    _KNOWLEDGE_BASE_NAME_KEY = 'knowledge_base_name'
    # 1. 先尝试清理同名残留知识库（异步删除需轮询等待真正消失）
    _delete_kb_and_wait(_TEST_KB_NAME, timeout=30)

    # 2. 创建知识库
    r = requests.post(
        f"{BASE_URL}/knowledge_base/create_knowledge_base",
        json={_KNOWLEDGE_BASE_NAME_KEY: _TEST_KB_NAME},
        timeout=30,
    )
    assert_base_response(r)

    # 3. 生成临时 txt 并上传
    tmp = tempfile.NamedTemporaryFile(  # pylint: disable=consider-using-with
        prefix="kb_api_test_",
        suffix=".txt",
        delete=False,
        mode="w",
        encoding="utf-8",
    )
    tmp_path = Path(tmp.name)
    try:
        tmp.write(
            "这是一个用于 API 自动化测试的临时文档。\n"
            "海思 RAG 知识库 API 测试，内容仅用于检索验证。\n"
            "关键词：测试、知识库、向量化、检索、海思。\n"
        )
        tmp.flush()
        tmp.close()

        with open(tmp_path, "rb") as f:
            files = [("files", (tmp_path.name, f, "text/plain"))]
            data = {_KNOWLEDGE_BASE_NAME_KEY: _TEST_KB_NAME, "override": True}
            r = requests.post(
                f"{BASE_URL}/knowledge_base/upload_docs",
                data=data,
                files=files,
                timeout=120,
            )
        assert_base_response(r)
    finally:
        try:
            tmp_path.unlink(missing_ok=True)
        except Exception as e:
            logger.debug("[mst-conftest] failed to delete temp file %s: %s", tmp_path, e)
            pass

    # 4. 适当等待向量化完成：轮询 list_files，直到能列出文件
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            lr = requests.get(
                f"{BASE_URL}/knowledge_base/list_files",
                params={_KNOWLEDGE_BASE_NAME_KEY: _TEST_KB_NAME},
                timeout=15,
            )
            if lr.status_code == 200:
                body = lr.json()
                if (
                    body.get("code") == 200
                    and isinstance(body.get("data"), list)
                    and body["data"]
                ):
                    break
        except requests.RequestException:
            pass
        time.sleep(1)

    yield _TEST_KB_NAME

    # 5. teardown：删除知识库（异步删除需轮询等待真正消失，忽略错误）
    _delete_kb_and_wait(_TEST_KB_NAME, timeout=30)


# ---------- 辅助函数 ----------

def post_json(path: str, json: dict = None, **kw) -> requests.Response:
    """POST {BASE_URL}{path}，发送 json body。"""
    return requests.post(f"{BASE_URL}{path}", json=json, **kw)


def get_json(path: str, params: dict = None, **kw) -> requests.Response:
    """GET {BASE_URL}{path}。"""
    return requests.get(f"{BASE_URL}{path}", params=params, **kw)


def assert_base_response(r: requests.Response):
    """断言响应为 BaseResponse 成功：HTTP 200 且 body.code == 200。"""
    if r.status_code != 200:
        raise AssertionError(f"HTTP {r.status_code}: {r.text[:200]}")
    body = r.json()
    if body.get("code") != 200:
        raise AssertionError(
            f"BaseResponse code={body.get('code')} msg={body.get('msg')}"
        )


def assert_http_ok(r: requests.Response):
    """仅断言 HTTP 200（用于返回非 BaseResponse 的接口）。"""
    if r.status_code != 200:
        raise AssertionError(f"HTTP {r.status_code}: {r.text[:200]}")
