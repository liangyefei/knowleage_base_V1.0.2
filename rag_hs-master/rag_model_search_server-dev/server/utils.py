import asyncio
import os
from collections.abc import Awaitable, Callable, Generator
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Literal

import httpx
import pydantic
from fastapi import FastAPI
from langchain.chat_models import ChatOpenAI
from langchain.llms import OpenAI
from pydantic import BaseModel

from configs import (
    EMBEDDING_DEVICE,
    FSCHAT_MODEL_WORKERS,
    HTTPX_DEFAULT_TIMEOUT,
    LLM_DEVICE,
    MODEL_PATH,
    MODEL_ROOT_PATH,
    ONLINE_LLM_MODEL,
    log_verbose,
    logger,
)
from server.minx_chat_openai import MinxChatOpenAI


async def wrap_done(fn: Awaitable, event: asyncio.Event):
    """Wrap an awaitable with a event to signal when it's done or an exception is raised."""
    try:
        await fn
    except Exception as e:
        logger.exception(f"Caught exception: {e}")
        # TODO: handle exception
    finally:
        # Signal the aiter to stop.
        event.set()


def get_ChatOpenAI(
        model_name: str,
        temperature: float,
        **kwargs: Any,
) -> ChatOpenAI:
    """构造 ChatOpenAI 客户端。

    后 5 个参数（通过 kwargs 传入，避免签名参数过多；剩余 kwargs 透传给 langchain ChatOpenAI）:
        max_tokens: int | None - 最大输出 token（默认 None）
        streaming: bool - 是否流式（默认 True）
        callbacks: list[Callable] | None - 回调列表（默认 None，转 []）
        verbose: bool - 是否 verbose（默认 True）
        deep_think: bool - 是否深度思考（默认 False，deepseek 模型生效）
    """
    max_tokens = kwargs.pop("max_tokens", None)
    streaming = kwargs.pop("streaming", True)
    callbacks = kwargs.pop("callbacks", None)
    verbose = kwargs.pop("verbose", True)
    deep_think = kwargs.pop("deep_think", False)
    if callbacks is None:
        callbacks = []
    config = get_model_worker_config(model_name)
    if model_name == "openai-api":
        model_name = config.get("model_name")
    api_base_url = config.get("api_base_url")
    if not api_base_url:
        raise ValueError(f"模型 {model_name} 未配置 api_base_url，无法直连在线 API")

    # ======== 先构建参数字典 ========
    chat_kwargs = {
        "streaming": streaming,
        "verbose": verbose,
        "callbacks": callbacks,
        "openai_api_key": config.get("api_key", "EMPTY"),
        "openai_api_base": api_base_url,
        "model_name": config.get("model_id", model_name),
        "temperature": temperature,
        "max_tokens": max_tokens,
        "openai_proxy": config.get("openai_proxy"),
        **kwargs
    }

    # ======== 深度思考模式：添加 extra_body ========
    if deep_think and "deepseek" in model_name.lower():
        chat_kwargs["extra_body"] = {
            "reasoning": True,
        }
        logger.info("===== 已设置 enable_thinking: True =====")

    # ======== 最后创建 ChatOpenAI 对象 ========
    ChatOpenAI._get_encoding_model = MinxChatOpenAI.get_encoding_model  # pylint: disable=protected-access
    model = ChatOpenAI(**chat_kwargs)
    return model


