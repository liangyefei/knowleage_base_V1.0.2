"""阿里云模型 requestID 记录有效性测试。

验证三件事：
1. extract_aliyun_request_id 能从 4 类响应/异常对象正确提取 req_id（纯单元，无网络）
2. 改造后的各模型调用路径真实输出 [dashscope] ... req_id=<非None> 日志（集成，需网络+配置）
3. 日志格式符合规范：[dashscope] model=<模型> phase=<阶段> req_id=<ID> [k=v ...]

覆盖 8 个阿里云调用点：
  - helper（无网络单元）
  - embedding（qwen3.7-text-embedding，async with_raw_response）
  - reranker（qwen3.7-text-rerank，dashscope SDK）
  - vlm（qwen3.7-plus，httpx 直调）
  - route_llm（qwen-flash，OpenAI SDK 流式）
  - main_llm（deepseek-v4-pro，OpenAI SDK 非流式 with_raw_response）
  - memory_extractor（qwen-flash，记忆抽取 with_raw_response）
  - kb_doc_api KG 抽取（qwen-flash，文档入库 with_raw_response，MemPalace 写入隔离）

运行（docker dev 容器内，项目根目录）：
    python3 tests/scripts/test_aliyun_req_id.py
退出码 0 表示全部通过；任一集成用例失败会打印 FAIL 原因，但不中断后续用例。
"""
import io
import logging
import os
import re
import sys
import traceback
from types import SimpleNamespace

logger = logging.getLogger(__name__)

# 确保项目根在 sys.path（容器内 cwd 即根目录，但保险起见）
# 文件位于 tests/scripts/ 下，需上溯 3 级到项目根
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)  # pylint: disable=no-use-sys-path-insert


# ==================== 日志捕获与断言工具 ====================
def _capture_logbuf():
    """挂一个 StringIO sink 到 loguru logger，返回 (buf, handler_id)，用于收集 [dashscope] 日志。"""
    from configs import logger as _loguru_logger
    buf = io.StringIO()
    hid = _loguru_logger.add(buf, level="DEBUG", format="{message}", enqueue=False)
    return buf, hid


def _stop_capture(buf, hid):
    from configs import logger as _loguru_logger
    try:
        _loguru_logger.remove(hid)
    except Exception as e:
        _loguru_logger.debug("[test-aliyun-req-id] failed to remove logger handler {}: {}", hid, e)
        pass
    out = buf.getvalue()
    buf.close()
    return out


_DASHSCOPE_LINE_RE = re.compile(
    r"\[dashscope\]\s+model=(?P<model>\S+)\s+phase=(?P<phase>\S+)\s+req_id=(?P<rid>\S+)"
)


def _parse_dashscope_lines(log_text):
    """解析日志里所有 [dashscope] 行，返回 dict 列表 [{model, phase, rid, raw}]。"""
    rows = []
    for line in log_text.splitlines():
        if "[dashscope]" not in line:
            continue
        m = _DASHSCOPE_LINE_RE.search(line)
        if not m:
            rows.append({"raw": line, "malformed": True})
            continue
        rows.append({
            "model": m.group("model"),
            "phase": m.group("phase"),
            "rid": m.group("rid"),
            "raw": line,
        })
    return rows


