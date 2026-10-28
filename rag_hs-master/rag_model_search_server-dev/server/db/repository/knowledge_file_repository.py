
from sqlalchemy import func

from configs import logger
from server.db.models.file_version_history_model import FileVersionHistoryModel
from server.db.models.knowledge_base_model import KnowledgeBaseModel
from server.db.models.knowledge_file_model import FileDocModel, KnowledgeFileModel
from server.db.session import with_session
from server.knowledge_base.utils import KnowledgeFile


def _ci_eq(column, value: str):
    """大小写不敏感的精确匹配（替代 ilike，避免 _ / % 通配符误匹配近名文件）。

    P1-5：SQL ILIKE 中 _ 匹配任意单字符、% 匹配任意序列，文件名含 _ 会误匹配
    近名文件（如 "rev-1_0" 匹配 "rev-1X0"）。改用 func.lower == func.lower
    做精确匹配（大小写不敏感，无通配符语义）。与 file_version._ci_eq 实现一致，
    此处独立定义避免 db.repository → knowledge_base 跨层依赖。
    """
    return func.lower(column) == func.lower(value)


@with_session
def list_file_num_docs_id_by_kb_name_and_file_name(session,
                                                   kb_name: str,
                                                   file_name: str,
                                                   ) -> list[int]:
    '''
    列出某知识库某文件对应的所有Document的id。
    返回形式：[str, ...]
    '''
    doc_ids = session.query(FileDocModel.doc_id).filter_by(kb_name=kb_name, file_name=file_name).all()
    return [int(_id[0]) for _id in doc_ids]


@with_session
def list_docs_from_db(session,
                      kb_name: str,
                      file_name: str = None,
                      metadata: dict = None,
                      ) -> list[dict]:
    '''
    列出某知识库某文件对应的所有Document。
    返回形式：[{"id": str, "metadata": dict}, ...]
    '''
    if metadata is None:
        metadata = {}
    docs = session.query(FileDocModel).filter(_ci_eq(FileDocModel.kb_name, kb_name))
    if file_name:
        docs = docs.filter(_ci_eq(FileDocModel.file_name, file_name))
    for k, v in metadata.items():
        docs = docs.filter(FileDocModel.meta_data[k].as_string() == str(v))

    return [{"id": x.doc_id, "metadata": x.metadata} for x in docs.all()]


@with_session
def delete_docs_from_db(session,
                        kb_name: str,
                        file_name: str = None,
                        ) -> list[dict]:
    '''
    删除某知识库某文件对应的所有Document，并返回被删除的Document。
    返回形式：[{"id": str, "metadata": dict}, ...]
    '''
    docs = list_docs_from_db(kb_name=kb_name, file_name=file_name)
    query = session.query(FileDocModel).filter(_ci_eq(FileDocModel.kb_name, kb_name))
    if file_name:
        query = query.filter(_ci_eq(FileDocModel.file_name, file_name))
    query.delete(synchronize_session=False)
    session.commit()
    return docs


@with_session
def add_docs_to_db(session,
                   kb_name: str,
                   file_name: str,
                   doc_infos: list[dict]):
    '''
    将某知识库某文件对应的所有Document信息添加到数据库。
    doc_infos形式：[{"id": str, "metadata": dict}, ...]
    '''
    # ! 这里会出现doc_infos为None的情况，需要进一步排查
    if doc_infos is None:
        logger.warning("add_docs_to_db: doc_infos 参数为 None")
        return False
    for d in doc_infos:
        obj = FileDocModel(
            kb_name=kb_name,
            file_name=file_name,
            doc_id=d["id"],
            meta_data=d["metadata"],
        )
        session.add(obj)
    return True


@with_session
def count_files_from_db(session, kb_name: str) -> int:
    return session.query(KnowledgeFileModel).filter(_ci_eq(KnowledgeFileModel.kb_name, kb_name)).count()


@with_session
def list_files_from_db(session, kb_name):
    files = session.query(KnowledgeFileModel).filter(_ci_eq(KnowledgeFileModel.kb_name, kb_name)).all()
    docs = [f.file_name for f in files]
    return docs


