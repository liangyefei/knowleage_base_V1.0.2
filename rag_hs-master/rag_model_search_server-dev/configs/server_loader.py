"""
服务器加载器

从 YAML 配置文件加载服务器配置，支持热加载。
配置文件路径: params/server_config.yaml
"""
import os
import sys
from typing import Any

import yaml

from configs.logger_manager import logger


class ServerLoader:
    """服务器加载器"""

    def __init__(self, config_path: str | None = None):
        """
        初始化加载器

        Args:
            config_path: YAML 配置文件路径，默认为 params/server_config.yaml
        """
        if config_path is None:
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "params",
                "server_config.yaml"
            )
        self.config_path = config_path
        self._config: dict = {}
        self._last_modified: float = 0.0

    def load(self, force_reload: bool = False) -> dict:
        """
        加载配置

        Args:
            force_reload: 是否强制重新加载

        Returns:
            配置字典
        """
        if not force_reload and self._config:
            try:
                current_mtime = os.path.getmtime(self.config_path)
                if current_mtime <= self._last_modified:
                    return self._config
                self._last_modified = current_mtime
            except OSError:
                logger.debug("配置文件 mtime 检查失败（首次加载或文件被删除），跳过缓存")
                pass

        try:
            with open(self.config_path, encoding='utf-8') as f:
                self._config = yaml.safe_load(f) or {}
            return self._config
        except FileNotFoundError as e:
            raise FileNotFoundError(f"配置文件不存在: {self.config_path}") from e
        except yaml.YAMLError as e:
            raise ValueError(f"YAML 解析错误: {e}") from e

    def get(self, key: str = None, default: Any = None) -> Any:
        """
        获取配置值

        Args:
            key: 配置键，支持点号分隔的多级键（如 "API_SERVER.port"）
            default: 默认值

        Returns:
            配置值
        """
        cfg = self.load()

        if key is None:
            return cfg

        keys = key.split('.')
        value = cfg
        for k in keys:
            if isinstance(value, dict) and k in value:
                value = value[k]
            else:
                return default
        return value

    def reload(self) -> dict:
        """强制重新加载配置"""
        return self.load(force_reload=True)


# 全局加载器实例
_loader: ServerLoader | None = None


def get_loader(config_path: str | None = None) -> ServerLoader:
    """获取全局加载器实例"""
    global _loader
    if _loader is None:
        _loader = ServerLoader(config_path)
    return _loader


def load_config(config_path: str | None = None) -> dict:
    """加载配置"""
    return get_loader(config_path).load()


def get_config(key: str = None, default: Any = None, config_path: str | None = None) -> Any:
    """获取配置值"""
    return get_loader(config_path).get(key, default)


# ==================== 模块级配置变量 ====================
# 在模块加载时自动初始化

SERVER_CONFIG = None


def _init_config() -> dict:
    """初始化配置（内部函数，fail-fast：加载失败直接抛异常，避免下游用空 dict 走硬编码默认值）"""
    return load_config()


def get_server_config(config_path: str | None = None) -> dict:
    """获取配置（兼容旧代码）"""
    global SERVER_CONFIG
    if config_path is not None:
        return load_config(config_path)
    if SERVER_CONFIG is None:
        SERVER_CONFIG = _init_config()
    return SERVER_CONFIG


# 模块加载时自动初始化
SERVER_CONFIG = _init_config()

# 默认绑定地址
DEFAULT_BIND_HOST = "0.0.0.0" if sys.platform != "win32" else "127.0.0.1"