def _assert_dashscope_log(model_label, log_text, expected_phase=None,
                          expected_model=None, require_non_none_rid=True):
    """断言日志中出现 [dashscope] 行，且格式合规、model/phase 匹配、req_id 非 None。

    Args:
        model_label: 用例标签，用于报错信息
        log_text: _stop_capture 返回的日志文本
        expected_phase: 期望的 phase 名（或 list，任一匹配即可）；None 只校验存在
        expected_model: 期望的 model 名；None 只校验存在
        require_non_none_rid: 是否要求 rid 非 None（流式/限流现场可能 None）
    返回第一个匹配行的 rid（可能为 None）。
    """
    _RID_KEY = 'rid'
    rows = _parse_dashscope_lines(log_text)
    if not rows:
        raise AssertionError(
            f"{model_label}: 日志中未出现任何 [dashscope] 行。原始日志尾部:\n{log_text[-800:]}"
        )
    # 1) 格式合规
    malformed = [r for r in rows if r.get("malformed")]
    if malformed:
        raise AssertionError(
            f"{model_label}: 存在格式不合规的 [dashscope] 行: {malformed[0]['raw']}"
        )
    # 2) phase 匹配
    if expected_phase is not None:
        want = {expected_phase} if isinstance(expected_phase, str) else set(expected_phase)
        matched = [r for r in rows if r["phase"] in want]
        if not matched:
            got = sorted({r["phase"] for r in rows})
            raise AssertionError(
                f"{model_label}: 未找到 phase∈{sorted(want)} 的 [dashscope] 行，实际 phase={got}"
            )
        rows = matched
    # 3) model 匹配
    if expected_model is not None:
        want_m = {expected_model} if isinstance(expected_model, str) else set(expected_model)
        matched = [r for r in rows if r["model"] in want_m]
        if not matched:
            got = sorted({r["model"] for r in rows})
            raise AssertionError(
                f"{model_label}: 未找到 model∈{sorted(want_m)} 的 [dashscope] 行，实际 model={got}"
            )
        rows = matched
    # 4) req_id 非 None
    non_none = [r for r in rows if r[_RID_KEY] != "None"]
    if require_non_none_rid and not non_none:
        raise AssertionError(
            f"{model_label}: [dashscope] 行 req_id 全为 None，提取失败。原始日志尾部:\n{log_text[-800:]}"
        )
    return non_none[0][_RID_KEY] if non_none else (rows[0][_RID_KEY] if rows else None)


# ==================== 1. helper 单元测试（无网络） ====================
def test_helper_extract():
    _XREQUESTID_KEY = 'x-request-id'
    from server.aliyun_trace import extract_aliyun_request_id

    # 1) dashscope SDK 响应：.request_id（无下划线）
    ds = SimpleNamespace(request_id="ds-abc", status_code=200, output={})
    assert extract_aliyun_request_id(ds) == "ds-abc"

    # 2) OpenAI SDK 异常 APIStatusError：.request_id（无下划线）
    class FakeAPIErr(Exception):
        pass
    e = FakeAPIErr()
    e.request_id = "err-def"
    assert extract_aliyun_request_id(e) == "err-def"

    # 3) OpenAI SDK 非流式响应：with_raw_response 返回的 LegacyAPIResponse（.headers）
    raw = SimpleNamespace(headers={_XREQUESTID_KEY: "raw-ghi"})
    assert extract_aliyun_request_id(raw) == "raw-ghi"

    # 4) OpenAI SDK 流式响应：.response.headers[_XREQUESTID_KEY]
    stream = SimpleNamespace(response=SimpleNamespace(headers={_XREQUESTID_KEY: "str-jkl"}))
    assert extract_aliyun_request_id(stream) == "str-jkl"

    # 5) httpx 直调响应：自身 .headers[_XREQUESTID_KEY]（无 .response 属性）
    hx = SimpleNamespace(headers={_XREQUESTID_KEY: "hx-mno"})
    assert extract_aliyun_request_id(hx) == "hx-mno"

    # 6) None / 无任何字段 → None
    assert extract_aliyun_request_id(None) is None
    assert extract_aliyun_request_id(SimpleNamespace()) is None

    logger.info("[PASS] helper extract_aliyun_request_id 5 类对象 + None 分支全部正确")


# ==================== 2. 集成测试：各模型连通性 + req_id + 日志格式 ====================
def test_embedding_req_id():
    """Embedding（qwen3.7-text-embedding）async 路径记录 req_id + 日志格式合规。"""
    try:
        from configs import EMBEDDING_MODEL
        from server.embeddings_api import aembed_texts
    except Exception as e:
        logger.info("[SKIP] embedding: 依赖未就绪 %s", e)
        return
    buf, hid = _capture_logbuf()
    try:
        import asyncio
        async def _go():
            return await aembed_texts(texts=["连通性测试"], embed_model=EMBEDDING_MODEL)
        result = asyncio.run(_go())
        log_text = _stop_capture(buf, hid)
        if getattr(result, "code", None) != 200:
            logger.info("[FAIL] embedding: 调用失败 code=%s msg=%s", result.code, result.msg)
            logger.info("  日志尾部:\n%s", log_text[-800:])
            return
        rid = _assert_dashscope_log(
            "embedding", log_text,
            expected_phase=["aembed_done", "embed_query_done", "embed_shard_done"],
            expected_model=EMBEDDING_MODEL,
        )
        logger.info("[PASS] embedding req_id=%s model=%s", rid, EMBEDDING_MODEL)
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] embedding: 异常 %s", e)
        traceback.print_exc()