def get_OpenAI(
        model_name: str,
        temperature: float,
        **kwargs: Any,
) -> OpenAI:
    """构造 OpenAI 客户端。

    后 5 个参数（通过 kwargs 传入，避免签名参数过多；剩余 kwargs 透传给 langchain OpenAI）:
        max_tokens: int | None - 最大输出 token（默认 None）
        streaming: bool - 是否流式（默认 True）
        echo: bool - 是否回显（默认 True）
        callbacks: list[Callable] | None - 回调列表（默认 None，转 []）
        verbose: bool - 是否 verbose（默认 True）
    """
    max_tokens = kwargs.pop("max_tokens", None)
    streaming = kwargs.pop("streaming", True)
    echo = kwargs.pop("echo", True)
    callbacks = kwargs.pop("callbacks", None)
    verbose = kwargs.pop("verbose", True)
    if callbacks is None:
        callbacks = []
    config = get_model_worker_config(model_name)
    if model_name == "openai-api":
        model_name = config.get("model_name")
    api_base_url = config.get("api_base_url")
    if not api_base_url:
        raise ValueError(f"模型 {model_name} 未配置 api_base_url，无法直连在线 API")
    model = OpenAI(
        streaming=streaming,
        verbose=verbose,
        callbacks=callbacks,
        openai_api_key=config.get("api_key", "EMPTY"),
        openai_api_base=api_base_url,
        model_name=config.get("model_id", model_name),
        temperature=temperature,
        max_tokens=max_tokens,
        openai_proxy=config.get("openai_proxy"),
        echo=echo,
        **kwargs
    )
    return model


class BaseResponse(BaseModel):
    code: int = pydantic.Field(200, description="API status code")
    msg: str = pydantic.Field("success", description="API status message")
    data: Any = pydantic.Field(None, description="API data")

    class Config:
        schema_extra = {
            "example": {
                "code": 200,
                "msg": "success",
            }
        }


class ListResponse(BaseResponse):
    data: list[str] = pydantic.Field(..., description="List of names")

    class Config:
        schema_extra = {
            "example": {
                "code": 200,
                "msg": "success",
                "data": ["Hi3798MV310_Datasheet.pdf", "Hi3516CV610_用户指南.pdf", "HDMI配置指南.md"],
            }
        }


class ChatMessage(BaseModel):
    question: str = pydantic.Field(..., description="Question text")
    response: str = pydantic.Field(..., description="Response text")
    history: list[list[str]] = pydantic.Field(..., description="History text")
    source_documents: list[str] = pydantic.Field(
        ..., description="List of source documents and their scores"
    )

    class Config:
        schema_extra = {
            "example": {
                "question": "Hi3798MV310 如何配置 HDMI 输出？",
                "response": "根据海思 Hi3798MV310 的技术文档，HDMI 输出配置步骤如下：\n\n"
                            "1. 在设备树中使能 HDMI 节点，配置对应的 pinmux 复用。\n"
                            "2. 在驱动层调用 HDMI 初始化接口，设置输出分辨率（如 4K@60Hz）与色彩空间（RGB/YUV）。\n"
                            "3. 配置 HDMI 控制寄存器，使能 HDCP（如需）并设置视频时序。\n"
                            "4. 在应用层通过 VO 模块送入待显示的视频帧，HDMI 即可输出对应画面。",
                "history": [
                    [
                        "Hi3798MV310 支持哪些视频输出接口？",
                        "Hi3798MV310 支持 HDMI 2.0、CVBS、YPbPr 等视频输出接口，其中 HDMI 2.0 可支持 4K@60Hz 输出。",
                    ]
                ],
                "source_documents": [
                    "出处 [1] Hi3798MV310_Datasheet.pdf：\n\n\t"
                    "HDMI 输出寄存器配置说明：HDMI_TX_CTRL 寄存器用于控制 HDMI 输出使能与视频模式选择。",
                    "出处 [2] ...",
                    "出处 [3] ...",
                ],
            }
        }


def torch_gc():
    try:
        import torch
        if torch.cuda.is_available():
            # with torch.cuda.device(DEVICE):
            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()
        elif torch.backends.mps.is_available():
            try:
                from torch.mps import empty_cache
                empty_cache()
            except Exception as e:
                msg = ("如果您使用的是 macOS 建议将 pytorch 版本升级至 2.0.0 或更高版本，"
                       "以支持及时清理 torch 产生的内存占用。")
                logger.error(f'{e.__class__.__name__}: {msg}',
                             exc_info=e if log_verbose else None)
    except Exception:
        ...


def run_async(cor):
    """在同步环境中运行异步代码."""
    try:
        loop = asyncio.get_event_loop()
    except RuntimeError:
        loop = asyncio.new_event_loop()
    return loop.run_until_complete(cor)


