"""
日志管理器

基于 loguru 的日志记录管理，支持控制台和文件输出。
"""
import os
import sys
import warnings
from datetime import datetime, timezone
from typing import Optional

from loguru import logger

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


# 应用 stderr 过滤器（如果还没有应用）
if not isinstance(sys.stderr, FilteredStderr):
    sys.stderr = FilteredStderr(sys.stderr)

# 延迟导入配置，避免循环依赖
_config_loaded = False
_cached_log_path = None
_cached_log_level = None


def _load_config():
    """加载配置（延迟加载）"""
    global _config_loaded, _cached_log_path, _cached_log_level
    if _config_loaded:
        return _cached_log_path, _cached_log_level
    try:
        from configs.basic_loader import LOG_LEVEL, LOG_PATH
        _cached_log_path = LOG_PATH
        _cached_log_level = LOG_LEVEL
        _config_loaded = True
        return LOG_PATH, LOG_LEVEL
    except ImportError:
        # 配置未加载时使用默认值
        _cached_log_path = "/mnt/d/data/release/logs/kw"
        _cached_log_level = "INFO"
        _config_loaded = True
        return _cached_log_path, _cached_log_level


def get_log_path():
    """获取日志路径"""
    log_path, _ = _load_config()
    return log_path


def get_log_level():
    """获取日志级别"""
    _, log_level = _load_config()
    return log_level


# Kafka 相关模块名（record["name"] 即调用方模块的 __name__）
# 命中即写入 kw_kafka_{date}.log，覆盖完整的消息处理链路
_KAFKA_MODULES = (
    "kafka_producer",
    "kafka_poller",
    "kafka_comm_server",
    "processing_engine",
    "task_factory",
    "callback_utils",
    "kb_gate",
    "offset_tracker",
)


def _is_kafka_module(name: str) -> bool:
    """判断 loguru record 的 name 字段是否属于 Kafka 相关模块。

    record["name"] 为调用方模块的 __name__（如 server.comm.kafka_producer），
    只要包含 _KAFKA_MODULES 中任一模块名即命中。
    """
    if not name:
        return False
    return any(mod in name for mod in _KAFKA_MODULES)


def create_logger(log_path: Optional[str] = None, log_level: Optional[str] = None):
    """
    创建日志记录器。注册 5 个 sink：
    1) 控制台（可由 LOG.ENABLE_CONSOLE 关闭）
    2) 通用文件（按大小滚动 + retention）
    3) 向量化专属文件（filter [tx]，便于诊断假成功）
    4) ERROR 独立文件（filter level>=ERROR，便于告警）
    5) Kafka 独立文件（filter 模块名匹配 comm 模块，成功+失败均写入，便于排查回调丢失）

    Args:
        log_path: 日志文件存储路径，默认从配置读取
        log_level: 日志级别，默认从配置读取

    Returns:
        logger 实例
    """
    _30DAYS_KEY = '30 days'
    _ZIP_KEY = 'zip'
    if log_path is None:
        log_path = get_log_path()
    if log_level is None:
        log_level = get_log_level()

    # 创建日志目录
    if not os.path.exists(log_path):
        os.makedirs(log_path)

    # 延迟导入配置子项（避免循环依赖）
    try:
        from configs.basic_loader import (
            LOG_ENABLE_CONSOLE,
            LOG_ERROR_FILE_RETENTION,
            LOG_ERROR_FILE_ROTATION,
            LOG_FILE_RETENTION,
            LOG_FILE_ROTATION,
            LOG_KAFKA_FILE_RETENTION,
            LOG_KAFKA_FILE_ROTATION,
            LOG_VECTORIZE_FILE_RETENTION,
            LOG_VECTORIZE_FILE_ROTATION,
        )
    except ImportError:
        LOG_ENABLE_CONSOLE = True
        LOG_FILE_ROTATION = "100 MB"
        LOG_FILE_RETENTION = _30DAYS_KEY
        LOG_VECTORIZE_FILE_ROTATION = "500 MB"
        LOG_VECTORIZE_FILE_RETENTION = _30DAYS_KEY
        LOG_ERROR_FILE_ROTATION = "50 MB"
        LOG_ERROR_FILE_RETENTION = "90 days"
        LOG_KAFKA_FILE_ROTATION = "200 MB"
        LOG_KAFKA_FILE_RETENTION = _30DAYS_KEY

    # 移除默认处理器
    logger.remove()

    current_date = datetime.now(timezone.utc).strftime('%Y-%m-%d')

    # 通用格式
    common_format = (
        "{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | "
        "{name}:{function}:{line} | {message}"
    )

    # ① 控制台（生产可关）
    if LOG_ENABLE_CONSOLE:
        logger.add(
            sys.stdout,
            colorize=True,
            format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> "
                   "<level>{level: <8}</level> "
                   "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> "
                   "- <level>{message}</level>",
            level=log_level,
            enqueue=True,
            catch=True,
        )

    # ② 通用文件（按大小滚动 + retention）
    logger.add(
        os.path.join(log_path, f"kw_{current_date}.log"),
        level=log_level,
        rotation=LOG_FILE_ROTATION,
        retention=LOG_FILE_RETENTION,
        compression=_ZIP_KEY,
        enqueue=True,
        catch=True,
        format=common_format,
    )

    # ③ 向量化专属日志（filter [tx]）
    logger.add(
        os.path.join(log_path, f"kw_vectorize_{current_date}.log"),
        filter=lambda record: "[tx]" in record["message"],
        level="DEBUG",
        rotation=LOG_VECTORIZE_FILE_ROTATION,
        retention=LOG_VECTORIZE_FILE_RETENTION,
        compression=_ZIP_KEY,
        enqueue=True,
        catch=True,
        format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {message}",
    )

    # ④ ERROR 独立文件（level >= ERROR）
    logger.add(
        os.path.join(log_path, f"kw_error_{current_date}.log"),
        filter=lambda record: record["level"].no >= 40,
        level="ERROR",
        rotation=LOG_ERROR_FILE_ROTATION,
        retention=LOG_ERROR_FILE_RETENTION,
        compression=_ZIP_KEY,
        enqueue=True,
        catch=True,
        format=common_format,
    )

    # ⑤ Kafka 独立日志（filter 模块名匹配 comm 模块，成功+失败均写入）
    # 覆盖：producer/poller/engine/comm_server/task_factory/callback_utils/kb_gate/offset_tracker
    # 便于排查"成功向量化无回调"等问题：从该文件即可看到完整的 Kafka 消息处理链路
    logger.add(
        os.path.join(log_path, f"kw_kafka_{current_date}.log"),
        filter=lambda record: _is_kafka_module(record["name"]),
        level="DEBUG",
        rotation=LOG_KAFKA_FILE_ROTATION,
        retention=LOG_KAFKA_FILE_RETENTION,
        compression=_ZIP_KEY,
        enqueue=True,
        catch=True,
        format=common_format,
    )

    return logger


# 创建默认 logger 实例
_default_logger = None


def get_logger(log_path: str = None, log_level: str = None):
    """
    获取 logger 实例

    Args:
        log_path: 日志路径
        log_level: 日志级别

    Returns:
        logger 实例
    """
    global _default_logger
    if _default_logger is None:
        _default_logger = create_logger(log_path, log_level)
    return _default_logger


# 模块加载时初始化默认 logger
logger = get_logger()