# 导出常用配置变量
HTTPX_DEFAULT_TIMEOUT = SERVER_CONFIG.get("HTTPX_DEFAULT_TIMEOUT", 500.0)
OPEN_CROSS_DOMAIN = SERVER_CONFIG.get("OPEN_CROSS_DOMAIN", False)
WEBUI_SERVER = SERVER_CONFIG.get("WEBUI_SERVER", {"host": DEFAULT_BIND_HOST, "port": 8501})
API_SERVER = SERVER_CONFIG.get("API_SERVER", {"host": DEFAULT_BIND_HOST, "port": 7861, "public_host": "192.168.80.250", "public_port": 16608, "workers": 1, "limit_concurrency": 1000, "backlog": 2048, "timeout_keep_alive": 30})  # pylint: disable=line-too-long
FSCHAT_MODEL_WORKERS = SERVER_CONFIG.get("FSCHAT_MODEL_WORKERS", {})
COMM_KAFKA_ADDRESS = SERVER_CONFIG.get("COMM_KAFKA_ADDRESS", {})
# Kafka 消费者线程数（不超过 topic 分区数 16；多实例部署时总消费者数 = 实例数 × N，受分区数约束）
COMM_KAFKA_WORKERS = int(COMM_KAFKA_ADDRESS.get("consumer_workers", 4)) if COMM_KAFKA_ADDRESS else 4
# Kafka 消费者优雅停止超时（ms），略大于单条最长处理时间（90 分钟）+ 余量
COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = int(COMM_KAFKA_ADDRESS.get("shutdown_timeout_ms", 7200000)) if COMM_KAFKA_ADDRESS else 7200000  # pylint: disable=line-too-long
# Kafka 单次 poll 最多拉取的消息条数（解耦 poll 与 exec，拉到的消息派发到 worker pool 并行处理）
COMM_KAFKA_POLL_MAX_RECORDS = int(COMM_KAFKA_ADDRESS.get("poll_max_records", 32)) if COMM_KAFKA_ADDRESS else 32
# 文档并行 worker 数（跨文件并行度，不同 documentId 路由到不同 worker 并行执行）
COMM_KAFKA_DOC_WORKER_POOL_SIZE = int(COMM_KAFKA_ADDRESS.get("doc_worker_pool_size", 16)) if COMM_KAFKA_ADDRESS else 16
# 控制车道 worker 数（KB_CREATED/DELETED/UPDATED 生命周期事件，建议 1 保持串行）
COMM_KAFKA_CONTROL_WORKER_POOL_SIZE = int(COMM_KAFKA_ADDRESS.get("control_worker_pool_size", 1)) if COMM_KAFKA_ADDRESS else 1  # pylint: disable=line-too-long
# VLM OCR 调用并发上限（Semaphore 限流，避免打爆 VLM 服务）
COMM_KAFKA_VLM_CONCURRENCY_LIMIT = int(COMM_KAFKA_ADDRESS.get("vlm_concurrency_limit", 8)) if COMM_KAFKA_ADDRESS else 8
# Kafka 回调投递同步等 ACK 超时（ms），send_callback 调 future.get(timeout) 确保消息被 broker ACK
COMM_KAFKA_CALLBACK_SEND_TIMEOUT_MS = int(COMM_KAFKA_ADDRESS.get("callback_send_timeout_ms", 10000)) if COMM_KAFKA_ADDRESS else 10000  # pylint: disable=line-too-long
# Kafka 回调投递最大重试次数：同一条消息（按 topic+partition+offset 跟踪）回调投递失败达该次数后，
# 强制推进 offset 并记录死信日志（ERROR 级，含完整 envelope），打破"回调永久失败 → offset 永不推进 →
# Kafka at-least-once 无限重投递 → 每轮重跑 task.run"的死循环。
# 默认 5：覆盖短时回调 topic 抖动（每次 poll ~1s + task 处理时间，共约数十秒自愈窗口）；
# 设 0 表示永不强制推进（保留纯 at-least-once 重投递，存在死循环风险，不推荐）。
COMM_KAFKA_CALLBACK_MAX_RETRIES = int(COMM_KAFKA_ADDRESS.get("callback_max_retries", 5)) if COMM_KAFKA_ADDRESS else 5
# offset 提交策略：按分区跟踪已完成 offset 连续推进（per_partition_completed）
COMM_KAFKA_COMMIT_STRATEGY = COMM_KAFKA_ADDRESS.get("commit_strategy", "per_partition_completed") if COMM_KAFKA_ADDRESS else "per_partition_completed"  # pylint: disable=line-too-long
SFS_MOUNT_PATH = SERVER_CONFIG.get("SFS_MOUNT_PATH", "")
MEMMACHINE_BASE_URL = SERVER_CONFIG.get("MEMMACHINE_BASE_URL", "")
MEMMACHINE_ORG_ID = SERVER_CONFIG.get("MEMMACHINE_ORG_ID", "")
MEMMACHINE_TOP_K = SERVER_CONFIG.get("MEMMACHINE_TOP_K", 5)
MEMMACHINE_PROJECT_ID = SERVER_CONFIG.get("MEMMACHINE_PROJECT_ID", "haisi_rag_user_profile")
MEMMACHINE_EXTRACT_ENABLED = SERVER_CONFIG.get("MEMMACHINE_EXTRACT_ENABLED", False)
MEMMACHINE_ENABLED = SERVER_CONFIG.get("MEMMACHINE_ENABLED", False)
MEMPALACE_BASE_URL = SERVER_CONFIG.get("MEMPALACE_BASE_URL", "")
MEMPALACE_TOP_K = SERVER_CONFIG.get("MEMPALACE_TOP_K", 5)
MEMPALACE_ENABLED = SERVER_CONFIG.get("MEMPALACE_ENABLED", False)
# mempalace 短超时（秒）：服务不可用时快速失败，避免 gather 等满 30s 拖垮主链路
MEMPALACE_TIMEOUT = float(SERVER_CONFIG.get("MEMPALACE_TIMEOUT", 3.0))
# 连续失败达到阈值后打开熔断，cooldown 期内直接跳过检索
MEMPALACE_FAIL_THRESHOLD = int(SERVER_CONFIG.get("MEMPALACE_FAIL_THRESHOLD", 3))
# 熔断打开后的冷却时间（秒），期间不再向 mempalace 发请求
MEMPALACE_COOLDOWN = float(SERVER_CONFIG.get("MEMPALACE_COOLDOWN", 60.0))
# dual-mcp 代码检索路开关：默认 True（与历史行为一致，无条件启用代码路）
DUAL_MCP_ENABLED = SERVER_CONFIG.get("DUAL_MCP_ENABLED", True)
# dual-mcp 熔断阈值：连续失败 N 次后打开熔断（与 mempalace 一致，复用同一模式）
DUAL_MCP_FAIL_THRESHOLD = int(SERVER_CONFIG.get("DUAL_MCP_FAIL_THRESHOLD", 3))
# dual-mcp 熔断冷却时间（秒）：期间直接跳过检索，避免每请求等满 15s 超时拖垮主链路
DUAL_MCP_COOLDOWN = float(SERVER_CONFIG.get("DUAL_MCP_COOLDOWN", 60.0))
# RAG 上下文预算（chars）：影响进 LLM 的 prompt 大小，进而影响 TTFT prefill 时延
RAG_CONTEXT_BUDGET = int(SERVER_CONFIG.get("RAG_CONTEXT_BUDGET", 8192))
# 历史对话 assistant 消息截断系数（相对 RAG_CONTEXT_BUDGET），0.2 ≈ 1638 chars
RAG_HISTORY_BUDGET_RATIO = float(SERVER_CONFIG.get("RAG_HISTORY_BUDGET_RATIO", 0.2))
# 历史对话总长度上限系数（相对 RAG_CONTEXT_BUDGET），超出则倒序保留最近的历史，
# 防止长对话堆叠拖垮 TTFT。0.3 × 8192 ≈ 2457 chars（约 2-4 轮长对话）
# A3：原值 0.2 < BUDGET_RATIO 0.3，导致截断后仍超总预算 → 历史全丢；交换使 total >= per-msg
RAG_HISTORY_TOTAL_BUDGET_RATIO = float(SERVER_CONFIG.get("RAG_HISTORY_TOTAL_BUDGET_RATIO", 0.3))
LAC_SERVICE_BASE_URL = SERVER_CONFIG.get("LAC_SERVICE_BASE_URL", "http://haisi-lac-service:5955")
# QA 服务调用 Ingestion 服务的 API 地址（双镜像拆分模式）
INGESTION_API_URL = SERVER_CONFIG.get("INGESTION_API_URL", "")


# 使用示例
if __name__ == "__main__":
    # 示例 1: 直接加载配置
    config = load_config()
    logger.info("配置键: {}", list(config.keys()))

    # 示例 2: 获取特定配置值
    timeout = get_config("HTTPX_DEFAULT_TIMEOUT")
    logger.info("\nHTTP 超时时间: {}", timeout)

    # 示例 3: 获取嵌套配置
    api_port = get_config("API_SERVER.port")
    logger.info("API 端口: {}", api_port)