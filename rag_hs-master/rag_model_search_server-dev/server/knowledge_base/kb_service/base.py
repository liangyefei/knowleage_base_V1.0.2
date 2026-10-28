import os
import time
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor, as_completed

import numpy as np
from langchain.docstore.document import Document
from langchain.embeddings.base import Embeddings

from configs import EMBEDDING_MODEL, KB_INFO, SCORE_THRESHOLD, VECTOR_SEARCH_TOP_K, kbs_config, logger
from configs.kb_loader import (
    EMBEDDING_BATCH_SIZE,
    EMBEDDING_MAX_RETRIES,
    EMBEDDING_RETRY_BACKOFF,
    VECTORIZE_ROLLBACK_ON_FAILURE,
    VECTORIZE_VERIFY_INSERT_COUNT,
)
from configs.model_loader import EMBEDDING_API_CONFIG
from server.db.repository.knowledge_base_repository import (
    add_kb_to_db,
    delete_kb_from_db,
    get_kb_detail,
    kb_exists,
    list_kbs_from_db,
    load_kb_from_db,
)
from server.db.repository.knowledge_file_repository import (
    add_file_to_db,
    count_files_from_db,
    delete_file_from_db,
    delete_files_from_db,
    file_exists_in_db,
    get_file_detail,
    list_docs_from_db,
    list_files_from_db,
)
from server.embeddings_api import aembed_texts, embed_documents, embed_texts
from server.knowledge_base.model.kb_document_model import DocumentWithVSId
from server.knowledge_base.utils import (
    KnowledgeFile,
    get_doc_path,
    get_kb_path,
    list_files_from_folder,
    list_kbs_from_folder,
)


def normalize(embeddings: list[list[float]]) -> np.ndarray:
    '''
    sklearn.preprocessing.normalize 的替代（使用 L2），避免安装 scipy, scikit-learn
    '''
    # 空输入保护：np.array([]) 是一维数组，axis=1 会越界（AxisError）
    # 注意：embeddings 可能是 list，也可能是 np.ndarray（embed_query 传入的是 ndarray），
    # 对多元素 ndarray 不能用 `not`，否则抛 "truth value of an array ... is ambiguous"
    if isinstance(embeddings, np.ndarray):
        if embeddings.size == 0:
            return np.array([])
    elif not embeddings:
        return np.array([])
    norm = np.linalg.norm(embeddings, axis=1)
    norm = np.reshape(norm, (norm.shape[0], 1))
    norm = np.tile(norm, (1, len(embeddings[0])))
    return np.divide(embeddings, norm)


class VectorizeError(Exception):
    """向量化事务失败异常。

    Attributes:
        stage: 失败阶段 "embedding" | "milvus_insert" | "verify" | "unknown"
        file_name: 失败的文件名
        detail: 错误详情
        partial_pks: 已写入 Milvus 的 PK 列表（用于回滚）
    """
    def __init__(self, stage: str, file_name: str, detail: str, partial_pks: list = None):
        self.stage = stage
        self.file_name = file_name
        self.detail = detail
        self.partial_pks = partial_pks or []
        super().__init__(f"[{stage}] {file_name}: {detail}")


def _resolve_batch_size(embed_model: str) -> int:
    """解析 embedding 批次大小。

    优先用 VECTORIZE_CONFIG.EMBEDDING_BATCH_SIZE；
    为 0 时回退到 EMBEDDING_API_CONFIG per-model batch_size；
    per-model 也缺失时回退默认 10（DashScope text-embedding-v3 硬限制）。
    """
    if EMBEDDING_BATCH_SIZE and EMBEDDING_BATCH_SIZE > 0:
        return EMBEDDING_BATCH_SIZE
    cfg = EMBEDDING_API_CONFIG.get(embed_model, {})
    per_model = cfg.get("batch_size", 10)
    return max(1, per_model)


