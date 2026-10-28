import json
import os
import re
import shutil
import urllib
from typing import Annotated, Any, Optional

import numpy as np
from fastapi import Body, File, Form, Query, UploadFile
from fastapi.responses import FileResponse
from langchain.docstore.document import Document
from pydantic import BaseModel, Field, Json
from pymilvus import MilvusClient
from sse_starlette import EventSourceResponse

from configs import (
    CHUNK_SIZE,
    DEFAULT_VS_TYPE,
    EMBEDDING_MODEL,
    MEMPALACE_BASE_URL,
    OVERLAP_SIZE,
    SCORE_THRESHOLD,
    VECTOR_SEARCH_TOP_K,
    ZH_TITLE_ENHANCE,
    log_verbose,
    logger,
)
from configs.kb_loader import kbs_config
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope
from server.db.repository.knowledge_file_repository import get_file_detail
from server.embeddings_api import embed_texts
from server.knowledge_base.kb_service.base import KBServiceFactory
from server.knowledge_base.milvus_filter import _escape_milvus_str
from server.knowledge_base.model.kb_document_model import DocumentWithVSId
from server.knowledge_base.search_filter import SearchFilter
from server.knowledge_base.utils import (
    SUPPORTED_EXTS,
    KnowledgeFile,
    files2docs_in_thread,
    get_file_path,
    list_files_from_folder,
    validate_kb_name,
)
from server.utils import BaseResponse, ListResponse, run_in_thread_pool
from server._http_constants import (
    HTTP_OK,
    HTTP_BAD_REQUEST,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_CONFLICT,
    HTTP_INTERNAL_ERROR,
    HTTP_METHOD_POST,
    SECURITY_ATTACK_MSG,
    SUCCESS_MSG,
    MILVUS_SEARCH_TIMEOUT_SEC,
    HTTP_UPLOAD_TIMEOUT_SEC,
    HTTP_INDEX_TIMEOUT_SEC,
    HTTP_DOWNLOAD_TIMEOUT_SEC,
)

"""
    知识库文档接口
    TODO CK 注意入库元数据的处理优化，是否需要添加更多字段，如文件路径、文件类型、文件大小、文件创建时间、芯片型号等。
"""

# 业务错误消息模板
KB_NOT_FOUND_MSG_TEMPLATE = "未找到知识库 {knowledge_base_name}"
COLLECTION_NOT_FOUND_MSG_TEMPLATE = "集合 {collection_name} 不存在"
INVALID_ZIP_MSG = "不是合法的 zip 文件"
ZIP_REJECT_MSG_TEMPLATE = "zip 解压被拒绝: {error}"
KB_ALREADY_EXISTS_MSG_TEMPLATE = "项目 {knowledge_base_name} 已存在，传 override=true 覆盖重建"
ROLLBACK_FAILED_MSG_TEMPLATE = "回滚后向量化失败: {failed}"
VERSION_LABEL_EMPTY_MSG_TEMPLATE = "版本标签不能为空: {version!r}"
VERSION_NOT_ROLLBACK_MSG_TEMPLATE = "版本 {ver} 不可回滚（记录或内容副本不存在）"
UPLOAD_WITH_FAILURES_MSG_TEMPLATE = "文件上传与向量化完成，但有 {count} 个文件处理失败"
UPDATE_WITH_FAILURES_MSG_TEMPLATE = "更新文档完成，但有 {count} 个文件解析失败"
UPLOAD_COMPLETE_MSG = "文件上传与向量化完成"
UPDATE_COMPLETE_MSG = "更新文档完成"
DELETE_COMPLETE_MSG = "文件删除完成"
ARCHIVE_COMPLETE_MSG = "文件归档完成"
KB_INFO_UPDATE_COMPLETE_MSG = "知识库介绍修改完成"
PROJECT_UPLOAD_COMPLETE_MSG = "项目上传完成，代码索引与文档向量化已触发"
ROLLBACK_COMPLETE_MSG_TEMPLATE = "已回滚 {file_name} 到 {ver}（登记为新版本）"
EMBEDDING_FAILED_MSG_TEMPLATE = "Embedding 失败: {msg}"
READ_FILE_FAILED_MSG_TEMPLATE = "{filename} 读取文件失败"

# Milvus 连接
milvus_host = kbs_config["milvus"]["host"]
milvus_port = kbs_config["milvus"]["port"]
milvus_url = f"http://{milvus_host}:{milvus_port}"
client = MilvusClient(uri=milvus_url)


def get_embeddings(texts):
    """使用配置的embedding模型获取文本向量"""
    result = embed_texts(texts=texts, embed_model=EMBEDDING_MODEL)
    if result.code == HTTP_OK and result.data:
        return np.array(result.data, dtype=np.float32)
    raise ValueError(f"Embedding 失败: {result.msg}")


def _search_milvus(query: str, top_k: int, knowledge_base_name: str,
                   score_threshold: float,
                   filter_opts: SearchFilter | None = None) -> list[DocumentWithVSId]:
    """与问答流程一致的 Milvus 检索：embedding → milvus_client.search → 余弦过滤。

    Bug B 修复：加 version/tags filter，与 QA 流（do_search/_search_milvus_impl）
    filter 一致。否则 HTTP /search_docs 旁路版本过滤，返回所有版本 chunk。
    Bug I 修复：in [...] filter 同样转义双引号，与 == filter 一致。
    filter_opts: 封装 version/tags 过滤参数（H3403 参数分组）。
    """
    version = filter_opts.version if filter_opts else None
    tags = filter_opts.tags if filter_opts else None
    _SOURCE_KEY = 'source'
    _TEXT_KEY = 'text'
    _ENTITY_KEY = 'entity'
    _PK_KEY = 'pk'
    encoded_queries = get_embeddings([query])
    e_queries = encoded_queries.tolist()
    # P2 filter 构造（与 milvus_retrieval._search_milvus_impl / do_search 一致）
    filter_expr = None
    if version is not None:
        esc = _escape_milvus_str(version)
        filter_expr = f'metadata["file_version_label"] == "{esc}"'
    else:
        try:
            from server.knowledge_base.file_version import build_files_version_map
            cur_map = build_files_version_map(knowledge_base_name)
            cur_labels = [l for l in cur_map.values() if l]
            if cur_labels:
                labels_str = ", ".join(f'"{_escape_milvus_str(l)}"' for l in cur_labels)
                # P1-1：加 `or is null` 让无 label 文档也通过，与客户端 _matches_version_tags 对齐
                filter_expr = (f'(metadata["file_version_label"] in [{labels_str}]) '
                               f'or (metadata["file_version_label"] is null)')
        except Exception as e:
            logger.warning(f"[search_docs] 查当前版本映射失败（fail-open 不过滤）: {e}")
    if tags:
        tag_clauses = [f'json_contains(metadata["tags"], "{_escape_milvus_str(t)}")' for t in tags]
        tags_or = " or ".join(tag_clauses)
        filter_expr = tags_or if filter_expr is None else f"({filter_expr}) and ({tags_or})"
    search_params = kbs_config.get("milvus_kwargs", {}).get("search_params", {})
    result_list = client.search(
        collection_name=knowledge_base_name,
        data=e_queries,
        limit=top_k,
        search_params=search_params,
        filter=filter_expr,
        output_fields=[_SOURCE_KEY, _PK_KEY, _TEXT_KEY, "metadata"],
        # B3：/search_docs HTTP 端点，与 QA 流一致加 5s 超时防挂起
        timeout=MILVUS_SEARCH_TIMEOUT_SEC,
    )
    data = []
    for sublist in result_list:
        for item in sublist:
            distance = item['distance']
            text = item[_ENTITY_KEY][_TEXT_KEY]
            source = item[_ENTITY_KEY][_SOURCE_KEY]
            meta = item[_ENTITY_KEY].get('metadata', {})
            if isinstance(meta, str):
                meta = json.loads(meta)
            if not isinstance(meta, dict):
                meta = {}
            meta[_SOURCE_KEY] = source
            meta["distance"] = distance
            meta["cosine_similarity"] = distance
            meta["id"] = meta.get(_PK_KEY)
            # score_threshold 过滤：0-1 余弦相似度，低于阈值的丢弃
            if score_threshold is not None and score_threshold > 0:
                if distance < score_threshold:
                    continue
            data.append(DocumentWithVSId(
                page_content=text,
                metadata=meta,
                score=distance,
                id=meta.get(_PK_KEY),
            ))
    return data


def get_doc_by_filename(
        collection_name: str = Body(..., description="Milvus 集合名称（知识库名）"),
        filename: str = Body(..., description="文件完整路径，如 /kb-files/.../Amlogic MBOX现场分析指南.pdf"),
):
    """
    根据文件名从 Milvus 中检索文档内容（精确匹配文件名，分页查询）
    """
    # 后端可能对路径做了 URL 编码（空格→%20、+ 等），统一解码为真实字符，
    # 保证与入库时存入 Milvus 的 source 字段（真实字符）精确匹配。
    filename = urllib.parse.unquote(filename)
    # 检查集合是否存在
    if not client.has_collection(collection_name):
        return BaseResponse(code=HTTP_NOT_FOUND, msg=COLLECTION_NOT_FOUND_MSG_TEMPLATE.format(collection_name=collection_name))

    try:
        matched_results = []

        # 用 filter 精确匹配文件名，避免全量分页扫描
        # filename 来自 HTTP Body（任意用户输入），必须转义反斜杠和双引号
        # 避免 filter 语法错误或表达式逃逸
        esc_filename = _escape_milvus_str(filename)
        _SOURCE_KEY = 'source'
        _TEXT_KEY = 'text'
        _METADATA_KEY = 'metadata'
        results = client.query(
            collection_name=collection_name,
            filter=f'{_SOURCE_KEY} == "{esc_filename}"',
            output_fields=[_SOURCE_KEY, _TEXT_KEY, _METADATA_KEY],
            limit=16384
        )

        for result in results:
            matched_results.append({
                "file_name": os.path.basename(result.get(_SOURCE_KEY, "")),
                _TEXT_KEY: result.get(_TEXT_KEY, ""),
                _METADATA_KEY: result.get(_METADATA_KEY, {})
            })

        if not matched_results:
            return BaseResponse(
                code=404,
                msg=f"未找到文件 {filename}",
                data=[]
            )

        return BaseResponse(
            code=HTTP_OK,
            msg=SUCCESS_MSG,
            data=matched_results
        )

    except Exception as e:
        return BaseResponse(
            code=500,
            msg=f"检索失败: {str(e)}",
            data=[]
        )


