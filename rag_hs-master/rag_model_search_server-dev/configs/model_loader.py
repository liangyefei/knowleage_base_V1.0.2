"""
模型加载器

从 YAML 配置文件加载模型配置，支持热加载。
配置文件路径: params/model_config.yaml
"""
import os
from typing import Any

import yaml

from configs.logger_manager import logger


class ModelLoader:
    """模型加载器"""

    def __init__(self, config_path: str | None = None):
        """
        初始化加载器

        Args:
            config_path: YAML 配置文件路径，默认为 params/model_config.yaml
        """
        if config_path is None:
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "params",
                "model_config.yaml"
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
            key: 配置键，支持点号分隔的多级键（如 "MODEL_PATH.embed_model.bge-m3"）
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

    def get_embedding_model_path(self, model_name: str = None) -> str:
        """
        获取 Embedding 模型路径

        Args:
            model_name: 模型名称，默认使用 EMBEDDING_MODEL

        Returns:
            模型路径
        """
        if model_name is None:
            model_name = self.get("EMBEDDING_MODEL", "bge-m3")
        return self.get(f"MODEL_PATH.embed_model.{model_name}", model_name)

    def get_llm_model_path(self, model_name: str) -> str:
        """
        获取 LLM 模型路径

        Args:
            model_name: 模型名称

        Returns:
            模型路径
        """
        return self.get(f"MODEL_PATH.llm_model.{model_name}", model_name)

    def get_reranker_model_path(self, model_name: str = None) -> str:
        """
        获取 Reranker 模型路径

        Args:
            model_name: 模型名称，默认使用 RERANKER_MODEL

        Returns:
            模型路径
        """
        if model_name is None:
            model_name = self.get("RERANKER_MODEL", "bge-reranker-large")
        return self.get(f"MODEL_PATH.reranker.{model_name}", model_name)


# 全局加载器实例
_loader: ModelLoader | None = None


def get_loader(config_path: str | None = None) -> ModelLoader:
    """获取全局加载器实例"""
    global _loader
    if _loader is None:
        _loader = ModelLoader(config_path)
    return _loader


def load_config(config_path: str | None = None) -> dict:
    """加载配置"""
    return get_loader(config_path).load()


def get_config(key: str = None, default: Any = None, config_path: str | None = None) -> Any:
    """获取配置值"""
    return get_loader(config_path).get(key, default)


# ==================== 模块级配置变量 ====================
# 在模块加载时自动初始化

MODEL_CONFIG = None


def _init_config() -> dict:
    """初始化配置（内部函数，fail-fast：加载失败直接抛异常，避免下游用空 dict 走硬编码默认值）"""
    return load_config()


def get_model_config(config_path: str | None = None) -> dict:
    """获取配置（兼容旧代码）"""
    global MODEL_CONFIG
    if config_path is not None:
        return load_config(config_path)
    if MODEL_CONFIG is None:
        MODEL_CONFIG = _init_config()
    return MODEL_CONFIG


# 模块加载时自动初始化
MODEL_CONFIG = _init_config()

# 导出常用配置变量
MODEL_ROOT_PATH = MODEL_CONFIG.get("MODEL_ROOT_PATH", "")
EMBEDDING_MODEL = MODEL_CONFIG.get("EMBEDDING_MODEL", "bge-m3")
EMBEDDING_DEVICE = MODEL_CONFIG.get("EMBEDDING_DEVICE", "cuda")
RERANKER_MODEL = MODEL_CONFIG.get("RERANKER_MODEL", "bge-reranker-large")
USE_RERANKER = MODEL_CONFIG.get("USE_RERANKER", True)
RERANKER_MAX_LENGTH = MODEL_CONFIG.get("RERANKER_MAX_LENGTH", 2048)
LLM_MODELS = MODEL_CONFIG.get("LLM_MODELS", [])
LLM_DEVICE = MODEL_CONFIG.get("LLM_DEVICE", "cuda")
HISTORY_LEN = MODEL_CONFIG.get("HISTORY_LEN", 3)
MAX_TOKENS = MODEL_CONFIG.get("MAX_TOKENS")
TEMPERATURE = MODEL_CONFIG.get("TEMPERATURE", 0.3)
NEXT_QUESTION_TEMPERATURE = MODEL_CONFIG.get("NEXT_QUESTION_TEMPERATURE", 0.4)
KB_CHAT_TEMPERATURE = MODEL_CONFIG.get("KB_CHAT_TEMPERATURE", 0.1)
MODEL_PATH = MODEL_CONFIG.get("MODEL_PATH", {})
ONLINE_LLM_MODEL = MODEL_CONFIG.get("ONLINE_LLM_MODEL", {})
EMBEDDING_API_CONFIG = MODEL_CONFIG.get("EMBEDDING_API_CONFIG", {})
RERANKER_API_CONFIG = MODEL_CONFIG.get("RERANKER_API_CONFIG", {})
VLM_API_CONFIG = MODEL_CONFIG.get("VLM_API_CONFIG", {})


def get_vlm_config(model_name: str = "doubao-seed-2.0-lite") -> dict:
    """
    获取 VLM 模型配置

    Args:
        model_name: VLM 模型名称，默认为 "doubao-seed-2.0-lite"

    Returns:
        VLM 模型配置字典，包含 api_key、api_base_url、model_id、timeout、max_retries 等
        如果配置不存在则返回空字典
    """
    return VLM_API_CONFIG.get(model_name, {}) if VLM_API_CONFIG else {}


def get_vlm_client_config(model_name: str = "doubao-seed-2.0-lite") -> dict:
    """
    获取 VLM 客户端初始化参数

    将 VLM_API_CONFIG 中的配置转换为 VLMClient 构造函数所需的参数格式。
    用于直接解包到 VLMClient 构造函数中：VLMClient(**get_vlm_client_config())

    Args:
        model_name: VLM 模型名称，默认为 "doubao-seed-2.0-lite"

    Returns:
        VLMClient 构造函数参数字典，包含:
        - api_key: API 密钥
        - model: 模型 ID
        - endpoint: API 端点 URL
        - timeout: 请求超时时间（秒）
        - max_retries: 最大重试次数
    """
    cfg = get_vlm_config(model_name)
    if not cfg:
        return {}
    return {
        "api_key": cfg.get("api_key", ""),
        "model": cfg.get("model_id", ""),
        "endpoint": cfg.get("api_base_url", ""),
        "timeout": cfg.get("timeout", 60),
        "max_retries": cfg.get("max_retries", 4),
    }


# 默认 VLM 客户端配置（针对 doubao-seed-2.0-lite）
VLM_CLIENT_CONFIG = get_vlm_client_config()

# 兼容旧代码的别名
NLTK_DATA_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "nltk_data")


# 使用示例
if __name__ == "__main__":
    # 示例 1: 直接加载配置
    config = load_config()
    logger.info("配置键: {}", list(config.keys()))

    # 示例 2: 获取特定配置值
    embedding_model = get_config("EMBEDDING_MODEL")
    logger.info("\nEmbedding 模型: {}", embedding_model)

    # 示例 3: 获取嵌套配置
    model_path = get_config("MODEL_PATH.embed_model.bge-m3")
    logger.info("模型路径: {}", model_path)

    # 示例 4: 使用便捷方法
    loader = get_loader()
    embed_path = loader.get_embedding_model_path()
    logger.info("Embedding 路径: {}", embed_path)