"""
知识库加载器

从 YAML 配置文件加载知识库配置，支持热加载。
配置文件路径: params/kb_config.yaml
"""
import os
from typing import Any

import yaml

from configs.logger_manager import logger


class KBLoader:
    """知识库加载器"""

    def __init__(self, config_path: str | None = None):
        """
        初始化加载器

        Args:
            config_path: YAML 配置文件路径，默认为 params/kb_config.yaml
        """
        if config_path is None:
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "params",
                "kb_config.yaml"
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
            key: 配置键，支持点号分隔的多级键（如 "kbs_config.milvus.host"）
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
_loader: KBLoader | None = None


def get_loader(config_path: str | None = None) -> KBLoader:
    """获取全局加载器实例"""
    global _loader
    if _loader is None:
        _loader = KBLoader(config_path)
    return _loader


def load_config(config_path: str | None = None) -> dict:
    """加载配置"""
    return get_loader(config_path).load()


def get_config(key: str = None, default: Any = None, config_path: str | None = None) -> Any:
    """获取配置值"""
    return get_loader(config_path).get(key, default)


# ==================== 模块级配置变量 ====================
# 在模块加载时自动初始化

KB_CONFIG = None


def _init_config() -> dict:
    """初始化配置（内部函数，fail-fast：加载失败直接抛异常，避免下游用空 dict 走硬编码默认值）"""
    return load_config()


def get_kb_config(config_path: str | None = None) -> dict:
    """获取配置（兼容旧代码）"""
    global KB_CONFIG
    if config_path is not None:
        return load_config(config_path)
    if KB_CONFIG is None:
        KB_CONFIG = _init_config()
    return KB_CONFIG


# 模块加载时自动初始化
KB_CONFIG = _init_config()

# 导出常用配置变量
DEFAULT_KNOWLEDGE_BASE = KB_CONFIG.get("DEFAULT_KNOWLEDGE_BASE", "samples")
DEFAULT_VS_TYPE = KB_CONFIG.get("DEFAULT_VS_TYPE", "milvus")
CACHED_VS_NUM = KB_CONFIG.get("CACHED_VS_NUM", 1)
CACHED_MEMO_VS_NUM = KB_CONFIG.get("CACHED_MEMO_VS_NUM", 10)
CHUNK_SIZE = KB_CONFIG.get("CHUNK_SIZE", 700)
OVERLAP_SIZE = KB_CONFIG.get("OVERLAP_SIZE", 150)
VECTOR_SEARCH_TOP_K = KB_CONFIG.get("VECTOR_SEARCH_TOP_K", 5)
SCORE_THRESHOLD = KB_CONFIG.get("SCORE_THRESHOLD", 0.55)
# chat 链路 TopK 解耦：召回数（三路并发召回的候选数）与 Reranker 返回数（精排后留给 LLM 的上下文数）分离
# 召回数大 → Reranker 有足够候选做精排；返回数小 → 控制 LLM 上下文长度
RECALL_TOP_K = KB_CONFIG.get("RECALL_TOP_K", 50)
RERANKER_TOP_N = KB_CONFIG.get("RERANKER_TOP_N", 10)
ZH_TITLE_ENHANCE = KB_CONFIG.get("ZH_TITLE_ENHANCE", True)
ZH_LABEL_ENHANCE = KB_CONFIG.get("ZH_LABEL_ENHANCE", True)
PDF_OCR_THRESHOLD = KB_CONFIG.get("PDF_OCR_THRESHOLD", [0.1, 0.1])
KB_INFO = KB_CONFIG.get("KB_INFO", {})
KB_ROOT_PATH = KB_CONFIG.get("KB_ROOT_PATH", "")
KW_ROOT_PATH = KB_CONFIG.get("KW_ROOT_PATH", "")
# 文件版本保留数量：每文件保留最近 N 版（含当前版本），更老的版本由 cleanup 清理
FILE_VERSION_KEEP = int(KB_CONFIG.get("FILE_VERSION_KEEP", 3))
SQLALCHEMY_DATABASE_URI = KB_CONFIG.get("SQLALCHEMY_DATABASE_URI", "")
kbs_config = KB_CONFIG.get("kbs_config", {})
text_splitter_dict = KB_CONFIG.get("text_splitter_dict", {})
TEXT_SPLITTER_NAME = KB_CONFIG.get("TEXT_SPLITTER_NAME", "ChineseRecursiveTextSplitter")
CHIP_TYPE_DICT = KB_CONFIG.get("CHIP_TYPE_DICT", {})

# ==================== 文档加载器配置 ====================
DOCUMENT_LOADER_CONFIG = KB_CONFIG.get("DOCUMENT_LOADER_CONFIG", {})
# 图片解析最小像素数：宽×高 < 此值视为小图标，跳过 VLM 直接返回 "null"
MIN_IMAGE_PIXELS = DOCUMENT_LOADER_CONFIG.get("MIN_IMAGE_PIXELS", 10000)

# ==================== MinerU 解析配置 ====================
MINERU_CONFIG = KB_CONFIG.get("MINERU_CONFIG", {})

# ==================== 向量化事务配置 ====================
VECTORIZE_CONFIG = KB_CONFIG.get("VECTORIZE_CONFIG", {})
EMBEDDING_BATCH_SIZE = VECTORIZE_CONFIG.get("EMBEDDING_BATCH_SIZE", 0)
EMBEDDING_MAX_RETRIES = VECTORIZE_CONFIG.get("EMBEDDING_MAX_RETRIES", 3)
EMBEDDING_RETRY_BACKOFF = VECTORIZE_CONFIG.get("EMBEDDING_RETRY_BACKOFF", 1.0)
VECTORIZE_ROLLBACK_ON_FAILURE = VECTORIZE_CONFIG.get("ROLLBACK_ON_FAILURE", True)
VECTORIZE_VERIFY_INSERT_COUNT = VECTORIZE_CONFIG.get("VERIFY_INSERT_COUNT", True)
VECTORIZE_FILE_TIMEOUT_SECONDS = VECTORIZE_CONFIG.get("FILE_TIMEOUT_SECONDS", 0)


# 使用示例
if __name__ == "__main__":
    # 示例 1: 直接加载配置
    config = load_config()
    logger.info("配置键: {}", list(config.keys()))

    # 示例 2: 获取特定配置值
    vs_type = get_config("DEFAULT_VS_TYPE")
    logger.info("\n默认向量库类型: {}", vs_type)

    # 示例 3: 获取嵌套配置
    milvus_host = get_config("kbs_config.milvus.host")
    logger.info("Milvus 主机: {}", milvus_host)