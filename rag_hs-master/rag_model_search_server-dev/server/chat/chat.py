import asyncio
import json
from collections.abc import AsyncIterable

from pydantic import BaseModel, Field
from langchain.callbacks import AsyncIteratorCallbackHandler
from langchain.chains import LLMChain
from langchain.prompts.chat import ChatPromptTemplate
from sse_starlette.sse import EventSourceResponse

from configs import LLM_MODELS, TEMPERATURE
from configs.logger_manager import logger
from server.callback_handler.conversation_callback_handler import ConversationCallbackHandler
from server.chat.utils import History
from server.db.repository import add_message_to_db, filter_message
from server.chat.pipeline.language_detect import resolve_language
from server.utils import get_ChatOpenAI, get_prompt_template, resolve_prompt_name, wrap_done


class ChatRequest(BaseModel):
    query: str = Field(..., description="用户输入的问题", examples=["Hi3798MV310 的启动流程是什么？"])
    conversation_id: str = Field("", description="对话 ID，用于关联会话历史（为空时新建会话）")
    user_id: str = Field(..., description="用户 ID（必填，UUID 格式，用于多租户隔离）")
    history_len: int = Field(-1, description="从数据库中取历史消息的数量，-1 表示不读取，0 表示取全部")
    history: int | list[History] = Field([],
                                         description="历史对话，设为一个整数可以从数据库中读取历史消息（conversation_id 非空且 history_len>0 时优先从数据库读取）",  # pylint: disable=line-too-long
                                         examples=[[
                                             {"role": "user",
                                              "content": "Hi3798MV310 支持哪些视频输出接口？"},
                                             {"role": "assistant",
                                              "content": "Hi3798MV310 支持 HDMI 2.0、CVBS、YPbPr 等视频输出接口，其中 HDMI 2.0 可支持 4K@60Hz 输出。"}]])  # pylint: disable=line-too-long
    stream: bool = Field(False, description="是否流式输出（SSE）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-2 之间", ge=0.0, le=2.0)
    max_tokens: int | None = Field(None, description="限制 LLM 生成的 Token 数量，None 表示使用模型默认最大值")
    # top_p: float = Field(TOP_P, description="LLM 核采样。勿与temperature同时设置", gt=0.0, lt=1.0),
    prompt_name: str = Field("default", description="使用的 prompt 模板名称（在 configs/prompt_templates.yaml 中配置）")
    language: str = Field("auto", description="回答语言：auto=自动检测，zh=强制中文，en=强制英文")


async def chat(request: ChatRequest):
    query = request.query
    conversation_id = request.conversation_id
    user_id = request.user_id
    history_len = request.history_len
    history = request.history
    stream = request.stream
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens
    prompt_name = request.prompt_name
    language = request.language

    async def chat_iterator() -> AsyncIterable[str]:
        _LLM_CHAT_KEY = 'llm_chat'
        _ANSWER_KEY = 'answer'
        _MSG_ID_KEY = 'message_id'
        nonlocal history, max_tokens
        logger.info(f"[chat] 收到请求 | user={user_id} | conversation={conversation_id} | query={query}")
        callback = AsyncIteratorCallbackHandler()
        callbacks = [callback]
        memory = None

        # 负责保存llm response到message db
        message_id = add_message_to_db(chat_type=_LLM_CHAT_KEY, query=query, conversation_id=conversation_id,
                                        user_id=user_id)
        conversation_callback = ConversationCallbackHandler(conversation_id=conversation_id, message_id=message_id,
                                                            chat_type=_LLM_CHAT_KEY,
                                                            query=query,
                                                            user_id=user_id)
        callbacks.append(conversation_callback)

        if isinstance(max_tokens, int) and max_tokens <= 0:
            max_tokens = None

        model = get_ChatOpenAI(
            model_name=model_name,
            temperature=temperature,
            max_tokens=max_tokens,
            callbacks=callbacks,
        )

        # 统一从 DB 取历史（前端传入 history 废弃，不可信且与 DB 可能不一致）
        # to_thread 避免 langdetect 同步 CPU 推理阻塞 async 事件循环（英文 cache miss 时）
        _lang = await asyncio.to_thread(resolve_language, query, language)
        logger.info(f"[lang] chat query_lang={_lang} | query={query[:60]}")
        _tpl = resolve_prompt_name(_LLM_CHAT_KEY, prompt_name, _lang)
        prompt_template = get_prompt_template(_LLM_CHAT_KEY, _tpl)
        history = []
        if conversation_id and history_len > 0:
            # filter_message 返回倒序（最近在前），reverse 为正序
            db_messages = filter_message(conversation_id=conversation_id, limit=history_len, user_id=user_id) or []
            db_messages = list(reversed(db_messages))
            # 每条 message 记录对应 user + assistant 两条 History
            history_data = []
            for m in db_messages:
                history_data.append({"role": "user", "content": m.get("query", "")})
                history_data.append({"role": "assistant", "content": m.get("response", "")})
            history = [History.from_data(h) for h in history_data]
        input_msg = History(role="user", content=prompt_template).to_msg_template(False)
        chat_prompt = ChatPromptTemplate.from_messages(
            [i.to_msg_template() for i in history] + [input_msg])
        chain = LLMChain(prompt=chat_prompt, llm=model, memory=memory)
        # Begin a task that runs in the background.
        task = asyncio.create_task(wrap_done(
            chain.acall({"input": query}),
            callback.done),
        )

        if stream:
            full_response = ""
            async for token in callback.aiter():
                full_response += token
                # Use server-sent-events to stream the response
                yield json.dumps(
                    {_ANSWER_KEY: token, "type": _ANSWER_KEY, _MSG_ID_KEY: message_id},
                    ensure_ascii=False)
            # 流结束后发送 docs（空列表，与 multi_knowledge_base_chat 格式一致）
            yield json.dumps({"docs": [], _MSG_ID_KEY: message_id}, ensure_ascii=False)
        else:
            full_response = ""

            async for token in callback.aiter():
                full_response += token
            yield json.dumps(
                {_ANSWER_KEY: full_response, "docs": [], _MSG_ID_KEY: message_id},
                ensure_ascii=False)

        logger.info(f"[chat] 返回结果 | user={user_id} | conversation={conversation_id} | "
                    f"response_len={len(full_response)} | response={full_response[:200]}...")
        await task

    return EventSourceResponse(chat_iterator())