def _resolve_max_workers(embed_model: str) -> int:
    """解析 embedding 批次并发度（跨批次并发请求数）。

    从 EMBEDDING_API_CONFIG per-model max_workers 取；
    缺失时回退默认 4（保守值，避免触发 DashScope QPS 限流）。
    """
    cfg = EMBEDDING_API_CONFIG.get(embed_model, {})
    return max(1, cfg.get("max_workers", 4))


class SupportedVSType:
    MILVUS = 'milvus'
    DEFAULT = 'default'
    ZILLIZ = 'zilliz'
    PG = 'pg'
    ES = 'es'
    CHROMADB = 'chromadb'


class KBService(ABC):

    def __init__(self,
                 knowledge_base_name: str,
                 embed_model: str = EMBEDDING_MODEL,
                 ):
        self.kb_name = knowledge_base_name
        self.kb_info = KB_INFO.get(knowledge_base_name, f"关于{knowledge_base_name}的知识库")
        self.embed_model = embed_model
        self.kb_path = get_kb_path(self.kb_name)
        self.doc_path = get_doc_path(self.kb_name)
        self.do_init()

    def __repr__(self) -> str:
        return f"{self.kb_name} @ {self.embed_model}"

    @staticmethod
    def save_vector_store():
        """
        保存向量库：milvus 保存到数据库。PGVector暂未支持
        """

    @staticmethod
    def get_doc_by_ids(ids: list[str]) -> list[Document]:
        return []

    @staticmethod
    def del_doc_by_ids(ids: list[str]) -> bool:
        raise NotImplementedError

    @staticmethod
    def list_kbs_type():
        return list(kbs_config.keys())

    @classmethod
    def list_kbs(cls):
        return list_kbs_from_db()

    def create_kb(self):
        """
        创建知识库
        """
        if not os.path.exists(self.doc_path):
            os.makedirs(self.doc_path)
        self.do_create_kb()
        status = add_kb_to_db(self.kb_name, self.kb_info, self.vs_type(), self.embed_model)
        return status

    def clear_vs(self):
        """
        删除向量库中所有内容
        """
        self.do_clear_vs()
        status = delete_files_from_db(self.kb_name)
        return status

    def drop_kb(self):
        """
        删除知识库
        """
        self.do_drop_kb()
        status = delete_kb_from_db(self.kb_name)
        return status

    def add_doc(self, kb_file: KnowledgeFile, docs: list[Document] = None, **kwargs):
        """向知识库添加文件（文件级事务）。

        事务边界：仅向量库（embedding + 向量写入）。
        全成功 → return True + add_file_to_db；
        任一失败 → 抛 VectorizeError + 回滚已写 PK（ROLLBACK_ON_FAILURE=True 时）。
        MemPalace 写入由上层 update_docs_use_path 以 best-effort 方式调用，不影响事务。

        Args:
            kb_file: 知识文件对象
            docs: 自定义 docs（为空则从 kb_file.file2text() 解析）
        """
        if docs is None:
            docs = []
        custom_docs = bool(docs)
        if custom_docs:
            for doc in docs:
                doc.metadata.setdefault("source", kb_file.filename)
        else:
            docs = kb_file.file2text()

        if not docs:
            logger.warning(f"[tx] add_doc: {kb_file.filename} 无可入库 docs")
            return False

        # 过滤 page_content 为空或 "null" 的 doc（防止小图片/VLM 空返回静默入库）
        valid_docs = []
        for d in docs:
            if d.page_content and d.page_content.strip() and d.page_content.strip() != "null":
                valid_docs.append(d)
        if len(valid_docs) != len(docs):
            logger.warning(f"[tx] add_doc: {kb_file.filename} "
                           f"过滤掉 {len(docs) - len(valid_docs)} 个空/'null' 切片")
        if not valid_docs:
            logger.warning(f"[tx] add_doc: {kb_file.filename} 过滤后无可入库 docs")
            return False
        docs = valid_docs

        file_name = kb_file.filename
        inserted_pks = []
        try:
            # ① 分批 embedding（全成功才继续）
            embeddings = self._batch_embed_with_retry(docs, file_name)
            logger.info(f"[tx] {file_name} embedding 成功: {len(docs)} 个向量")

            # ② 事务化写入向量库（全成功才提交 DB）
            inserted_pks = self._transactional_milvus_insert(
                docs, embeddings, file_name, file_version_label=kwargs.get("file_version_label"),
                tags=kwargs.get("tags"))
            logger.info(f"[tx] {file_name} 向量库写入成功: {len(inserted_pks)} 个 PK")

            # ③ 校验 PK 数 == chunk 数
            if VECTORIZE_VERIFY_INSERT_COUNT and len(inserted_pks) != len(docs):
                raise VectorizeError(
                    stage="verify", file_name=file_name,
                    detail=f"PK 数 {len(inserted_pks)} != chunk 数 {len(docs)}",
                    partial_pks=inserted_pks,
                )

            # ④ DB 记录（向量库成功才写）
            # Bug 4 修复：zip 在 PK 数 != doc 数时静默截断 + PK 与 doc 错位
            # （如 ids=[1,None,3] 过滤 None 后 pks=[1,3]，PK3 错位映射 doc1）。
            # 无条件校验长度一致（不受 VECTORIZE_VERIFY_INSERT_COUNT 开关影响，
            # 作为数据正确性兜底），不一致抛错拒绝错位写入 DB。
            if len(inserted_pks) != len(docs):
                raise VectorizeError(
                    stage="verify", file_name=file_name,
                    detail=f"PK 数 {len(inserted_pks)} != chunk 数 {len(docs)}，"
                           f"doc_infos 无法正确映射，拒绝写入 DB",
                    partial_pks=inserted_pks,
                )
            doc_infos = [{"id": pk, "metadata": d.metadata}
                         for pk, d in zip(inserted_pks, docs, strict=False)]
            add_file_to_db(kb_file, custom_docs=custom_docs,
                           docs_count=len(docs), doc_infos=doc_infos)
            logger.info(f"[tx] <<< {file_name} vectorize SUCCESS: "
                        f"{len(inserted_pks)} PKs written")
            return True

        except VectorizeError as e:
            if VECTORIZE_ROLLBACK_ON_FAILURE and e.partial_pks:
                self._rollback_pks(e.partial_pks, file_name)
            logger.error(f"[tx] <<< {file_name} vectorize FAILED at [{e.stage}]: "
                         f"{e.detail}, rolled_back={len(e.partial_pks)} PKs")
            raise
        except Exception as e:
            if VECTORIZE_ROLLBACK_ON_FAILURE and inserted_pks:
                self._rollback_pks(inserted_pks, file_name)
            logger.error(f"[tx] <<< {file_name} vectorize FAILED at [unknown]: {e}, "
                         f"rolled_back={len(inserted_pks)} PKs")
            raise VectorizeError(
                stage="unknown", file_name=file_name,
                detail=str(e), partial_pks=inserted_pks,
            ) from e

    def delete_doc(self, kb_file: KnowledgeFile, delete_content: bool = False, **kwargs):
        """
        从知识库删除文件
        """
        self.do_delete_doc(kb_file, **kwargs)
        status = delete_file_from_db(kb_file)
        if delete_content and os.path.exists(kb_file.filepath):
            os.remove(kb_file.filepath)
        return status

    def update_info(self, kb_info: str):
        """
        更新知识库介绍
        """
        self.kb_info = kb_info
        status = add_kb_to_db(self.kb_name, self.kb_info, self.vs_type(), self.embed_model)
        return status

    def update_doc(self, kb_file: KnowledgeFile, docs: list[Document] = None, **kwargs):
        """P2 版本共存：追加新版本 chunk，不删旧版本 chunk。

        旧版本 chunk 保留在 Milvus，靠 metadata.file_version_label 区分。
        删除文件用 delete_doc（显式调用）。同内容重传由上游 record_current
        返回 is_new=False 跳过本方法，避免重复加 chunk。
        """
        if docs is None:
            docs = []
        if os.path.exists(kb_file.filepath):
            return self.add_doc(kb_file, docs=docs, **kwargs)
        # 文件不存在时显式返回 False + 警告，避免隐式 None 被调用方
        # `if not ok` 误报"向量化失败"——真实原因是文件路径无效
        logger.warning(f"[tx] update_doc 跳过：{kb_file.filepath} 不存在")
        return False

    def exist_doc(self, file_name: str):
        return file_exists_in_db(KnowledgeFile(knowledge_base_name=self.kb_name,
                                               filename=file_name))

    def list_files(self):
        return list_files_from_db(self.kb_name)

    def count_files(self):
        return count_files_from_db(self.kb_name)

    def search_docs(self,
                    query: str,
                    top_k: int = VECTOR_SEARCH_TOP_K,
                    score_threshold: float = SCORE_THRESHOLD,
                    version: str | None = None,
                    tags: list[str] | None = None,
                    ) ->list[Document]:
        docs = self.do_search(query, top_k, score_threshold,
                              version=version, tags=tags)
        return docs

    def update_doc_by_ids(self, docs: dict[str, Document]) -> bool:
        '''
        传入参数为： {doc_id: Document, ...}
        如果对应 doc_id 的值为 None，或其 page_content 为空，则删除该文档
        TODO：是否要支持新增 docs ？
        '''
        ids_to_delete = list(docs.keys())
        new_docs = []
        new_ids = []
        for k, v in docs.items():
            if not v or not v.page_content.strip():
                continue  # 空内容只删不增
            new_ids.append(k)
            new_docs.append(v)
        # Bug 6 修复：原 del→add 非原子，add 失败则旧文档已删、新文档未写，数据丢失。
        # 改为 add→del 补偿顺序：add 失败时旧文档仍在（不丢数据，raise 让上层处理）；
        # add 成功 del 失败则新旧并存（冗余可重试清理，但不丢数据，降级为 warning）。
        # 空内容 doc 在 del 阶段统一删除。
        if new_docs:
            try:
                self.do_add_doc(docs=new_docs, ids=new_ids)
            except Exception as e:
                logger.error(f"update_doc_by_ids 新增失败，旧文档保留未删除 "
                             f"(待删 ids={len(ids_to_delete)}): {e}", exc_info=True)
                raise
        try:
            self.del_doc_by_ids(ids_to_delete)
        except Exception as e:
            # del 失败：新旧文档并存，记录警告供后续清理，但不丢数据故不 raise
            logger.warning(f"update_doc_by_ids 新增成功但删除旧文档失败，"
                           f"新旧文档并存 (待删 ids={len(ids_to_delete)}): {e}",
                           exc_info=True)
        return True

    def list_docs(self, file_name: str = None, metadata: dict = None) -> list[DocumentWithVSId]:
        '''
        通过file_name或metadata检索Document
        '''
        if metadata is None:
            metadata = {}
        doc_infos = list_docs_from_db(kb_name=self.kb_name, file_name=file_name, metadata=metadata)
        docs = []
        for x in doc_infos:
            docs_by_id = self.get_doc_by_ids([x["id"]])
            if not docs_by_id:
                logger.warning(f"doc_id={x['id']} 在向量库中未找到，已跳过 (kb={self.kb_name}, file={file_name})")
                continue
            doc_with_id = DocumentWithVSId(**docs_by_id[0].dict(), id=x["id"])
            docs.append(doc_with_id)
        return docs

    def exists(self, kb_name: str = None):
        kb_name = kb_name or self.kb_name
        return kb_exists(kb_name)

    @abstractmethod
    def do_create_kb(self):
        """
        创建知识库子类实自己逻辑
        """

    @abstractmethod
    def vs_type(self) -> str:
        pass

    @abstractmethod
    def do_init(self):
        pass

    @abstractmethod
    def do_drop_kb(self):
        """
        删除知识库子类实自己逻辑
        """

    @abstractmethod
    def do_search(self,
                  query: str,
                  top_k: int,
                  score_threshold: float,
                  version: int | None = None,
                  ) -> list[tuple[Document, float]]:
        """
        搜索知识库子类实自己逻辑
        """

    @abstractmethod
    def do_add_doc(self,
                   docs: list[Document],
                   **kwargs,
                   ) -> list[dict]:
        """
        向知识库添加文档子类实自己逻辑
        """

    @abstractmethod
    def do_delete_doc(self,
                      kb_file: KnowledgeFile):
        """
        从知识库删除文档子类实自己逻辑
        """

    @abstractmethod
    def do_clear_vs(self):
        """
        从知识库删除全部向量子类实自己逻辑
        """

    def _docs_to_embeddings(self, docs: list[Document]) -> dict:
        """
        将 List[Document] 转化为 VectorStore.add_embeddings 可以接受的参数
        """
        return embed_documents(docs=docs, embed_model=self.embed_model, to_query=False)

    def _batch_embed_with_retry(self, docs: list[Document], file_name: str) -> list[list[float]]:
        """分批 embedding + 指数退避重试（批次间并发）。全成功才返回；任一批失败抛 VectorizeError。

        并发度从 embed_model.max_workers 取（默认 4），不超过批次数。
        每个批次独立重试，任一重试耗尽即抛 VectorizeError，其他在途批次跑完后抛。

        Args:
            docs: Document 列表
            file_name: 文件名（用于日志和异常）

        Returns:
            embeddings 列表，len == len(docs)

        Raises:
            VectorizeError: stage="embedding"，任一批重试耗尽
        """
        texts = [d.page_content for d in docs]
        if not texts:
            logger.warning(f"[tx] >>> {file_name} embedding skip: 0 chunks")
            return []
        batch_size = _resolve_batch_size(self.embed_model)
        total_batches = (len(texts) + batch_size - 1) // batch_size
        # 并发度：从 EMBEDDING_API_CONFIG per-model max_workers 取，不超过批次数
        max_workers = min(total_batches, _resolve_max_workers(self.embed_model))
        logger.info(f"[tx] >>> {file_name} embedding start: {len(docs)} chunks, "
                    f"batch_size={batch_size}, batches={total_batches}, max_workers={max_workers}")

        # 分批
        batches = [texts[i:i + batch_size] for i in range(0, len(texts), batch_size)]

        def _embed_one_batch(batch_idx: int, batch: list[str]) -> tuple[int, list[list[float]]]:
            """单批次 embedding + 重试。返回 (batch_idx, vectors)。重试耗尽抛 VectorizeError。"""
            last_err = None
            for attempt in range(EMBEDDING_MAX_RETRIES):
                try:
                    result = embed_texts(texts=batch, embed_model=self.embed_model, to_query=False)
                    if result.code != 200 or not result.data:
                        raise RuntimeError(
                            f"embedding API 返回 code={result.code} msg={getattr(result, 'msg', '')}")
                    if len(result.data) != len(batch):
                        raise RuntimeError(
                            f"API 返回 {len(result.data)} 个向量，预期 {len(batch)}")
                    logger.info(f"[tx] {file_name} batch {batch_idx + 1}/{total_batches} "
                                f"embedding OK: {len(batch)} texts -> {len(result.data)} vectors")
                    return batch_idx, result.data
                except Exception as e:
                    last_err = e
                    if attempt < EMBEDDING_MAX_RETRIES - 1:
                        backoff = EMBEDDING_RETRY_BACKOFF * (2 ** attempt)
                        logger.warning(f"[tx] {file_name} batch {batch_idx + 1} "
                                       f"attempt {attempt + 1} failed: {e}, retry in {backoff:.1f}s")
                        time.sleep(backoff)
            logger.error(f"[tx] {file_name} batch {batch_idx + 1} "
                         f"failed after {EMBEDDING_MAX_RETRIES} retries: {last_err}")
            raise VectorizeError(
                stage="embedding",
                file_name=file_name,
                detail=f"批次 {batch_idx + 1}/{total_batches} "
                       f"重试{EMBEDDING_MAX_RETRIES}次仍失败: {last_err}",
            )

        # 并发执行所有批次，用 batch_idx 保持结果顺序
        batch_results: list[list[list[float]] | None] = [None] * total_batches
        first_error: VectorizeError | None = None
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {
                executor.submit(_embed_one_batch, idx, batch): idx
                for idx, batch in enumerate(batches)
            }
            for future in as_completed(futures):
                try:
                    idx, vectors = future.result()
                    batch_results[idx] = vectors
                except VectorizeError as e:
                    # 记录第一个错误，停止遍历；with 块退出会等在途任务完成
                    if first_error is None:
                        first_error = e
                    break
        if first_error is not None:
            raise first_error

        all_embeddings: list[list[float]] = []
        for vectors in batch_results:
            all_embeddings.extend(vectors)
        return all_embeddings

    def _transactional_milvus_insert(self, docs: list[Document],
                                     embeddings: list[list[float]],
                                     file_name: str,
                                     file_version_label: str = None,
                                     tags=None) -> list[int]:
        """事务化写入向量库的默认回退实现。

        非 Milvus 服务（PG/ES/ZILLIZ/CHROMADB 等）无预计算向量写入 API，
        回退到 do_add_doc（内部重新 embed）。MilvusKBService 覆盖此方法
        实现真正的预计算向量事务化写入。
        """
        doc_infos = self.do_add_doc(docs, file_version_label=file_version_label,
                                    tags=tags)
        return [d.get("id") for d in (doc_infos or []) if d.get("id") is not None]

    def _rollback_pks(self, pks: list[int], file_name: str):
        """回滚已写 PK 的默认回退实现。非 Milvus 服务无 PK 概念，no-op。"""
        if pks:
            logger.debug(f"[tx] {file_name} skip rollback (vs_type={self.vs_type()}, "
                         f"pks={len(pks)})")