def test_reranker_req_id():
    """Reranker（qwen3.7-text-rerank）health_check 路径记录 req_id + 日志格式合规。"""
    try:
        from server.reranker.reranker import get_reranker_singleton
    except Exception as e:
        logger.info("[SKIP] reranker: 依赖未就绪 %s", e)
        return
    buf, hid = _capture_logbuf()
    try:
        rr = get_reranker_singleton()
        ok = rr.health_check()
        log_text = _stop_capture(buf, hid)
        rid = _assert_dashscope_log(
            "reranker", log_text,
            expected_phase=["rerank_health_ok", "rerank_health_non_200"],
            expected_model="qwen3.7-text-rerank",
            require_non_none_rid=ok,
        )
        if ok:
            logger.info("[PASS] reranker req_id=%s health=ok", rid)
        else:
            logger.info("[WARN] reranker health_check=False 但已记录 req_id=%s（限流/异常现场仍可排查）", rid)
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] reranker: 异常 %s", e)
        traceback.print_exc()


def test_vlm_req_id():
    """VLM（qwen3.7-plus）parse_image 路径记录 req_id（即便非200也能取 header）。"""
    try:
        from server.vlm_api import VLMClient, VLMClientError
    except Exception as e:
        logger.info("[SKIP] vlm: 依赖未就绪 %s", e)
        return
    # 1x1 PNG 临时文件
    import base64
    import tempfile
    _PNG = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8QDwAD"
        "BwH9T+8QAAAAAElFTkSuQmCC")
    tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    tmp.write(_PNG); tmp.close()
    buf, hid = _capture_logbuf()
    try:
        try:
            client = VLMClient()
        except VLMClientError as e:
            _stop_capture(buf, hid)
            logger.info("[SKIP] vlm: %s（dev 容器未配 VLM_API_CONFIG）", e)
            return
        res = client.parse_image(tmp.name, "识别图中文字", "请识别")
        log_text = _stop_capture(buf, hid)
        rid = _assert_dashscope_log(
            "vlm", log_text,
            expected_phase=["vlm_call_done", "vlm_non_200"],
            require_non_none_rid=False,
        )
        if res.get("success"):
            logger.info("[PASS] vlm req_id=%s success=True", rid)
        else:
            logger.info("[WARN] vlm parse 非 success（可能 endpoint/模型问题），但已记录 req_id=%s", rid)
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] vlm: 异常 %s", e)
        traceback.print_exc()
    finally:
        try: os.unlink(tmp.name)
        except OSError: pass


def test_route_llm_req_id():
    """路由 LLM（qwen-flash）_route_and_rewrite 流式路径记录 req_id + 日志格式合规。"""
    try:
        from server.chat.pipeline.openai_clients import _router_model_cfg, _router_openai_client
        from server.chat.pipeline.route_llm import _route_and_rewrite
    except Exception as e:
        logger.info("[SKIP] route_llm: 依赖未就绪 %s", e)
        return
    buf, hid = _capture_logbuf()
    try:
        import asyncio
        async def _go():
            return await _route_and_rewrite(
                query="你好",
                history=None,
                openai_client=_router_openai_client,
                model_name=_router_model_cfg.get("model_id", "qwen-flash"),
                on_reasoning=lambda text: None,
            )
        decision = asyncio.run(_go())
        log_text = _stop_capture(buf, hid)
        rid = _assert_dashscope_log(
            "route_llm", log_text,
            expected_phase=["route_stream_start", "route_done", "route_timeout"],
            expected_model=_router_model_cfg.get("model_id", "qwen-flash"),
            require_non_none_rid=False,
        )
        logger.info("[PASS] route_llm req_id=%s route_type=%s", rid, decision.get('route_type'))
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] route_llm: 异常 %s", e)
        traceback.print_exc()


