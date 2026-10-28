import json

from pydantic import BaseModel, Field
from sse_starlette import EventSourceResponse

from configs import (
    DEFAULT_VS_TYPE,
    EMBEDDING_MODEL,
    LLM_MODELS,
    OVERLAP_SIZE,
    TEMPERATURE,
    logger,
)
from server.knowledge_base.kb_service.base import KBServiceFactory
from server.knowledge_base.kb_summary.base import KBSummaryService
from server.knowledge_base.kb_summary.summary_chunk import SummaryAdapter
from server.knowledge_base.model.kb_document_model import DocumentWithVSId
from server.knowledge_base.utils import list_files_from_folder
from server.utils import BaseResponse, get_ChatOpenAI


class RecreateSummaryVectorStoreRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    allow_empty_kb: bool = Field(True, description="是否允许对空知识库执行重建")
    vs_type: str = Field(DEFAULT_VS_TYPE, description="向量库类型，默认 milvus")
    embed_model: str = Field(EMBEDDING_MODEL, description="嵌入模型名称")
    file_description: str = Field('', description="文件描述（附加在摘要前，有助于提升摘要质量）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)
    max_tokens: int | None = Field(2048, description="限制 LLM 生成的 Token 数量（DashScope 要求 1-32768）")


def recreate_summary_vector_store(
        request: RecreateSummaryVectorStoreRequest,
):
    """
    重建知识库全部文件摘要（流式输出处理进度）。
    适用于海思长文档（如 Datasheet、开发指南）的摘要向量化入库。
    """
    knowledge_base_name = request.knowledge_base_name
    allow_empty_kb = request.allow_empty_kb
    vs_type = request.vs_type
    embed_model = request.embed_model
    file_description = request.file_description
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens

    def output():

        _CODE_KEY = 'code'
        _MSG_KEY = 'msg'
        kb = KBServiceFactory.get_service(knowledge_base_name, vs_type, embed_model)
        if not kb.exists() and not allow_empty_kb:
            yield {_CODE_KEY: 404, _MSG_KEY: f"未找到知识库 ‘{knowledge_base_name}’"}
        else:
            # 重新创建知识库
            kb_summary = KBSummaryService(knowledge_base_name, embed_model)
            kb_summary.drop_kb_summary()
            kb_summary.create_kb_summary()

            llm = get_ChatOpenAI(
                model_name=model_name,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            reduce_llm = get_ChatOpenAI(
                model_name=model_name,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            # 文本摘要适配器
            summary = SummaryAdapter.form_summary(llm=llm,
                                                  reduce_llm=reduce_llm,
                                                  overlap_size=OVERLAP_SIZE)
            files = list_files_from_folder(knowledge_base_name)

            i = 0
            for i, file_name in enumerate(files):

                doc_infos = kb.list_docs(file_name=file_name)
                docs = summary.summarize(file_description=file_description,
                                         docs=doc_infos)
                if not docs:
                    msg = f"文件‘{file_name}’摘要生成失败（docs 为空或超过 context length），已跳过"
                    logger.error(msg)
                    yield json.dumps({_CODE_KEY: 500, _MSG_KEY: msg})
                    continue

                status_kb_summary = kb_summary.add_kb_summary(summary_combine_docs=docs)
                if status_kb_summary:
                    logger.info(f"({i + 1} / {len(files)}): {file_name} 总结完成")
                    yield json.dumps({
                        _CODE_KEY: 200,
                        _MSG_KEY: f"({i + 1} / {len(files)}): {file_name}",
                        "total": len(files),
                        "finished": i + 1,
                        "doc": file_name,
                    }, ensure_ascii=False)
                else:

                    msg = f"知识库'{knowledge_base_name}'总结文件‘{file_name}’时出错。已跳过。"
                    logger.error(msg)
                    yield json.dumps({
                        _CODE_KEY: 500,
                        _MSG_KEY: msg,
                    })
                i += 1

    return EventSourceResponse(output())


class SummaryFileToVectorStoreRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    file_name: str = Field(..., description="文件名称", examples=["Hi3798MV310_Datasheet.pdf"])
    allow_empty_kb: bool = Field(True, description="是否允许对空知识库执行摘要")
    vs_type: str = Field(DEFAULT_VS_TYPE, description="向量库类型，默认 milvus")
    embed_model: str = Field(EMBEDDING_MODEL, description="嵌入模型名称")
    file_description: str = Field('', description="文件描述（附加在摘要前，有助于提升摘要质量）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)
    max_tokens: int | None = Field(2048, description="限制 LLM 生成的 Token 数量（DashScope 要求 1-32768）")


def summary_file_to_vector_store(
        request: SummaryFileToVectorStoreRequest,
):
    """
    按文件名生成知识库摘要并入库（流式输出处理进度）。
    适用于海思长文档（如 Datasheet、开发指南）的摘要向量化入库。
    """
    knowledge_base_name = request.knowledge_base_name
    file_name = request.file_name
    allow_empty_kb = request.allow_empty_kb
    vs_type = request.vs_type
    embed_model = request.embed_model
    file_description = request.file_description
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens

    def output():
        _CODE_KEY = 'code'
        _MSG_KEY = 'msg'
        kb = KBServiceFactory.get_service(knowledge_base_name, vs_type, embed_model)
        if not kb.exists() and not allow_empty_kb:
            yield {_CODE_KEY: 404, _MSG_KEY: f"未找到知识库 ‘{knowledge_base_name}’"}
        else:
            # 重新创建知识库
            kb_summary = KBSummaryService(knowledge_base_name, embed_model)
            kb_summary.create_kb_summary()

            llm = get_ChatOpenAI(
                model_name=model_name,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            reduce_llm = get_ChatOpenAI(
                model_name=model_name,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            # 文本摘要适配器
            summary = SummaryAdapter.form_summary(llm=llm,
                                                  reduce_llm=reduce_llm,
                                                  overlap_size=OVERLAP_SIZE)

            doc_infos = kb.list_docs(file_name=file_name)
            docs = summary.summarize(file_description=file_description,
                                     docs=doc_infos)
            if not docs:
                msg = f"文件‘{file_name}’摘要生成失败（docs 为空或超过 context length），已跳过"
                logger.error(msg)
                yield json.dumps({_CODE_KEY: 500, _MSG_KEY: msg})
                return

            status_kb_summary = kb_summary.add_kb_summary(summary_combine_docs=docs)
            if status_kb_summary:
                logger.info(f" {file_name} 总结完成")
                yield json.dumps({
                    _CODE_KEY: 200,
                    _MSG_KEY: f"{file_name} 总结完成",
                    "doc": file_name,
                }, ensure_ascii=False)
            else:

                msg = f"知识库'{knowledge_base_name}'总结文件‘{file_name}’时出错。已跳过。"
                logger.error(msg)
                yield json.dumps({
                    _CODE_KEY: 500,
                    _MSG_KEY: msg,
                })

    return EventSourceResponse(output())


class SummaryDocIdsToVectorStoreRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    doc_ids: list = Field([], description="文档 ID 列表", examples=[["uuid1", "uuid2"]])
    vs_type: str = Field(DEFAULT_VS_TYPE, description="向量库类型，默认 milvus")
    embed_model: str = Field(EMBEDDING_MODEL, description="嵌入模型名称")
    file_description: str = Field('', description="文件描述（附加在摘要前，有助于提升摘要质量）")
    model_name: str = Field(LLM_MODELS[0], description="LLM 模型名称，取值见 configs/model_config.yaml")
    temperature: float = Field(TEMPERATURE, description="LLM 采样温度，取值越大随机性越强，0-1 之间", ge=0.0, le=1.0)
    max_tokens: int | None = Field(2048, description="限制 LLM 生成的 Token 数量（DashScope 要求 1-32768）")


def summary_doc_ids_to_vector_store(
        request: SummaryDocIdsToVectorStoreRequest,
) -> BaseResponse:
    """
    按文档 ID 批量生成知识库摘要并返回。
    """
    knowledge_base_name = request.knowledge_base_name
    doc_ids = request.doc_ids
    vs_type = request.vs_type
    embed_model = request.embed_model
    file_description = request.file_description
    model_name = request.model_name
    temperature = request.temperature
    max_tokens = request.max_tokens

    kb = KBServiceFactory.get_service(knowledge_base_name, vs_type, embed_model)
    if not kb.exists():
        return BaseResponse(code=404, msg=f"未找到知识库 {knowledge_base_name}", data={})
    llm = get_ChatOpenAI(
        model_name=model_name,
        temperature=temperature,
        max_tokens=max_tokens,
    )
    reduce_llm = get_ChatOpenAI(
        model_name=model_name,
        temperature=temperature,
        max_tokens=max_tokens,
    )
    # 文本摘要适配器
    summary = SummaryAdapter.form_summary(llm=llm,
                                          reduce_llm=reduce_llm,
                                          overlap_size=OVERLAP_SIZE)

    doc_infos = kb.get_doc_by_ids(ids=doc_ids)
    # doc_infos转换成DocumentWithVSId包装的对象
    doc_info_with_ids = [DocumentWithVSId(**doc.dict(), id=with_id) for with_id, doc in zip(doc_ids, doc_infos, strict=False)]  # pylint: disable=line-too-long

    docs = summary.summarize(file_description=file_description,
                             docs=doc_info_with_ids)

    # 将docs转换成dict
    resp_summarize = [{**doc.dict()} for doc in docs]

    return BaseResponse(code=200, msg="总结完成", data={"summarize": resp_summarize})