class KBServiceFactory:

    @staticmethod
    def get_service(kb_name: str,
                    vector_store_type: str | SupportedVSType,
                    embed_model: str = EMBEDDING_MODEL,
                    ) -> KBService:
        """获取知识库对象"""
        if isinstance(vector_store_type, str):
            vector_store_type = getattr(SupportedVSType, vector_store_type.upper())
        if SupportedVSType.PG == vector_store_type:
            from server.knowledge_base.kb_service.pg_kb_service import PGKBService
            return PGKBService(kb_name, embed_model=embed_model)
        elif SupportedVSType.MILVUS == vector_store_type:
            from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService
            return MilvusKBService(kb_name,embed_model=embed_model)
        elif SupportedVSType.ZILLIZ == vector_store_type:
            from server.knowledge_base.kb_service.zilliz_kb_service import ZillizKBService
            return ZillizKBService(kb_name, embed_model=embed_model)
        elif SupportedVSType.DEFAULT == vector_store_type:
            from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService
            return MilvusKBService(kb_name,
                                   embed_model=embed_model)  # other milvus parameters are set in model_config.kbs_config  # pylint: disable=line-too-long
        elif SupportedVSType.ES == vector_store_type:
            from server.knowledge_base.kb_service.es_kb_service import ESKBService
            return ESKBService(kb_name, embed_model=embed_model)
        elif SupportedVSType.CHROMADB == vector_store_type:
            from server.knowledge_base.kb_service.chromadb_kb_service import ChromaKBService
            return ChromaKBService(kb_name, embed_model=embed_model)
        elif SupportedVSType.DEFAULT == vector_store_type:  # kb_exists of default kbservice is False, to make validation easier.  # pylint: disable=line-too-long
            from server.knowledge_base.kb_service.default_kb_service import DefaultKBService
            return DefaultKBService(kb_name)
        return None

    @staticmethod
    def get_service_by_name(kb_name: str) -> KBService:
        _, vs_type, embed_model = load_kb_from_db(kb_name)
        if _ is None:  # kb not in db, just return None
            return None
        return KBServiceFactory.get_service(kb_name, vs_type, embed_model)

    @staticmethod
    def get_default():
        return KBServiceFactory.get_service("default", SupportedVSType.DEFAULT)


