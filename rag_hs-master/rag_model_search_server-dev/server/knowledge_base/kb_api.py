import threading
import urllib

from fastapi import Body

from configs import DEFAULT_VS_TYPE, EMBEDDING_MODEL, log_verbose, logger
from server.db.repository.knowledge_base_repository import list_kbs_from_db
from server.knowledge_base.kb_service.base import KBServiceFactory
from server.knowledge_base.utils import validate_kb_name
from server.utils import BaseResponse, ListResponse


def list_kbs():
    # Get List of Knowledge Base
    kbs_list = list_kbs_from_db()
    logger.info(f"/kw/knowledge_base/list_knowledge_bases 获取知识库列表:{str(kbs_list)}")
    return ListResponse(data=kbs_list)


def create_kb(
        knowledge_base_name: str = Body(..., description="知识库名称，建议使用芯片型号命名（如 hi3798mv310）", examples=["hi3798mv310"]),
        vector_store_type: str = Body(DEFAULT_VS_TYPE, description="向量库类型，默认 milvus（配置后强制使用默认值）"),
        embed_model: str = Body(EMBEDDING_MODEL, description="嵌入模型名称（配置后强制使用默认值）"),
        ) -> BaseResponse:
    # 创建知识库时候向量库和embeding模型使用默认的不给用户设置
    vector_store_type = DEFAULT_VS_TYPE
    embed_model = EMBEDDING_MODEL
    logger.info(f"/create_kb knowledge_base_name:{knowledge_base_name}")
    # Create selected knowledge base
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=403, msg="Don't attack me")
    if knowledge_base_name is None or knowledge_base_name.strip() == "":
        return BaseResponse(code=404, msg="知识库名称不能为空，请重新填写知识库名称")

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)
    if kb is not None:
        logger.info(f"/create_kb failed: 已存在同名知识库 {knowledge_base_name}")
        return BaseResponse(code=404, msg=f"已存在同名知识库 {knowledge_base_name}")

    kb = KBServiceFactory.get_service(knowledge_base_name, vector_store_type, embed_model)
    try:
        kb.create_kb()
    except Exception as e:
        msg = f"创建知识库出错： {e}"
        logger.error(f'{e.__class__.__name__}: {msg}',
                     exc_info=e if log_verbose else None)
        return BaseResponse(code=500, msg=msg)
    logger.info(f"/create_kb success: 已新增知识库 {knowledge_base_name}")
    return BaseResponse(code=200, msg=f"已新增知识库 {knowledge_base_name}")


def delete_kb(
        knowledge_base_name: str = Body(..., description="要删除的知识库名称", examples=["hi3798mv310"])
) -> BaseResponse:
    logger.info(f"/knowledge_base/delete_knowledge_base request:{knowledge_base_name}")
    # Delete selected knowledge base
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=403, msg="Don't attack me")
    knowledge_base_name = urllib.parse.unquote(knowledge_base_name)

    kb = KBServiceFactory.get_service_by_name(knowledge_base_name)

    if kb is None:
        return BaseResponse(code=404, msg=f"未找到知识库 {knowledge_base_name}")

    try:
        # 异步删除向量库和知识库，不去管后续是否成功失败，避免知识库太大删除错误
        thread = threading.Thread(target=clear_kb, args=(kb,knowledge_base_name))
        thread.start()
        thread.join(5)
        return BaseResponse(code=200, msg=f"成功删除知识库 {knowledge_base_name}")

    except Exception as e:
        msg = f"删除知识库时出现意外： {e}"
        logger.error(f'{e.__class__.__name__}: {msg}',
                     exc_info=e if log_verbose else None)
        return BaseResponse(code=500, msg=msg)

    # return BaseResponse(code=500, msg=f"删除知识库失败 {knowledge_base_name}")



def clear_kb(kb, knowledge_base_name):
    try:
        logger.info(f"start clear_kb:{knowledge_base_name}")
        kb.clear_vs()
        kb.drop_kb()
        # 版本记录与版本区 blob 同步清理：残留会让同名重建库的版本号虚高
        try:
            from server.knowledge_base.file_version import remove_kb_history
            n = remove_kb_history(knowledge_base_name)
            logger.info(f"clear_kb 版本记录清理: {knowledge_base_name} 删除 {n} 行")
        except Exception as fv_err:
            logger.warning(f"[file-version] 清理版本记录失败（不影响删库）: {fv_err}")
        logger.info(f"end clear_kb:{knowledge_base_name}")
    except Exception as e:
        logger.error(str(e))
