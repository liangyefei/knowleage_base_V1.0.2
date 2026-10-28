#!/usr/bin/env python3
# @Time : 2024/3/27 16:49:44
# @Author : Hetao
# @File : [Code] --> next_question.py
# @Software: PyCharm
# @Function: 根据上一个问题预测下一个问题是什么
import asyncio
import json
import re
from collections.abc import AsyncIterable

from pydantic import BaseModel, Field
from langchain.callbacks import AsyncIteratorCallbackHandler
from langchain.chains import LLMChain
from langchain.prompts.chat import ChatPromptTemplate
from sse_starlette.sse import EventSourceResponse

from configs import LLM_MODELS, NEXT_QUESTION_TEMPERATURE, logger
from server.chat.utils import History
from server.chat.pipeline.language_detect import resolve_language
from server.utils import get_ChatOpenAI, get_prompt_template, resolve_prompt_name, wrap_done


class NextQuestionRequest(BaseModel):
    query: str = Field(..., description="上一个问题的内容", examples=["Hi3798MV310 的启动流程是什么？"])
    history: int | list[History] = Field([],
                                         description="历史对话，传入历史对话内容",
                                         examples=[[
                                             {"role": "user",
                                              "content": "Hi3798MV310 的启动流程是什么？"},
                                             {"role": "assistant",
                                              "content": "Hi3798MV310 的启动流程主要包括：1. ROM Boot 阶段加载 Bootloader；2. Bootloader 初始化 DDR、加载内核与设备树；3. 内核启动并挂接根文件系统；4. 启动 init 进程完成系统初始化。"}]])  # pylint: disable=line-too-long
    predict_number: int = Field(3, description="要生成预测问题的数量，默认 3，最小取 1，最大取 5", ge=1,
                                 le=5, examples=[3])
    # stream: bool = Field(False, description="流式输出"),
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(NEXT_QUESTION_TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-2 之间", ge=0.0,  # pylint: disable=line-too-long
                                le=2.0)
    max_tokens: int | None = Field(None,
                                    description="限制 LLM 生成的 Token 数量，None 表示使用模型默认最大值")
    # top_p: float = Field(TOP_P, description="LLM 核采样。勿与temperature同时设置", gt=0.0, lt=1.0),
    prompt_name: str = Field("default",
                             description="使用的 prompt 模板名称（在 configs/prompt_templates.yaml 中配置）")
    language: str = Field("auto", description="回答语言：auto=自动检测，zh=强制中文，en=强制英文")


async def next_question(request: NextQuestionRequest):
    query = request.query
    history = request.history
    predict_number = request.predict_number
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens
    prompt_name = request.prompt_name
    language = request.language

    async def chat_iterator() -> AsyncIterable[str]:
        _LLM_CHAT_KEY = 'llm_chat'
        nonlocal history, max_tokens
        callback = AsyncIteratorCallbackHandler()
        callbacks = [callback]
        memory = None

        # 下一个问题的提示词设置
        # to_thread 避免 langdetect 同步 CPU 推理阻塞 async 事件循环（英文 cache miss 时）
        _lang = await asyncio.to_thread(resolve_language, query, language)
        logger.info(f"[lang] next_question query_lang={_lang} | query={query[:60]}")
        request_query = query.replace("?", "").replace("？", "")
        if _lang == "en":
            final_query = (f"What are the {predict_number} likely follow-up questions a user "
                           f"might ask after the question '{request_query}'? "
                           f"Output only numbered questions, nothing else.")
        else:
            final_query = f"'{request_query}'该问题的后续用户可能提出的问题是什么，请输出{predict_number}个预测，注意输出内容按序号给出不要有多余内容。"

        if isinstance(max_tokens, int) and max_tokens <= 0:
            max_tokens = None

        model = get_ChatOpenAI(
                model_name=model_name,
                temperature=temperature,
                max_tokens=max_tokens,
                callbacks=callbacks,
        )

        _tpl = resolve_prompt_name(_LLM_CHAT_KEY, prompt_name, _lang)
        if history:  # 优先使用前端传入的历史消息
            history = [History.from_data(h) for h in history]
            prompt_template = get_prompt_template(_LLM_CHAT_KEY, _tpl)
            input_msg = History(role="user", content=prompt_template).to_msg_template(False)
            chat_prompt = ChatPromptTemplate.from_messages(
                    [i.to_msg_template() for i in history] + [input_msg])
        else:
            prompt_template = get_prompt_template(_LLM_CHAT_KEY, _tpl)
            input_msg = History(role="user", content=prompt_template).to_msg_template(False)
            chat_prompt = ChatPromptTemplate.from_messages([input_msg])
        logger.debug(f"chat_prompt: {chat_prompt}")
        chain = LLMChain(prompt=chat_prompt, llm=model, memory=memory)
        logger.debug(f"chain: {chat_prompt}")
        # Begin a task that runs in the background.
        task = asyncio.create_task(wrap_done(
                chain.acall({"input": final_query}),
                callback.done),
        )

        answer = ""
        async for token in callback.aiter():
            answer += token

        questions_str_list = format_questions_str2list(answer_str=answer)
        yield json.dumps(
                {"text": questions_str_list},
                ensure_ascii=False)
        await task

    return EventSourceResponse(chat_iterator())


def format_questions_str2list(answer_str: str):
    """ 格式化输出问题内容
    Args:
        answer_str (str): data: {"text": "\n1. xxx？\n2. xxx？\n3. xxx？\n4. xxx？\n5. xxx？"}
    Returns:
        data: {"text": [xxx？],[xxx？],[xxx？], [xxx？], [xxx？]}
    """
    # 正则表达式模式，匹配出实际问题
    pattern = r'(\d+\.\s*[^(\？|\?)]+[\？|\?])'
    res_list = re.findall(pattern, answer_str)
    questions_str_list = []
    for que_str in res_list:
        sub_pattern = r'([^\.]*[\？|\?])'
        q_res = re.search(sub_pattern, que_str).group().strip().strip("\"")
        if "：" in q_res:
            q_res = q_res.split("：")[1]
        if len(q_res) > 5:  # 问题内容如果太短可能不是一个合格的问题需要过滤掉
            questions_str_list.append(q_res)
    return questions_str_list
