"""
configs 各 loader 模块的单元测试

验证 configs 包中各 loader(basic/model/kb/server/prompt)模块:
- 可正常 import
- 暴露的顶层常量存在且类型符合预期

这些测试不依赖外部服务,仅校验配置加载后的模块级变量。
若某些配置项在 yaml 中被注释或留空,则按存在性断言而非非空断言。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

# 将项目根目录加入 sys.path,保证可以直接 import configs 包
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))


def test_kb_loader_constants():
    """kb_loader 暴露的知识库相关常量类型与结构校验。"""
    from configs import (
        CHIP_TYPE_DICT,
        CHUNK_SIZE,
        DEFAULT_VS_TYPE,
        OVERLAP_SIZE,
        VECTOR_SEARCH_TOP_K,
        kbs_config,
        text_splitter_dict,
    )

    assert isinstance(DEFAULT_VS_TYPE, str) and DEFAULT_VS_TYPE
    assert isinstance(CHUNK_SIZE, int) and CHUNK_SIZE > 0
    assert isinstance(OVERLAP_SIZE, int) and OVERLAP_SIZE >= 0
    assert isinstance(VECTOR_SEARCH_TOP_K, int) and VECTOR_SEARCH_TOP_K > 0
    assert isinstance(kbs_config, dict)
    assert isinstance(text_splitter_dict, dict)
    assert isinstance(CHIP_TYPE_DICT, dict) and len(CHIP_TYPE_DICT) > 0
    # Hi3798MV310 是项目实际使用的芯片型号键
    assert "Hi3798MV310" in CHIP_TYPE_DICT


def test_model_loader_constants():
    """model_loader 暴露的模型相关常量类型与结构校验。"""
    from configs import (
        EMBEDDING_MODEL,
        LLM_MODELS,
        MODEL_PATH,
        ONLINE_LLM_MODEL,
    )

    assert isinstance(EMBEDDING_MODEL, str) and EMBEDDING_MODEL
    assert isinstance(LLM_MODELS, list)
    assert isinstance(MODEL_PATH, dict)
    # MODEL_PATH 应至少包含 embed_model / llm_model 等 key(即使值为空 dict)
    assert "embed_model" in MODEL_PATH
    assert "llm_model" in MODEL_PATH
    assert isinstance(ONLINE_LLM_MODEL, dict)


def test_basic_loader_constants():
    """basic_loader 暴露的日志/环境相关常量类型校验。"""
    from configs import (
        ENV_MODE,
        LOG_LEVEL,
        LOG_PATH,
    )

    assert isinstance(LOG_LEVEL, str) and LOG_LEVEL
    assert isinstance(ENV_MODE, str) and ENV_MODE
    # LOG_PATH 可能为空字符串,仅校验类型
    assert isinstance(LOG_PATH, str)


def test_server_loader_constants():
    """server_loader 暴露的服务/Kafka 相关常量类型与结构校验。"""
    from configs import (
        API_SERVER,
        COMM_KAFKA_ADDRESS,
        HTTPX_DEFAULT_TIMEOUT,
        WEBUI_SERVER,
    )

    assert isinstance(API_SERVER, dict)
    assert "host" in API_SERVER
    assert "port" in API_SERVER

    assert isinstance(WEBUI_SERVER, dict)
    assert "host" in WEBUI_SERVER
    assert "port" in WEBUI_SERVER

    assert isinstance(HTTPX_DEFAULT_TIMEOUT, (int, float))
    assert HTTPX_DEFAULT_TIMEOUT > 0

    assert isinstance(COMM_KAFKA_ADDRESS, dict)
    # COMM_KAFKA_ADDRESS 可能为空 dict(本地未配置),仅在非空时校验 key
    if COMM_KAFKA_ADDRESS:
        assert "bootstrap_servers" in COMM_KAFKA_ADDRESS


def test_prompt_loader_constants():
    """prompt_loader 暴露的 PROMPT_TEMPLATES 是 dict 且含 llm_chat 键。"""
    from configs import PROMPT_TEMPLATES

    assert isinstance(PROMPT_TEMPLATES, dict)
    assert len(PROMPT_TEMPLATES) > 0
    assert "llm_chat" in PROMPT_TEMPLATES


def test_get_prompt_template():
    """
    get_prompt(llm_chat) 应返回非空字符串模板。

    注:源码中 get_prompt 签名为 get_prompt(category, name=default, config_path=None),
    传入 llm_chat 时返回该类别下 default 模板字符串。
    """
    from configs import get_prompt

    template = get_prompt("llm_chat")
    assert isinstance(template, str)
    assert len(template) > 0


def test_configs_version():
    """configs 包应暴露 VERSION 常量且为字符串。"""
    from configs import VERSION

    assert isinstance(VERSION, str) and VERSION