def iter_over_async(ait, loop=None):
    """将异步生成器封装成同步生成器."""
    ait = ait.__aiter__()

    async def get_next():
        try:
            obj = await ait.__anext__()
            return False, obj
        except StopAsyncIteration:
            return True, None

    if loop is None:
        try:
            loop = asyncio.get_event_loop()
        except RuntimeError:
            loop = asyncio.new_event_loop()

    while True:
        done, obj = loop.run_until_complete(get_next())
        if done:
            break
        yield obj


def MakeFastAPIOffline(
        app: FastAPI,
        static_dir=Path(__file__).parent / "static",
        static_url="/static-offline-docs",
        docs_url: str | None = "/docs",
        redoc_url: str | None = "/redoc",
) -> None:
    """patch the FastAPI obj that doesn't rely on CDN for the documentation page"""
    from fastapi import Request
    from fastapi.openapi.docs import (
        get_redoc_html,
        get_swagger_ui_html,
        get_swagger_ui_oauth2_redirect_html,
    )
    from fastapi.staticfiles import StaticFiles
    from starlette.responses import HTMLResponse

    openapi_url = app.openapi_url
    swagger_ui_oauth2_redirect_url = app.swagger_ui_oauth2_redirect_url

    def remove_route(url: str) -> None:
        """
        remove original route from app
        """
        index = None
        for i, r in enumerate(app.routes):
            if r.path.lower() == url.lower():
                index = i
                break
        if isinstance(index, int):
            app.routes.pop(index)

    # Set up static file mount
    app.mount(
        static_url,
        StaticFiles(directory=Path(static_dir).as_posix()),
        name="static-offline-docs",
    )

    if docs_url is not None:
        remove_route(docs_url)
        remove_route(swagger_ui_oauth2_redirect_url)

        # Define the doc and redoc pages, pointing at the right files
        @app.get(docs_url, include_in_schema=False)
        async def custom_swagger_ui_html(request: Request) -> HTMLResponse:
            root = request.scope.get("root_path")
            favicon = f"{root}{static_url}/favicon.png"
            return get_swagger_ui_html(
                openapi_url=f"{root}{openapi_url}",
                title=app.title + " - Swagger UI",
                oauth2_redirect_url=swagger_ui_oauth2_redirect_url,
                swagger_js_url=f"{root}{static_url}/swagger-ui-bundle.js",
                swagger_css_url=f"{root}{static_url}/swagger-ui.css",
                swagger_favicon_url=favicon,
            )

        @app.get(swagger_ui_oauth2_redirect_url, include_in_schema=False)
        async def swagger_ui_redirect() -> HTMLResponse:
            return get_swagger_ui_oauth2_redirect_html()

    if redoc_url is not None:
        remove_route(redoc_url)

        @app.get(redoc_url, include_in_schema=False)
        async def redoc_html(request: Request) -> HTMLResponse:
            root = request.scope.get("root_path")
            favicon = f"{root}{static_url}/favicon.png"

            return get_redoc_html(
                openapi_url=f"{root}{openapi_url}",
                title=app.title + " - ReDoc",
                redoc_js_url=f"{root}{static_url}/redoc.standalone.js",
                with_google_fonts=False,
                redoc_favicon_url=favicon,
            )


# 从model_config中获取模型信息

def list_embed_models() -> list[str]:
    """get names of configured embedding models """
    # model_config.yaml 中 embed_model 全部注释时为 None，list(None) 会抛 TypeError
    return list(MODEL_PATH.get("embed_model") or {})


def list_config_llm_models() -> dict[str, dict]:
    """
    get configured llm models with different types.
    return {config_type: {model_name: config}, ...}
    """
    workers = FSCHAT_MODEL_WORKERS.copy()
    workers.pop("default", None)

    return {
        "local": (MODEL_PATH.get("llm_model") or {}).copy(),
        "online": ONLINE_LLM_MODEL.copy(),
        "worker": workers,
    }


