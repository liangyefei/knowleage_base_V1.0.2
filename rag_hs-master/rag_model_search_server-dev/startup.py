# pylint: disable=redefined-outer-name
import argparse
import asyncio
import multiprocessing as mp
import os
import subprocess
import sys
import warnings
from multiprocessing import Process
from pprint import pprint

# ============================================================
# 屏蔽 LangChain 弃用警告 - 必须在所有导入之前
# ============================================================

# 设置环境变量
os.environ["PYTHONWARNINGS"] = "ignore"
os.environ["LANGCHAIN_HIDE_DEPRECATION_WARNINGS"] = "true"

# 全局屏蔽警告
warnings.simplefilter("ignore")
warnings.filterwarnings("ignore")
warnings.filterwarnings("ignore", category=DeprecationWarning)
warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", message=".*langchain.*")
warnings.filterwarnings("ignore", message=".*LangChain.*")


# 创建 stderr 过滤器类，过滤 LangChain 警告
class FilteredStderr:
    """过滤 stderr 输出，屏蔽 LangChain 弃用警告"""

    def __init__(self, original_stderr):
        self.original_stderr = original_stderr

    def __getattr__(self, name):
        return getattr(self.original_stderr, name)

    def write(self, text):
        # 过滤 LangChain 弃用警告
        _keyword_hit = False
        for _kw in [
            "LangChainDeprecationWarning",
            "langchain is deprecated",
            "langchain-community",
            "python.langchain.com",
            "langchain==0.2.0"
        ]:
            if _kw in text:
                _keyword_hit = True
                break
        if _keyword_hit:
            return len(text)  # 丢弃这些警告
        return self.original_stderr.write(text)

    def flush(self):
        return self.original_stderr.flush()


# 应用 stderr 过滤器（在导入其他模块之前）
_original_stderr = sys.stderr
sys.stderr = FilteredStderr(_original_stderr)

# KafkaCommServer 改为在 start_main_server 的 ingest/all 分支内延迟 import，
# 避免 qa 镜像（瘦身去 kafka-python）import startup 时顶层 ImportError。

# 设置numexpr最大线程数，默认为CPU核心数
try:
    import numexpr

    n_cores = numexpr.utils.detect_number_of_cores()
    os.environ["NUMEXPR_MAX_THREADS"] = str(n_cores)
except Exception as e:
    logger.debug(f"[startup] failed to detect numexpr cores, leave default: {e}")
    pass
sys.path.append(os.path.dirname(os.path.dirname(__file__)))

from configs import (
    API_SERVER,
    EMBEDDING_MODEL,
    LLM_MODELS,
    LOG_PATH,
    TEXT_SPLITTER_NAME,
    VERSION,
    WEBUI_SERVER,
    log_verbose,
    logger,
)
from server.db.base import engine
from server.knowledge_base.migrate import create_tables
from server.utils import FastAPI, embedding_device, get_model_worker_config, llm_device


def _set_app_event(app: FastAPI, started_event: mp.Event = None):
    @app.on_event("startup")
    async def on_startup():
        if started_event is not None:
            started_event.set()


def _wait_for_port_ready(host: str, port: int, timeout: int = 120):
    """轮询端口探活，等待 API 服务就绪。

    用于 workers>1 的 factory 模式：此时 started_event 无法跨进程传入，
    改用 TCP 连接探活判断 API 是否就绪。
    """
    import socket
    import time
    probe_host = "127.0.0.1" if host in ("0.0.0.0", "", None) else host
    deadline = time.time() + timeout
    last_err = None
    while time.time() < deadline:
        try:
            with socket.create_connection((probe_host, port), timeout=1):
                return
        except OSError as err:
            last_err = err
            time.sleep(0.5)
    raise TimeoutError(
        f"API server 在 {timeout}s 内未就绪 ({probe_host}:{port}): {last_err}"
    )


