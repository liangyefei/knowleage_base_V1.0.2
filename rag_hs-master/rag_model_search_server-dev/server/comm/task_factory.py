#!/usr/bin/env python3
# @Time : 2024/3/4 15:24:45
# @Author : Hetao
# @File : [Code] --> task_factory.py
# @Software: PyCharm
# @Function: Kafka 任务的分配工厂类
from abc import ABCMeta, abstractmethod

from configs import CHUNK_SIZE, OVERLAP_SIZE, ZH_TITLE_ENHANCE, logger
from server.comm.callback_utils import safe_json_dumps, safe_str
from server.comm.sfs_path import resolve_sfs_path
from server.knowledge_base.kb_api import create_kb, delete_kb
from server.knowledge_base.kb_doc_api import archive_docs, delete_docs, upload_docs_use_path, UploadDocsUsePathRequest
from server.utils import BaseResponse


# 抽象产品角色：所有任务类的基类
class RunTask(metaclass=ABCMeta):

    @abstractmethod
    def request(self, envelope):
        pass

    def run(self, envelope):
        try:
            res = self.request(envelope)
            response = {'code': res.code, 'msg': res.msg, 'data': res.data}
        except Exception as e:
            response = {'code': 500, 'msg': str(e), 'data': None}
            logger.error(
                f"任务执行异常: eventType={envelope.get('eventType')}, "
                f"kbId={envelope.get('kbId')}, error={safe_str(e)} | "
                f"envelope={safe_json_dumps(envelope)}",
                exc_info=True,
            )
        logger.debug(f"task response: {safe_json_dumps(response)}")
        return response


class CreateKnowledgeTask(RunTask):
    """创建知识库"""

    @staticmethod
    def request(envelope):
        # vector_store_type / embed_model 在 create_kb 内部已强制为默认值
        res = create_kb(knowledge_base_name=envelope["kbId"])
        # 幂等创建：知识库在模型端已存在（404 "已存在同名知识库"）时视为创建成功，
        # 避免 rebalance 重投递触发 PROCESSING_FAILED 回调
        if res.code == 404 and "已存在" in (res.msg or ""):
            logger.info(
                f"知识库已存在，视为创建成功（幂等）: kbId={envelope['kbId']}"
            )
            res = BaseResponse(code=200, msg=f"知识库已存在，视为创建成功: {envelope['kbId']}", data=None)
        # 约定 kbId == dual-mcp 项目名：建库成功后后台触发代码图建索引
        if res.code == 200:
            try:
                from server.comm.dual_mcp_client import trigger_index_async
                trigger_index_async(envelope["kbId"])
            except Exception as trigger_err:
                logger.warning(f"trigger_index_async 失败，不影响 create_kb 成功: {safe_str(trigger_err)}")
        return res


class DeleteKnowledgeTask(RunTask):
    """删除知识库"""

    @staticmethod
    def request(envelope):
        res = delete_kb(knowledge_base_name=envelope["kbId"])
        # 幂等删除：知识库在模型端不存在（404）时视为已删除成功，避免向后端回投 PROCESSING_FAILED
        if res.code == 404:
            return BaseResponse(code=200, msg=f"知识库不存在，视为已删除: {envelope['kbId']}", data=None)
        return res


class _UploadDocumentTask(RunTask):
    """上传文档到知识库（向量化入库）的公共实现"""

    @staticmethod
    def request(envelope):
        payload = envelope.get("payload", {})
        file_path = payload.get("filePath")
        # 多版本管理：从 payload 读取后端约定的版本标签与文件标签，透传给入库链路
        # （upload_docs_use_path → record_current 写 history 表 → kb.update_doc 写
        # Milvus metadata 嵌套层 file_version_label → _store_docs_to_mempalace 写 dual-mcp）
        file_version_label = payload.get("file_version_label") or ""
        tags = payload.get("tags") or []
        if not file_path:
            return BaseResponse(code=200, msg="filePath 缺失，跳过", data=None)
        abs_path = resolve_sfs_path(file_path)

        # 文件不存在时的处理（区分两种情况）：
        # 1. 文件在 .trash/ 下：删除与向量化事件竞态，平台已把文件移入回收站，
        #    此时文件本就不应入库，按 code=200（PROCESSING_COMPLETED）返回是幂等正确的。
        # 2. 文件不在磁盘也不在回收站：可能是 SFS 同步未到位、路径编码错误或文件从未上传。
        #    按 code=500（PROCESSING_FAILED）如实上报，让后端标记文档失败，
        #    避免"前端显示向量化成功但 get_doc_by_filename 查不到文件"的假成功。
        #    （此前一律返回 200 是为规避后端 FAILED 重投的重试风暴，但会掩盖真实失败，现已改为如实上报。）
        import os

        from configs import SFS_MOUNT_PATH
        if not os.path.isfile(abs_path):
            rel = os.path.relpath(abs_path, SFS_MOUNT_PATH)
            trash_path = os.path.join(SFS_MOUNT_PATH, ".trash", rel)
            if os.path.isfile(trash_path):
                logger.info(f"文件已删除（回收站中），跳过向量化: {abs_path}")
                return BaseResponse(code=200, msg="文件已删除，跳过向量化", data=None)
            logger.warning(f"文件不存在，向量化失败: {abs_path}")
            return BaseResponse(code=500, msg=f"文件不存在，向量化失败: {abs_path}", data=None)

        res = upload_docs_use_path(
            request=UploadDocsUsePathRequest(
                files=[abs_path],
                knowledge_base_name=envelope["kbId"],
                override=True,
                to_vector_store=True,
                chunk_size=CHUNK_SIZE,
                chunk_overlap=OVERLAP_SIZE,
                zh_title_enhance=ZH_TITLE_ENHANCE,
                not_refresh_vs_cache=False,
                file_version_label=file_version_label,
                tags=tags,
            ),
        )
        # 上传的是代码文件且向量化成功 → 后台触发 dual-mcp 重索引保持代码图新鲜
        if res.code == 200:
            try:
                from server.comm.dual_mcp_client import is_code_file, trigger_reindex_async
                if is_code_file(abs_path):
                    trigger_reindex_async(envelope["kbId"])
            except Exception as trigger_err:
                logger.warning(f"trigger_reindex_async 失败，不影响 upload 成功: {safe_str(trigger_err)}")
        return res