class SearchDocsRequest(BaseModel):
    query: str = Field("", description="用户输入的问题", examples=["Hi3798MV310 如何配置 HDMI 输出？"])
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    top_k: int = Field(VECTOR_SEARCH_TOP_K, description="向量检索返回的候选文档数量")
    score_threshold: float = Field(SCORE_THRESHOLD,
                                    description="知识库匹配严格度，0-1 之间，0 全要，1 最严格，建议 0.5 左右",
                                    ge=0, le=1.0)
    file_name: str = Field("", description="文件名称，支持 SQL 通配符（如 Hi3798MV310_Datasheet.pdf）")
    metadata: dict = Field({}, description="按 metadata 过滤，仅支持一级键")
    version: str | None = Field(None, description="指定版本标签检索（覆盖默认当前版本过滤）")
    tags: list[str] | None = Field(None, description="文件标签过滤（OR 语义，为空则不过滤）")


def search_docs(
        request: SearchDocsRequest = Body(...),
) -> list[DocumentWithVSId]:
    query = request.query
    knowledge_base_name = request.knowledge_base_name
    top_k = request.top_k
    score_threshold = request.score_threshold
    file_name = request.file_name
    metadata = request.metadata
    version = request.version
    tags = request.tags
    logger.info(f"/kw/knowledge_base/search_docs request query:{query}")
    data = []
    if query:
        # Bug B 修复：_search_milvus 加 version/tags filter，与 QA 流一致
        data = _search_milvus(query, top_k, knowledge_base_name, score_threshold,
                              filter_opts=SearchFilter(version=version, tags=tags))
    else:
        kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
        if kb is not None and (file_name or metadata):
            data = kb.list_docs(file_name=file_name, metadata=metadata)
    logger.info(f"/kw/knowledge_base/search_docs respond: {len(data)} docs, top_k={top_k}")
    return data


class BatchSearchDocsRequest(BaseModel):
    query: str = Field("", description="用户输入的问题", examples=["Hi3798MV310 如何配置 HDMI 输出？"])
    knowledge_base_names: list[str] = Field(..., description="知识库名称，支持多个库", examples=[["hi3798mv310", "hi3516cv610"]])
    top_k: int = Field(VECTOR_SEARCH_TOP_K, description="向量检索返回的候选文档数量")
    score_threshold: float = Field(SCORE_THRESHOLD,
                                    description="知识库匹配严格度，0-1 之间，0 全要，1 最严格，建议 0.5 左右",
                                    ge=0, le=1.0)
    file_name: str = Field("", description="文件名称，支持 SQL 通配符")
    metadata: dict = Field({}, description="按 metadata 过滤，仅支持一级键")
    version: str | None = Field(None, description="指定版本标签检索（覆盖默认当前版本过滤）")
    tags: list[str] | None = Field(None, description="文件标签过滤（OR 语义，为空则不过滤）")


def batch_search_docs(
        request: BatchSearchDocsRequest = Body(...),
) -> list:
    """
    Args:
    Returns:
    返回结构中，data = [
        {
            "page_content": "import android.view.View;",
            "metadata": {
            "source": "/mnt/d/data/release/kw/knowledge_base/samples2/content/PromptView.java",
            "content_type": "simplified_code",
            "language": "Language.JAVA",
            "pk": 449755822406244860
            },
            "type": "Document",
            "id": null,
            "score": 0.31730738282203674
        },...
    ]
    """
    query = request.query
    knowledge_base_names = request.knowledge_base_names
    top_k = request.top_k
    score_threshold = request.score_threshold
    file_name = request.file_name
    metadata = request.metadata
    version = request.version
    tags = request.tags
    logger.info(f"/kw/knowledge_base/batch_search_docs request query:{query}")
    datas = []
    for knowledge_base_name in knowledge_base_names:
        data = []
        if query:
            # Bug B 修复：_search_milvus 加 version/tags filter，与 QA 流一致
            data = _search_milvus(query, top_k, knowledge_base_name, score_threshold,
                                  filter_opts=SearchFilter(version=version, tags=tags))
        else:
            kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
            if kb is not None and (file_name or metadata):
                data = kb.list_docs(file_name=file_name, metadata=metadata)

        res_dict = {"knowledge_base_name": knowledge_base_name, "data": data}
        datas.append(res_dict)
    logger.info(f"/kw/knowledge_base/batch_search_docs respond:{str(datas)}")
    return datas


def update_docs_by_id(
        knowledge_base_name: str = Body(..., description="知识库名称", examples=["hi3798mv310"]),
        docs: dict[str, Document] = Body(..., description="要更新的文档内容，形如：{id: Document, ...}")
) -> BaseResponse:
    """
    按照文档 ID 更新文档内容
    """
    logger.info(f"/kw/knowledge_base/update_docs_by_id request knowledge_base_name:{knowledge_base_name}")
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        msg = f"指定的知识库 {knowledge_base_name} 不存在"
        logger.info(msg)
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=msg)
    if kb.update_doc_by_ids(docs=docs):
        logger.info("文档更新成功")
        return BaseResponse(msg="文档更新成功")
    logger.info("文档更新失败")
    return BaseResponse(msg="文档更新失败")


def list_files(
        knowledge_base_name: str
) -> ListResponse:
    logger.info(f"/kw/knowledge_base/list_files request knowledge_base_name:{knowledge_base_name}")
    if not validate_kb_name(knowledge_base_name):
        return ListResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG + ".", data=[])

    knowledge_base_name = urllib.parse.unquote(knowledge_base_name)
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        msg = KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name)
        logger.info(msg)
        return ListResponse(code=HTTP_NOT_FOUND, msg=msg, data=[])
    all_doc_names = kb.list_files()
    logger.info(f"/kw/knowledge_base/list_files respond:{str(all_doc_names)}")
    return ListResponse(data=all_doc_names)


def _save_files_in_thread(files: list[UploadFile],
                          knowledge_base_name: str,
                          override: bool):
    """
    通过多线程将上传的文件保存到对应知识库目录内。
    生成器返回保存结果：{"code":200, "msg": "xxx", "data": {"knowledge_base_name":"xxx", "file_name": "xxx"}}
    """

    def save_file(file: UploadFile, knowledge_base_name: str, override: bool) -> dict:
        """保存单个文件"""
        try:
            filename = file.filename
            file_path = get_file_path(knowledge_base_name=knowledge_base_name, doc_name=filename)
            data = {"knowledge_base_name": knowledge_base_name, "file_name": filename}

            file_content = file.file.read()  # 读取上传文件的内容
            if (os.path.isfile(file_path)
                    and not override
                    and os.path.getsize(file_path) == len(file_content)
            ):
                # TODO: filesize 不同后的处理
                file_status = f"文件 {filename} 已存在。"
                logger.info(file_status)
                return dict(code=HTTP_NOT_FOUND, msg=file_status, data=data)

            from server.knowledge_base.file_version import register_superseded
            register_superseded(knowledge_base_name, filename, file_path)

            if not os.path.isdir(os.path.dirname(file_path)):
                os.makedirs(os.path.dirname(file_path))
            with open(file_path, "wb") as f:
                f.write(file_content)
            return dict(code=HTTP_OK, msg=f"成功上传文件 {filename}", data=data)
        except Exception as e:
            msg = f"{filename} 文件上传失败，报错信息为: {e}"
            logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
            return dict(code=HTTP_INTERNAL_ERROR, msg=msg, data=data)

    params = [{"file": file, "knowledge_base_name": knowledge_base_name, "override": override} for file in files]
    for result in run_in_thread_pool(save_file, params=params):
        yield result


# TODO: 等langchain.document_loaders支持内存文件的时候再开通
# def files2docs(files: List[UploadFile] = File(..., description="上传文件，支持多文件"),
#                 knowledge_base_name: str = Form(..., description="知识库名称", examples=["samples"]),
#                 override: bool = Form(False, description="覆盖已有文件"),
#                 save: bool = Form(True, description="是否将文件保存到知识库目录")):
#     def save_files(files, knowledge_base_name, override):
#         for result in _save_files_in_thread(files, knowledge_base_name=knowledge_base_name, override=override):
#             yield json.dumps(result, ensure_ascii=False)

#     def files_to_docs(files):
#         for result in files2docs_in_thread(files):
#             yield json.dumps(result, ensure_ascii=False)


class UploadDocsRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    override: bool = Field(False, description="是否覆盖已存在的同名文件")
    to_vector_store: bool = Field(True, description="上传文件后是否进行向量化")
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    docs: dict[str, Any] = Field({}, description="自定义的 docs",
                       examples=[{"test.txt": [{"page_content": "custom doc", "metadata": {}}]}])
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")
    file_version_label: str = Field("", description="后端约定的版本标签（展示用，如 v1.0/rev-202610）；空则模型端用 sha[:8] 兜底")
    tags: list[str] | None = Field(None, description="文件标签列表（如 [GPIO,UART]，OR 语义过滤）")


def upload_docs(
        request: Annotated[UploadDocsRequest, Form()],
        files: list[UploadFile] = File(..., description="上传文件，支持多文件（如 Hi3798MV310_Datasheet.pdf）"),
) -> BaseResponse:
    """
    API接口：上传文件，并/或向量化
    """
    knowledge_base_name = request.knowledge_base_name
    override = request.override
    to_vector_store = request.to_vector_store
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    docs = request.docs
    not_refresh_vs_cache = request.not_refresh_vs_cache
    file_version_label = request.file_version_label
    tags = request.tags
    _FAILED_FILES_KEY = 'failed_files'
    _CHUNK_COUNT_KEY = 'chunkCount'
    _VERSION_INFO_KEY = 'version_info'
    logger.info(f"/kw/knowledge_base/update_docs request kb={knowledge_base_name} files={[f.filename for f in files]} override={override}")  # pylint: disable=line-too-long
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    file_names = list(docs.keys())
    chunk_count = 0  # to_vector_store=False 时不进向量化分支，未初始化会在响应组装处 UnboundLocalError
    version_info = {}  # 同上：to_vector_store=False 时保持空 dict，响应仍含 version_info 键

    # 先将上传的文件保存到磁盘
    for result in _save_files_in_thread(files, knowledge_base_name=knowledge_base_name, override=override):
        filename = result["data"]["file_name"]
        if result["code"] != 200:
            failed_files[filename] = result["msg"]

        if filename not in file_names:
            file_names.append(filename)

    # 对保存的文件进行向量化
    if to_vector_store:
        result = update_docs(
                request=UpdateDocsRequest(
                    knowledge_base_name=knowledge_base_name,
                    file_names=file_names,
                    override_custom_docs=True,
                    chunk_size=chunk_size,
                    chunk_overlap=chunk_overlap,
                    zh_title_enhance=zh_title_enhance,
                    docs=docs,
                    not_refresh_vs_cache=True,
                    file_version_label=file_version_label, tags=tags,
                ),
        )
        failed_files.update(result.data[_FAILED_FILES_KEY])
        chunk_count = result.data.get(_CHUNK_COUNT_KEY, 0)
        version_info = result.data.get(_VERSION_INFO_KEY, {})
        if not not_refresh_vs_cache:
            kb.save_vector_store()
    # 上传的文件里有代码文件 → 后台触发 dual-mcp 重索引，保证代码图随上传更新
    from server.comm.dual_mcp_client import is_code_file, stage_code_files_to_kb_root, trigger_reindex_async
    if any(is_code_file(f) for f in file_names):
        from server.knowledge_base.file_version import build_files_version_map
        stage_code_files_to_kb_root(knowledge_base_name, file_names)
        trigger_reindex_async(knowledge_base_name,
                              files_version=build_files_version_map(knowledge_base_name))
    from server.knowledge_base.file_version import cleanup_async
    cleanup_async(knowledge_base_name)
    logger.info("/kw/knowledge_base/update_docs 文件上传与向量化完成")
    if failed_files:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=UPLOAD_WITH_FAILURES_MSG_TEMPLATE.format(count=len(failed_files)), data={_FAILED_FILES_KEY: failed_files, _CHUNK_COUNT_KEY: chunk_count, _VERSION_INFO_KEY: version_info})  # pylint: disable=line-too-long
    return BaseResponse(code=HTTP_OK, msg=UPLOAD_COMPLETE_MSG, data={_FAILED_FILES_KEY: failed_files, _CHUNK_COUNT_KEY: chunk_count, _VERSION_INFO_KEY: version_info})  # pylint: disable=line-too-long