def run_api_server(started_event: mp.Event = None, run_mode: str = None):
    import io
    # 防止 uvicorn 把当前进程的 stdin FD 传给 spawn worker 导致 Bad file descriptor。
    # mp.Process / docker exec 下 stdin 可能是非标准 FD，spawn worker 不继承，os.fdopen 失败。
    # StringIO.fileno() 抛 OSError，uvicorn 会捕获后传 None 给 worker 跳过 fdopen。
    sys.stdin = io.StringIO()

    import uvicorn

    from server.api import create_app

    host = API_SERVER["host"]
    port = API_SERVER["port"]
    workers = int(API_SERVER.get("workers", 1))

    # 依赖适配：uvloop / httptools 未安装则回退 auto（uvloop 需 pip install）
    import importlib.util
    _has_uvloop = importlib.util.find_spec("uvloop") is not None
    _has_httptools = importlib.util.find_spec("httptools") is not None
    loop = "uvloop" if _has_uvloop else "auto"
    http = "httptools" if _has_httptools else "auto"

    # 通用性能参数：并发上限（背压）、TCP backlog、keep-alive
    run_kwargs = dict(
        host=host,
        port=port,
        loop=loop,
        http=http,
        limit_concurrency=int(API_SERVER.get("limit_concurrency", 1000)),
        backlog=int(API_SERVER.get("backlog", 2048)),
        timeout_keep_alive=int(API_SERVER.get("timeout_keep_alive", 30)),
    )

    if workers > 1:
        # 多 worker 模式：uvicorn 限制必须传 import string + factory=True
        # run_mode 通过环境变量传递（factory 下 create_app() 无参）
        # 注意：spawn 启动方式下每个 worker 冷加载模型，内存 = workers × 单进程内存
        # 仅当未显式设置 HAISI_RUN_MODE（如 K8s 部署 ingest/qa）时，才用 --lite 兜底写入
        if run_mode and not os.environ.get("HAISI_RUN_MODE"):
            os.environ["HAISI_RUN_MODE"] = run_mode
        logger.info(f"以多 worker 模式启动 API: workers={workers}, loop={loop}, http={http}")
        uvicorn.run("server.api:create_app", factory=True, workers=workers, **run_kwargs)
    else:
        # 单 worker 模式：保留 app 对象 + started_event 跨进程同步
        app = create_app(run_mode=run_mode)
        _set_app_event(app, started_event)
        logger.info(f"以单 worker 模式启动 API: loop={loop}, http={http}")
        uvicorn.run(app, **run_kwargs)


def run_webui(started_event: mp.Event = None, run_mode: str = None):
    from server.utils import set_httpx_config
    set_httpx_config()

    host = WEBUI_SERVER["host"]
    port = WEBUI_SERVER["port"]

    cmd = ["streamlit", "run", "webui.py",
           "--server.address", host,
           "--server.port", str(port),
           "--theme.base", "light",
           "--theme.primaryColor", "#165dff",
           "--theme.secondaryBackgroundColor", "#f5f5f5",
           "--theme.textColor", "#000000",
           ]
    if run_mode == "lite":
        cmd += [
            "--",
            "lite",
        ]
    p = subprocess.Popen(cmd)
    started_event.set()
    p.wait()


class _EarlyExit(Exception):
    """parse_args 或 start_main_server 中检测到需要提前退出（如废弃参数、无服务选择），
    由入口处 if __name__ == "__main__" 捕获后 sys.exit，避免在函数内直接 sys.exit。"""
    def __init__(self, code=0):
        super().__init__()
        self.code = code


def parse_args():
    _STORE_TRUE_KEY = 'store_true'
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-a",
        "--all-webui",
        action=_STORE_TRUE_KEY,
        help="启动 API 与 WebUI 服务（等价于 --api --webui）",
        dest="all_webui",
    )
    parser.add_argument(
        "-init",
        "--init-db",
        action=_STORE_TRUE_KEY,
        help="已废弃：请单独运行 python3 init_database.py",
        dest="init_db",
    )
    parser.add_argument(
        "--api",
        action=_STORE_TRUE_KEY,
        help="run api.py server",
        dest="api",
    )
    parser.add_argument(
        "-w",
        "--webui",
        action=_STORE_TRUE_KEY,
        help="run webui.py server",
        dest="webui",
    )
    parser.add_argument(
        "-n",
        "--model-name",
        type=str,
        nargs="+",
        default=LLM_MODELS,
        help="指定 LLM 模型名称（用于配置加载）",
        dest="model_name",
    )
    parser.add_argument(
        "-q",
        "--quiet",
        action=_STORE_TRUE_KEY,
        help="减少服务 log 信息",
        dest="quiet",
    )
    parser.add_argument(
        "-i",
        "--lite",
        action=_STORE_TRUE_KEY,
        help="以Lite模式运行：仅支持在线API的LLM对话、搜索引擎对话",
        dest="lite",
    )
    args = parser.parse_args()

    # --init-db 已废弃：检查并提示退出（避免静默忽略，老脚本误以为初始化了）
    if args.init_db:
        logger.warning("-init/--init-db 已废弃，请单独运行 python3 init_database.py")
        raise _EarlyExit(0)

    # -a/--all-webui 等价于 --api --webui
    if args.all_webui:
        args.api = True
        args.webui = True

    return args, parser


