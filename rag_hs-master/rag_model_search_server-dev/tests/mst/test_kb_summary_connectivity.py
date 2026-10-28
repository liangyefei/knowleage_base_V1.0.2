"""KB 摘要 API 连通性测试（Task 6）。

依赖 tests/api/conftest.py 的 test_kb fixture + 已上传文档。
所有摘要接口本质都需要 LLM（调用火山引擎 doubao 做文本摘要），
故每个 test 函数都请求 require_llm fixture：未传 --run-llm 时自动 skip。

覆盖路由：
- POST /knowledge_base/kb_summary_api/summary_file_to_vector_store (SSE 流)
- POST /knowledge_base/kb_summary_api/summary_doc_ids_to_vector_store (BaseResponse)
- POST /knowledge_base/kb_summary_api/recreate_summary_vector_store (SSE 流)
"""
# pylint: disable=no-use-sys-path-insert
import json
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
from tests.mst.conftest import BASE_URL, assert_base_response, assert_http_ok, get_json, post_json


def _parse_sse(response):
    """从 requests 流式响应收集 data: 行，返回 JSON 解析后的对象列表。

    kb_summary_api 的 output() 通过 yield json.dumps({...}) 发送事件，
    故每个 data 行都是合法 JSON 字符串。解析失败的行保留原始字符串以便调试。
    """
    chunks = []
    for line in response.iter_lines(decode_unicode=True):
        if not line:
            continue
        if line.startswith("data:"):
            payload = line[len("data:"):].strip()
            if not payload:
                continue
            try:
                chunks.append(json.loads(payload))
            except json.JSONDecodeError:
                chunks.append(payload)
    return chunks


def _sse_post(path, body, timeout=300):
    """以 SSE 方式发起 POST 请求，返回 (response, parsed_chunks)。

    摘要接口可能较慢（需调用 LLM），默认 timeout=300s。
    """
    r = requests.post(f"{BASE_URL}{path}", json=body, stream=True, timeout=timeout)
    assert r.status_code == 200, f"HTTP {r.status_code}: {r.text[:200]}"
    chunks = _parse_sse(r)
    return r, chunks


def test_summary_file_to_vector_store(test_kb, require_llm):
    """覆盖路由 POST /knowledge_base/kb_summary_api/summary_file_to_vector_store。

    签名（kb_summary_api.py）：knowledge_base_name + file_name（均必填）
    + allow_empty_kb/vs_type/embed_model/file_description/model_name/
    temperature/max_tokens 等可选参数。

    file_name 需为知识库中实际存在的文件，故先调用 list_files 获取
    fixture 上传的临时 txt 文件名（带随机后缀）。

    该接口返回 EventSourceResponse（SSE 流），data 为 json.dumps 的
    {"code":200,"msg":"... 总结完成","doc":file_name} 字符串。
    断言至少收到一个 SSE 事件且最后一个事件含 code 字段。
    """
    # 先获取 fixture 上传的临时 txt 文件名
    lr = get_json(
        "/knowledge_base/list_files",
        params={"knowledge_base_name": test_kb},
    )
    assert_base_response(lr)
    file_list = lr.json().get("data")
    assert isinstance(file_list, list) and len(file_list) > 0, (
        f"list_files 返回空列表，无法获取摘要目标文件名: {lr.text[:200]}"
    )
    file_name = file_list[0]

    _, chunks = _sse_post(
        "/knowledge_base/kb_summary_api/summary_file_to_vector_store",
        body={
            "knowledge_base_name": test_kb,
            "file_name": file_name,
        },
    )
    assert chunks, "未收到任何 SSE data 事件"
    last = chunks[-1]
    assert isinstance(last, dict), f"最后一个 SSE 事件非 JSON 对象: {last!r}"
    assert "code" in last, f"SSE 事件缺少 code 字段: {last!r}"


def test_summary_doc_ids_to_vector_store(test_kb, require_llm):
    """覆盖路由 POST /knowledge_base/kb_summary_api/summary_doc_ids_to_vector_store。

    签名（kb_summary_api.py）：knowledge_base_name + doc_ids(List，默认 [])
    + vs_type/embed_model/file_description/model_name/temperature/max_tokens
    等可选参数。返回 BaseResponse（非 SSE），response_model=BaseResponse。

    doc_ids 传空列表：内部 get_doc_by_ids 返回空，summarize 处理空 docs，
    最终返回 BaseResponse(code=200, msg="总结完成", data={"summarize": []})。
    但若 LLM 未配置，get_ChatOpenAI 可能在返回前抛异常导致 HTTP 500。
    """
    r = post_json(
        "/knowledge_base/kb_summary_api/summary_doc_ids_to_vector_store",
        json={
            "knowledge_base_name": test_kb,
            "doc_ids": [],
        },
    )
    assert_base_response(r)


def test_recreate_summary_vector_store(test_kb, require_llm):
    """覆盖路由 POST /knowledge_base/kb_summary_api/recreate_summary_vector_store。

    签名（kb_summary_api.py）：knowledge_base_name（必填）
    + allow_empty_kb(默认 True)/vs_type/embed_model/file_description/
    model_name/temperature/max_tokens 等可选参数。

    该接口返回 EventSourceResponse（SSE 流），data 为 json.dumps 的
    {"code":200,"msg":"(i/n): file","total":n,"finished":i,"doc":file}
    字符串。断言至少收到一个 SSE 事件且最后一个事件含 code 字段。
    """
    _, chunks = _sse_post(
        "/knowledge_base/kb_summary_api/recreate_summary_vector_store",
        body={
            "knowledge_base_name": test_kb,
        },
    )
    assert chunks, "未收到任何 SSE data 事件"
    last = chunks[-1]
    assert isinstance(last, dict), f"最后一个 SSE 事件非 JSON 对象: {last!r}"
    assert "code" in last, f"SSE 事件缺少 code 字段: {last!r}"
