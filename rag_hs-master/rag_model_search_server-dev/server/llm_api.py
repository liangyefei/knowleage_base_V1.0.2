
from fastapi import Body

from configs import ONLINE_LLM_MODEL, log_verbose, logger
from server.utils import BaseResponse, get_model_worker_config, list_config_llm_models


def list_running_models(
    controller_address: str = Body(None, description="已废弃（FastChat 移除），保留参数以兼容前端"),
    placeholder: str = Body(None, description="占位参数，无实际效果"),
) -> BaseResponse:
    """
    列出当前已加载（在线）的 LLM 模型列表及其配置项。

    在线 API 模型配置即在线，直接从 ONLINE_LLM_MODEL 返回所有配置了 api_base_url 的模型。
    """
    try:
        data = {}
        for model_name, config in ONLINE_LLM_MODEL.items():
            if config.get("api_base_url"):
                data[model_name] = get_model_config(model_name).data
        return BaseResponse(data=data)
    except Exception as e:
        logger.error(f'{e.__class__.__name__}: {e}',
                        exc_info=e if log_verbose else None)
        return BaseResponse(
            code=500,
            data={},
            msg=f"failed to get available models. 错误信息是： {e}")


def list_config_models(
    types: list[str] = Body(["local", "online"], description="模型配置类别，可选值：local（本地）、online（在线）、worker（工作进程）"),
    placeholder: str = Body(None, description="占位参数，无实际效果")
) -> BaseResponse:
    """
    从本地 configs 中获取已配置的 LLM 模型列表。
    """
    data = {}
    for type, models in list_config_llm_models().items():
        if type in types:
            data[type] = {m: get_model_config(m).data for m in models}
    return BaseResponse(data=data)


def get_model_config(
    model_name: str = Body(description="配置中 LLM 模型的名称", examples=["doubao-1.5-pro-32k-api"]),
    placeholder: str = Body(None, description="占位参数，无实际效果")
) -> BaseResponse:
    """
    获取指定 LLM 模型的合并配置项（已脱敏，不含 api_key/secret 等敏感字段）。
    """
    config = {}
    # 删除ONLINE_MODEL配置中的敏感信息
    for k, v in get_model_worker_config(model_name=model_name).items():
        if not (k == "worker_class"
            or "key" in k.lower()
            or "secret" in k.lower()
            or k.lower().endswith("id")):
            config[k] = v

    return BaseResponse(data=config)


def stop_llm_model(
    model_name: str = Body(..., description="要停止的 LLM 模型名称", examples=["doubao-1.5-pro-32k-api"]),
    controller_address: str = Body(None, description="已废弃（FastChat 移除），保留参数以兼容前端")
) -> BaseResponse:
    """
    停止指定的 LLM 模型。

    在线 API 模型配置即在线，无需停止，返回友好提示。
    """
    return BaseResponse(
        code=200,
        msg=f"在线 API 模型 {model_name} 无需停止（配置即在线）"
    )


def change_llm_model(
    model_name: str = Body(..., description="当前运行的模型名称", examples=["doubao-1.5-pro-32k-api"]),
    new_model_name: str = Body(..., description="要切换到的目标模型名称", examples=["chatglm3-api"]),
    controller_address: str = Body(None, description="已废弃（FastChat 移除），保留参数以兼容前端")
) -> BaseResponse:
    """
    切换指定的 LLM 模型。

    在线 API 模型配置即在线，无需切换，提示直接修改配置并重启服务。
    """
    return BaseResponse(
        code=200,
        msg=f"在线 API 模型无需切换，请直接修改 params/model_config.yaml 并重启服务（当前: {model_name}, 目标: {new_model_name}）"
    )
