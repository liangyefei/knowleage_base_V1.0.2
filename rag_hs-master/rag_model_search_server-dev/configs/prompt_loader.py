"""
Prompt 模板加载器

从 YAML 配置文件加载提示词模板，支持热加载。
"""
import os

import yaml

from configs.logger_manager import logger


class PromptLoader:
    """提示词模板加载器"""

    def __init__(self, config_path: str | None = None):
        """
        初始化加载器

        Args:
            config_path: YAML 配置文件路径，默认为 params 目录下的 prompt_templates.yaml
        """
        if config_path is None:
            # 默认路径：项目根目录下的 params/prompt_templates.yaml
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "params",
                "prompt_templates.yaml"
            )
        self.config_path = config_path
        self._templates: dict = {}
        self._last_modified: float = 0.0

    def load(self, force_reload: bool = False) -> dict:
        """
        加载提示词模板

        Args:
            force_reload: 是否强制重新加载

        Returns:
            模板字典
        """
        # 检查文件修改时间，实现热加载
        if not force_reload and self._templates:
            try:
                current_mtime = os.path.getmtime(self.config_path)
                if current_mtime <= self._last_modified:
                    return self._templates
                self._last_modified = current_mtime
            except OSError:
                logger.debug("配置文件 mtime 检查失败（首次加载或文件被删除），跳过缓存")
                pass

        # 加载 YAML 文件
        try:
            with open(self.config_path, encoding='utf-8') as f:
                self._templates = yaml.safe_load(f) or {}
            return self._templates
        except FileNotFoundError as e:
            raise FileNotFoundError(f"Prompt 配置文件不存在: {self.config_path}") from e
        except yaml.YAMLError as e:
            raise ValueError(f"YAML 解析错误: {e}") from e

    def get_template(self, category: str, name: str = "default") -> str:
        """
        获取指定模板

        Args:
            category: 模板类别 (如 llm_chat, knowledge_base_chat 等)
            name: 模板名称 (默认为 default)

        Returns:
            模板字符串

        Raises:
            KeyError: 模板不存在
        """
        tpls = self.load()

        if category not in tpls:
            raise KeyError(f"模板类别不存在: {category}")

        if name not in tpls[category]:
            available = list(tpls[category].keys())
            raise KeyError(f"模板 '{name}' 不存在于类别 '{category}'，可用模板: {available}")

        return tpls[category][name]

    def get_category(self, category: str) -> dict[str, str]:
        """
        获取指定类别的所有模板

        Args:
            category: 模板类别

        Returns:
            该类别下的所有模板字典
        """
        tpls = self.load()

        if category not in tpls:
            raise KeyError(f"模板类别不存在: {category}")

        return tpls[category]

    def list_categories(self) -> list:
        """
        列出所有模板类别

        Returns:
            类别名称列表
        """
        tpls = self.load()
        return list(tpls.keys())

    def list_templates(self, category: str) -> list:
        """
        列出指定类别的所有模板名称

        Args:
            category: 模板类别

        Returns:
            模板名称列表
        """
        tpls = self.load()

        if category not in tpls:
            raise KeyError(f"模板类别不存在: {category}")

        return list(tpls[category].keys())

    def reload(self) -> dict:
        """
        强制重新加载模板

        Returns:
            重新加载后的模板字典
        """
        return self.load(force_reload=True)


# 创建全局加载器实例
_loader: PromptLoader | None = None


def get_loader(config_path: str | None = None) -> PromptLoader:
    """
    获取全局加载器实例

    Args:
        config_path: 配置文件路径

    Returns:
        PromptLoader 实例
    """
    global _loader
    if _loader is None:
        _loader = PromptLoader(config_path)
    return _loader


def load_prompts(config_path: str | None = None) -> dict:
    """
    加载所有提示词模板

    Args:
        config_path: 配置文件路径

    Returns:
        模板字典
    """
    return get_loader(config_path).load()


def get_prompt(category: str, name: str = "default", config_path: str | None = None) -> str:
    """
    获取指定模板

    Args:
        category: 模板类别
        name: 模板名称
        config_path: 配置文件路径

    Returns:
        模板字符串
    """
    return get_loader(config_path).get_template(category, name)


# 兼容旧代码：在模块加载时直接初始化 PROMPT_TEMPLATES 变量
# 这样可以直接导入使用: from configs.prompt_loader import PROMPT_TEMPLATES
PROMPT_TEMPLATES = None


def _init_prompt_templates() -> dict:
    """
    初始化 PROMPT_TEMPLATES（内部函数，fail-fast：加载失败直接抛异常，避免下游用空 dict 走硬编码默认值）
    """
    return load_prompts()


def get_prompt_templates(config_path: str | None = None) -> dict:
    """
    获取 PROMPT_TEMPLATES 变量（兼容旧代码）

    Args:
        config_path: 配置文件路径（可选，用于自定义配置文件路径）

    Returns:
        模板字典
    """
    global PROMPT_TEMPLATES
    if config_path is not None:
        # 指定了配置文件路径，重新加载
        return load_prompts(config_path)
    if PROMPT_TEMPLATES is None:
        PROMPT_TEMPLATES = _init_prompt_templates()
    return PROMPT_TEMPLATES


# 模块加载时自动初始化
PROMPT_TEMPLATES = _init_prompt_templates()


# 使用示例
if __name__ == "__main__":
    # 示例 1: 直接加载所有模板
    templates = load_prompts()
    logger.info("模板类别: {}", list(templates.keys()))

    # 示例 2: 获取指定模板
    prompt = get_prompt("knowledge_base_chat", "default")
    logger.info("\n知识库默认模板:")
    logger.info("{}...", prompt[:100])

    # 示例 3: 使用加载器类
    loader = PromptLoader()
    logger.info("\nLLM 对话模板列表: {}", loader.list_templates("llm_chat"))

    # 示例 4: 模板渲染（配合 Jinja2）
    from jinja2 import Template

    template_str = get_prompt("knowledge_base_chat", "default")
    template = Template(template_str)
    rendered = template.render(
        context="这是知识库内容...",
        question="这是用户问题？"
    )
    logger.info("\n渲染后的提示词:")
    logger.info("{}", rendered)