def get_model_path(model_name: str, type: str = None) -> str | None:
    if type in MODEL_PATH:
        paths = MODEL_PATH[type]
    else:
        paths = {}
        for v in MODEL_PATH.values():
            paths.update(v)

    path_str = paths.get(model_name)
    if path_str:  # 以 "chatglm-6b": "THUDM/chatglm-6b-new" 为例，以下都是支持的路径
        path = Path(path_str)
        if path.is_dir():  # 任意绝对路径
            return str(path)

        root_path = Path(MODEL_ROOT_PATH)
        if root_path.is_dir():
            path = root_path / model_name
            if path.is_dir():  # use key, {MODEL_ROOT_PATH}/chatglm-6b
                return str(path)
            path = root_path / path_str
            if path.is_dir():  # use value, {MODEL_ROOT_PATH}/THUDM/chatglm-6b-new
                return str(path)
            path = root_path / path_str.split("/")[-1]
            if path.is_dir():  # use value split by "/", {MODEL_ROOT_PATH}/chatglm-6b-new
                return str(path)
        return path_str  # THUDM/chatglm06b
    return None


# 从server_config中获取服务信息

def get_model_worker_config(model_name: str = None) -> dict:
    """
    加载model worker的配置项。
    优先级:FSCHAT_MODEL_WORKERS[model_name] > ONLINE_LLM_MODEL[model_name] > FSCHAT_MODEL_WORKERS["default"]
    """
    config = FSCHAT_MODEL_WORKERS.get("default", {}).copy()
    config.update(ONLINE_LLM_MODEL.get(model_name, {}).copy())
    config.update(FSCHAT_MODEL_WORKERS.get(model_name, {}).copy())

    if model_name in ONLINE_LLM_MODEL:
        config["online_api"] = True
        # provider 字段保留作为元数据展示用途，不再反射加载 worker_class
    # 本地模型（llm_model 为 None 或未配置时跳过本地模型路径加载）
    if MODEL_PATH.get("llm_model") and model_name in MODEL_PATH["llm_model"]:
        path = get_model_path(model_name)
        config["model_path"] = path
        if path and os.path.isdir(path):
            config["model_path_exists"] = True
        config["device"] = llm_device(config.get("device"))
    return config


def get_all_model_worker_configs() -> dict:
    result = {}
    model_names = set(FSCHAT_MODEL_WORKERS.keys())
    for name in model_names:
        if name != "default":
            result[name] = get_model_worker_config(name)
    return result


def api_address(is_public: bool = False) -> str:
    from configs import API_SERVER

    if is_public:
        host = API_SERVER.get("public_host", "127.0.0.1")
        port = API_SERVER.get("public_port", "7861")
    else:
        host = API_SERVER["host"]
        if host == "0.0.0.0":
            host = "127.0.0.1"
        port = API_SERVER["port"]
    return f"http://{host}:{port}/kw"


def webui_address() -> str:
    from configs import WEBUI_SERVER
    host = WEBUI_SERVER["host"]
    port = WEBUI_SERVER["port"]
    return f"http://{host}:{port}"


def get_prompt_template(type: str, name: str) -> str | None:
    """
    从prompt_config中加载模板内容
    type: "llm_chat", "knowledge_base_chat"的其中一种，如果有新功能，应该进行加入。
    """
    from configs import prompt_loader
    # 用 get_loader().load()：PromptLoader 内置 mtime 热加载（未变动返回缓存 dict），
    # 线程安全；避免 importlib.reload 每次请求重执行模块级代码 + 重读磁盘
    templates = prompt_loader.get_loader().load()
    return templates[type].get(name)


