from pydantic import BaseModel, root_validator

from configs import TEMPERATURE
from server.utils import get_model_worker_config

__all__ = ["ApiConfigParams", "ApiModelParams", "ApiChatParams", "ApiCompletionParams", "ApiEmbeddingsParams"]


class ApiConfigParams(BaseModel):
    """
    在线API配置参数，未提供的值会自动从model_config.ONLINE_LLM_MODEL中读取
    """
    api_base_url: str | None = None
    api_proxy: str | None = None
    api_key: str | None = None
    secret_key: str | None = None
    group_id: str | None = None  # for minimax
    is_pro: bool = False  # for minimax

    APPID: str | None = None  # for xinghuo
    APISecret: str | None = None  # for xinghuo
    is_v2: bool = False  # for xinghuo

    worker_name: str | None = None

    class Config:
        extra = "allow"

    @root_validator(pre=True)
    def validate_config(cls, v: dict) -> dict:  # pylint: disable=no-self-argument
        config = get_model_worker_config(v.get("worker_name"))
        if config:
            for n in cls.__fields__:
                if n in config:
                    v[n] = config[n]
        return v

    def load_config(self, worker_name: str):
        self.worker_name = worker_name
        config = get_model_worker_config(worker_name)
        if config:
            for n in self.__fields__:
                if n in config:
                    setattr(self, n, config[n])
        return self


class ApiModelParams(ApiConfigParams):
    """
    模型配置参数
    """
    version: str | None = None
    version_url: str | None = None
    api_version: str | None = None  # for azure
    deployment_name: str | None = None  # for azure
    resource_name: str | None = None  # for azure

    temperature: float = TEMPERATURE
    max_tokens: int | None = None
    top_p: float | None = 1.0


class ApiChatParams(ApiModelParams):
    """
    chat请求参数
    """
    messages: list[dict[str, str]]
    system_message: str | None = None  # for minimax
    role_meta: dict = {}  # for minimax


class ApiCompletionParams(ApiModelParams):
    prompt: str


class ApiEmbeddingsParams(ApiConfigParams):
    texts: list[str]
    embed_model: str | None = None
    to_query: bool = False  # for minimax
