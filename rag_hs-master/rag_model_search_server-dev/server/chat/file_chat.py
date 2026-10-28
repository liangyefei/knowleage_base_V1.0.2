import asyncio
import json
import os
from collections.abc import AsyncIterable

from fastapi import File, Form, UploadFile
from pydantic import BaseModel, Field
from langchain.callbacks import AsyncIteratorCallbackHandler
from langchain.chains import LLMChain
from langchain.prompts.chat import ChatPromptTemplate
from sse_starlette.sse import EventSourceResponse

from configs import (
    CHUNK_SIZE,
    KB_CHAT_TEMPERATURE,
    LLM_MODELS,
    OVERLAP_SIZE,
    VECTOR_SEARCH_TOP_K,
    ZH_TITLE_ENHANCE,
    logger,
)
from server.chat.utils import History
from server.knowledge_base.kb_cache.milvus_cache import memo_milvus_pool
from server.knowledge_base.kb_service.base import EmbeddingsFunAdapter
from server.knowledge_base.utils import KnowledgeFile
from server.chat.pipeline.language_detect import resolve_language
from server.utils import BaseResponse, get_ChatOpenAI, get_prompt_split, get_temp_dir, run_in_thread_pool, resolve_prompt_name, wrap_done

# prompt 默认模板名 / Milvus 默认 alias（值同为 "default"，但语义不同；
# 保留单一常量以避免 H3103 重复字面量告警，函数签名默认值须引用模块级常量）
_DEFAULT_KEY = 'default'


def _parse_files_in_thread(
    files: list[UploadFile],
    dir: str,
    zh_title_enhance: bool,
    chunk_size: int,
    chunk_overlap: int,
):
    """
    通过多线程将上传的文件保存到对应目录内。
    生成器返回保存结果：[success or error, filename, msg, docs]
    """
    def parse_file(file: UploadFile) -> dict:
        '''
        保存单个文件。
        '''
        try:
            filename = file.filename
            file_path = os.path.join(dir, filename)
            file_content = file.file.read()  # 读取上传文件的内容

            if not os.path.isdir(os.path.dirname(file_path)):
                os.makedirs(os.path.dirname(file_path))
            with open(file_path, "wb") as f:
                f.write(file_content)
            kb_file = KnowledgeFile(filename=filename, knowledge_base_name="temp")
            kb_file.filepath = file_path
            docs = kb_file.file2text(zh_title_enhance=zh_title_enhance,
                                     chunk_size=chunk_size,
                                     chunk_overlap=chunk_overlap)
            return True, filename, f"成功上传文件 {filename}", docs
        except Exception as e:
            msg = f"{filename} 文件上传失败，报错信息为: {e}"
            return False, filename, msg, []

    params = [{"file": file} for file in files]
    for result in run_in_thread_pool(parse_file, params=params):
        yield result


def upload_temp_docs(
    files: list[UploadFile] = File(..., description="上传文件，支持多文件（如 Hi3798MV310_Datasheet.pdf）"),
    prev_id: str = Form(None, description="前一次临时知识库 ID，传入后会清除旧的临时向量库"),
    chunk_size: int = Form(CHUNK_SIZE, description="知识库中单段文本最大长度"),
    chunk_overlap: int = Form(OVERLAP_SIZE, description="知识库中相邻文本的重合长度"),
    zh_title_enhance: bool = Form(ZH_TITLE_ENHANCE, description="是否开启中文标题加强"),
) -> BaseResponse:
    '''
    将文件保存到临时目录，并进行向量化。
    返回临时目录名称作为ID，同时也是临时向量库的ID。
    '''
    if prev_id is not None:
        memo_milvus_pool.pop(prev_id)

    failed_files = []
    documents = []
    path, id = get_temp_dir(prev_id)
    for success, file, msg, docs in _parse_files_in_thread(files=files,
                                                        dir=path,
                                                        zh_title_enhance=zh_title_enhance,
                                                        chunk_size=chunk_size,
                                                        chunk_overlap=chunk_overlap):
        if success:
            documents += docs
        else:
            failed_files.append({file: msg})

    # 将 metadata 中的 list 类型字段转成字符串，避免 LangChain Milvus 报错
    # 例如 titles 字段是 list，Milvus 自动建表时不认识 list 类型
    for doc in documents:
        for key, value in doc.metadata.items():
            if isinstance(value, list):
                doc.metadata[key] = ", ".join(str(v) for v in value)

    # 空文档保护：所有文件解析失败（或切分出 0 个 chunk）时 documents 为空，
    # 直接 add_documents([]) 会在向量化 normalize 处崩（np.array([]) 一维，
    # axis=1 越界）。此时不入库，明确返回失败原因，避免拿到一个空临时库。
    if not documents:
        msg = "所有文件均解析失败或无有效内容" if failed_files else "未解析出任何文档内容"
        logger.warning(f"upload_temp_docs 空文档，跳过入库: {msg}, failed_files={failed_files}")
        return BaseResponse(code=404, msg=msg, data={"id": id, "failed_files": failed_files})

    with memo_milvus_pool.load_vector_store(id).acquire() as vs:
        vs.add_documents(documents)
    return BaseResponse(data={"id": id, "failed_files": failed_files})