class BatchUploadDocsRequest(BaseModel):
    files_paths: list[str] = Field(..., description="要处理的文件夹路径列表，支持多文件",
                                    examples=[["/data/haisi/hi3798mv310/docs", "/data/haisi/hi3516cv610/docs"]])
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    override: bool = Field(False, description="是否覆盖已存在的同名文件")
    to_vector_store: bool = Field(True, description="上传文件后是否进行向量化")
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")
    file_version_label: str = Field("", description="后端约定的版本标签（展示用，如 v1.0/rev-202610）；空则模型端用 sha[:8] 兜底")
    tags: list[str] | None = Field(None, description="文件标签列表（如 [GPIO,UART]，OR 语义过滤）")


def batch_upload_docs(
        request: BatchUploadDocsRequest = Body(...),
) -> BaseResponse:
    """
    API接口：上传文件夹，(并/或)对文件夹里的文件进行向量化
    """
    files_paths = request.files_paths
    knowledge_base_name = request.knowledge_base_name
    override = request.override
    to_vector_store = request.to_vector_store
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    not_refresh_vs_cache = request.not_refresh_vs_cache
    file_version_label = request.file_version_label
    tags = request.tags
    _FAILED_FILES_KEY = 'failed_files'
    _VERSION_INFO_KEY = 'version_info'
    logger.info(f"/kw/knowledge_base/batch_upload_docs request knowledge_base_name:{knowledge_base_name}")
    # 检查名字中是否包含预期外的字符或路径攻击关键字，比如"../"
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    docs = {}
    file_names = list(docs.keys())
    version_info = {}  # to_vector_store=False 或无文件时保持空 dict，响应仍含 version_info 键
    for files_path in files_paths:
        files = get_all_file_paths(files_path)
        # 先检查给出的文件路径的文件是否已经存在
        for result in _checking_files_in_thread(files, knowledge_base_name=knowledge_base_name, override=override):
            filename = result["data"]["file_name"]
            if result["code"] != 200:
                failed_files[filename] = result["msg"]

            if filename not in file_names:
                file_names.append(filename)

        # 对文件进行向量化
        if to_vector_store:
            result = update_docs_use_path(
                    request=UpdateDocsUsePathRequest(
                        knowledge_base_name=knowledge_base_name,
                        file_paths=file_names,
                        override_custom_docs=True,
                        chunk_size=chunk_size,
                        chunk_overlap=chunk_overlap,
                        zh_title_enhance=zh_title_enhance,
                        docs=docs,
                        not_refresh_vs_cache=True,
                        file_version_label=file_version_label, tags=tags,
                    ),
            )
            failed_files.update(result.data[_FAILED_FILES_KEY])
            version_info.update(result.data.get(_VERSION_INFO_KEY, {}))
            if not not_refresh_vs_cache:
                kb.save_vector_store()
    # 上传的文件里有代码文件 → 后台触发 dual-mcp 重索引，保证代码图随上传更新
    from server.comm.dual_mcp_client import is_code_file, stage_code_files_to_kb_root, trigger_reindex_async
    if any(is_code_file(f) for f in file_names):
        from server.knowledge_base.file_version import build_files_version_map
        stage_code_files_to_kb_root(knowledge_base_name, file_names)
        trigger_reindex_async(knowledge_base_name,
                              files_version=build_files_version_map(knowledge_base_name))
    logger.info("/kw/knowledge_base/batch_upload_docs success: 文件上传与向量化完成")
    if failed_files:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=UPLOAD_WITH_FAILURES_MSG_TEMPLATE.format(count=len(failed_files)), data={_FAILED_FILES_KEY: failed_files, _VERSION_INFO_KEY: version_info})  # pylint: disable=line-too-long
    return BaseResponse(code=HTTP_OK, msg=UPLOAD_COMPLETE_MSG, data={_FAILED_FILES_KEY: failed_files, _VERSION_INFO_KEY: version_info})


def get_all_file_paths(directory):
    file_paths = []
    for root, _, files in os.walk(directory):
        for filename in files:
            filepath = os.path.join(root, filename)
            file_paths.append(filepath)
    return file_paths


class UploadDocsUsePathRequest(BaseModel):
    files: list[str] = Field(..., description="要处理的文件路径列表，支持多文件",
                             examples=["/data/haisi/Hi3798MV310_Datasheet.pdf", "/data/haisi/HDMI配置指南.md"])
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    override: bool = Field(False, description="是否覆盖已存在的同名文件")
    to_vector_store: bool = Field(True, description="上传文件后是否进行向量化")
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")
    file_version_label: str = Field("", description="后端约定的版本标签（展示用，如 v1.0/rev-202610）；空则模型端用 sha[:8] 兜底")
    tags: list[str] | None = Field(None, description="文件标签列表（如 [GPIO,UART]，OR 语义过滤）")


def upload_docs_use_path(
        request: UploadDocsUsePathRequest = Body(...)) -> BaseResponse:
    """
    API接口：上传文件，并/或向量化

    file_version_label/tags：后端约定的展示标签与文件标签，向下透传至
    update_docs_use_path → record_current（写 history 表）→ kb.update_doc
    （写 Milvus metadata 嵌套层 file_version_label）→ _store_docs_to_mempalace
    （写 dual-mcp）。空 label 由 record_current 用 sha[:8] 兜底。
    """
    files = request.files
    knowledge_base_name = request.knowledge_base_name
    override = request.override
    to_vector_store = request.to_vector_store
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    not_refresh_vs_cache = request.not_refresh_vs_cache
    file_version_label = request.file_version_label
    tags = request.tags
    _FAILED_FILES_KEY = 'failed_files'
    _CHUNK_COUNT_KEY = 'chunkCount'
    _VERSION_INFO_KEY = 'version_info'
    logger.info(f"upload_docs_use_path request:{knowledge_base_name}->{files}, file_version_label={file_version_label}, tags={tags}")  # pylint: disable=line-too-long
    # 检查名字中是否包含预期外的字符或路径攻击关键字，比如"../"
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    docs = {}
    file_names = list(docs.keys())
    chunk_count = 0  # to_vector_store=False 时不进向量化分支，未初始化会在响应组装处 UnboundLocalError
    version_info = {}  # 同上：to_vector_store=False 时保持空 dict，响应仍含 version_info 键
    # 先检查给出的文件路径的文件是否已经存在
    for result in _checking_files_in_thread(files, knowledge_base_name=knowledge_base_name, override=override):
        filename = result["data"]["file_name"]
        if result["code"] != 200:
            failed_files[filename] = result["msg"]
            continue
        # 支持的文件格式才加入解析列表；不支持的格式上报为失败，避免"成功但 0 切片"假成功
        name, ext = os.path.splitext(filename)
        basename = os.path.basename(filename).lower()
        if ext in SUPPORTED_EXTS or basename in SUPPORTED_EXTS:
            if filename not in file_names:
                file_names.append(filename)
        else:
            if filename not in failed_files:
                logger.warning(f"不支持的文件格式，跳过向量化: {filename}")
                failed_files[filename] = f"不支持的文件格式: {ext or basename}"

    # 对保存的文件进行向量化
    if to_vector_store:
        result = update_docs_use_path(
                request=UpdateDocsUsePathRequest(
                    knowledge_base_name=knowledge_base_name,
                    file_paths=file_names,
                    override_custom_docs=True,
                    chunk_size=chunk_size,
                    chunk_overlap=chunk_overlap,
                    zh_title_enhance=zh_title_enhance,
                    docs=docs,
                    not_refresh_vs_cache=True,
                    file_version_label=file_version_label,
                    tags=tags,
                ),
        )
        logger.info(f"upload_docs result from update_docs_use_path: code={result.code}, failed_files={result.data.get(_FAILED_FILES_KEY, 'NO KEY')}")  # pylint: disable=line-too-long
        failed_files.update(result.data[_FAILED_FILES_KEY])
        chunk_count = result.data.get(_CHUNK_COUNT_KEY, 0)
        version_info = result.data.get(_VERSION_INFO_KEY, {})
        if not not_refresh_vs_cache:
            kb.save_vector_store()

    if failed_files:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=UPLOAD_WITH_FAILURES_MSG_TEMPLATE.format(count=len(failed_files)), data={_FAILED_FILES_KEY: failed_files, _CHUNK_COUNT_KEY: chunk_count, _VERSION_INFO_KEY: version_info})  # pylint: disable=line-too-long
    return BaseResponse(code=HTTP_OK, msg=UPLOAD_COMPLETE_MSG, data={_FAILED_FILES_KEY: failed_files, _CHUNK_COUNT_KEY: chunk_count, _VERSION_INFO_KEY: version_info})  # pylint: disable=line-too-long


def _checking_files_in_thread(files: list[str],
                              knowledge_base_name: str,
                              override: bool):
    """
    通过先检查给出的文件路径的文件是否已经存在，如果存在检测是否相同。
    生成器返回保存结果：{"code":200, "msg": "xxx", "data": {"knowledge_base_name":"xxx", "file_name": "xxx"}}
    """

    def validate_file(file_path: str, knowledge_base_name: str, override: bool) -> dict:
        """
        根据override确认是否需要验证文件是否已有文件
        Args:
            file_path (str): 文件的存储路径
            knowledge_base_name (str): 知识库名字
            override (bool): 如果文件名已经存在是否覆盖
        Returns:
        """
        # TODO: hetaoist
        data = {"knowledge_base_name": knowledge_base_name, "file_name": file_path}
        try:
            # 查看知识库中的文件中是否已经存在该文件
            if not validate_kb_name(knowledge_base_name):
                return dict(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG, data=[])
            kw_base_name = urllib.parse.unquote(knowledge_base_name)
            kb = KBServiceFactory.get_service_by_name(kw_base_name)
            if kb is None:
                return dict(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name), data=[])
            all_doc_names = kb.list_files()
            is_exist_file = file_path in all_doc_names

            if os.path.isfile(file_path):
                if not override and is_exist_file:
                    # TODO: filesize 不同后的处理
                    file_status = f"文件 {file_path} 已存在。"
                    logger.info(file_status)
                    return dict(code=HTTP_NOT_FOUND, msg=file_status, data=data)
            else:
                file_status = f"文件 {file_path} 不存在或者不是文件。"
                logger.info(file_status)
                return dict(code=HTTP_NOT_FOUND, msg=file_status, data=data)

            return dict(code=HTTP_OK, msg=f"成功读取文件 {file_path}", data=data)
        except Exception as e:
            msg = f"{file_path} 文件上传失败，报错信息为: {e}"
            logger.error(f'{e.__class__.__name__}: {msg}',exc_info=e if log_verbose else None)
            return dict(code=HTTP_INTERNAL_ERROR, msg=msg, data=data)

    params = []
    for file_path in files:
        params.append({"file_path": file_path, "knowledge_base_name": knowledge_base_name, "override": override})
    for result in run_in_thread_pool(validate_file, params=params):
        yield result