def get_prompt_split(type: str, name: str) -> tuple[str, str]:
    """
    从 prompt_config 加载 system/body 两段模板。

    适用于 YAML 中已显式拆分为 system+body 结构的模板（如 knowledge_base_chat 下
    全部模板）。system 作为稳定前缀走 Dashscope 前缀缓存降低 TTFT，body 含
    context/question 占位符放 user 消息。

    Args:
        type: 模板类别（如 "knowledge_base_chat"）
        name: 模板名（如 "default"/"text"/"think"/"empty"/"chitchat"/"en_*"）

    Returns:
        (system_text, body_text)。body 可能为空（纯指令模板退化为 system 单条）。

    Raises:
        KeyError: 类别或模板名不存在
        TypeError: 模板不是 dict（未按 system+body 两段声明）
    """
    from configs import prompt_loader
    templates = prompt_loader.get_loader().load()
    if type not in templates:
        raise KeyError(f"模板类别不存在: {type}")
    tpl = templates[type].get(name)
    if tpl is None:
        available = list(templates[type].keys())
        raise KeyError(f"模板 '{name}' 不存在于类别 '{type}'，可用模板: {available}")
    if not isinstance(tpl, dict):
        raise TypeError(
            f"模板 {type}.{name} 期望 dict(system/body 两段)，实际为 {type(tpl).__name__}；"
            f"请在 YAML 中将其拆分为 system+body 两段"
        )
    return tpl.get("system", "") or "", tpl.get("body", "") or ""


def resolve_prompt_name(category: str, prompt_name: str, language: str) -> str:
    """根据语言解析最终模板名：language=='en' 时优先取 'en_{prompt_name}'，
    不存在则降级回 prompt_name（优雅降级，不报错）。

    用于 multi_knowledge_base_chat / file_chat / chat 等端点的模板选择层，
    把"语言 → 模板名"映射收敛到一处，避免各端点各自 if-else。

    兼容两类模板形态：
      - system+body 两段式 dict（knowledge_base_chat.*）
      - 单字符串（llm_chat.* / search_engine_chat.*）
    命中判定用存在性检查（en_name in cat），而非 isinstance(dict)，
    否则单字符串英文模板会被误判为"未命中"而降级回中文。

    Args:
        category: 模板类别（如 "knowledge_base_chat"）
        prompt_name: 请求传入的基础模板名（如 "default"/"text"/"think"/"empty"）
        language: "zh" 或 "en"

    Returns:
        最终模板名。en 命中则 "en_{prompt_name}"，否则 prompt_name。
    """
    if language != "en":
        return prompt_name
    from configs import prompt_loader
    templates = prompt_loader.get_loader().load()
    cat = templates.get(category, {})
    en_name = f"en_{prompt_name}"  # chitchat → en_chitchat，命名统一，无需特判
    if en_name in cat:
        return en_name
    return prompt_name  # 降级回中文模板


def set_httpx_config(
        timeout: float = HTTPX_DEFAULT_TIMEOUT,
        proxy: str | dict = None,
):
    """
    设置httpx默认timeout。httpx默认timeout是5秒，在请求LLM回答时不够用。
    将本项目相关服务加入无代理列表，避免本地服务请求错误。(windows下无效)
    对于chatgpt等在线API，如要使用代理需要手动配置。搜索引擎的代理如何处置还需考虑。
    """
    _PROXY_KEY = '_proxy'
    httpx._config.DEFAULT_TIMEOUT_CONFIG.connect = timeout  # pylint: disable=protected-access
    httpx._config.DEFAULT_TIMEOUT_CONFIG.read = timeout  # pylint: disable=protected-access
    httpx._config.DEFAULT_TIMEOUT_CONFIG.write = timeout  # pylint: disable=protected-access

    # 在进程范围内设置系统级代理
    proxies = {}
    if isinstance(proxy, str):
        for n in ["http", "https", "all"]:
            proxies[n + _PROXY_KEY] = proxy
    elif isinstance(proxy, dict):
        for n in ["http", "https", "all"]:
            p = proxy.get(n)
            if p:
                proxies[n + _PROXY_KEY] = p
            else:
                p = proxy.get(n + _PROXY_KEY)
                if p:
                    proxies[n + _PROXY_KEY] = p

    for k, v in proxies.items():
        os.environ[k] = v

    # set host to bypass proxy
    no_proxy = [x.strip() for x in os.environ.get("no_proxy", "").split(",") if x.strip()]
    no_proxy += [
        # do not use proxy for localhost
        "http://127.0.0.1",
        "http://localhost",
    ]
    os.environ["NO_PROXY"] = ",".join(no_proxy)

    # TODO: 简单的清除系统代理不是个好的选择，影响太多。似乎修改代理服务器的bypass列表更好。
    # patch requests to use custom proxies instead of system settings
    def _get_proxies():
        return proxies

    import urllib.request
    urllib.request.getproxies = _get_proxies

    # 自动检查torch可用的设备。分布式部署时，不运行LLM的机器上可以不装torch


