from sqlalchemy import func

from server.db.models.knowledge_base_model import KnowledgeBaseModel
from server.db.session import with_session


def _ci_eq(column, value: str):
    """大小写不敏感的精确匹配（替代 ilike，避免 _ / % 通配符误匹配近名库）。

    与 knowledge_file_repository._ci_eq 同实现，此处独立定义避免跨文件耦合。
    kb_name 含 _ 时 ilike 会误匹配近名库（如 "my_kb" 匹配 "myxkb"），
    delete_kb_from_db 甚至会删错库。
    """
    return func.lower(column) == func.lower(value)


@with_session
def add_kb_to_db(session, kb_name, kb_info, vs_type, embed_model):
    # 创建知识库实例
    kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_name)).first()
    if not kb:
        kb = KnowledgeBaseModel(kb_name=kb_name, kb_info=kb_info, vs_type=vs_type, embed_model=embed_model)
        session.add(kb)
    else:  # update kb with new vs_type and embed_model
        kb.kb_info = kb_info
        kb.vs_type = vs_type
        kb.embed_model = embed_model
    return True


@with_session
def list_kbs_from_db(session, min_file_count: int = -1):
    kbs = session.query(KnowledgeBaseModel.kb_name).filter(KnowledgeBaseModel.file_count > min_file_count).all()
    kbs = [kb[0] for kb in kbs]
    return kbs


@with_session
def kb_exists(session, kb_name):
    kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_name)).first()
    status = True if kb else False
    return status


@with_session
def load_kb_from_db(session, kb_name):
    kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_name)).first()
    if kb:
        kb_name, vs_type, embed_model = kb.kb_name, kb.vs_type, kb.embed_model
    else:
        kb_name, vs_type, embed_model = None, None, None
    return kb_name, vs_type, embed_model


@with_session
def delete_kb_from_db(session, kb_name):
    kb = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_name)).first()
    if kb:
        session.delete(kb)
    return True


@with_session
def get_kb_detail(session, kb_name: str) -> dict:
    kb: KnowledgeBaseModel = session.query(KnowledgeBaseModel).filter(_ci_eq(KnowledgeBaseModel.kb_name, kb_name)).first()  # pylint: disable=line-too-long
    if kb:
        return {
            "kb_name": kb.kb_name,
            "kb_info": kb.kb_info,
            "vs_type": kb.vs_type,
            "embed_model": kb.embed_model,
            "file_count": kb.file_count,
            "create_time": kb.create_time,
        }
    return {}
