"""其它接口连通性测试（Task 7）。

覆盖路由：
- POST /other/embed_texts：将文本向量化，返回 BaseResponse(data=List[List[float]])
- POST /other/completion：LLM 文本补全，SSE 流式响应（依赖 LLM）
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from tests.mst.conftest import BASE_URL, assert_base_response, assert_http_ok, get_json, post_json

# 统一使用火山引擎 doubao 模型
MODEL_NAME = "doubao-1.5-pro-32k-api"


def test_embed_texts():
    """POST /other/embed_texts：对两段文本向量化，data 应为长度 2 的向量列表。

    依赖在线 embedding 模型 qwen3-embedding-api（配置在 model_config.yaml，
    需网络）。已知服务端功能缺口：server/embeddings_api.py 的 embed_texts
    仅支持本地 embedding 模型（list_embed_models() 返回
    MODEL_PATH["embed_model"]），而 qwen3-embedding-api 配置在
    EMBEDDING_API_CONFIG（在线 HTTP API 模型），不在 list_embed_models() 中，
    故返回 code=500, msg 含"不支持"。

    此缺口需用户决策是否扩展 embed_texts 支持在线模型（不要擅自改服务端），
    本测试以断言式记录该缺口。详见 tests/api/question.md。
    """
    r = post_json("/other/embed_texts",
                  json={"texts": ["你好", "世界"],
                        "embed_model": "qwen3-embedding-api",
                        "to_query": False})
    body = r.json()
    assert body.get("code") == 500, (
        f"预期 code=500（功能缺口），实际 code={body.get('code')} "
        f"msg={body.get('msg')}"
    )
    assert "不支持" in body.get("msg", ""), (
        f"预期 msg 含'不支持'，实际 msg={body.get('msg')}"
    )


def test_completion(require_llm):
    """POST /other/completion：LLM 文本补全（SSE 流式响应）。

    路由：server/api.py -> app.post("/kw/other/completion")(completion)
    completion 是 async 函数，返回 EventSourceResponse；completion_iterator
    在 stream=False 时累加所有 token 后 yield 整段 answer（纯文本，非 JSON）。
    故仅断言 HTTP 200 且至少收到一个非空 SSE data 行。
    """
    body = {
        "query": "恼羞成怒",
        "stream": False,
        "model_name": MODEL_NAME,
        "temperature": 0.7,
        "max_tokens": 64,
    }
    r = requests.post(f"{BASE_URL}/other/completion", json=body,
                      stream=True, timeout=120)
    assert_http_ok(r)
    # 收集 SSE data 行（data 为补全文本，非 JSON）
    text_parts = []
    for line in r.iter_lines(decode_unicode=True):
        if not line:
            continue
        if line.startswith("data:"):
            payload = line[len("data:"):].strip()
            if payload:
                text_parts.append(payload)
    assert text_parts, "未收到任何 SSE data 事件"
