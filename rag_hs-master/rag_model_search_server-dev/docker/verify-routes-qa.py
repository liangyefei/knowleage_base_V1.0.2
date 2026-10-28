"""QA 镜像路由清单验证：QA 模式应有问答路由，无入库专属路由。

与 verify-routes-ingestion.py 对称：用标准库 urllib（无外部依赖，ingestion/qa 镜像均可跑）。
路由归属依据 server/api.py 的 _enable_qa / _enable_ingest 分流：
  - 两端都挂：/server/configs(POST)、/health(GET)
  - qa 块：file_chat / feedback / list_knowledge_bases(GET) / llm_model/list_running_models(POST)
  - ingest 块（qa 不挂）：create_knowledge_base / upload_docs / upload_temp_docs / delete_docs
"""
import urllib.request
import urllib.error

from configs.logger_manager import logger

BASE = "http://127.0.0.1:7861/kw"

_GET_KEY = 'GET'

# 两端都挂的基础路由（qa 模式应存在 → 200/405，不应 404）
BASE_ROUTES = [
    "/health",           # GET，200
    "/server/configs",   # POST，GET 探测返回 405 = 路由存在
]

# 问答路由（QA 块挂载，qa 模式应存在 → 405/200，不应 404）
QA_ROUTES = [
    "/chat/file_chat",
    "/chat/feedback",
    "/knowledge_base/list_knowledge_bases",
    "/llm_model/list_running_models",
]

# 入库路由（ingest 块挂载，qa 模式不应挂载 → 应 404）
INGEST_ROUTES = [
    "/knowledge_base/create_knowledge_base",
    "/knowledge_base/upload_docs",
    "/knowledge_base/upload_temp_docs",
    "/knowledge_base/delete_docs",
]


def check(path, method="GET"):
    url = BASE + path
    try:
        req = urllib.request.Request(url, method=method)
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        # 405 = 路由存在但方法不对；404 = 路由不存在
        return e.code
    except Exception as e:
        return f"ERR: {e}"


def main():
    _OK_KEY = 'OK'
    _FAIL_KEY = 'FAIL'
    logger.info("=" * 60)
    logger.info("QA 容器路由清单验证（HAISI_RUN_MODE=qa）")
    logger.info(f"BASE = {BASE}")
    logger.info("=" * 60)
    all_ok = True

    logger.info("\n[1] 基础路由（两端都挂，应存在 → 200/405，不应 404）:")
    for r in BASE_ROUTES:
        code = check(r, _GET_KEY)
        status = _OK_KEY if code != 404 else _FAIL_KEY
        logger.info(f"  [{status}] {r} -> {code}")
        if code == 404:
            all_ok = False

    logger.info("\n[2] 问答路由（QA 块挂载，应存在 → 405/200，不应 404）:")
    for r in QA_ROUTES:
        code = check(r, _GET_KEY)
        status = _OK_KEY if code != 404 else _FAIL_KEY
        logger.info(f"  [{status}] {r} -> {code}")
        if code == 404:
            all_ok = False

    logger.info("\n[3] 入库路由（ingest 块挂载，qa 模式应不挂载 → 404）:")
    for r in INGEST_ROUTES:
        code = check(r, _GET_KEY)
        status = _OK_KEY if code == 404 else _FAIL_KEY
        logger.info(f"  [{status}] {r} -> {code}")
        if code != 404:
            all_ok = False

    logger.info("\n" + "=" * 60)
    logger.info("RESULT: {}", "ALL PASS" if all_ok else "SOME FAIL")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()