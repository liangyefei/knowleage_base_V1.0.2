"""Chat / LLM 依赖连通性测试（Task 8）。

覆盖路由：
- POST /chat/chat                普通对话（SSE，data 含 text/message_id）
- POST /chat/next_question       预测下一问题（SSE，data 含 text=问题列表）
- POST /chat/feedback            聊天反馈（BaseResponse，仅写 DB 不调 LLM）
- POST /chat/file_chat           临时文件对话（SSE，data 含 answer/docs）

除 feedback 外，均依赖 LLM（火山引擎 doubao），通过 require_llm fixture
在未传 --run-llm 时自动 skip。feedback 仅写 DB 不调 LLM，默认运行。
"""
# pylint: disable=no-use-sys-path-insert
import json
import sys
import tempfile
from pathlib import Path

import pytest
import requests

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
from tests.mst.conftest import (
    BASE_URL,
    assert_base_response,
    assert_http_ok,
    get_json,
    post_json,
)

# 统一使用火山引擎 doubao 模型
MODEL_NAME = "doubao-1.5-pro-32k-api"
# 测试用 user_id（后端 /chat/* 接口要求必填，用于多租户隔离）
TEST_USER_ID = "test_user_001"


def _parse_sse(response):
    """从 requests 流式响应收集 data: 行，返回 JSON 解析后的对象列表。

    每个 SSE 事件形如 `data: {json}`，逐行读取 iter_lines，
    把每段 data 反序列化后收集到列表返回。
    解析失败的行保留原始字符串以便调试。
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


def _sse_post(path, body, timeout=180):
    """以 SSE 方式发起 POST 请求，返回 (response, parsed_chunks)。

    直接用 requests.post(..., stream=True) 读取 iter_lines，
    并断言 HTTP 200 且至少收到一个可解析的 SSE 事件。
    """
    r = requests.post(f"{BASE_URL}{path}", json=body, stream=True, timeout=timeout)
    assert r.status_code == 200, f"HTTP {r.status_code}: {r.text[:200]}"
    chunks = _parse_sse(r)
    return r, chunks


# ---------- Chat / LLM 依赖测试 ----------


def test_chat(require_llm):
    """POST /chat/chat：普通对话，验证 SSE 可解析出非空 text 字段。

    路由：server/api.py -> app.post("/kw/chat/chat")(chat)
    SSE data 形如 {"text": "...", "message_id": "..."}。
    """
    body = {
        "query": "你好",
        "user_id": TEST_USER_ID,
        "stream": False,
        "model_name": MODEL_NAME,
        "temperature": 0.7,
    }
    _, chunks = _sse_post("/chat/chat", body)
    assert chunks, "未收到任何 SSE data 事件"
    last = chunks[-1]
    assert isinstance(last, dict), f"最后一个 SSE 事件非 JSON 对象: {last!r}"
    assert "text" in last, f"SSE 事件缺少 text 字段: {last!r}"
    assert last["text"], f"text 字段为空: {last!r}"


def test_next_question(require_llm):
    """POST /chat/next_question：预测下一问题，验证 SSE 可解析出 text 字段。

    路由：server/api.py -> app.post("/kw/chat/next_question")(next_question)
    SSE data 形如 {"text": [问题列表]}。
    """
    body = {
        "query": "海思的总部在哪里？",
        "history": [],
        "predict_number": 3,
        "model_name": MODEL_NAME,
    }
    _, chunks = _sse_post("/chat/next_question", body)
    assert chunks, "未收到任何 SSE data 事件"
    last = chunks[-1]
    assert isinstance(last, dict), f"最后一个 SSE 事件非 JSON 对象: {last!r}"
    assert "text" in last, f"SSE 事件缺少 text 字段: {last!r}"
    # text 通常是问题列表（可能为空列表，但 key 必须存在）


def test_feedback():
    """POST /chat/feedback：聊天反馈，返回 BaseResponse。

    路由：server/api.py -> app.post("/kw/chat/feedback")(chat_feedback)
    feedback 不调用 LLM，仅写 message db，因此默认运行（不请求 require_llm）。
    message_id 可为任意字符串，feedback_message_to_db 会处理不存在的 id。
    """
    body = {
        "message_id": "test_msg_001",
        "user_id": TEST_USER_ID,
        "score": 80,
        "reason": "测试反馈",
    }
    r = post_json("/chat/feedback", json=body)
    assert_base_response(r)


def test_file_chat(require_llm):
    """POST /chat/file_chat：临时文件对话，验证 SSE 可解析。

    路由：server/api.py -> app.post("/kw/chat/file_chat")(file_chat)
    file_chat 需要先调用 /knowledge_base/upload_temp_docs 上传临时文件获取
    knowledge_id，再以 JSON body 调用 /chat/file_chat。
    file_chat signature（见 server/chat/file_chat.py）：
        query, knowledge_id, top_k, score_threshold, history, stream,
        model_name, temperature, max_tokens, prompt_name
    upload_temp_docs signature：files(List[UploadFile]), prev_id, chunk_size,
        chunk_overlap, zh_title_enhance（multipart form）。
    SSE data 形如 {"answer": "..."} / {"docs": [...]}。
    """
    # 1. 上传临时文档，获取 knowledge_id
    tmp = tempfile.NamedTemporaryFile(  # pylint: disable=consider-using-with
        prefix="file_chat_test_",
        suffix=".txt",
        delete=False,
        mode="w",
        encoding="utf-8",
    )
    tmp_path = Path(tmp.name)
    try:
        tmp.write("海思是一家半导体公司。本文件用于 file_chat 接口测试。\n")
        tmp.flush()
        tmp.close()
        with open(tmp_path, "rb") as f:
            files = [("files", (tmp_path.name, f, "text/plain"))]
            upload_r = requests.post(
                f"{BASE_URL}/knowledge_base/upload_temp_docs",
                files=files,
                timeout=120,
            )
        assert_base_response(upload_r)
        knowledge_id = upload_r.json()["data"]["id"]
        assert knowledge_id, "upload_temp_docs 未返回 id"
    finally:
        tmp_path.unlink(missing_ok=True)

    # 2. 调用 file_chat
    body = {
        "query": "这个文件讲了什么？",
        "knowledge_id": knowledge_id,
        "top_k": 5,
        "score_threshold": 1.0,
        "history": [],
        "stream": False,
        "model_name": MODEL_NAME,
        "temperature": 0.7,
    }
    _, chunks = _sse_post("/chat/file_chat", body)
    assert chunks, "未收到任何 SSE 事件"
    last = chunks[-1]
    assert isinstance(last, dict), f"最后一个 SSE 事件非 JSON 对象: {last!r}"
    assert "answer" in last or "docs" in last, (
        f"SSE 事件缺少 answer/docs 字段: {last!r}"
    )