class PublishDocumentTask(_UploadDocumentTask):
    """发布文档到知识库（向量化入库）"""


class RetryProcessDocumentTask(_UploadDocumentTask):
    """重试文档处理（覆盖重建向量）"""


class DeleteDocumentTask(RunTask):
    """删除知识库内指定文件"""

    @staticmethod
    def request(envelope):
        payload = envelope.get("payload", {})
        file_path = payload.get("filePath")
        if file_path:
            abs_path = resolve_sfs_path(file_path)
            res = delete_docs(
                knowledge_base_name=envelope["kbId"],
                file_names=[abs_path],
                delete_content=False,
                not_refresh_vs_cache=False,
            )
            return res
        # 后端尚未补充 filePath 时按成功返回，避免阻塞消费链路
        logger.warning(
            f"DOCUMENT_DELETED missing filePath in payload, no-op. "
            f"kbId={envelope.get('kbId')}, documentId={envelope.get('documentId')}"
        )
        return BaseResponse(code=200, msg="DOCUMENT_DELETED missing filePath in payload, no-op", data=None)


class ArchiveDocumentTask(RunTask):
    """归档文档（只删除 Milvus 向量，保留 SQLite 和磁盘文件）"""

    @staticmethod
    def request(envelope):
        payload = envelope.get("payload", {})
        file_path = payload.get("filePath")
        if not file_path:
            logger.warning(
                f"DOCUMENT_ARCHIVED missing filePath in payload, no-op. "
                f"kbId={envelope.get('kbId')}, documentId={envelope.get('documentId')}"
            )
            return BaseResponse(code=200, msg="DOCUMENT_ARCHIVED missing filePath in payload, no-op", data=None)

        abs_path = resolve_sfs_path(file_path)
        res = archive_docs(
            knowledge_base_name=envelope["kbId"],
            file_names=[abs_path],
            not_refresh_vs_cache=False,
        )
        return res


class NoopTask(RunTask):
    """空操作任务（KB_UPDATED 等模型端无对应动作）"""

    @staticmethod
    def request(envelope):
        logger.info(f"NoopTask for eventType={envelope.get('eventType')}, kbId={envelope.get('kbId')}")
        return BaseResponse(code=200, msg="no-op", data=None)


# 工厂类角色
class KafkaTaskFactory:

    @staticmethod
    def create_task(envelope):
        """
        工厂方法：按 envelope["eventType"] 单字段分发任务
        Args:
            envelope (dict): Kafka 消息信封
        Returns: RunTask 子类实例
        """
        event_type = envelope.get("eventType")
        if event_type == "KB_CREATED":
            return CreateKnowledgeTask()
        if event_type == "KB_UPDATED":
            return NoopTask()
        if event_type == "KB_DELETED":
            return DeleteKnowledgeTask()
        if event_type == "DOCUMENT_PUBLISHED":
            return PublishDocumentTask()
        if event_type == "DOCUMENT_ARCHIVED":
            return ArchiveDocumentTask()
        if event_type == "DOCUMENT_DELETED":
            return DeleteDocumentTask()
        if event_type == "DOCUMENT_RETRY_PROCESS":
            return RetryProcessDocumentTask()
        raise TypeError(f"No such task for eventType: {event_type}")
