import asyncio
from collections.abc import AsyncIterable

from pydantic import BaseModel, Field
from langchain.callbacks import AsyncIteratorCallbackHandler
from langchain.chains import LLMChain
from langchain.prompts.chat import ChatPromptTemplate, HumanMessagePromptTemplate
from sse_starlette.sse import EventSourceResponse

from configs import LLM_MODELS, TEMPERATURE
from server.utils import get_ChatOpenAI, get_prompt_template, wrap_done


class CompletionRequest(BaseModel):
    query: str = Field(..., description="用户输入的补全提示", examples=["Hi3798MV310 是一款面向"])
    stream: bool = Field(False, description="是否流式输出（SSE）")
    echo: bool = Field(False, description="是否回显输入内容（除输出外，还返回输入）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)
    max_tokens: int | None = Field(1024, description="限制 LLM 生成的 Token 数量，None 表示使用模型默认最大值")
    # top_p: float = Field(TOP_P, description="LLM 核采样。勿与temperature同时设置", gt=0.0, lt=1.0),
    prompt_name: str = Field("default",
                             description="使用的 prompt 模板名称（在 configs/prompt_templates.yaml 中配置）")


async def completion(request: CompletionRequest):
    query = request.query
    stream = request.stream
    echo = request.echo
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens
    prompt_name = request.prompt_name

    #todo 因ApiModelWorker 默认是按chat处理的，会对params["prompt"] 解析为messages，因此ApiModelWorker 使用时需要有相应处理
    async def completion_iterator(query: str,
                                  model_name: str = LLM_MODELS[0],
                                  prompt_name: str = prompt_name,
                                  echo: bool = echo,
                                  ) -> AsyncIterable[str]:
        nonlocal max_tokens
        callback = AsyncIteratorCallbackHandler()
        if isinstance(max_tokens, int) and max_tokens <= 0:
            max_tokens = None

        model = get_ChatOpenAI(
            model_name=model_name,
            temperature=temperature,
            max_tokens=max_tokens,
            callbacks=[callback],
        )

        prompt_template = get_prompt_template("completion", prompt_name)
        human_msg = HumanMessagePromptTemplate.from_template(
            prompt_template, template_format="jinja2"
        )
        chat_prompt = ChatPromptTemplate.from_messages([human_msg])
        chain = LLMChain(prompt=chat_prompt, llm=model)

        # Begin a task that runs in the background.
        task = asyncio.create_task(wrap_done(
            chain.acall({"input": query}),
            callback.done),
        )

        if stream:
            async for token in callback.aiter():
                # Use server-sent-events to stream the response
                yield token
        else:
            answer = ""
            async for token in callback.aiter():
                answer += token
            yield answer

        await task

    return EventSourceResponse(completion_iterator(query=query,
                                                 model_name=model_name,
                                                 prompt_name=prompt_name),
                             )