def dump_server_info(after_start=False, args=None):
    import platform
    from pprint import pformat

    import langchain

    from server.utils import api_address, webui_address

    logger.info("\n")
    logger.info("=" * 30 + "HaiSI RAG Configuration" + "=" * 30)
    logger.info(f"操作系统：{platform.platform()}.")
    logger.info(f"python版本：{sys.version}")
    logger.info(f"项目版本：{VERSION}")
    logger.info(f"langchain版本：{langchain.__version__}.")
    logger.info("\n")

    models = LLM_MODELS
    if args and args.model_name:
        models = args.model_name

    logger.info(f"当前使用的分词器：{TEXT_SPLITTER_NAME}")
    logger.info(f"当前启动的LLM模型：{models} @ {llm_device()}")

    for model in models:
        config = get_model_worker_config(model)
        logger.info("\n{}", pformat(config))
    logger.info(f"当前Embbedings模型： {EMBEDDING_MODEL} @ {embedding_device()}")

    if after_start:
        logger.info("\n")
        logger.info("服务端运行信息：")
        if args.api:
            logger.info(f"    HaiSi RAG API  Server: {api_address()}")
        if args.webui:
            logger.info(f"    HaiSi RAG WEBUI Server: {webui_address()}")
    logger.info("=" * 30 + "HaiSI RAG Configuration" + "=" * 30)
    logger.info("\n")