def get_kb_details() -> list[dict]:
    kbs_in_folder = list_kbs_from_folder()
    kbs_in_db = KBService.list_kbs()
    result = {}

    for kb in kbs_in_folder:
        result[kb] = {
            "kb_name": kb,
            "vs_type": "",
            "kb_info": "",
            "embed_model": "",
            "file_count": 0,
            "create_time": None,
            "in_folder": True,
            "in_db": False,
        }

    for kb in kbs_in_db:
        kb_detail = get_kb_detail(kb)
        if kb_detail:
            kb_detail["in_db"] = True
            if kb in result:
                result[kb].update(kb_detail)
            else:
                kb_detail["in_folder"] = False
                result[kb] = kb_detail

    data = []
    for i, v in enumerate(result.values()):
        v['No'] = i + 1
        data.append(v)

    return data


def get_kb_file_details(kb_name: str) -> list[dict]:
    kb = KBServiceFactory.get_service_by_name(kb_name)
    if kb is None:
        return []

    files_in_folder = list_files_from_folder(kb_name)
    files_in_db = kb.list_files()
    result = {}

    for doc in files_in_folder:
        result[doc] = {
            "kb_name": kb_name,
            "file_name": doc,
            "file_ext": os.path.splitext(doc)[-1],
            "file_version_label": "",
            "document_loader": "",
            "docs_count": 0,
            "text_splitter": "",
            "create_time": None,
            "in_folder": True,
            "in_db": False,
        }
    lower_names = {x.lower(): x for x in result}
    for doc in files_in_db:
        doc_detail = get_file_detail(kb_name, doc)
        if doc_detail:
            doc_detail["in_db"] = True
            matched_key = lower_names.get(doc.lower())
            if matched_key is not None:
                result.setdefault(matched_key, {}).update(doc_detail)
            else:
                doc_detail["in_folder"] = False
                result[doc] = doc_detail

    data = []
    for i, v in enumerate(result.values()):
        v['No'] = i + 1
        data.append(v)

    return data