def delete_docs(
        knowledge_base_name: str = Body(..., description="知识库名称", examples=["hi3798mv310"]),
        file_names: list[str] = Body(..., description="要删除的文件名列表", examples=[["Hi3798MV310_Datasheet.pdf", "HDMI配置指南.md"]]),  # pylint: disable=line-too-long
        delete_content: bool = Body(False, description="是否同时删除 content 目录下的源文件"),
        not_refresh_vs_cache: bool = Body(False, description="暂不保存向量库缓存（用于批量删除优化）"),
) -> BaseResponse:
    logger.info(f"/kw/knowledge_base/delete_docs request kb={knowledge_base_name} files={file_names}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    knowledge_base_name = urllib.parse.unquote(knowledge_base_name)
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    # P1-3：删除操作前捕获每文件 history max_id，作 mark_history_deleted 上界，
    # 防并发 upload 在 delete_doc 与 mark_history_deleted 之间新写入的行被误标
    # deleted（否则新内容被标 deleted → 后续同内容重传 record_current 查不到
    # 非 deleted top → 重插行 + 重复 add）
    try:
        from server.knowledge_base.file_version import (
            get_history_max_id,
            invalidate_version_map_cache,
            mark_history_deleted,
        )
        max_ids = {fn: get_history_max_id(knowledge_base_name, fn) for fn in file_names}
    except Exception as e:
        logger.warning(f"[tx] delete_docs 捕获 max_id 失败（将不设上界）: {e}")
        max_ids = {}
    for file_name in file_names:
        is_exist_doc = kb.exist_doc(file_name)
        if not is_exist_doc:
            # 如果文件不存在也提示删除成功(前端业务需要设置这样的逻辑-20240611日)
            logger.info(f"未找到文件 {file_name}")
        else:
            try:
                kb_file = KnowledgeFile(filename=file_name,
                                        knowledge_base_name=knowledge_base_name)
                kb.delete_doc(kb_file, delete_content, not_refresh_vs_cache=True)
            except Exception as e:
                msg = f"{file_name} 文件删除失败，错误信息：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
                failed_files[file_name] = msg

    if not not_refresh_vs_cache:
        kb.save_vector_store()
    # 失效版本映射缓存 + 标 history deleted：删除文件后其 history 行不再是当前，
    # 标 deleted 让 record_current 的 sha 去重不再命中已删文件（Bug A 修复），
    # 否则同内容重传会被误判 is_new=False 跳过 Milvus add → "已入库但搜不到"。
    try:
        invalidate_version_map_cache(knowledge_base_name)
        for fn in file_names:
            if fn not in failed_files:
                mark_history_deleted(knowledge_base_name, fn, max_id=max_ids.get(fn))
    except Exception as e:
        logger.warning(f"[tx] delete_docs 失效版本映射/标记 history 失败（60s 后 TTL 自愈）: {e}")
    logger.info("/kw/knowledge_base/delete_docs success: 文件删除完成")
    return BaseResponse(code=HTTP_OK, msg=DELETE_COMPLETE_MSG, data={"failed_files": failed_files})

def archive_docs(
        knowledge_base_name: str = Body(..., description="知识库名称", examples=["hi3798mv310"]),
        file_names: list[str] = Body(..., description="要归档的文件名列表", examples=[["Hi3798MV310_Datasheet.pdf"]]),
        not_refresh_vs_cache: bool = Body(False, description="暂不保存向量库缓存（用于批量操作优化）"),
) -> BaseResponse:
    """
    归档文档：只删除 Milvus 向量数据，不删除 SQLite 记录和磁盘文件
    """
    logger.info(f"/kw/knowledge_base/archive_docs request knowledge_base_name: {knowledge_base_name}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    knowledge_base_name = urllib.parse.unquote(knowledge_base_name)
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    # P1-3：归档操作前捕获每文件 history max_id，作 mark_history_deleted 上界，
    # 防并发 upload 在 do_delete_doc 与 mark_history_deleted 之间新写入的行被
    # 误标 deleted（同 delete_docs 理由）
    try:
        from server.knowledge_base.file_version import (
            get_history_max_id,
            invalidate_version_map_cache,
            mark_history_deleted,
        )
        max_ids = {fn: get_history_max_id(knowledge_base_name, fn) for fn in file_names}
    except Exception as e:
        logger.warning(f"[tx] archive_docs 捕获 max_id 失败（将不设上界）: {e}")
        max_ids = {}
    for file_name in file_names:
        is_exist_doc = kb.exist_doc(file_name)
        if not is_exist_doc:
            logger.info(f"未找到文件 {file_name}")
            failed_files[file_name] = f"未找到文件 {file_name}"
        else:
            try:
                kb_file = KnowledgeFile(filename=file_name,
                                        knowledge_base_name=knowledge_base_name)
                kb.do_delete_doc(kb_file, not_refresh_vs_cache=True)
            except Exception as e:
                msg = f"{file_name} 文件归档失败，错误信息：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
                failed_files[file_name] = msg

    if not not_refresh_vs_cache:
        kb.save_vector_store()
    # 归档只删 Milvus chunk，但语义上该文件不再是当前版本。标 history deleted
    # + 失效缓存，与 delete_docs 一致（Bug A 修复）：否则同内容重传会命中
    # 旧 history top 行 → is_new=False → 跳过 Milvus add → 搜不到。
    try:
        invalidate_version_map_cache(knowledge_base_name)
        for fn in file_names:
            if fn not in failed_files:
                mark_history_deleted(knowledge_base_name, fn, max_id=max_ids.get(fn))
    except Exception as e:
        logger.warning(f"[tx] archive_docs 失效版本映射/标记 history 失败: {e}")
    logger.info("/kw/knowledge_base/archive_docs success: 文件归档完成")
    return BaseResponse(code=HTTP_OK, msg=ARCHIVE_COMPLETE_MSG, data={"failed_files": failed_files})


def update_info(
        knowledge_base_name: str = Body(..., description="知识库名称", examples=["hi3798mv310"]),
        kb_info: str = Body(..., description="知识库介绍（用于 Agent 选择知识库时的描述）", examples=["海思 Hi3798MV310 机顶盒芯片资料库，包含 Datasheet、开发指南、对接规范等"]),  # pylint: disable=line-too-long
):
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))
    kb.update_info(kb_info)

    return BaseResponse(code=HTTP_OK, msg=KB_INFO_UPDATE_COMPLETE_MSG, data={"kb_info": kb_info})


def _update_docs_internal(knowledge_base_name: str, file_names: list[str], **kwargs):
    """update_docs 的核心实现（普通参数，供端点与内部调用复用）。

    回滚等内部调用不能直接调 FastAPI 端点函数（Body/Json 默认值未注入
    时是参数对象而非值）。返回 (failed_files, total_chunks, version_info)；
    kb 不存在时返回 (None, None, None)。
    version_info: 每个文件名映射到含版本标签与 tags 的 dict，让入库调用方
    立即知道每个文件的版本 label + tags，不必事后查 list_versions。
    """
    _FILE_VERSION_LABEL_KEY = 'file_version_label'
    _TAGS_KEY = 'tags'
    override_custom_docs = kwargs.get("override_custom_docs", True)
    not_refresh_vs_cache = kwargs.get("not_refresh_vs_cache", False)
    chunk_size = kwargs.get("chunk_size", None)
    chunk_overlap = kwargs.get("chunk_overlap", None)
    zh_title_enhance = kwargs.get("zh_title_enhance", None)
    docs = kwargs.get("docs", None)
    file_version_label = kwargs.get(_FILE_VERSION_LABEL_KEY, "")
    tags = kwargs.get(_TAGS_KEY, None)

    chunk_size = chunk_size if chunk_size is not None else CHUNK_SIZE
    chunk_overlap = chunk_overlap if chunk_overlap is not None else OVERLAP_SIZE
    zh_title_enhance = zh_title_enhance if zh_title_enhance is not None else ZH_TITLE_ENHANCE
    docs = docs or {}

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return None, None, None

    failed_files = {}
    version_info = {}
    kb_files = []

    # 生成需要加载docs的文件列表
    for file_name in file_names:
        file_detail = get_file_detail(kb_name=knowledge_base_name, filename=file_name)
        # 如果该文件之前使用了自定义docs，则根据参数决定略过或覆盖
        if file_detail.get("custom_docs") and not override_custom_docs:
            continue
        if file_name not in docs:
            try:
                kb_files.append(KnowledgeFile(filename=file_name,
                                              knowledge_base_name=knowledge_base_name
                                              ))
            except Exception as e:
                msg = f"加载文档 {file_name} 时出错：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
                failed_files[file_name] = msg

    total_chunks = 0

    # 从文件生成docs，并进行向量化。
    # 这里利用了KnowledgeFile的缓存功能，在多线程中加载Document，然后传给KnowledgeFile
    for status, result in files2docs_in_thread(kb_files,
                                               chunk_size=chunk_size,
                                               chunk_overlap=chunk_overlap,
                                               zh_title_enhance=zh_title_enhance):
        if status:
            kb_name, file_name, new_docs = result
            logger.info(f"update_doc: file={file_name}, docs_count={len(new_docs) if new_docs else 0}")
            kb_file = KnowledgeFile(filename=file_name,
                                    knowledge_base_name=knowledge_base_name
                                    )
            kb_file.splited_docs = new_docs
            try:
                from server.knowledge_base.file_version import record_current
                # 先登记当前内容为新版本拿 label（同内容幂等不新增行），再传给
                # update_doc 写入 Milvus metadata 嵌套层 file_version_label，
                # 与 dual-mcp 三端同版本标签
                # P2：record_current 返回 (label, is_new)；is_new=False 同内容
                # 重传，跳过 Milvus add（避免重复加 chunk），label 仍透出给调用方
                r = record_current(knowledge_base_name, file_name, kb_file.filepath,
                                   file_version_label=file_version_label, tags=tags)
                if r is None:
                    logger.warning(f"[tx] {file_name} record_current 失败，跳过 Milvus add")
                    failed_files[file_name] = "record_current 失败（文件不存在/sha 计算失败）"
                    continue
                v, is_new = r
                if not is_new:
                    logger.info(f"[tx] {file_name} 内容未变（sha 命中），跳过 Milvus add")
                    version_info[file_name] = {_FILE_VERSION_LABEL_KEY: v, _TAGS_KEY: tags or []}
                    continue
                ok = kb.update_doc(kb_file, not_refresh_vs_cache=True, file_version_label=v, tags=tags)
                if not ok:
                    failed_files[file_name] = "update_doc 返回 False（向量化失败）"
                else:
                    total_chunks += len(new_docs) if new_docs else 0
                    version_info[file_name] = {_FILE_VERSION_LABEL_KEY: v, _TAGS_KEY: tags or []}
                    # MemPalace 作为 best-effort 副作用，失败不影响文件成功状态
                    # 传 v 与 Milvus 同版本标签，避免 get_current_version race
                    try:
                        _store_docs_to_mempalace(knowledge_base_name, file_name,
                                                 new_docs, file_version_label=v, tags=tags)
                    except Exception as mp_err:
                        logger.warning(f"[mempalace] {file_name} 写入失败"
                                       f"(不影响向量化成功): {mp_err}")
            except Exception as e:
                failed_files[file_name] = f"向量化失败: {e}"
        else:
            kb_name, file_name, error = result
            failed_files[file_name] = error

    # 将自定义的docs进行向量化, docs实际没有使用到并且修改后报错多，直接注释掉这段代码。
    if not not_refresh_vs_cache:
        kb.save_vector_store()
    # 失效版本映射缓存：record_current 可能新增 history 行（新 label），
    # 旧 TTL 缓存会让默认当前版本过滤漏掉新 chunk，显式 pop 让下次检索重建。
    try:
        from server.knowledge_base.file_version import invalidate_version_map_cache
        invalidate_version_map_cache(knowledge_base_name)
    except Exception as e:
        logger.warning(f"[tx] 失效版本映射缓存失败（不影响入库，60s 后 TTL 自愈）: {e}")
    return failed_files, total_chunks, version_info


class UpdateDocsRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    file_names: list[str] = Field(..., description="文件名称列表，支持多文件", examples=[["Hi3798MV310_Datasheet.pdf", "HDMI配置指南.md"]])
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    override_custom_docs: bool = Field(False, description="是否覆盖之前自定义的 docs")
    docs: dict[str, Any] = Field({}, description="自定义的 docs",
                       examples=[{"test.txt": [{"page_content": "custom doc", "metadata": {}}]}])
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")
    file_version_label: str = Field("", description="后端约定的版本标签（展示用，如 v1.0/rev-202610）；空则模型端用 sha[:8] 兜底")
    tags: list[str] | None = Field(None, description="文件标签列表（如 [GPIO,UART]，OR 语义过滤）")


def update_docs(
        request: UpdateDocsRequest = Body(...),
) -> BaseResponse:
    """
    更新知识库文档
    """
    knowledge_base_name = request.knowledge_base_name
    file_names = request.file_names
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    override_custom_docs = request.override_custom_docs
    docs = request.docs
    not_refresh_vs_cache = request.not_refresh_vs_cache
    file_version_label = request.file_version_label
    tags = request.tags
    logger.info(f"/kw/knowledge_base/update_docs request knowledge_base_name: {knowledge_base_name}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    failed_files, total_chunks, version_info = _update_docs_internal(
        knowledge_base_name, file_names,
        override_custom_docs=override_custom_docs,
        not_refresh_vs_cache=not_refresh_vs_cache,
        chunk_size=chunk_size, chunk_overlap=chunk_overlap,
        zh_title_enhance=zh_title_enhance, docs=docs,
        file_version_label=file_version_label, tags=tags)
    if failed_files is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))
    logger.info(f"/kw/knowledge_base/update_docs success: 更新文档完成, failed_files={failed_files}, total_chunks={total_chunks}")  # pylint: disable=line-too-long
    if failed_files:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=UPDATE_WITH_FAILURES_MSG_TEMPLATE.format(count=len(failed_files)), data={"failed_files": failed_files, "chunkCount": total_chunks, "version_info": version_info})  # pylint: disable=line-too-long
    return BaseResponse(code=HTTP_OK, msg=UPDATE_COMPLETE_MSG, data={"failed_files": failed_files, "chunkCount": total_chunks, "version_info": version_info})  # pylint: disable=line-too-long


class UpdateDocsUsePathRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    file_paths: list[str] = Field(..., description="文件路径列表，支持多文件", examples=["/data/haisi/Hi3798MV310_Datasheet.pdf", "/data/haisi/HDMI配置指南.md"])
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    override_custom_docs: bool = Field(False, description="是否覆盖之前自定义的 docs")
    docs: dict[str, Any] = Field({}, description="自定义的 docs",
                       examples=[{"test.txt": [{"page_content": "custom doc", "metadata": {}}]}])
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")
    file_version_label: str = Field("", description="后端约定的版本标签（展示用，如 v1.0/rev-202610）；空则模型端用 sha[:8] 兜底")
    tags: list[str] | None = Field(None, description="文件标签列表（如 [GPIO,UART]，OR 语义过滤）")


def update_docs_use_path(
        request: UpdateDocsUsePathRequest = Body(...),
) -> BaseResponse:
    """
    更新知识库文档
    """
    knowledge_base_name = request.knowledge_base_name
    file_paths = request.file_paths
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    override_custom_docs = request.override_custom_docs
    docs = request.docs
    not_refresh_vs_cache = request.not_refresh_vs_cache
    file_version_label = request.file_version_label
    tags = request.tags
    _FILE_VERSION_LABEL_KEY = 'file_version_label'
    _TAGS_KEY = 'tags'
    logger.info(f"/kw/knowledge_base/update_docs_use_path request knowledge_base_name: {knowledge_base_name}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    failed_files = {}
    version_info = {}
    kb_files = []
    # 生成需要加载docs的文件列表
    for file_name in file_paths:
        if file_name not in docs:
            try:
                kw = KnowledgeFile(filename=file_name, knowledge_base_name=knowledge_base_name)
                kw.filepath = file_name  # 上传的路径，所以filename就是filepath
                kb_files.append(kw)
            except Exception as e:
                msg = f"加载文档 {file_name} 时出错：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
                failed_files[file_name] = msg

    total_chunks = 0

    # 从文件生成docs，并进行向量化。
    # 这里利用了KnowledgeFile的缓存功能，在多线程中加载Document，然后传给KnowledgeFile
    for status, result in files2docs_in_thread(kb_files,
                                               chunk_size=chunk_size,
                                               chunk_overlap=chunk_overlap,
                                               zh_title_enhance=zh_title_enhance):
        if status:
            kb_name, file_name, new_docs = result
            kb_file = KnowledgeFile(filename=file_name, knowledge_base_name=knowledge_base_name)
            kb_file.filepath = file_name  # 上传的路径，所以filename就是filepath
            kb_file.splited_docs = new_docs
            try:
                from server.knowledge_base.file_version import record_current
                # 先登记当前内容为新版本拿 label（同内容幂等不新增行），再传给
                # update_doc 写入 Milvus metadata 嵌套层 file_version_label，
                # 与 dual-mcp 三端同版本标签
                # P2：record_current 返回 (label, is_new)；is_new=False 同内容
                # 重传，跳过 Milvus add（避免重复加 chunk），label 仍透出给调用方
                r = record_current(knowledge_base_name, file_name, kb_file.filepath,
                                   file_version_label=file_version_label, tags=tags)
                if r is None:
                    logger.warning(f"[tx] {file_name} record_current 失败，跳过 Milvus add")
                    failed_files[file_name] = "record_current 失败（文件不存在/sha 计算失败）"
                    continue
                v, is_new = r
                if not is_new:
                    logger.info(f"[tx] {file_name} 内容未变（sha 命中），跳过 Milvus add")
                    version_info[file_name] = {_FILE_VERSION_LABEL_KEY: v, _TAGS_KEY: tags or []}
                    continue
                ok = kb.update_doc(kb_file, not_refresh_vs_cache=True, file_version_label=v, tags=tags)
                if not ok:
                    failed_files[file_name] = "update_doc 返回 False（向量化失败）"
                else:
                    total_chunks += len(new_docs) if new_docs else 0
                    version_info[file_name] = {_FILE_VERSION_LABEL_KEY: v, _TAGS_KEY: tags or []}
                    # MemPalace 作为 best-effort 副作用，失败不影响文件成功状态
                    # 传 v 与 Milvus 同版本标签，避免 get_current_version race
                    try:
                        _store_docs_to_mempalace(knowledge_base_name, file_name,
                                                 new_docs, file_version_label=v, tags=tags)
                    except Exception as mp_err:
                        logger.warning(f"[mempalace] {file_name} 写入失败"
                                       f"(不影响向量化成功): {mp_err}")
            except Exception as e:
                failed_files[file_name] = f"向量化失败: {e}"
        else:
            kb_name, file_name, error = result
            failed_files[file_name] = error

    # 将自定义的docs进行向量化
    # Bug 1 修复：与常规路径对齐——record_current 拿 label、传 file_version_label、
    # best-effort 写 mempalace。否则自定义 docs 在 Milvus/mempalace 三端不同步：
    # Milvus chunk 无 file_version_label、mempalace 完全不写入。
    # P2：record_current 返回 (label, is_new)；is_new=False 同内容重传跳过 add，
    # label 仍透出。file_name 非真实路径时 record_current 返回 None，跳过该文件
    # （update_doc 也会因路径不存在返回 False，此处统一跳过避免误报"向量化失败"）。
    for file_name, v in docs.items():
        try:
            v = [x if isinstance(x, Document) else Document(**x) for x in v]
            kb_file = KnowledgeFile(filename=file_name, knowledge_base_name=knowledge_base_name)
            kb_file.filepath = file_name  # 上传的路径，所以filename就是filepath
            from server.knowledge_base.file_version import record_current
            r = record_current(knowledge_base_name, file_name, kb_file.filepath,
                                file_version_label=file_version_label, tags=tags)
            if r is None:
                logger.warning(f"[tx] {file_name} record_current 失败（路径非真实/sha 失败），跳过")
                failed_files[file_name] = "record_current 失败（文件不存在/sha 计算失败）"
                continue
            fv, is_new = r
            if not is_new:
                logger.info(f"[tx] {file_name} 内容未变（sha 命中），跳过 Milvus add")
                version_info[file_name] = {_FILE_VERSION_LABEL_KEY: fv, _TAGS_KEY: tags or []}
                continue
            ok = kb.update_doc(kb_file, docs=v, not_refresh_vs_cache=True, file_version_label=fv, tags=tags)
            if not ok:
                failed_files[file_name] = "update_doc 返回 False（向量化失败）"
            else:
                total_chunks += len(v) if v else 0
                version_info[file_name] = {_FILE_VERSION_LABEL_KEY: fv, _TAGS_KEY: tags or []}
                try:
                    _store_docs_to_mempalace(knowledge_base_name, file_name, v,
                                             file_version_label=fv, tags=tags)
                except Exception as mp_err:
                    logger.warning(f"[mempalace] {file_name} 写入失败"
                                   f"(不影响向量化成功): {mp_err}")
        except Exception as e:
            msg = f"为 {file_name} 添加自定义docs时出错：{e}"
            logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
            failed_files[file_name] = msg

    if not not_refresh_vs_cache:
        kb.save_vector_store()
    # 失效版本映射缓存：record_current 可能新增 history 行（新 label），
    # 旧 TTL 缓存会让默认当前版本过滤漏掉新 chunk，显式 pop 让下次检索重建。
    try:
        from server.knowledge_base.file_version import invalidate_version_map_cache
        invalidate_version_map_cache(knowledge_base_name)
    except Exception as e:
        logger.warning(f"[tx] update_docs_use_path 失效版本映射缓存失败（60s 后 TTL 自愈）: {e}")
    # 上传的文件里有代码文件 → 暂存复制进 kbId 项目根 + 后台触发 dual-mcp 重索引
    # （文档暂存树 /projects/kb/<库名>/ 与 /projects/<kbId> 约定不一致，
    #  代码先复制对齐到 kbId 根，再按默认路径重索引——避免命中空壳项目
    #  后按空目录重索引的死结）
    from server.comm.dual_mcp_client import is_code_file, stage_code_files_to_kb_root, trigger_reindex_async
    if any(is_code_file(f) for f in file_paths):
        from server.knowledge_base.file_version import build_files_version_map
        stage_code_files_to_kb_root(knowledge_base_name, file_paths)
        trigger_reindex_async(knowledge_base_name,
                              files_version=build_files_version_map(knowledge_base_name))
    logger.info(f"/kw/knowledge_base/update_docs_use_path success: 更新文档完成, failed_files={failed_files}, total_chunks={total_chunks}")  # pylint: disable=line-too-long
    if failed_files:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=UPDATE_WITH_FAILURES_MSG_TEMPLATE.format(count=len(failed_files)), data={"failed_files": failed_files, "chunkCount": total_chunks, "version_info": version_info})  # pylint: disable=line-too-long
    return BaseResponse(code=HTTP_OK, msg=UPDATE_COMPLETE_MSG, data={"failed_files": failed_files, "chunkCount": total_chunks, "version_info": version_info})  # pylint: disable=line-too-long


