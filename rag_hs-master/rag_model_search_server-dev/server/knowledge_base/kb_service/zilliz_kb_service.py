
from langchain.schema import Document
from langchain.vectorstores import Zilliz

from configs import kbs_config
from server.knowledge_base.kb_service.base import (
    EmbeddingsFunAdapter,
    KBService,
    SupportedVSType,
    score_threshold_process,
)
from server.knowledge_base.milvus_filter import _escape_milvus_str
from server.knowledge_base.utils import KnowledgeFile


class ZillizKBService(KBService):
    zilliz: Zilliz

    @staticmethod
    def get_collection(zilliz_name):
        from pymilvus import Collection
        return Collection(zilliz_name)

    @staticmethod
    def search(zilliz_name, content, limit=3):
        search_params = {
            "metric_type": "IP",
            "params": {},
        }
        c = ZillizKBService.get_collection(zilliz_name)
        return c.search(content, "embeddings", search_params, limit=limit, output_fields=["content"])

    @staticmethod
    def do_create_kb():
        pass

    @staticmethod
    def vs_type() -> str:
        return SupportedVSType.ZILLIZ

    def get_doc_by_ids(self, ids: list[str]) -> list[Document]:
        result = []
        if self.zilliz.col:
            data_list = self.zilliz.col.query(expr=f'pk in {ids}', output_fields=["*"])
            for data in data_list:
                text = data.pop("text")
                result.append(Document(page_content=text, metadata=data))
        return result

    def del_doc_by_ids(self, ids: list[str]) -> bool:
        self.zilliz.col.delete(expr=f'pk in {ids}')

    def do_init(self):
        self._load_zilliz()

    def do_drop_kb(self):
        if self.zilliz.col:
            self.zilliz.col.release()
            self.zilliz.col.drop()

    def do_search(self, query: str, top_k: int, score_threshold: float,
                  version: int | None = None):
        self._load_zilliz()
        embed_func = EmbeddingsFunAdapter(self.embed_model)
        embeddings = embed_func.embed_query(query)
        docs = self.zilliz.similarity_search_with_score_by_vector(embeddings, top_k)
        return score_threshold_process(score_threshold, top_k, docs)

    def do_add_doc(self, docs: list[Document], **kwargs) -> list[dict]:
        for doc in docs:
            for k, v in doc.metadata.items():
                doc.metadata[k] = str(v)
            for field in self.zilliz.fields:
                doc.metadata.setdefault(field, "")
            doc.metadata.pop(self.zilliz._text_field, None)  # pylint: disable=protected-access
            doc.metadata.pop(self.zilliz._vector_field, None)  # pylint: disable=protected-access

        ids = self.zilliz.add_documents(docs)
        doc_infos = [{"id": id, "metadata": doc.metadata} for id, doc in zip(ids, docs, strict=False)]
        return doc_infos

    def do_delete_doc(self, kb_file: KnowledgeFile, **kwargs):
        if self.zilliz.col:
            # Zilliz 用 Milvus 兼容 filter 语法，filepath 需转义反斜杠和双引号
            # 含双引号会破坏 expr 或注入（之前只转义反斜杠）
            filepath = _escape_milvus_str(kb_file.filepath)
            delete_list = []
            for item in self.zilliz.col.query(expr=f'source == "{filepath}"', output_fields=["pk"]):
                delete_list.append(item.get("pk"))
            self.zilliz.col.delete(expr=f'pk in {delete_list}')

    def do_clear_vs(self):
        if self.zilliz.col:
            self.do_drop_kb()
            self.do_init()

    def _load_zilliz(self):
        zilliz_args = kbs_config.get("zilliz")
        self.zilliz = Zilliz(embedding_function=EmbeddingsFunAdapter(self.embed_model),
                             collection_name=self.kb_name, connection_args=zilliz_args)


if __name__ == '__main__':
    from server.db.base import Base, engine

    Base.metadata.create_all(bind=engine)
    zillizService = ZillizKBService("test")
