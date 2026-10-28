"""qwen3-rerank 接口探活脚本（绕过 pytest ensure_server_up，直接调 DashScope）。

用法（容器内）:
    python3 tests/scripts/probe_reranker.py
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from langchain_core.documents import Document

from configs import RERANKER_API_CONFIG, RERANKER_MODEL, USE_RERANKER, logger
from server.reranker_workers.qwen3_reranker import Qwen3Reranker

MODEL = "qwen3-rerank"

_MODEL_ID_KEY = 'model_id'


def main():
    _EXCEPTION_MSG_FMT = '  -> 异常: {}'
    logger.info("USE_RERANKER={}, RERANKER_MODEL={}", USE_RERANKER, RERANKER_MODEL)
    cfg = RERANKER_API_CONFIG.get(MODEL)
    if not cfg:
        logger.info("[FAIL] RERANKER_API_CONFIG 未配置 {}", MODEL)
        sys.exit(1)

    logger.info("端点: {}", cfg.get('api_base_url'))
    logger.info("model_id: {}, top_n={}, max_workers={}", cfg.get(_MODEL_ID_KEY), cfg.get('top_n'), cfg.get('max_workers'))

    # 诊断：dashscope SDK 默认端点（不设 base_http_api_url）直接调用，对比是否端点配置问题
    import dashscope
    logger.info("\n[诊断] dashscope {} 默认端点直接调用...", getattr(dashscope, '__version__', '?'))
    dashscope.api_key = cfg.get("api_key", "")
    # 故意不设 base_http_api_url，用 SDK 默认端点
    try:
        resp = dashscope.TextReRank.call(
            model=cfg.get(_MODEL_ID_KEY, MODEL),
            query="ping",
            documents=["pong"],
            top_n=1,
            return_documents=False,
        )
        logger.info("  status_code={}", resp.status_code)
        if resp.status_code == 200:
            logger.info("  -> 默认端点 OK（说明 base_http_api_url 设成 /compatible-api/v1 是错的）")
        else:
            logger.info("  code={} message={}", getattr(resp, 'code', None), getattr(resp, 'message', None))
    except Exception as e:
        logger.info("  异常: {}", e)

    r = Qwen3Reranker(
        api_base_url=cfg["api_base_url"],
        api_key=cfg.get("api_key", ""),
        model_id=cfg.get(_MODEL_ID_KEY, MODEL),
        instruct=cfg.get("instruct", ""),
        return_documents=cfg.get("return_documents", True),
        timeout=cfg.get("timeout", 60),
        top_n=cfg.get("top_n", 5),
        max_workers=cfg.get("max_workers", 8),
    )

    logger.info("\n[1/3] health_check...")
    try:
        ok = r.health_check()
        logger.info("  -> {}", 'OK' if ok else 'FAIL')
        if not ok:
            logger.info("  健康检查失败，后续测试可能失败，继续尝试...")
    except Exception as e:
        logger.info(_EXCEPTION_MSG_FMT, e)

    logger.info("\n[2/3] compress_documents（3 个文档，query=芯片开发工具）...")
    docs = [
        Document(page_content="海思 CV610 芯片开发环境搭建指南", metadata={}),
        Document(page_content="今天天气不错，适合户外运动", metadata={}),
        Document(page_content="reg-tools 工具用于芯片寄存器读写与调试", metadata={}),
    ]
    try:
        result = r.compress_documents(docs, "芯片开发工具")
        logger.info("  -> 返回 {} 个文档:", len(result))
        for d in result:
            logger.info("     score=%.4f {}", d.metadata.get('relevance_score', 0), d.page_content[:30])
    except Exception as e:
        logger.info(_EXCEPTION_MSG_FMT, e)

    logger.info("\n[3/3] score（单文档，相关 vs 无关 query）...")
    doc = "reg-tools 工具用于海思芯片寄存器读写与调试"
    try:
        rel = r.score("芯片寄存器调试工具", doc)
        irr = r.score("今天午餐吃什么", doc)
        logger.info("  相关 query 评分: {}", rel)
        logger.info("  无关 query 评分: {}", irr)
        logger.info("  -> {}", 'OK (相关>无关)' if rel > irr else 'WARN (相关<=无关)')
    except Exception as e:
        logger.info(_EXCEPTION_MSG_FMT, e)

    logger.info("\n探活完成.")


if __name__ == "__main__":
    main()