def download_doc(
        knowledge_base_name: str = Query(..., description="知识库名称", examples=["hi3798mv310"]),
        file_name: str = Query(..., description="文件名称", examples=["Hi3798MV310_Datasheet.pdf"]),
        preview: bool = Query(False, description="是否在浏览器内预览：true 浏览器内预览，false 下载"),
):
    """
    下载知识库文档
    """
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    if preview:
        content_disposition_type = "inline"
    else:
        content_disposition_type = None

    try:
        kb_file = KnowledgeFile(filename=file_name,
                                knowledge_base_name=knowledge_base_name)

        if os.path.exists(kb_file.filepath):
            return FileResponse(
                    path=kb_file.filepath,
                    filename=kb_file.filename,
                    media_type="multipart/form-data",
                    content_disposition_type=content_disposition_type,
            )
    except Exception as e:
        # msg = f"{kb_file.filename} 读取文件失败，错误信息是：{e}"  # kb_file读取不成功会报错
        msg = f"{file_name} 读取文件失败，错误信息是：{e}"
        logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=msg)

    return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=READ_FILE_FAILED_MSG_TEMPLATE.format(filename=kb_file.filename))


class RecreateVectorStoreRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称", examples=["hi3798mv310"])
    allow_empty_kb: bool = Field(True, description="是否允许对空知识库（未入库或无文档）执行重建")
    vs_type: str = Field(DEFAULT_VS_TYPE, description="向量库类型，默认 milvus")
    embed_model: str = Field(EMBEDDING_MODEL, description="嵌入模型名称")
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存（用于批量入库优化）")


def recreate_vector_store(
        request: RecreateVectorStoreRequest = Body(...),
):
    """
    根据 content 目录下的文档重建向量库。
    适用于用户直接把文件拷贝到 content 目录（而非通过 HTTP 上传）的场景。
    默认情况下，get_service_by_name 只返回 info.db 中存在且有文档的知识库；
    设置 allow_empty_kb=True 可应用于未在 info.db 中或无文档的空知识库。
    """
    knowledge_base_name = request.knowledge_base_name
    allow_empty_kb = request.allow_empty_kb
    vs_type = request.vs_type
    embed_model = request.embed_model
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    not_refresh_vs_cache = request.not_refresh_vs_cache

    def output():
        _CODE_KEY = 'code'
        _MSG_KEY = 'msg'
        kb = KBServiceFactory.get_service(knowledge_base_name, vs_type, embed_model)
        if not kb.exists() and not allow_empty_kb:
            yield {_CODE_KEY: 404, _MSG_KEY: f"未找到知识库 ‘{knowledge_base_name}’"}
        else:
            # P1-3：clear_vs 前捕获整库 history max_id，作 mark_history_deleted
            # 上界，防并发 upload 在 clear_vs 与 mark_history_deleted 之间新写入
            # 的行被误标 deleted
            try:
                from server.knowledge_base.file_version import get_history_max_id, mark_history_deleted, record_current
                max_id = get_history_max_id(knowledge_base_name)
            except Exception as e:
                logger.warning(f"[recreate] 捕获 max_id 失败（将不设上界）: {e}")
                max_id = None
            if kb.exists():
                kb.clear_vs()
            kb.create_kb()
            # Bug A 修复：clear_vs drop 了整个 collection，旧 history 行已不代表
            # 当前 chunk。标整库 history deleted，让重建时 record_current 新增行
            # （否则 sha 去重命中旧未删行 → is_new=False → 跳过 add_doc）。
            # Bug E 修复：重建时每个文件调 record_current 拿 label 传给 add_doc，
            # 否则重建后 chunk 无 file_version_label → 默认当前版本 filter 排除所有。
            try:
                mark_history_deleted(knowledge_base_name, max_id=max_id)
            except Exception as e:
                logger.warning(f"[recreate] 标记 history deleted 失败（继续重建）: {e}")
                record_current = None
            files = list_files_from_folder(knowledge_base_name)
            kb_files = [(file, knowledge_base_name) for file in files]
            i = 0
            for status, result in files2docs_in_thread(kb_files,
                                                       chunk_size=chunk_size,
                                                       chunk_overlap=chunk_overlap,
                                                       zh_title_enhance=zh_title_enhance):
                if status:
                    kb_name, file_name, docs = result
                    kb_file = KnowledgeFile(filename=file_name, knowledge_base_name=kb_name)
                    kb_file.splited_docs = docs
                    # Bug E：record_current 拿 label 传给 add_doc（失败则 label 空）
                    _label = ""
                    if record_current is not None:
                        try:
                            _v = record_current(kb_name, file_name, kb_file.filepath)
                            if _v:
                                _label = _v[0]
                        except Exception as e:
                            logger.warning(f"[recreate] record_current {file_name} 失败: {e}")
                    yield json.dumps({
                        _CODE_KEY: 200,
                        _MSG_KEY: f"({i + 1} / {len(files)}): {file_name}",
                        "total": len(files),
                        "finished": i + 1,
                        "doc": file_name,
                    }, ensure_ascii=False)
                    kb.add_doc(kb_file, not_refresh_vs_cache=True, file_version_label=_label)
                else:
                    kb_name, file_name, error = result
                    msg = f"添加文件‘{file_name}’到知识库‘{knowledge_base_name}’时出错：{error}。已跳过。"
                    logger.error(msg)
                    yield json.dumps({
                        _CODE_KEY: 500,
                        _MSG_KEY: msg,
                    })
                i += 1
            if not not_refresh_vs_cache:
                kb.save_vector_store()

    return EventSourceResponse(output())


# ── 项目级上传：zip 解压 + 代码/文档自动分流 ─────────────────────────

_PROJECT_DOC_EXTS = {".md", ".pdf", ".docx", ".doc", ".xlsx", ".xls",
                     ".pptx", ".ppt", ".txt", ".csv", ".html", ".htm",
                     ".xml", ".rst", ".ipynb"}


def _safe_extract_zip(zf, dest: str):
    """安全解压：逐成员校验归一化路径必须在目标目录内（防 zip slip 路径穿越）。"""
    import zipfile  # noqa: F401
    dest_abs = os.path.abspath(dest)
    for member in zf.namelist():
        target = os.path.abspath(os.path.join(dest, member))
        if not (target == dest_abs or target.startswith(dest_abs + os.sep)):
            raise ValueError(f"zip 成员路径越界: {member}")
    zf.extractall(dest)




_MEMPALACE_ENTRY_MAX = 8000  # 单条记忆上限（字符），超长文档按此切分为多条
_MP_PALACE_READY = set()  # 本进程内已确保存在的宫殿，避免每个文档重复 POST /palace


def _join_docs_dedup_overlap(docs) -> str:
    """把 splitter 产出的 Document 列表还原为去重后的连续文本。

    LangChain 切块带 overlap（相邻 chunk 尾部与前部重复），直接 join 会把
    重复段原样写入 MemPalace（实测 BSP FAQ.md 的"解决办法…如何使用USB键盘
    鼠标"连续出现两次）。按"前块后缀 == 后块前缀"逐段裁剪恢复原文。
    """
    texts = [getattr(d, "page_content", "") or "" for d in docs]
    if not texts:
        return ""
    out = texts[0]
    for t in texts[1:]:
        cut = 0
        for k in range(min(len(t), len(out), 500), 0, -1):
            if out.endswith(t[:k]):
                cut = k
                break
        out += t[cut:]
    return out


_MP_HTML_NOISE_RULES = (
    # <a name="xxx"></a> / <a name="xxx"> 导出锚点，对语义零贡献
    (re.compile(r'<a\s+name="[^"]*">\s*</a>\s*'), ""),
    (re.compile(r'<a\s+name="[^"]*">'), ""),
    # markdown 锚点链接目标 [文字](#ZH-CN_TOPIC_xxx) → [文字]
    (re.compile(r'\(#[A-Za-z\-_0-9]+\)'), ""),
)


def _strip_html_noise(text: str) -> str:
    """去掉 HTML 锚点等噪声后再入库/抽 KG。

    实测同一 chunk：净化后 qwen3-rerank 0.8315，带 <a name> 噪声仅 0.6855——
    噪声会把正确答案 chunk 挤出 rerank top_n（2026-09-01 NPU 问题 dev 答错根因之二）。
    """
    for pat, rep in _MP_HTML_NOISE_RULES:
        text = pat.sub(rep, text)
    return text