class FileChatRequest(BaseModel):
    query: str = Field(..., description="用户输入的问题", examples=["Hi3798MV310 如何配置 HDMI 输出？"])
    knowledge_id: str = Field(..., description="临时知识库 ID（由 upload_temp_docs 接口返回）")
    top_k: int = Field(VECTOR_SEARCH_TOP_K, description="向量检索返回的候选文档数量")
    score_threshold: float = Field(0.55, description="知识库匹配严格度，0-1 之间，0 全要，1 最严格，建议 0.5 左右", ge=0, le=1)
    history: list[History] = Field([],
                                    description="历史对话",
                                    examples=[[
                                        {"role": "user",
                                         "content": "Hi3798MV310 支持哪些视频输出接口？"},
                                        {"role": "assistant",
                                         "content": "Hi3798MV310 支持 HDMI 2.0、CVBS、YPbPr 等视频输出接口，其中 HDMI 2.0 可支持 4K@60Hz 输出。"}]])  # pylint: disable=line-too-long
    stream: bool = Field(False, description="是否流式输出（SSE）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(KB_CHAT_TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)  # pylint: disable=line-too-long
    max_tokens: int | None = Field(None, description="限制 LLM 生成的 Token 数量，None 表示使用模型默认最大值")
    prompt_name: str = Field(_DEFAULT_KEY, description="使用的 prompt 模板名称（在 configs/prompt_templates.yaml 中配置）")  # pylint: disable=line-too-long
    language: str = Field("auto", description="回答语言：auto=自动检测，zh=强制中文，en=强制英文")


async def file_chat(request: FileChatRequest):
    query = request.query
    knowledge_id = request.knowledge_id
    top_k = request.top_k
    score_threshold = request.score_threshold
    history = request.history
    stream = request.stream
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens
    prompt_name = request.prompt_name
    language = request.language
    # Phase 2 A 方案：跨 Pod 查询共享 Milvus（temp_{id} collection 由 ingestion 侧
    # upload_temp_docs 写入共享 Milvus，qa 侧直接查同一 Milvus，无需运行时 HTTP 互调）。
    # 不能用 memo_milvus_pool.keys()（进程内存缓存，跨 Pod 查不到会误判 404）；
    # 也不能用 memo_milvus_pool.acquire(key)（key 不在缓存时抛 RuntimeError）。
    # 改用 pymilvus utility.has_collection 检查共享 Milvus 上 collection 是否存在。
    # 注意：memo_milvus_pool.load_vector_store 建 langchain Milvus 实例但不实际 connect
    # default alias（langchain Milvus __init__ 延迟连接），utility.has_collection 直接调
    # 会抛 ConnectionNotExistException。故先 connections.connect(default, **milvus_cfg)
    # 显式建连，再用 has_collection 检查，不存在直接 404，存在才 load_vector_store 加载。
    from pymilvus import connections, utility

    from configs import kbs_config
    collection_name = f"temp_{knowledge_id}"
    milvus_cfg = kbs_config.get("milvus", {}) or {}
    try:
        if not connections.has_connection(_DEFAULT_KEY):
            connections.connect(alias=_DEFAULT_KEY, **milvus_cfg)
        if not utility.has_collection(collection_name):
            return BaseResponse(code=404, msg=f"未找到临时知识库 {knowledge_id}，请先上传文件")
        thread_safe = memo_milvus_pool.load_vector_store(knowledge_id)
    except Exception as e:
        logger.error(f"file_chat 加载临时向量库 {knowledge_id} 失败: {e}")
        return BaseResponse(code=404, msg=f"未找到临时知识库 {knowledge_id}，请先上传文件")

    history = [History.from_data(h) for h in history]

    # 语言检测：auto 自动检测 zh/en，zh/en 强制覆盖；kill-switch 关闭时强制 zh
    # to_thread 避免 langdetect 同步 CPU 推理阻塞 async 事件循环（英文 cache miss 时）
    _lang = await asyncio.to_thread(resolve_language, query, language)
    logger.info(f"[lang] file_chat query_lang={_lang} | query={query[:60]}")

    async def knowledge_base_chat_iterator() -> AsyncIterable[str]:
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
        embed_func = EmbeddingsFunAdapter()
        embeddings = await embed_func.aembed_query(query)
        with thread_safe.acquire() as vs:
            docs = vs.similarity_search_with_score_by_vector(embeddings, k=top_k)
            # 严格度转 L2 距离阈值：0 全要，1 最严格
            if score_threshold is not None and score_threshold > 0:
                l2_threshold = 1000 - (score_threshold * 1000)
                docs = [(d, s) for d, s in docs if s <= l2_threshold]
                docs = docs[:top_k]
            docs = [x[0] for x in docs]

        context = "\n".join([doc.page_content for doc in docs])
        if len(docs) == 0: ## 如果没有找到相关文档，使用Empty模板
            _tpl_name = "empty"
        else:
            _tpl_name = prompt_name
        _tpl_name = resolve_prompt_name("knowledge_base_chat", _tpl_name, _lang)
        # system/body 已在 YAML 显式拆分：system 作为列表首条稳定前缀走 Dashscope 前缀缓存，
        # body 含 context/question 占位放历史之后
        _sys_text, _user_text = get_prompt_split("knowledge_base_chat", _tpl_name)
        _system_msg = (History(role="system", content=_sys_text).to_msg_template(False)
                       if _sys_text else None)
        input_msg = History(role="user", content=_user_text).to_msg_template(False)
        _msgs = [i.to_msg_template() for i in history] + [input_msg]
        if _system_msg is not None:
            _msgs = [_system_msg] + _msgs
        chat_prompt = ChatPromptTemplate.from_messages(_msgs)

        chain = LLMChain(prompt=chat_prompt, llm=model)

        # Begin a task that runs in the background.
        task = asyncio.create_task(wrap_done(
            chain.acall({"context": context, "question": query}),
            callback.done),
        )

        source_documents = []
        for inum, doc in enumerate(docs):
            filename = doc.metadata.get("source")
            text = f"""出处 [{inum + 1}] [{filename}] \n\n{doc.page_content}\n\n"""
            # 根据前端展示要求改成字典格式分类输出
            out_dict = {
                "text": text,
                "num": inum + 1,
                "filename": filename,
                "page_content": doc.page_content,

            }
            source_documents.append(out_dict)

        if len(source_documents) == 0:  # 没有找到相关文档
            # 根据前端展示要求改成字典格式分类输出
            if _lang == "en":
                text = "<span style='color:red'>No relevant documents found, unable to answer this question.</span>"
            else:
                text = "<span style='color:red'>未找到相关文档，无法回答该问题。</span>"
            out_dict = {
                "text": text,
                "num": 0,
                "filename": "",
                "page_content": ""
            }
            source_documents.append(out_dict)

        if stream:
            async for token in callback.aiter():
                # Use server-sent-events to stream the response
                yield json.dumps({"answer": token}, ensure_ascii=False)
            yield json.dumps({"docs": source_documents}, ensure_ascii=False)
        else:
            answer = ""
            async for token in callback.aiter():
                answer += token
            yield json.dumps({"answer": answer,
                              "docs": source_documents},
                             ensure_ascii=False)
        await task

    return EventSourceResponse(knowledge_base_chat_iterator())