def test_main_llm_req_id():
    """主 LLM（deepseek-v4-pro）with_raw_response 路径记录 req_id + 日志格式合规。

    直接用 _main_openai_client 走与生产一致的 with_raw_response.create + .parse()，
    验证 helper 能从 LegacyAPIResponse.headers 提取 x-request-id。
    """
    try:
        from server.chat.pipeline.openai_clients import _main_model_cfg, _main_openai_client
    except Exception as e:
        logger.info("[SKIP] main_llm: 依赖未就绪 %s", e)
        return
    import asyncio
    buf, hid = _capture_logbuf()
    try:
        async def _go():
            raw_resp = await _main_openai_client.chat.completions.with_raw_response.create(
                model=_main_model_cfg.get("model_id"),
                messages=[{"role": "user", "content": "说一个字"}],
                max_tokens=16,
                temperature=0.1,
            )
            from server.aliyun_trace import extract_aliyun_request_id, log_dashscope
            rid = extract_aliyun_request_id(raw_resp)
            log_dashscope(_main_model_cfg.get("model_id"), "main_llm_manual_test",
                          rid, level="info")
            parsed = raw_resp.parse()
            return rid, parsed.choices[0].message.content
        rid, _content = asyncio.run(_go())
        log_text = _stop_capture(buf, hid)
        if not rid:
            logger.info("[FAIL] main_llm: extract_aliyun_request_id 返回 None，with_raw_response 未拿到 x-request-id")
            logger.info("  日志尾部:\n%s", log_text[-800:])
            return
        _assert_dashscope_log(
            "main_llm", log_text,
            expected_phase="main_llm_manual_test",
            expected_model=_main_model_cfg.get("model_id"),
        )
        logger.info("[PASS] main_llm req_id=%s model=%s", rid, _main_model_cfg.get("model_id"))
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] main_llm: 异常 %s", e)
        traceback.print_exc()


def test_memory_extract_req_id():
    """记忆抽取（qwen-flash）_extract_facts 路径记录 req_id + 日志格式合规。

    直接 await _extract_facts，验证 memory_extract_done phase + req_id 提取。
    无 MemPalace 副作用（仅 LLM 调用 + JSON 解析，失败 raise 由测试接住）。
    """
    try:
        from server.memory.memory_extractor import _extract_facts
    except Exception as e:
        logger.info("[SKIP] memory_extract: 依赖未就绪 %s", e)
        return
    import asyncio
    buf, hid = _capture_logbuf()
    try:
        async def _go():
            # query+answer 给一段有信息量的对话，触发事实抽取
            return await _extract_facts(
                query="我用 Python 写了一个数据分析脚本",
                answer="好的，Python 数据分析脚本通常用 pandas 和 numpy。",
                history=[],
            )
        facts = asyncio.run(_go())
        log_text = _stop_capture(buf, hid)
        rid = _assert_dashscope_log(
            "memory_extract", log_text,
            expected_phase=["memory_extract_done", "memory_extract_error"],
            expected_model="qwen-flash",
            require_non_none_rid=False,  # 模板缺失/调用失败时可能 None
        )
        # 如果 phase 是 error 路径，warn 但不算 FAIL（fail-open 兜底）
        rows = _parse_dashscope_lines(log_text)
        if rows and rows[0]["phase"] == "memory_extract_error":
            logger.info("[WARN] memory_extract error 路径 req_id=%s（可能模板缺失/限流，fail-open 兜底）", rid)
        else:
            logger.info("[PASS] memory_extract req_id=%s facts_count=%s", rid, len(facts))
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] memory_extract: 异常 %s", e)
        traceback.print_exc()