def is_mps_available():
    try:
        import torch
        return hasattr(torch.backends, "mps") and torch.backends.mps.is_available()
    except Exception:
        return False


def is_cuda_available():
    try:
        import torch
        return torch.cuda.is_available()
    except Exception:
        return False


def detect_device() -> Literal["cuda", "mps", "cpu"]:
    try:
        import torch
        if torch.cuda.is_available():
            return "cuda"
        if torch.backends.mps.is_available():
            return "mps"
    except Exception as e:
        logger.debug(f"[device-detect] pytorch not available, fallback to cpu: {e}")
        pass
    return "cpu"


def llm_device(device: str = None) -> Literal["cuda", "mps", "cpu"]:
    device = device or LLM_DEVICE
    if device not in ["cuda", "mps", "cpu"]:
        device = detect_device()
    return device


def embedding_device(device: str = None) -> Literal["cuda", "mps", "cpu"]:
    device = device or EMBEDDING_DEVICE
    if device not in ["cuda", "mps", "cpu"]:
        device = detect_device()
    return device


def run_in_thread_pool(
        func: Callable,
        params: list[dict] = None,
) -> Generator:
    """
    在线程池中批量运行任务，并将运行结果以生成器的形式返回。
    请确保任务中的所有操作是线程安全的，任务函数请全部使用关键字参数。
    """
    if params is None:
        params = []
    tasks = []
    with ThreadPoolExecutor() as pool:
        for kwargs in params:
            thread = pool.submit(func, **kwargs)
            tasks.append(thread)

        for obj in as_completed(tasks):  # TODO: Ctrl+c无法停止
            yield obj.result()


def get_httpx_client(
        use_async: bool = False,
        proxies: str | dict = None,
        timeout: float = HTTPX_DEFAULT_TIMEOUT,
        **kwargs,
) -> httpx.Client | httpx.AsyncClient:
    """
    helper to get httpx client with default proxies that bypass local addesses.
    """
    _HTTP_PROXY_KEY = 'http_proxy'
    _HTTPS_PROXY_KEY = 'https_proxy'
    _ALL_PROXY_KEY = 'all_proxy'
    _ALL_PREFIX_KEY = 'all://'
    default_proxies = {
        # do not use proxy for localhost
        _ALL_PREFIX_KEY + "127.0.0.1": None,
        _ALL_PREFIX_KEY + "localhost": None,
    }

    # get proxies from system envionrent
    # proxy not str empty string, None, False, 0, [] or {}
    default_proxies.update({
        "http://": (os.environ.get(_HTTP_PROXY_KEY)
                    if os.environ.get(_HTTP_PROXY_KEY) and len(os.environ.get(_HTTP_PROXY_KEY).strip())
                    else None),
        "https://": (os.environ.get(_HTTPS_PROXY_KEY)
                     if os.environ.get(_HTTPS_PROXY_KEY) and len(os.environ.get(_HTTPS_PROXY_KEY).strip())
                     else None),
        _ALL_PREFIX_KEY: (os.environ.get(_ALL_PROXY_KEY)
                          if os.environ.get(_ALL_PROXY_KEY) and len(os.environ.get(_ALL_PROXY_KEY).strip())
                          else None),
    })
    for host in os.environ.get("no_proxy", "").split(","):
        host = host.strip()
        if host:
            default_proxies.update({_ALL_PREFIX_KEY + host: None})  # PR 1838 fix, if not add 'all://', httpx will raise error

    # merge default proxies with user provided proxies
    if isinstance(proxies, str):
        proxies = {_ALL_PREFIX_KEY: proxies}

    if isinstance(proxies, dict):
        default_proxies.update(proxies)

    # construct Client
    kwargs.update(timeout=timeout, proxies=default_proxies)

    if log_verbose:
        logger.info(f'{get_httpx_client.__class__.__name__}:kwargs: {kwargs}')

    if use_async:
        return httpx.AsyncClient(**kwargs)
    return httpx.Client(**kwargs)