async def start_main_server():
    import signal
    import time

    args, parser = parse_args()

    def handler(signalname):
        def f(signal_received, frame):
            raise KeyboardInterrupt(f"{signalname} received")
        return f

    signal.signal(signal.SIGINT, handler("SIGINT"))
    signal.signal(signal.SIGTERM, handler("SIGTERM"))

    # Linux 下用 fork：子进程共享父进程内存（写时复制），多 worker 内存占用小。
    # Kafka 消费者在主进程独立线程跑，fork 后子进程不继承该线程（API 子进程不碰 Kafka，安全）。
    mp.set_start_method("fork", force=True)
    manager = mp.Manager()
    run_mode = None

    # -a/--all-webui 等价于 --api --webui（parse_args 内已处理，此处无需重复）

    if args.lite:
        run_mode = "lite"

    dump_server_info(args=args)

    if len(sys.argv) > 1:
        logger.info("正在启动服务：")
        logger.info(f"如需查看 llm_api 日志，请前往 {LOG_PATH}")

    processes = {}

    def process_count():
        return len(processes)

    if args.quiet or not log_verbose:
        log_level = "ERROR"
    else:
        log_level = "INFO"

    api_started = manager.Event()
    if args.api:
        process = Process(
            target=run_api_server,
            name="API Server",
            kwargs=dict(started_event=api_started, run_mode=run_mode),
            # 必须 daemon=False：workers>1 时 uvicorn 要 spawn worker 子进程，
            # Python 禁止 daemon 进程创建子进程（AssertionError: daemonic processes
            # are not allowed to have children）。主进程退出时由 finally 块 p.kill() 兜底。
            daemon=False,
        )
        processes["api"] = process

    webui_started = manager.Event()
    if args.webui:
        process = Process(
            target=run_webui,
            name="WEBUI Server",
            kwargs=dict(started_event=webui_started, run_mode=run_mode),
            daemon=True,
        )
        processes["webui"] = process

    # 解析生效的运行模式：HAISI_RUN_MODE 环境变量优先（K8s 部署 ingest/qa），--lite 兜底
    haisi_run_mode = os.environ.get("HAISI_RUN_MODE") or (run_mode or None)

    # 添加 Kafka 通信服务与后端交互（替代 Redis Stream）
    # kcs 延迟到所有 fork 子进程之后创建+启动：KafkaConsumer 持有 broker socket fd，
    # fork 前创建会导致 API/webui 子进程 + uvicorn workers 继承该 fd，
    # 多进程共享同一 consumer member_id，broker 踢出 group，poller 无法继续 poll。
    # 同 PG engine.dispose() 的 fork 继承问题（见下方 __main__ 注释），Kafka 也需在 fork 后创建。
    # 仅 ingest / 兼容模式（all/lite）启动 Kafka 消费者；qa 模式不消费，
    # 避免 qa Pod 误加入消费组与 ingestion Pod 争抢 member_id。
    kcs = None

    if process_count() == 0:
        parser.print_help()
        raise _EarlyExit(1)
    else:
        try:
            p = processes.get("api")
            if p:
                p.start()
                p.name = f"{p.name} ({p.pid})"
                # workers>1 时 factory 模式无法接收 started_event，改用端口探活
                if int(API_SERVER.get("workers", 1)) > 1:
                    _wait_for_port_ready(API_SERVER["host"], API_SERVER["port"], timeout=120)
                else:
                    # 单 worker 用 Event 同步，加超时避免 API 启动失败时主进程永久阻塞
                    if not api_started.wait(timeout=120):
                        raise TimeoutError("API server 120s 内未就绪，可能启动失败（端口占用/import 错误）")

            p = processes.get("webui")
            if p:
                p.start()
                p.name = f"{p.name} ({p.pid})"
                # 同 api：加超时避免 webui 子进程启动失败时主进程永久阻塞
                if not webui_started.wait(timeout=120):
                    raise TimeoutError("WebUI server 120s 内未就绪，可能启动失败")

            # fork 完成后创建+启动 Kafka 消费者：主进程独占 socket fd，子进程不继承
            if haisi_run_mode != "qa":
                # 延迟 import：qa 镜像瘦身去 kafka-python，顶层 import 会 ImportError；
                # 仅 ingest/all 分支（镜像装了 kafka-python）才 import
                from server.comm.kafka_comm_server import KafkaCommServer
                kcs = KafkaCommServer()
                kcs.start()
                logger.info("Kafka Comm Server Success (mode={})", haisi_run_mode or "all")
            else:
                logger.info("HAISI_RUN_MODE=qa，跳过 KafkaCommServer 启动（问答 Pod 不消费 Kafka）")

            dump_server_info(after_start=True, args=args)

            # 主进程保持运行
            while True:
                time.sleep(3600)
        except (Exception, KeyboardInterrupt) as err:
            # KeyboardInterrupt 是 BaseException 子类，不被 except Exception 捕获；
            # 显式加入以区分信号退出（SIGINT/SIGTERM）与异常退出，便于日志排查
            if isinstance(err, KeyboardInterrupt):
                logger.warning("Caught signal (SIGINT/SIGTERM), shutting down...")
            else:
                logger.error(err)
                logger.warning("Caught exception, shutting down...")
        finally:
            # 先优雅停止 Kafka 消费者线程（等待当前消息处理完成或超时），再 kill 子进程
            logger.info("Stopping KafkaCommServer...")
            try:
                if kcs is not None and kcs.is_alive():
                    kcs.stop()
                else:
                    logger.info("KafkaCommServer 未启动，跳过 stop")
            except Exception as _e:
                logger.error(f"KafkaCommServer 停止异常: {_e}", exc_info=True)
            logger.info("KafkaCommServer 停止完成")

            for p in processes.values():
                # p.kill() 在 _popen 为 None（start() 未调用或失败，例如 API 子进程
                # 启动超时后 webui 尚未 start）时会抛 AttributeError: 'NoneType' object
                # has no attribute 'kill'，掩盖真正的启动异常。用 is_alive() 跳过未启动
                # /已退出的进程，仅对存活进程发 SIGKILL。
                if p.is_alive():
                    logger.warning("Sending SIGKILL to {}", p)
                    p.kill()
                else:
                    logger.info("Process not alive, skip kill: {}", p)

            for p in processes.values():
                logger.info("Process status: {}", p)

# python3 startup.py -a
if __name__ == "__main__":
    # 确保数据库表被创建
    create_tables()
    # fork 前释放连接池：create_tables 执行 DDL 时 checkout 了 PG 连接，
    # 若不 dispose，p.start() fork 的 API 子进程会继承父进程已建立的连接池，
    # 导致多进程共享同一底层 PG 连接（协议帧交错、连接损坏）。
    # dispose 后各进程首次访问时各自新建连接（lazy connect）。
    engine.dispose()

    # 同步调用协程代码（__main__ 顶层无 running loop，直接新建）
    _main_loop = asyncio.new_event_loop()
    asyncio.set_event_loop(_main_loop)
    try:
        _main_loop.run_until_complete(start_main_server())
    except _EarlyExit as _e:
        sys.exit(_e.code)