import uuid
from typing import Any

import chromadb
from chromadb.api.types import GetResult, QueryResult
from langchain.docstore.document import Document

from configs import SCORE_THRESHOLD
from server.knowledge_base.kb_service.base import EmbeddingsFunAdapter, KBService, SupportedVSType
from server.knowledge_base.utils import KnowledgeFile, get_kb_path, get_vs_path


def _get_result_to_documents(get_result: GetResult) -> list[Document]:
    _DOCUMENTS_KEY = 'documents'
    if not get_result[_DOCUMENTS_KEY]:
        return []

    _metadatas = get_result['metadatas'] if get_result['metadatas'] else [{}] * len(get_result[_DOCUMENTS_KEY])

    document_list = []
    for page_content, metadata in zip(get_result[_DOCUMENTS_KEY], _metadatas, strict=False):
        document_list.append(Document(**{'page_content': page_content, 'metadata': metadata}))

    return document_list


def _results_to_docs_and_scores(results: Any) -> list[tuple[Document, float]]:
    """
    from langchain_community.vectorstores.chroma import Chroma
    """
    _result_list = []
    for result in zip(
        results["documents"][0],
        results["metadatas"][0],
        results["distances"][0], strict=False,
    ):
        # TODO: Chroma can do batch querying,
        _result_list.append((Document(page_content=result[0], metadata=result[1] or {}), result[2]))
    return _result_list


class ChromaKBService(KBService):
    vs_path: str
    kb_path: str

    client = None
    collection = None

    @staticmethod
    def vs_type() -> str:
        return SupportedVSType.CHROMADB

    def get_vs_path(self) -> str:
        return get_vs_path(self.kb_name, self.embed_model)

    def get_kb_path(self) -> str:
        return get_kb_path(self.kb_name)

    def do_init(self) -> None:
        self.kb_path = self.get_kb_path()
        self.vs_path = self.get_vs_path()
        self.client = chromadb.PersistentClient(path=self.vs_path)
        self.collection = self.client.get_or_create_collection(self.kb_name)

    def do_create_kb(self) -> None:
        # In ChromaDB, creating a KB is equivalent to creating a collection
        self.collection = self.client.get_or_create_collection(self.kb_name)

    def do_drop_kb(self):
        # Dropping a KB is equivalent to deleting a collection in ChromaDB
        try:
            self.client.delete_collection(self.kb_name)
        except ValueError as e:
            if not str(e) == f"Collection {self.kb_name} does not exist.":
                raise e

    def do_search(self, query: str, top_k: int, score_threshold: float = SCORE_THRESHOLD,
                  version: int | None = None) -> list[
        tuple[Document, float]]:
        embed_func = EmbeddingsFunAdapter(self.embed_model)
        embeddings = embed_func.embed_query(query)
        query_result: QueryResult = self.collection.query(query_embeddings=embeddings, n_results=top_k)
        return _results_to_docs_and_scores(query_result)

    def do_add_doc(self, docs: list[Document], **kwargs) -> list[dict]:
        doc_infos = []
        data = self._docs_to_embeddings(docs)
        ids = [str(uuid.uuid1()) for _ in range(len(data["texts"]))]
        for _id, text, embedding, metadata in zip(ids, data["texts"], data["embeddings"], data["metadatas"], strict=False):  # pylint: disable=line-too-long
            self.collection.add(ids=_id, embeddings=embedding, metadatas=metadata, documents=text)
            doc_infos.append({"id": _id, "metadata": metadata})
        return doc_infos

    def get_doc_by_ids(self, ids: list[str]) -> list[Document]:
        get_result: GetResult = self.collection.get(ids=ids)
        return _get_result_to_documents(get_result)

    def del_doc_by_ids(self, ids: list[str]) -> bool:
        self.collection.delete(ids=ids)
        return True

    def do_clear_vs(self):
        # Clearing the vector store might be equivalent to dropping and recreating the collection
        self.do_drop_kb()

    def do_delete_doc(self, kb_file: KnowledgeFile, **kwargs):
        return self.collection.delete(where={"source": kb_file.filepath})
