import os
import shutil

from elasticsearch import BadRequestError, Elasticsearch
from langchain.schema import Document
from langchain.vectorstores.elasticsearch import ElasticsearchStore

from configs import EMBEDDING_DEVICE, KB_ROOT_PATH, kbs_config, logger
from server.knowledge_base.kb_service.base import KBService, SupportedVSType
from server.knowledge_base.utils import KnowledgeFile
from server.utils import load_local_embeddings


class ESKBService(KBService):

    @staticmethod
    def get_kb_path(knowledge_base_name: str):
        return os.path.join(KB_ROOT_PATH, knowledge_base_name)

    @staticmethod
    def get_vs_path(knowledge_base_name: str):
        return os.path.join(ESKBService.get_kb_path(knowledge_base_name), "vector_store")

    @staticmethod
    def vs_type() -> str:
        return SupportedVSType.ES

    def do_init(self):
        _DENSE_VECTOR_KEY = 'dense_vector'
        self.kb_path = self.get_kb_path(self.kb_name)
        self.index_name = os.path.split(self.kb_path)[-1]
        self.IP = kbs_config[self.vs_type()]['host']
        self.PORT = kbs_config[self.vs_type()]['port']
        self.user = kbs_config[self.vs_type()].get("user",'')
        self.password = kbs_config[self.vs_type()].get("password",'')
        self.dims_length = kbs_config[self.vs_type()].get("dims_length",None)
        self.embeddings_model = load_local_embeddings(self.embed_model, EMBEDDING_DEVICE)
        try:
            # ES python客户端连接（仅连接）
            if self.user != "" and self.password != "":
                self.es_client_python =  Elasticsearch(f"http://{self.IP}:{self.PORT}",
                basic_auth=(self.user,self.password))
            else:
                logger.warning("ES未配置用户名和密码")
                self.es_client_python = Elasticsearch(f"http://{self.IP}:{self.PORT}")
        except ConnectionError as e:
            logger.error("连接到 Elasticsearch 失败！")
            raise ConnectionError from e
        except Exception as e:
            logger.error(f"Error 发生 : {e}")
            raise e
        try:
            # 首先尝试通过es_client_python创建
            mappings = {
                "properties": {
                    _DENSE_VECTOR_KEY: {
                        "type": _DENSE_VECTOR_KEY,
                        "dims": self.dims_length,
                        "index": True
                    }
                }
            }
            self.es_client_python.indices.create(index=self.index_name, mappings=mappings)
        except BadRequestError as e:
            logger.error("创建索引失败,重新")
            logger.error(e)

        try:
            # langchain ES 连接、创建索引
            if self.user != "" and self.password != "":
                self.db_init = ElasticsearchStore(
                es_url=f"http://{self.IP}:{self.PORT}",
                index_name=self.index_name,
                query_field="context",
                vector_query_field=_DENSE_VECTOR_KEY,
                embedding=self.embeddings_model,
                es_user=self.user,
                es_password=self.password
            )
            else:
                logger.warning("ES未配置用户名和密码")
                self.db_init = ElasticsearchStore(
                    es_url=f"http://{self.IP}:{self.PORT}",
                    index_name=self.index_name,
                    query_field="context",
                    vector_query_field=_DENSE_VECTOR_KEY,
                    embedding=self.embeddings_model,
                )
        except ConnectionError as e:
            logger.error("### 初始化 Elasticsearch 失败！")
            raise ConnectionError from e
        except Exception as e:
            logger.error(f"Error 发生 : {e}")
            raise e
        try:
            # 尝试通过db_init创建索引
            self.db_init._create_index_if_not_exists(  # pylint: disable=protected-access
                                                     index_name=self.index_name,
                                                     dims_length=self.dims_length
                                                     )
        except Exception as e:
            logger.error("创建索引失败...")
            logger.error(e)
            # raise e

    def do_create_kb(self):
        if os.path.exists(self.doc_path):
            if not os.path.exists(os.path.join(self.kb_path, "vector_store")):
                os.makedirs(os.path.join(self.kb_path, "vector_store"))
            else:
                logger.warning("directory `vector_store` already exists.")

    def do_search(self, query:str, top_k: int, score_threshold: float,
                  version: int | None = None):
        # 文本相似性检索
        docs = self.db_init.similarity_search_with_score(query=query,
                                         k=top_k)
        return docs

    def get_doc_by_ids(self, ids: list[str]) -> list[Document]:
        results = []
        for doc_id in ids:
            try:
                response = self.es_client_python.get(index=self.index_name, id=doc_id)
                source = response["_source"]
                # Assuming your document has "text" and "metadata" fields
                text = source.get("context", "")
                metadata = source.get("metadata", {})
                results.append(Document(page_content=text, metadata=metadata))
            except Exception as e:
                logger.error(f"Error retrieving document from Elasticsearch! {e}")
        return results

    def del_doc_by_ids(self, ids: list[str]) -> bool:
        for doc_id in ids:
            try:
                self.es_client_python.delete(index=self.index_name,
                                            id=doc_id,
                                            refresh=True)
            except Exception as e:
                logger.error(f"ES Docs Delete Error! {e}")

    def do_delete_doc(self, kb_file, **kwargs):
        if self.es_client_python.indices.exists(index=self.index_name):
            # 从向量数据库中删除索引(文档名称是Keyword)
            query = {
                "query": {
                    "term": {
                        "metadata.source.keyword": kb_file.filepath
                    }
                }
            }
            # 注意设置size，默认返回10个。
            search_results = self.es_client_python.search(body=query, size=50)
            delete_list = [hit["_id"] for hit in search_results['hits']['hits']]
            if not delete_list:
                return
            for doc_id in delete_list:
                try:
                    self.es_client_python.delete(index=self.index_name,
                                                 id=doc_id,
                                                 refresh=True)
                except Exception as e:
                    logger.error(f"ES Docs Delete Error! {e}")

    def do_add_doc(self, docs: list[Document], **kwargs):
        '''向知识库添加文件'''
        _HITS_KEY = 'hits'
        logger.info(f"es_kb_service.do_add_doc 输入 docs 参数长度: {len(docs)}")
        self._load_es(docs=docs, embed_model=self.embeddings_model)
        # 获取 id 和 source , 格式：[{"id": str, "metadata": dict}, ...]
        logger.info("写入数据成功.")
        logger.info("{}", "*"*100)

        if self.es_client_python.indices.exists(index=self.index_name):
            file_path = docs[0].metadata.get("source")
            query = {
                "query": {
                    "term": {
                        "metadata.source.keyword": file_path
                    }
                }
            }
            # 注意设置size，默认返回10个。
            search_results = self.es_client_python.search(body=query, size=50)
            if len(search_results[_HITS_KEY][_HITS_KEY]) == 0:
                raise ValueError("召回元素个数为0")
        info_docs = [{"id":hit["_id"], "metadata": hit["_source"]["metadata"]} for hit in search_results[_HITS_KEY][_HITS_KEY]]  # pylint: disable=line-too-long
        return info_docs

    def do_clear_vs(self):
        """从知识库删除全部向量"""
        if self.es_client_python.indices.exists(index=self.kb_name):
            self.es_client_python.indices.delete(index=self.kb_name)

    def do_drop_kb(self):
        """删除知识库"""
        # self.kb_file: 知识库路径
        if os.path.exists(self.kb_path):
            shutil.rmtree(self.kb_path)

    def _load_es(self, docs, embed_model):
        # 将docs写入到ES中
        try:
            # 连接 + 同时写入文档
            if self.user != "" and self.password != "":
                self.db = ElasticsearchStore.from_documents(
                        documents=docs,
                        embedding=embed_model,
                        es_url= f"http://{self.IP}:{self.PORT}",
                        index_name=self.index_name,
                        distance_strategy="COSINE",
                        query_field="context",
                        vector_query_field="dense_vector",
                        verify_certs=False,
                        es_user=self.user,
                        es_password=self.password
                    )
            else:
                self.db = ElasticsearchStore.from_documents(
                        documents=docs,
                        embedding=embed_model,
                        es_url= f"http://{self.IP}:{self.PORT}",
                        index_name=self.index_name,
                        distance_strategy="COSINE",
                        query_field="context",
                        vector_query_field="dense_vector",
                        verify_certs=False)
        except ConnectionError as ce:
            logger.error(f"连接到 Elasticsearch 失败！{ce}")
        except Exception as e:
            logger.error(f"Error 发生 : {e}")


if __name__ == '__main__':
    esKBService = ESKBService("test")
    esKBService.add_doc(KnowledgeFile(filename="README.md", knowledge_base_name="test"))
    logger.info("{}", esKBService.search_docs("如何启动api服务"))

