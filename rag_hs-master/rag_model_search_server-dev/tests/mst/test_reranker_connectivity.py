"""qwen3-rerank 模型接口连通性测试。

从 configs.RERANKER_API_CONFIG 读取 qwen3-rerank 配置，构造 Qwen3Reranker，
真实调用 DashScope TextReRank 接口验证：
- 配置完整性（api_key/api_base_url/model_id 非空）
- health_check 探活（一次最小重排请求）
- compress_documents 真实重排（返回相关性分数 + 降序 + 无关文档排后）
- score 单文档评分（相关 query 评分高于无关 query）

需 dashscope SDK + --run-llm 才运行真实调用；配置完整性测试不联网。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

import pytest
from langchain_core.documents import Document

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

# dashscope 未安装则整模块跳过（Qwen3Reranker 构造时懒加载，调用时才需要）
pytest.importorskip("dashscope")

from configs import RERANKER_API_CONFIG, RERANKER_MODEL, USE_RERANKER, logger  # noqa: E402
from server.reranker_workers.qwen3_reranker import Qwen3Reranker  # noqa: E402

_MODEL_NAME = "qwen3-rerank"


@pytest.fixture(scope="module")
def qwen3_reranker():
    """从 configs 读取 qwen3-rerank 配置，构造 Qwen3Reranker 实例。"""
    if not USE_RERANKER:
        pytest.skip("USE_RERANKER=False，reranker 未启用")
    if RERANKER_MODEL != _MODEL_NAME:
        pytest.skip(f"当前 RERANKER_MODEL={RERANKER_MODEL}，非 {_MODEL_NAME}")
    cfg = RERANKER_API_CONFIG.get(_MODEL_NAME)
    if not cfg:
        pytest.skip(f"RERANKER_API_CONFIG 未配置 {_MODEL_NAME}")

    logger.info("\n[reranker] 端点: {}", cfg.get('api_base_url'))
    logger.info("[reranker] model_id: {}", cfg.get('model_id'))
    logger.info("[reranker] top_n: {} max_workers: {}", cfg.get('top_n'), cfg.get('max_workers'))

    return Qwen3Reranker(
        api_base_url=cfg["api_base_url"],
        api_key=cfg.get("api_key", ""),
        model_id=cfg.get("model_id", _MODEL_NAME),
        instruct=cfg.get("instruct", ""),
        return_documents=cfg.get("return_documents", True),
        timeout=cfg.get("timeout", 60),
        top_n=cfg.get("top_n", 5),
        max_workers=cfg.get("max_workers", 8),
    )


def test_config_integrity():
    """配置完整性：api_key/api_base_url/model_id 非空（不联网）。"""
    cfg = RERANKER_API_CONFIG.get(_MODEL_NAME)
    assert cfg, f"RERANKER_API_CONFIG 未配置 {_MODEL_NAME}"
    assert cfg.get("api_key"), "api_key 为空"
    assert cfg.get("api_base_url"), "api_base_url 为空"
    assert cfg.get("model_id"), "model_id 为空"
    assert cfg.get("api_base_url", "").endswith("/v1"), (
        "api_base_url 应以 /compatible-api/v1 结尾"
    )


def test_health_check(qwen3_reranker, require_llm):
    """health_check 探活：DashScope 应返回 200。"""
    ok = qwen3_reranker.health_check()
    logger.info("[reranker] health_check: {}", ok)
    assert ok is True


def test_compress_documents(qwen3_reranker, require_llm):
    """compress_documents 真实重排：返回非空、写入 relevance_score、降序、无关文档排后。"""
    _RELEVANCE_SCORE_KEY = 'relevance_score'
    docs = [
        Document(page_content="海思 CV610 芯片开发环境搭建指南", metadata={}),
        Document(page_content="今天天气不错，适合户外运动", metadata={}),
        Document(page_content="reg-tools 工具用于芯片寄存器读写与调试", metadata={}),
    ]
    result = qwen3_reranker.compress_documents(docs, "芯片开发工具")

    assert len(result) > 0
    assert _RELEVANCE_SCORE_KEY in result[0].metadata

    scores = [d.metadata[_RELEVANCE_SCORE_KEY] for d in result]
    assert scores == sorted(scores, reverse=True), f"未按降序: {scores}"

    for d in result:
        logger.info("[reranker] score={:.4f} content={}", d.metadata[_RELEVANCE_SCORE_KEY], d.page_content[:30])

    # 无关文档（天气）应排在最后或被 top_n 过滤掉
    weather_doc = next(
        (d for d in result if "天气" in d.page_content), None
    )
    if weather_doc is not None:
        assert weather_doc is result[-1], "无关文档未排在最后"


def test_score(qwen3_reranker, require_llm):
    """score 单文档评分：相关 query 评分应高于无关 query。"""
    doc = "reg-tools 工具用于海思芯片寄存器读写与调试"
    rel = qwen3_reranker.score("芯片寄存器调试工具", doc)
    irr = qwen3_reranker.score("今天午餐吃什么", doc)

    logger.info("[reranker] 相关 query 评分: {}", rel)
    logger.info("[reranker] 无关 query 评分: {}", irr)

    assert isinstance(rel, float)
    assert isinstance(irr, float)
    assert rel > irr, f"相关评分({rel})应高于无关评分({irr})"