class EmbeddingsFunAdapter(Embeddings):
    def __init__(self, embed_model: str = EMBEDDING_MODEL):
        self.embed_model = embed_model

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        embeddings = embed_texts(texts=texts, embed_model=self.embed_model, to_query=False).data
        return normalize(embeddings).tolist()

    def embed_query(self, text: str) -> list[float]:
        embeddings = embed_texts(texts=[text], embed_model=self.embed_model, to_query=True).data
        query_embed = embeddings[0]
        query_embed_2d = np.reshape(query_embed, (1, -1))  # 将一维数组转换为二维数组
        normalized_query_embed = normalize(query_embed_2d)
        return normalized_query_embed[0].tolist()  # 将结果转换为一维数组并返回

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        embeddings = (await aembed_texts(texts=texts, embed_model=self.embed_model, to_query=False)).data
        return normalize(embeddings).tolist()

    async def aembed_query(self, text: str) -> list[float]:
        embeddings = (await aembed_texts(texts=[text], embed_model=self.embed_model, to_query=True)).data
        query_embed = embeddings[0]
        query_embed_2d = np.reshape(query_embed, (1, -1))  # 将一维数组转换为二维数组
        normalized_query_embed = normalize(query_embed_2d)
        return normalized_query_embed[0].tolist()  # 将结果转换为一维数组并返回


def score_threshold_process(score_threshold, k, docs):
    """按 score_threshold 过滤候选文档，返回前 k 个。

    统一语义：score 为相似度（越大越好），阈值越大越严格。
    调用方需保证传入的 similarity 已归一化为相似度（大=好）：
      - Milvus COSINE / Zilliz IP：langchain 返回的 score 本身就是相似度，可直接传入。
      - PGVector EUCLIDEAN：langchain 返回 L2 距离（小=好），需在 do_search 内转成相似度
        （如 1/(1+distance)）后再传入，否则过滤方向会反。
    """
    if score_threshold is not None:
        docs = [
            (doc, similarity)
            for doc, similarity in docs
            if similarity is not None and similarity >= score_threshold
        ]
    return docs[:k]