def _store_docs_to_mempalace(kb_name: str, file_name: str, docs: list,
                             file_version_label: str = None, tags: list[str] = None):
    """把文档解析后的全文写入 MemPalace（palace=kbId，topic=文档名，verbatim 存储）。

    在向量化主流程中调用，复用已解析的 Document 文本，不做二次解析；
    净化后的同一份文本同时供给 mempalace store 与 KG 实体关系抽取。
    任何异常只告警，绝不中断向量化主流程。

    file_version_label：优先用调用方传入的版本标签（与 Milvus 写入保持一致，
    避免并发上传同一文件时 get_current_version 拿到的是别人刚写的标签导致
    三端版本错位）。为 None 时回退到 get_current_version。tags 规范化为
    逗号分隔串随 payload 一并写入（供 dual-mcp 侧元数据展示/过滤）。
    """
    try:
        if not docs:
            return
        text = _strip_html_noise(_join_docs_dedup_overlap(docs)).strip()
        if not text:
            return
        import urllib.parse as _up
        import urllib.request as _ur
        base = MEMPALACE_BASE_URL or os.environ.get("MEMPALACE_REST_URL", "http://haisi-mempalace-rest:8777")
        if kb_name not in _MP_PALACE_READY:
            # 必须先建宫殿再 store：宫殿不存在时 store 会隐式创建，
            # 之后显式 POST /palace 触发 ChromaDB 修复（Fixed collection missing
            # _type）会丢失先写入的条目
            _ur.urlopen(_ur.Request(base + f"/palace?palace={_up.quote(kb_name)}",
                                    method=HTTP_METHOD_POST), timeout=HTTP_UPLOAD_TIMEOUT_SEC).read()
            _MP_PALACE_READY.add(kb_name)
        doc_name = os.path.basename(file_name)
        from server.knowledge_base.file_version import _tags_to_str, get_current_version
        if file_version_label is None:
            # 兜底：调用方未传时再查（保持向后兼容，但主链路应直接传）
            file_version_label = get_current_version(kb_name, file_name)
        tags_str = _tags_to_str(tags)
        for i in range(0, len(text), _MEMPALACE_ENTRY_MAX):
            # room=文档名 + source_file=文件名 → tool_add_drawer 文档路径：
            # 检索结果可溯源（报告 P1），add_drawer 内部自动分块去重
            payload = {"entry": text[i:i + _MEMPALACE_ENTRY_MAX],
                       "agent_name": "rag", "topic": doc_name,
                       "wing": "wing_rag", "palace": kb_name,
                       "room": doc_name, "source_file": file_name,
                       "file_version_label": file_version_label,
                       "tags": tags_str}
            req = _ur.Request("/".join([base.rstrip("/"), "store"]),
                              data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                              method=HTTP_METHOD_POST, headers={"Content-Type": "application/json"})
            with _ur.urlopen(req, timeout=HTTP_DOWNLOAD_TIMEOUT_SEC) as r:
                if r.status not in (200, 201):
                    logger.warning(f"[mempalace] store {doc_name} HTTP {r.status}")
        _extract_kg_facts_mempalace(kb_name, file_name, text)
        # 入库自动冲突检测（按库开关 conflict_detection_switch，默认关；best-effort）
        try:
            from server.knowledge_base.kb_conflict_api import trigger_auto_conflict_detection
            _cd_path = file_name if os.path.isabs(file_name) else get_file_path(kb_name, file_name)
            trigger_auto_conflict_detection(kb_name, _cd_path)
        except Exception as e:
            logger.warning(f"[mempalace] auto conflict detection skipped (best-effort): {e}")
            pass
    except Exception as e:
        logger.warning(f"[mempalace] 存储文档全文失败（不影响主流程）: {e}")


_KG_EXTRACT_MODEL = os.environ.get("MEMPALACE_KG_EXTRACT_MODEL", "qwen-flash")
_KG_EXTRACT_MAX_TEXT = 3000  # 送 LLM 抽取的文本上限（字符）
_KG_EXTRACT_MAX_FACTS = 30   # 每文档最多写入的关系数
_KG_EXTRACT_MAX_TOPICS = 20  # has_topic 实体数上限
_KG_EXTRACT_MIN_TEXT = 50    # 低于此长度的文本不做抽取


def _extract_kg_facts_mempalace(kb_name: str, file_name: str, text: str):
    """用 LLM 从文档全文抽取实体关系，写入 MemPalace 知识图谱。

    除关系三元组外，还补 文档名 --has_topic--> 实体 边，支撑"用实体名查图谱"。
    纯增强功能：LLM 不可用/超时/输出非法 JSON 时告警并跳过，绝不影响上传主流程。
    可用 MEMPALACE_KG_EXTRACT=0 环境变量关闭。
    """
    _API_KEY_KEY = 'api_key'
    _API_BASE_URL_KEY = 'api_base_url'
    _SUBJECT_KEY = 'subject'
    _PREDICATE_KEY = 'predicate'
    _OBJECT_KEY = 'object'
    if os.environ.get("MEMPALACE_KG_EXTRACT", "1") == "0":
        return
    try:
        if len(text) < _KG_EXTRACT_MIN_TEXT:
            return
        import urllib.request as _ur

        snippet = text[:_KG_EXTRACT_MAX_TEXT]
        prompt = (
            "你是知识图谱实体关系抽取器。从下面的文本中抽取实体以及实体之间的关系。\n"
            "只输出一个 JSON 数组，不要输出任何其他内容。每个元素形如：\n"
            '{"subject": "实体名", "predicate": "关系", "object": "实体名"}\n'
            "要求：\n"
            "1. subject 和 object 必须是文本中出现的人名、地名、组织、技能、物品等实体，"
            "实体名尽量简短（不超过20字）；\n"
            "2. predicate 用简短英文（如 same_as、master_of、named、born_at、discovered、"
            "learned、located_in、part_of、contains、uses）；\n"
            f"3. 最多输出 {_KG_EXTRACT_MAX_FACTS} 条最重要的关系；\n"
            "4. 如果文本没有明显实体关系，输出空数组 []。\n\n"
            f"文本：\n{snippet}\n"
        )

        from server.utils import get_model_worker_config
        model_name = _KG_EXTRACT_MODEL
        config = get_model_worker_config(model_name)
        if not config.get(_API_KEY_KEY) or not config.get(_API_BASE_URL_KEY):
            from configs import LLM_MODELS
            for _m in LLM_MODELS:
                _c = get_model_worker_config(_m)
                if _c.get(_API_KEY_KEY) and _c.get(_API_BASE_URL_KEY):
                    config, model_name = _c, _m
                    break
            else:
                raise ValueError("无可用在线 LLM 配置，跳过实体关系抽取")

        from openai import OpenAI
        _llm_client = OpenAI(api_key=config.get(_API_KEY_KEY),
                        base_url=config.get(_API_BASE_URL_KEY), timeout=HTTP_INDEX_TIMEOUT_SEC)
        # with_raw_response 拿到 httpx 响应头提取 x-request-id（openai 1.14.3
        # 普通 create 返回的 ChatCompletion 已被 pydantic 剥离 raw headers）
        raw = _llm_client.chat.completions.with_raw_response.create(
            model=config.get("model_id", model_name),
            messages=[{"role": "user", "content": prompt}],
            temperature=0.1,
            max_tokens=2000,
        )
        # create 成功即提取 req_id（阿里云限流排查用，debug 级低噪）
        _req_id = extract_aliyun_request_id(raw)
        log_dashscope(config.get("model_id", model_name), "kg_extract_done",
                      _req_id, level="debug")
        resp = raw.parse()
        content = resp.choices[0].message.content or ""
        m = re.search(r"\[.*\]", content, re.S)
        facts = json.loads(m.group(0) if m else content)
        if not isinstance(facts, list):
            raise ValueError(f"LLM 输出不是数组: {content[:100]}")

        base = MEMPALACE_BASE_URL or os.environ.get("MEMPALACE_REST_URL", "http://haisi-mempalace-rest:8777")
        doc_name = os.path.basename(file_name)
        seen, topics = set(), []
        for f in facts[:_KG_EXTRACT_MAX_FACTS]:
            if not isinstance(f, dict):
                continue
            subj = str(f.get(_SUBJECT_KEY, "")).strip()
            pred = str(f.get(_PREDICATE_KEY, "")).strip().lower().replace(" ", "_")
            obj = str(f.get(_OBJECT_KEY, "")).strip()
            _field_missing = not subj or not pred or not obj
            _field_too_long = len(subj) > 128 or len(obj) > 128
            if _field_missing or _field_too_long:
                continue
            key = (subj, pred, obj)
            if key in seen:
                continue
            seen.add(key)
            payload = {_SUBJECT_KEY: subj, _PREDICATE_KEY: pred, _OBJECT_KEY: obj,
                       "palace": kb_name, "source_file": doc_name}
            try:
                _ur.urlopen(_ur.Request(
                    "/".join([base.rstrip("/"), "kg"]),
                    data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                    method=HTTP_METHOD_POST, headers={"Content-Type": "application/json"}),
                    timeout=HTTP_UPLOAD_TIMEOUT_SEC).read()
            except Exception as e:
                logger.debug(f"[mempalace] failed to send kg relation {subj} {pred} {obj}: {e}")
                pass
            if subj not in topics:
                topics.append(subj)
            if obj not in topics:
                topics.append(obj)
        for ent in topics[:_KG_EXTRACT_MAX_TOPICS]:
            payload = {_SUBJECT_KEY: doc_name, _PREDICATE_KEY: "has_topic", _OBJECT_KEY: ent,
                       "palace": kb_name, "source_file": doc_name}
            try:
                _ur.urlopen(_ur.Request(
                    "/".join([base.rstrip("/"), "kg"]),
                    data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                    method=HTTP_METHOD_POST, headers={"Content-Type": "application/json"}),
                    timeout=HTTP_UPLOAD_TIMEOUT_SEC).read()
            except Exception as e:
                logger.debug(f"[mempalace] failed to send kg topic {doc_name} has_topic {ent}: {e}")
                pass
        logger.info(f"[mempalace] KG 实体关系抽取完成: {doc_name} -> "
                    f"{len(seen)} 条关系, {len(topics)} 个实体")
    except Exception as e:
        _req_id = extract_aliyun_request_id(e)
        log_dashscope(_KG_EXTRACT_MODEL, "kg_extract_error", _req_id,
                      level="warning", reason=str(e)[:200])
        logger.warning(f"[mempalace] KG 实体关系抽取失败（不影响主流程）: {e}{fmt_req_id(_req_id)}")