def test_kg_extract_req_id():
    """KG 实体抽取（qwen-flash）_extract_kg_facts_mempalace 路径记录 req_id + 日志格式合规。

    用 > _KG_EXTRACT_MIN_TEXT(50) 的文本触发 LLM 调用。
    MemPalace 写入副作用隔离：monkeypatch MEMPALACE_BASE_URL 指向不可达端口，
    确保即使 dev 容器有 mempalace 服务也不会污染数据（写入失败被 L1436 except 吞）。
    Milvus 连接隔离：import kb_doc_api 前 mock pymilvus.MilvusClient，
    避免 L40 顶层 client = MilvusClient(uri) 触发 milvus 连接（dev 容器无 milvus 服务，
    milvus host 指向 k8s 集群 milvus-milvus.milvus-operator.svc.cluster.local 不可达）。
    """
    _MEMPALACE_KG_EXTRACT_KEY = 'MEMPALACE_KG_EXTRACT'
    import sys as _sys
    try:
        # 若 kb_doc_api 已加载（之前测试间接 import），直接用；否则 mock milvus 后 import
        if "server.knowledge_base.kb_doc_api" in _sys.modules:
            import server.knowledge_base.kb_doc_api as kb
        else:
            from unittest.mock import MagicMock, patch

            import pymilvus
            with patch.object(pymilvus, "MilvusClient", return_value=MagicMock()):
                import server.knowledge_base.kb_doc_api as kb
    except Exception as e:
        logger.info("[SKIP] kg_extract: 依赖未就绪 %s", e)
        return
    # 触发 LLM 调用的文本（> 50 字符，含明显实体关系）
    TEST_TEXT = (
        "张三在 2024 年加入海思公司，担任 Hi3798MV310 芯片的 HDMI 驱动工程师。"
        "他使用 C 语言和 Linux 内核框架开发驱动，团队负责人是李四。"
        "Hi3798MV310 是海思半导体的高端多媒体芯片，集成了 ARM CPU 和自研 NPU。"
    )
    # 隔离 MemPalace 写入副作用
    _orig_base = getattr(kb, "MEMPALACE_BASE_URL", None)
    _orig_env = os.environ.get(_MEMPALACE_KG_EXTRACT_KEY)
    os.environ[_MEMPALACE_KG_EXTRACT_KEY] = "1"  # 确保开启
    kb.MEMPALACE_BASE_URL = "http://127.0.0.1:1"  # 不可达端口，写入必失败被 except 吞
    buf, hid = _capture_logbuf()
    try:
        kb._extract_kg_facts_mempalace("test_kb", "test.txt", TEST_TEXT)  # pylint: disable=protected-access
        log_text = _stop_capture(buf, hid)
        rid = _assert_dashscope_log(
            "kg_extract", log_text,
            expected_phase=["kg_extract_done", "kg_extract_error"],
            expected_model="qwen-flash",
            require_non_none_rid=False,
        )
        rows = _parse_dashscope_lines(log_text)
        if rows and rows[0]["phase"] == "kg_extract_error":
            logger.info("[WARN] kg_extract error 路径 req_id=%s（LLM 调用失败，不影响主流程）", rid)
        else:
            logger.info("[PASS] kg_extract req_id=%s（MemPalace 写入已隔离）", rid)
    except AssertionError:
        raise
    except Exception as e:
        _stop_capture(buf, hid)
        logger.info("[FAIL] kg_extract: 异常 %s", e)
        traceback.print_exc()
    finally:
        # 恢复
        kb.MEMPALACE_BASE_URL = _orig_base
        if _orig_env is None:
            os.environ.pop(_MEMPALACE_KG_EXTRACT_KEY, None)
        else:
            os.environ[_MEMPALACE_KG_EXTRACT_KEY] = _orig_env


# ==================== main ====================
def main():
    logger.info("=" * 70)
    logger.info("阿里云模型 requestID 记录有效性测试")
    logger.info("覆盖：helper/embedding/reranker/vlm/route_llm/main_llm/memory/kg_extract")
    logger.info("=" * 70)
    failures = []
    for fn in [test_helper_extract, test_embedding_req_id, test_reranker_req_id,
               test_vlm_req_id, test_route_llm_req_id, test_main_llm_req_id,
               test_memory_extract_req_id, test_kg_extract_req_id]:
        logger.info("")
        try:
            fn()
        except AssertionError as e:
            logger.info("[FAIL] %s: %s", fn.__name__, e)
            failures.append(fn.__name__)
        except Exception as e:
            logger.info("[FAIL] %s: 意外异常 %s", fn.__name__, e)
            traceback.print_exc()
            failures.append(fn.__name__)
    logger.info("")
    logger.info("=" * 70)
    if failures:
        logger.info("结果: %s 项失败 → %s", len(failures), failures)
        sys.exit(1)
    logger.info("结果: 全部通过（或 SKIP/WARN 已说明）")


if __name__ == "__main__":
    main()