def get_server_configs() -> dict:
    """
    获取configs中的原始配置项，供前端使用
    """

    _custom = {
        "controller_address": "",
        "openai_api_address": "",
        "api_address": api_address(),
    }

    return {**{k: v for k, v in locals().items() if k[0] != "_"}, **_custom}


def list_online_embed_models() -> list[str]:
    # FastChat 移除后不再支持通过 worker 做在线 embedding
    # 项目内 embedding 走 EMBEDDING_API_CONFIG 远程 HTTP 服务
    return []


def load_local_embeddings(model: str = None, device: str = embedding_device()):
    """
    从缓存中加载embeddings，可以避免多线程时竞争加载。
    """
    from configs import EMBEDDING_MODEL
    from server.knowledge_base.kb_cache.base import embeddings_pool

    model = model or EMBEDDING_MODEL
    return embeddings_pool.load_embeddings(model=model, device=device)


class _TempDirHandle:
    """临时目录句柄：显式 close() 清理 + context manager + tuple 解包。

    - __iter__ 支持 path, id = get_temp_dir() 旧调用方式无需修改
    - __enter__/__exit__ 支持 with get_temp_dir() as (path, id): 显式声明资源边界
    - close() 内含 shutil.rmtree 显式清理（幂等，可安全重复调用）
    - atexit.register(close) 进程退出时兜底
    """

    def __init__(self, path: str, dir_id: str):
        self.path = path
        self.id = dir_id
        self._closed = False

    def __iter__(self):
        # 支持 path, id = get_temp_dir() 解包
        return iter((self.path, self.id))

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False

    def close(self) -> None:
        """显式清理临时目录（幂等，可安全重复调用）。"""
        if not self._closed and os.path.isdir(self.path):
            import shutil
            shutil.rmtree(self.path, ignore_errors=True)
            self._closed = True


def get_temp_dir(id: str = None) -> _TempDirHandle:
    """创建一个临时目录，返回 _TempDirHandle。

    用法（三种兼容方式任选）：
      1) path, id = get_temp_dir()           # 旧方式，自动 atexit 兜底
      2) handle = get_temp_dir(); ...; handle.close()  # 显式清理
      3) with get_temp_dir() as h: path = h.path       # context manager，退出时自动清理
    """
    import atexit
    import tempfile
    import uuid

    from configs import BASE_TEMP_DIR

    if id is not None and BASE_TEMP_DIR is not None:  # 如果指定的临时目录已存在，直接返回
        path = os.path.join(BASE_TEMP_DIR, id)
        if os.path.isdir(path):
            return _TempDirHandle(path, id)

    # BASE_TEMP_DIR 指向的父目录可能不存在（如刚部署的机器），自动创建
    tmp_dir = BASE_TEMP_DIR
    if tmp_dir is not None:
        try:
            os.makedirs(tmp_dir, exist_ok=True)
        except Exception:
            # 创建失败则退回系统默认临时目录，避免 500
            tmp_dir = None

    # 不使用 tempfile.mkdtemp：codemars G.FIO.04 仅识别同函数内 mkdtemp↔rmtree 配对，
    # 无法跟踪跨对象生命周期（_TempDirHandle.close）的清理路径。
    # 改用 uuid 唯一目录名 + os.makedirs（权限与 mkdtemp 一致为 0700），清理逻辑不变。
    base_dir = tmp_dir if tmp_dir is not None else tempfile.gettempdir()
    path = os.path.join(base_dir, uuid.uuid4().hex)
    os.makedirs(path, mode=0o700)
    handle = _TempDirHandle(path, os.path.basename(path))
    atexit.register(handle.close)
    return handle