def _notify_mempalace(kb_name: str, doc_files: list, code_files: list, skipped: list):
    """把项目上传结果写入 MemPalace（幂等建宫殿 + 图谱事实 + 上传摘要记忆）。

    palace=kbId 与 store/search 的宫殿隔离约定一致；kg 为全局单图，
    subject=kbId 的事实可直接 GET /kg?entity=<kbId> 查到。任何异常仅告警，
    绝不中断上传主流程。
    """
    try:
        import urllib.parse as _up
        import urllib.request as _ur
        base = MEMPALACE_BASE_URL or os.environ.get("MEMPALACE_REST_URL", "http://haisi-mempalace-rest:8777")

        def _post(path: str, payload=None, timeout=HTTP_UPLOAD_TIMEOUT_SEC):
            data = json.dumps(payload).encode() if payload is not None else None
            req = _ur.Request(base + path, data=data, method=HTTP_METHOD_POST,
                              headers={"Content-Type": "application/json"})
            with _ur.urlopen(req, timeout=timeout) as r:
                return r.status

        _post(f"/palace?palace={_up.quote(kb_name)}")
        for f in doc_files[:100]:
            _post("/kg", {"subject": kb_name, "predicate": "contains_doc",
                          "object": os.path.basename(f), "source_file": f,
                          "palace": kb_name})
        _post("/store", {"entry": f"项目上传完成：文档 {len(doc_files)} 个、"
                                  f"代码 {len(code_files)} 个、跳过 {len(skipped)} 个",
                         "agent_name": "rag", "topic": kb_name, "palace": kb_name})
    except Exception as e:
        logger.warning(f"[mempalace] 写入失败（不影响主流程）: {e}")

class UploadProjectRequest(BaseModel):
    knowledge_base_name: str = Field(..., description="知识库名称（kbId，与 dual-mcp 项目名一致）")
    override: bool = Field(False, description="已存在项目时是否覆盖重建")
    to_vector_store: bool = Field(True, description="文档文件是否向量化入库")
    chunk_size: int = Field(CHUNK_SIZE, description="知识库中单段文本最大长度")
    chunk_overlap: int = Field(OVERLAP_SIZE, description="知识库中相邻文本的重合长度")
    zh_title_enhance: bool = Field(ZH_TITLE_ENHANCE, description="是否开启中文标题加强")
    not_refresh_vs_cache: bool = Field(False, description="暂不保存向量库缓存")


def upload_project(
        request: Annotated[UploadProjectRequest, Form()],
        file: UploadFile = File(..., description="项目 zip 压缩包（解压后直接是项目根目录）"),
) -> BaseResponse:
    """
    API接口：上传整个项目（zip）。

    端侧闭环：zip 解压到共享存储 SFS_MOUNT_PATH/<kb>/code/（== dual-mcp 容器的
    /projects/<kb>/code/），代码文件触发 dual-mcp 后台建索引，文档文件走向量化入库。
    """
    knowledge_base_name = request.knowledge_base_name
    override = request.override
    to_vector_store = request.to_vector_store
    chunk_size = request.chunk_size
    chunk_overlap = request.chunk_overlap
    zh_title_enhance = request.zh_title_enhance
    not_refresh_vs_cache = request.not_refresh_vs_cache
    import io
    import zipfile

    logger.info(f"/kw/knowledge_base/upload_project request kb:{knowledge_base_name} file:{file.filename}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    from configs import SFS_MOUNT_PATH
    code_dir = os.path.join(SFS_MOUNT_PATH, knowledge_base_name, "code")
    if os.path.isdir(code_dir) and os.listdir(code_dir) and not override:
        return BaseResponse(code=HTTP_CONFLICT, msg=KB_ALREADY_EXISTS_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    # 逐文件版本化替换（不再整目录 rmtree）：同内容文件跳过（版本不变）；
    # 变化文件先登记旧版本再覆盖；新 zip 中不存在的旧文件登记版本后删除
    # （可回滚找回）。文件逻辑名 = code 目录下相对路径（与 dual-mcp 图谱
    # file_path 对齐，供版本透出）。
    import tempfile

    from server.knowledge_base.file_version import _sha256_file, record_current, register_superseded
    os.makedirs(code_dir, exist_ok=True)
    tmp_dir = tempfile.mkdtemp(prefix="upload_proj_")
    try:
        with zipfile.ZipFile(io.BytesIO(file.file.read()), "r") as zf:
            _safe_extract_zip(zf, tmp_dir)
        kept: set = set()
        for root, _dirs, names in os.walk(tmp_dir):
            for n in names:
                src = os.path.join(root, n)
                rel = os.path.relpath(src, tmp_dir)
                dst = os.path.normpath(os.path.join(code_dir, rel))
                if os.path.isfile(dst) and _sha256_file(src) == _sha256_file(dst):
                    kept.add(dst)
                    continue
                register_superseded(knowledge_base_name, rel, dst)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(src, dst)
                kept.add(dst)
        for root, _dirs, names in os.walk(code_dir):
            for n in names:
                p = os.path.normpath(os.path.join(root, n))
                if p not in kept:
                    register_superseded(knowledge_base_name, os.path.relpath(p, code_dir), p)
                    os.remove(p)
    except zipfile.BadZipFile:
        return BaseResponse(code=HTTP_BAD_REQUEST, msg=INVALID_ZIP_MSG)
    except ValueError as e:
        return BaseResponse(code=HTTP_BAD_REQUEST, msg=ZIP_REJECT_MSG_TEMPLATE.format(error=e))
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

    # 自动分流：代码扩展名 → dual-mcp 索引；文档扩展名 → 向量化；其余跳过
    from server.comm.dual_mcp_client import is_code_file
    code_files, doc_files, skipped = [], [], []
    for root, _dirs, names in os.walk(code_dir):
        for n in names:
            p = os.path.join(root, n)
            if is_code_file(p):
                code_files.append(p)
            elif os.path.splitext(n)[1].lower() in _PROJECT_DOC_EXTS:
                doc_files.append(p)
            else:
                skipped.append(p)

    failed_files = {}
    if to_vector_store and doc_files:
        res = upload_docs_use_path(
            request=UploadDocsUsePathRequest(
                files=doc_files,
                knowledge_base_name=knowledge_base_name,
                override=True,
                to_vector_store=True,
                chunk_size=chunk_size,
                chunk_overlap=chunk_overlap,
                zh_title_enhance=zh_title_enhance,
                not_refresh_vs_cache=True,
            ),
        )
        failed_files.update(res.data.get("failed_files", {}))
        if not not_refresh_vs_cache:
            kb.save_vector_store()

    # 登记当前版本（代码文件不经向量化链路，需在此显式登记）
    from server.knowledge_base.file_version import cleanup_async
    for p in code_files:
        record_current(knowledge_base_name, os.path.relpath(p, code_dir), p)

    # 代码触发 dual-mcp 后台建索引（kbId == 项目名约定），带 per-file 版本映射
    from server.comm.dual_mcp_client import trigger_index_async
    from server.knowledge_base.file_version import build_files_version_map
    trigger_index_async(knowledge_base_name,
                        project_path=f"/projects/{knowledge_base_name}/code",
                        files_version=build_files_version_map(knowledge_base_name))

    _notify_mempalace(knowledge_base_name, doc_files, code_files, skipped)

    cleanup_async(knowledge_base_name)

    data = {
        "code_dir": code_dir,
        "code_files": len(code_files),
        "doc_files": len(doc_files),
        "skipped": len(skipped),
        "failed_files": failed_files,
        "dual_mcp": "indexing triggered (async)",
    }
    logger.info(f"/kw/knowledge_base/upload_project done: {data}")
    return BaseResponse(code=HTTP_OK, msg=PROJECT_UPLOAD_COMPLETE_MSG, data=data)


def list_file_versions(
        knowledge_base_name: str = Query(..., description="知识库名称"),
        file_name: str = Query(..., description="文件逻辑名（文档=上传文件名；代码=code 目录下相对路径）"),
) -> BaseResponse:
    """API 接口：列出文件的历史版本（含当前版本与可回滚标记）。"""
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    from server.knowledge_base.file_version import list_versions
    versions = list_versions(knowledge_base_name, file_name)
    return BaseResponse(data={"knowledge_base_name": knowledge_base_name,
                              "file_name": file_name, "versions": versions})


def rollback_file_version(
        knowledge_base_name: str = Body(..., description="知识库名称"),
        file_name: str = Body(..., description="文件逻辑名（同 list_file_versions）"),
        version: str = Body(..., description='目标版本标签（file_version_label 字符串，如 "v1.0"/"rev-202610"）'),
) -> BaseResponse:
    """API 接口：回滚文件到历史版本。

    把目标版本内容写回当前路径后走常规链路：文档重新向量化（Milvus 重写；
    相同内容 chunk 内容哈希幂等复用）；代码文件触发 dual-mcp 增量重索引。
    回滚内容作为新版本行登记（label 用 sha[:8] 兜底，note 标记来源），
    回滚后仍可再回滚。
    """
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is None:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))

    from server.knowledge_base.file_version import (
        build_files_version_map,
        cleanup_async,
        record_current,
        restore_version,
    )
    ver = (version or "").strip()
    if not ver:
        return BaseResponse(code=HTTP_BAD_REQUEST, msg=VERSION_LABEL_EMPTY_MSG_TEMPLATE.format(version=version))
    file_path = restore_version(knowledge_base_name, file_name, ver)
    if not file_path:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=VERSION_NOT_ROLLBACK_MSG_TEMPLATE.format(ver=ver))

    data = {"knowledge_base_name": knowledge_base_name, "file_name": file_name,
            "rolled_back_from": ver, "file_path": file_path}
    from server.comm.dual_mcp_client import is_code_file, stage_code_files_to_kb_root, trigger_reindex_async
    if is_code_file(file_path):
        # 代码文件：恢复内容 + 增量重索引（CBM file_hashes 只重索引变化文件）
        record_current(knowledge_base_name, file_name, file_path,
                       note=f"restored from {ver}")
        stage_code_files_to_kb_root(knowledge_base_name, [file_path])
        trigger_reindex_async(knowledge_base_name,
                              files_version=build_files_version_map(knowledge_base_name))
    else:
        # 文档：走常规向量化（Milvus 重写；record_current 内 sha[:8] 兜底 label，
        # 相同内容 chunk 幂等，store 侧更新版本元数据）
        from server.knowledge_base.kb_doc_api import _update_docs_internal
        failed, _chunks, _version_info = _update_docs_internal(knowledge_base_name, [file_name],
                                                override_custom_docs=True,
                                                not_refresh_vs_cache=False)
        if failed is None:
            return BaseResponse(code=HTTP_NOT_FOUND, msg=KB_NOT_FOUND_MSG_TEMPLATE.format(knowledge_base_name=knowledge_base_name))
        if failed:
            return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=ROLLBACK_FAILED_MSG_TEMPLATE.format(failed=failed), data=data)
    cleanup_async(knowledge_base_name)
    logger.info(f"/kw/knowledge_base/file_rollback done: {data}")
    return BaseResponse(code=HTTP_OK, msg=ROLLBACK_COMPLETE_MSG_TEMPLATE.format(file_name=file_name, ver=ver), data=data)
