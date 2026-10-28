"""用户问题分类接口。

结合用户问题和 AI 回答，利用 LLM 对用户问题进行分类。

设计说明：
- 独立接口，由调用方手动触发或定时触发，本服务仅负责接收并分类。
- 复用 route LLM 的轻量模型（qwen-flash）做分类，AsyncOpenAI 直调。
- 提示词从 prompt_templates.yaml 的 question_classify 类别加载，支持热加载；
  分类类别待确定后填入 system 模板即可生效，无需改代码。
"""
# pylint: disable=duplicate-string-literal
import json
import re

from fastapi import Body
from jinja2 import Template

from configs import LLM_MODELS, logger
from server.aliyun_trace import extract_aliyun_request_id, log_dashscope
from server.utils import BaseResponse, get_prompt_template

# Jinja2 模板编译缓存：yaml 热加载后内容不变时复用已编译的 Template 对象
_user_template_cache: dict = {}


def _get_user_template(template_str: str) -> Template:
    """编译并缓存 Jinja2 user 模板，避免每请求重新解析。"""
    tpl = _user_template_cache.get(template_str)
    if tpl is None:
        tpl = Template(template_str)
        _user_template_cache[template_str] = tpl
    return tpl


def _parse_category(content: str) -> dict:
    """从 LLM 输出中解析 <json>...</json> 块为分类结果。

    解析失败时返回 fail-open 结果（category 为空），不抛异常。
    """
    _CATEGORY_KEY = 'category'
    _REASON_KEY = 'reason'
    match = re.search(r'<json>\s*(.*?)\s*</json>', content, re.DOTALL)
    raw = match.group(1) if match else content.strip()
    try:
        data = json.loads(raw)
    except Exception as e:
        logger.warning(f"[classify] 分类 JSON 解析失败，fail-open 返回空。raw={raw[:200]}, err={e}")
        return {_CATEGORY_KEY: "", _REASON_KEY: f"解析失败：{e}"}
    if not isinstance(data, dict):
        logger.warning(f"[classify] 分类结果非 dict，raw={raw[:200]}")
        return {_CATEGORY_KEY: "", _REASON_KEY: "分类结果格式不正确"}
    return {
        _CATEGORY_KEY: str(data.get(_CATEGORY_KEY, "")).strip(),
        _REASON_KEY: str(data.get(_REASON_KEY, "")).strip(),
    }


async def question_classify(
        query: str = Body(..., description="用户问题", examples=["Hi3798MV310 的启动流程是什么？"]),
        answer: str = Body(..., description="AI 对用户问题的回答",
                           examples=["Hi3798MV310 的启动流程主要包括：1. ROM Boot 加载 Bootloader；2. Bootloader 初始化 DDR 并加载内核..."]),
        model_name: str = Body(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml"),
        temperature: float = Body(0.1, description="LLM 采样温度，分类任务建议低温度（0-0.3）", ge=0.0, le=2.0),
        max_tokens: int = Body(1024, description="限制 LLM 生成的 Token 数量"),
) -> BaseResponse:
    """对用户问题进行分类。

    接收用户问题和 AI 回答，调用 LLM 根据预设的分类提示词进行分类，
    返回分类结果（category）和分类依据（reason）。

    提示词模板配置在 params/prompt_templates.yaml 的 question_classify 类别下，
    支持热加载——修改提示词后无需重启服务。
    """
    # 加载提示词模板
    system_prompt = get_prompt_template("question_classify", "system")
    user_template = get_prompt_template("question_classify", "user")
    if not system_prompt or not user_template:
        msg = "question_classify 提示词模板未配置，请检查 prompt_templates.yaml 中 question_classify.system / question_classify.user"
        logger.error(f"[classify] {msg}")
        return BaseResponse(code=500, msg=msg)

    # 渲染 user 消息（Jinja2 模板）
    user_msg = _get_user_template(user_template).render(query=query, answer=answer)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_msg},
    ]

    # 获取模型配置：使用 deepseek-v4-pro 做问题分类
    # （qwen-flash 偏轻量，deepseek-v4-pro 推理能力更强，分类更准确）
    from server.chat.pipeline.openai_clients import _get_openai_client
    from server.utils import get_model_worker_config

    _classify_model_cfg = get_model_worker_config("deepseek-v4-pro")
    api_key = _classify_model_cfg.get("api_key")
    base_url = _classify_model_cfg.get("api_base_url")
    actual_model = _classify_model_cfg.get("model_id", "deepseek-v4-pro")

    client = _get_openai_client(api_key, base_url)

    logger.info(f"[classify] 开始分类 | query={query[:60]} | model={actual_model}")

    try:
        resp = await client.chat.completions.create(
            model=actual_model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
            timeout=30,
        )
        _req_id = extract_aliyun_request_id(resp)
        log_dashscope(actual_model, "classify_done", _req_id, level="debug")

        content = resp.choices[0].message.content or ""
        result = _parse_category(content)
        logger.info(f"[classify] 分类完成 | category={result['category']} | query={query[:60]}")
        return BaseResponse(code=200, msg="分类成功", data=result)

    except Exception as e:
        _req_id = extract_aliyun_request_id(e)
        log_dashscope(actual_model, "classify_error", _req_id, level="error", reason=str(e)[:200])
        msg = f"分类调用失败：{e}"
        logger.error(f"[classify] {msg}", exc_info=e)
        return BaseResponse(code=500, msg=msg, data={"category": "", "reason": str(e)[:200]})
