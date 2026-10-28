import os
from abc import ABC

from langchain.docstore.document import Document

from configs import EMBEDDING_MODEL, KB_ROOT_PATH
from server.db.repository.knowledge_metadata_repository import add_summary_to_db, delete_summary_from_db
from server.knowledge_base.kb_cache.milvus_cache import ThreadSafeMilvus, kb_milvus_pool

# 摘要向量库在 Milvus 中的 collection 名后缀
SUMMARY_VECTOR_NAME = "summary"


# TODO 暂不考虑文件更新，需要重新删除相关文档，再重新添加
class KBSummaryService(ABC):
    kb_name: str
    embed_model: str
    vs_path: str
    kb_path: str

    def __init__(self,
                 knowledge_base_name: str,
                 embed_model: str = EMBEDDING_MODEL
                 ):
        self.kb_name = knowledge_base_name
        self.embed_model = embed_model

        self.kb_path = self.get_kb_path()
        self.vs_path = self.get_vs_path()

        if not os.path.exists(self.vs_path):
            os.makedirs(self.vs_path)


    def get_vs_path(self):
        return os.path.join(self.get_kb_path(), "summary_vector_store")

    def get_kb_path(self):
        return os.path.join(KB_ROOT_PATH, self.kb_name)

    def load_vector_store(self) -> ThreadSafeMilvus:
        return kb_milvus_pool.load_vector_store(kb_name=self.kb_name,
                                               vector_name=SUMMARY_VECTOR_NAME,
                                               embed_model=self.embed_model,
                                               create=True)

    def add_kb_summary(self, summary_combine_docs: list[Document]):
        with self.load_vector_store().acquire() as vs:
            ids = vs.add_documents(documents=summary_combine_docs)
            # Milvus 自动持久化，无需 save_local

        summary_infos = [{"summary_context": doc.page_content,
                          "summary_id": id,
                          "doc_ids": doc.metadata.get('doc_ids'),
                          "metadata": doc.metadata} for id, doc in zip(ids, summary_combine_docs, strict=False)]
        status = add_summary_to_db(kb_name=self.kb_name, summary_infos=summary_infos)
        return status

    def create_kb_summary(self):
        """
        创建知识库chunk summary
        :return:
        """

        if not os.path.exists(self.vs_path):
            os.makedirs(self.vs_path)

    def drop_kb_summary(self):
        """
        删除知识库chunk summary
        :param kb_name:
        :return:
        """
        kb_milvus_pool.drop_vector_store(self.kb_name, SUMMARY_VECTOR_NAME)
        delete_summary_from_db(kb_name=self.kb_name)
