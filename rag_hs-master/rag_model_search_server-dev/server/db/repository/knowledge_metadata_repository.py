
from sqlalchemy import func

from server.db.models.knowledge_metadata_model import SummaryChunkModel
from server.db.session import with_session


def _ci_eq(column, value: str):
    """大小写不敏感的精确匹配（替代 ilike，避免 _ / % 通配符误匹配近名库）。

    与 knowledge_file_repository._ci_eq 同实现，此处独立定义避免跨文件耦合。
    kb_name 含 _ 时 ilike 会误匹配近名库（如 "my_kb" 匹配 "myxkb"），
    delete_summary_from_db 甚至会删错库 summary。
    """
    return func.lower(column) == func.lower(value)


@with_session
def list_summary_from_db(session,
                         kb_name: str,
                         metadata: dict = None,
                         ) -> list[dict]:
    '''
    列出某知识库chunk summary。
    返回形式：[{"id": str, "summary_context": str, "doc_ids": str}, ...]
    '''
    if metadata is None:
        metadata = {}
    docs = session.query(SummaryChunkModel).filter(_ci_eq(SummaryChunkModel.kb_name, kb_name))

    for k, v in metadata.items():
        docs = docs.filter(SummaryChunkModel.meta_data[k].as_string() == str(v))

    return [{"id": x.id,
             "summary_context": x.summary_context,
             "summary_id": x.summary_id,
             "doc_ids": x.doc_ids,
             "metadata": x.metadata} for x in docs.all()]


@with_session
def delete_summary_from_db(session,
                           kb_name: str
                           ) -> list[dict]:
    '''
    删除知识库chunk summary，并返回被删除的Dchunk summary。
    返回形式：[{"id": str, "summary_context": str, "doc_ids": str}, ...]
    '''
    docs = list_summary_from_db(kb_name=kb_name)
    query = session.query(SummaryChunkModel).filter(_ci_eq(SummaryChunkModel.kb_name, kb_name))
    query.delete(synchronize_session=False)
    session.commit()
    return docs


@with_session
def add_summary_to_db(session,
                      kb_name: str,
                      summary_infos: list[dict]):
    '''
    将总结信息添加到数据库。
    summary_infos形式：[{"summary_context": str, "doc_ids": str}, ...]
    '''
    for summary in summary_infos:
        obj = SummaryChunkModel(
            kb_name=kb_name,
            summary_context=summary["summary_context"],
            summary_id=summary["summary_id"],
            doc_ids=summary["doc_ids"],
            meta_data=summary["metadata"],
        )
        session.add(obj)

    session.commit()
    return True


@with_session
def count_summary_from_db(session, kb_name: str) -> int:
    return session.query(SummaryChunkModel).filter(_ci_eq(SummaryChunkModel.kb_name, kb_name)).count()
