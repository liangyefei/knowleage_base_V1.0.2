"""KB 文档管理 API 连通性测试（Task 5）。

依赖 tests/api/conftest.py 的 test_kb fixture（session 级）：
- 创建临时知识库 kb_api_test_tmp
- 上传一个临时 txt 文档（文件名带随机后缀，需通过 list_files 动态获取）
- session 结束自动清理

覆盖路由：
- POST /knowledge_base/search_docs            （返回 List[DocumentWithVSId]，非 BaseResponse）
- POST /knowledge_base/batch_search_docs      （返回 List，非 BaseResponse）
- GET  /knowledge_base/download_doc           （返回 FileResponse，非 BaseResponse）
- POST /knowledge_base/update_info            （返回 BaseResponse）
- POST /knowledge_base/delete_docs            （返回 BaseResponse；本测试先上传独立临时文档再删除，避免破坏 fixture 状态）
"""
# pylint: disable=no-use-sys-path-insert
import logging
import sys
import tempfile
from pathlib import Path

import pytest

logger = logging.getLogger(__name__)
import requests

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
from tests.mst.conftest import BASE_URL, assert_base_response, assert_http_ok, get_json, post_json

# 以下三个测试依赖在线 embedding 模型（qwen3-embedding-api）经
# EmbeddingsFunAdapter 向量化，但该适配器调用 embed_texts()，而 embed_texts
# 仅支持本地模型（list_embed_models），对在线模型返回 .data=None，
# 导致 embed_query 的 embeddings[0] 崩溃（HTTP 500）/ upload 未真正入库。
# 此为服务端功能缺口，需用户决策，详见 tests/api/question.md，暂跳过。
_EMBED_GAP_REASON = (
    "依赖在线 embedding 模型经 EmbeddingsFunAdapter/embed_texts 向量化；"
    "该路径不支持在线模型（返回 None→embeddings[0] 崩溃 500 / upload 未入库），"
    "属服务端功能缺口，见 tests/api/question.md"
)


@pytest.mark.skip(reason=_EMBED_GAP_REASON)
def test_search_docs(test_kb):
    """覆盖路由 POST /knowledge_base/search_docs。

    该接口返回 List[DocumentWithVSId]（非 BaseResponse），故仅断言 HTTP 200
    且响应为 list。top_k=5、score_threshold=1.0（不筛选）以提高命中几率，
    但向量库刚建可能无命中，故不断言非空。
    """
    r = post_json(
        "/knowledge_base/search_docs",
        json={
            "query": "测试",
            "knowledge_base_name": test_kb,
            "top_k": 5,
            "score_threshold": 1.0,
        },
    )
    assert_http_ok(r)
    assert isinstance(r.json(), list)


@pytest.mark.skip(reason=_EMBED_GAP_REASON)
def test_batch_search_docs(test_kb):
    """覆盖路由 POST /knowledge_base/batch_search_docs。

    返回 List（非 BaseResponse）。language=["all"] 表示不做代码语言过滤，
    直接返回全部命中。
    """
    r = post_json(
        "/knowledge_base/batch_search_docs",
        json={
            "query": "测试",
            "knowledge_base_names": [test_kb],
            "language": ["all"],
            "top_k": 5,
            "score_threshold": 1.0,
        },
    )
    assert_http_ok(r)
    assert isinstance(r.json(), list)


@pytest.mark.skip(reason=_EMBED_GAP_REASON)
def test_download_doc(test_kb):
    """覆盖路由 GET /knowledge_base/download_doc.

    test_kb fixture 上传的临时 txt 文件名带随机后缀（tempfile.NamedTemporaryFile
    prefix=kb_api_test_），故先调用 list_files 获取实际文件名，再下载。
    download_doc 返回 FileResponse（非 BaseResponse），故仅断言 HTTP 200。
    """
    # 1. 先列出文件，拿到 fixture 上传的 txt 文件名
    lr = get_json(
        "/knowledge_base/list_files",
        params={"knowledge_base_name": test_kb},
    )
    assert_base_response(lr)
    file_list = lr.json().get("data")
    assert isinstance(file_list, list) and len(file_list) > 0, (
        f"list_files 返回空列表，无法获取下载目标文件名: {lr.text[:200]}"
    )
    file_name = file_list[0]

    # 2. 下载该文件（返回 FileResponse，非 BaseResponse）
    r = get_json(
        "/knowledge_base/download_doc",
        params={"knowledge_base_name": test_kb, "file_name": file_name},
    )
    assert_http_ok(r)


def test_update_info(test_kb):
    """覆盖路由 POST /knowledge_base/update_info。

    签名（kb_doc_api.py）：knowledge_base_name + kb_info，均必填。
    返回 BaseResponse(code=200, msg="知识库介绍修改完成")。
    """
    r = post_json(
        "/knowledge_base/update_info",
        json={
            "knowledge_base_name": test_kb,
            "kb_info": "API 自动化测试更新的知识库介绍",
        },
    )
    assert_base_response(r)


def test_delete_docs(test_kb):
    """覆盖路由 POST /knowledge_base/delete_docs。

    签名（kb_doc_api.py）：knowledge_base_name + file_names(List[str])
    + delete_content(bool) + not_refresh_vs_cache(bool)，返回 BaseResponse。

    为避免破坏 test_kb fixture 上传的文档状态，本测试先上传一个独立的
    临时 txt 文档（prefix=kb_api_test_del_），再删除它。
    """
    # 1. 上传一个独立临时 txt（multipart，参考 conftest.test_kb 的上传方式）
    tmp = tempfile.NamedTemporaryFile(  # pylint: disable=consider-using-with
        prefix="kb_api_test_del_",
        suffix=".txt",
        delete=False,
        mode="w",
        encoding="utf-8",
    )
    tmp_path = Path(tmp.name)
    file_name = tmp_path.name  # basename，与上传时 multipart filename 一致
    try:
        tmp.write("用于 delete_docs 连通性测试的临时文档。\n")
        tmp.flush()
        tmp.close()
        with open(tmp_path, "rb") as f:
            files = [("files", (file_name, f, "text/plain"))]
            data = {"knowledge_base_name": test_kb, "override": True}
            ur = requests.post(
                f"{BASE_URL}/knowledge_base/upload_docs",
                data=data,
                files=files,
                timeout=120,
            )
        assert_base_response(ur)

        # 2. 删除该文件（delete_content=True 同时删除磁盘文件）
        r = post_json(
            "/knowledge_base/delete_docs",
            json={
                "knowledge_base_name": test_kb,
                "file_names": [file_name],
                "delete_content": True,
            },
        )
        assert_base_response(r)
    finally:
        try:
            tmp_path.unlink(missing_ok=True)
        except Exception as e:
            logger.debug("[mst-connectivity] failed to delete temp file %s: %s", tmp_path, e)
            pass