@with_session
def add_file_to_db(session,
                   kb_file: KnowledgeFile,
                   docs_count: int = 0,
                   custom_docs: bool = False,
                   doc_infos: list[dict] = None,  # 形式：[{"id": str, "metadata": dict}, ...]
                   ):
    if doc_infos is None:
        doc_infos = []
    kb = session.query(KnowledgeBaseModel).filter_by(kb_name=kb_file.kb_name).first()
    if kb:
        # 如果已经存在该文件，则更新文件信息与版本号
        existing_file: KnowledgeFileModel = (session.query(KnowledgeFileModel)
                                             .filter(_ci_eq(KnowledgeFileModel.kb_name, kb_file.kb_name),
                                                     _ci_eq(KnowledgeFileModel.file_name, kb_file.filename))
                                             .first())
        mtime = kb_file.get_mtime()
        size = kb_file.get_size()

        if existing_file:
            existing_file.file_mtime = mtime
            existing_file.file_size = size
            existing_file.docs_count = docs_count
            existing_file.custom_docs = custom_docs
            # P2-1：file_version（INT）是旧版本方案遗留字段。新方案用
            # file_version_label（String，存 FileVersionHistoryModel）。update_doc
            # 是 delete+add 路径，记录被删重建后恒为 default，此处 +=1 仅在
            # 非 update_doc 路径（如直接 add_file_to_db）触发，实际很少生效。
            # 保留是为了向后兼容，不影响版本判断逻辑（版本以 history 表为准）。
            existing_file.file_version += 1
        # 否则，添加新文件
        else:
            new_file = KnowledgeFileModel(
                file_name=kb_file.filename,
                file_ext=kb_file.ext,
                kb_name=kb_file.kb_name,
                document_loader_name=kb_file.document_loader_name,
                text_splitter_name=kb_file.text_splitter_name or "SpacyTextSplitter",
                file_mtime=mtime,
                file_size=size,
                docs_count=docs_count,
                custom_docs=custom_docs,
            )
            kb.file_count += 1
            session.add(new_file)
        add_docs_to_db(kb_name=kb_file.kb_name, file_name=kb_file.filename, doc_infos=doc_infos)
    return True


@with_session
def delete_file_from_db(session, kb_file: KnowledgeFile):
    existing_file = (session.query(KnowledgeFileModel)
                     .filter(_ci_eq(KnowledgeFileModel.file_name, kb_file.filename),
                             _ci_eq(KnowledgeFileModel.kb_name, kb_file.kb_name))
                     .first())
    if existing_file:
        session.delete(existing_file)
        delete_docs_from_db(kb_name=kb_file.kb_name, file_name=kb_file.filename)
        session.commit()

        kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_file.kb_name)).first()
        if kb:
            kb.file_count -= 1
            session.commit()
    return True


@with_session
def delete_files_from_db(session, knowledge_base_name: str):
    session.query(KnowledgeFileModel).filter(_ci_eq(KnowledgeFileModel.kb_name, knowledge_base_name)).delete(
        synchronize_session=False)
    session.query(FileDocModel).filter(_ci_eq(FileDocModel.kb_name, knowledge_base_name)).delete(
        synchronize_session=False)
    kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, knowledge_base_name)).first()
    if kb:
        kb.file_count = 0

    session.commit()
    return True


@with_session
def file_exists_in_db(session, kb_file: KnowledgeFile):
    existing_file = (session.query(KnowledgeFileModel)
                     .filter(_ci_eq(KnowledgeFileModel.file_name, kb_file.filename),
                             _ci_eq(KnowledgeFileModel.kb_name, kb_file.kb_name))
                     .first())
    return True if existing_file else False


@with_session
def get_file_detail(session, kb_name: str, filename: str) -> dict:
    file: KnowledgeFileModel = (session.query(KnowledgeFileModel)
                                .filter(_ci_eq(KnowledgeFileModel.file_name, filename),
                                        _ci_eq(KnowledgeFileModel.kb_name, kb_name))
                                .first())
    if file:
        # P3-1：file_version_label（String）是当前版本标识，从 FileVersionHistoryModel
        # 查最新非 deleted 行的 label。file_version（INT，KnowledgeFileModel 列）是
        # 遗留字段（见 add_file_to_db 注释），不再在 API 响应中返回——前端统一用
        # file_version_label 显示版本。
        version_label = (session.query(FileVersionHistoryModel.file_version_label)
                         .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                                 _ci_eq(FileVersionHistoryModel.file_name, filename),
                                 FileVersionHistoryModel.status != "deleted")
                         .order_by(FileVersionHistoryModel.id.desc())
                         .first())
        return {
            "kb_name": file.kb_name,
            "file_name": file.file_name,
            "file_ext": file.file_ext,
            "document_loader": file.document_loader_name,
            "text_splitter": file.text_splitter_name,
            "create_time": file.create_time,
            "file_mtime": file.file_mtime,
            "file_size": file.file_size,
            "custom_docs": file.custom_docs,
            "docs_count": file.docs_count,
            "file_version_label": version_label[0] if version_label else "",
        }
    return {}
