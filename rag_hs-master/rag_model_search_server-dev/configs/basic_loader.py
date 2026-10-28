"""
基础加载器

从 YAML 配置文件加载基础配置，支持热加载。
配置文件路径: params/basic_config.yaml
"""
import os
from typing import Any

import yaml

from configs.logger_manager import logger


class BasicLoader:
    """基础加载器"""

    def __init__(self, config_path: str | None = None):
        """
        初始化加载器

        Args:
            config_path: YAML 配置文件路径，默认为 params/basic_config.yaml
        """
        if config_path is None:
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "params",
                "basic_config.yaml"
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
            key: 配置键，支持点号分隔的多级键（如 "LOGGER.PATH"）
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
_loader: BasicLoader | None = None


def get_loader(config_path: str | None = None) -> BasicLoader:
    """获取全局加载器实例"""
    global _loader
    if _loader is None:
        _loader = BasicLoader(config_path)
    return _loader


def load_config(config_path: str | None = None) -> dict:
    """加载配置"""
    return get_loader(config_path).load()


def get_config(key: str = None, default: Any = None, config_path: str | None = None) -> Any:
    """获取配置值"""
    return get_loader(config_path).get(key, default)


# ==================== 模块级配置变量 ====================
# 在模块加载时自动初始化

BASIC_CONFIG = None


def _init_config() -> dict:
    """初始化配置（内部函数，fail-fast：加载失败直接抛异常，避免下游用空 dict 走硬编码默认值）"""
    return load_config()


def get_basic_config(config_path: str | None = None) -> dict:
    """获取配置（兼容旧代码）"""
    global BASIC_CONFIG
    if config_path is not None:
        return load_config(config_path)
    if BASIC_CONFIG is None:
        BASIC_CONFIG = _init_config()
    return BASIC_CONFIG


# 模块加载时自动初始化
BASIC_CONFIG = _init_config()

# 导出常用配置变量
LOG_PATH = BASIC_CONFIG.get("LOG", {}).get("PATH", "/mnt/d/data/release/logs/kw")
LOG_FORMAT = BASIC_CONFIG.get("LOG", {}).get("FORMAT", "")
LOG_LEVEL = BASIC_CONFIG.get("LOG", {}).get("LEVEL", "INFO")
LOG_CONFIG = BASIC_CONFIG.get("LOG", {})
LOG_ENABLE_CONSOLE = LOG_CONFIG.get("ENABLE_CONSOLE", True)
LOG_FILE_ROTATION = LOG_CONFIG.get("FILE_ROTATION", "100 MB")
LOG_FILE_RETENTION = LOG_CONFIG.get("FILE_RETENTION", "30 days")
LOG_VECTORIZE_FILE_ROTATION = LOG_CONFIG.get("VECTORIZE_FILE_ROTATION", "500 MB")
LOG_VECTORIZE_FILE_RETENTION = LOG_CONFIG.get("VECTORIZE_FILE_RETENTION", "30 days")
LOG_ERROR_FILE_ROTATION = LOG_CONFIG.get("ERROR_FILE_ROTATION", "50 MB")
LOG_ERROR_FILE_RETENTION = LOG_CONFIG.get("ERROR_FILE_RETENTION", "90 days")
LOG_KAFKA_FILE_ROTATION = LOG_CONFIG.get("KAFKA_FILE_ROTATION", "200 MB")
LOG_KAFKA_FILE_RETENTION = LOG_CONFIG.get("KAFKA_FILE_RETENTION", "30 days")
LOG_SERVER_NAME = BASIC_CONFIG.get("LOG", {}).get("SERVER_NAME", "kw")
ENV_MODE = BASIC_CONFIG.get("ENV", {}).get("MODE", "prod")
log_verbose = BASIC_CONFIG.get("log_verbose", False)
langchain_verbose = BASIC_CONFIG.get("langchain_verbose", False)


# 临时文件目录（主要用于文件对话的 upload_temp_docs）；None 表示使用系统默认临时目录
BASE_TEMP_DIR = BASIC_CONFIG.get("BASE_TEMP_DIR", None)


# 使用示例
if __name__ == "__main__":
    # 示例 1: 直接加载配置
    config = load_config()
    logger.info("配置键: {}", list(config.keys()))

    # 示例 2: 获取特定配置值
    log_path = get_config("LOG.PATH")
    logger.info("\n日志路径: {}", log_path)

    # 示例 3: 获取嵌套配置
    log_format = get_config("LOG.FORMAT")
    logger.info("日志格式: {}", log_format)