
from langchain.schema import Document
from langchain.vectorstores.milvus import Milvus

from configs import kbs_config, logger
from server.db.repository import list_file_num_docs_id_by_kb_name_and_file_name
from server.knowledge_base.kb_service.base import (
    EmbeddingsFunAdapter,
    KBService,
    SupportedVSType,
    VectorizeError,
    score_threshold_process,
)
from server.knowledge_base.milvus_filter import _escape_milvus_str
from server.knowledge_base.utils import KnowledgeFile


class MilvusKBService(KBService):
    milvus: Milvus | None = None

    @staticmethod
    def get_collection(milvus_name):
        from pymilvus import Collection
        return Collection(milvus_name)

    @staticmethod
    def search(milvus_name, content, limit=3):
        search_params = {
            "metric_type": "COSINE",
            "params": {"ef": 64},
        }
        c = MilvusKBService.get_collection(milvus_name)
        return c.search(content, "embeddings", search_params, limit=limit, output_fields=["content"])

    @staticmethod
    def do_create_kb():
        pass

    @staticmethod
    def vs_type() -> str:
        return SupportedVSType.MILVUS

    def get_doc_by_ids(self, ids: list[str]) -> list[Document]:
        result = []
        if self.milvus.col:
            int_ids = [int(_id) for _id in ids]
            data_list = self.milvus.col.query(
                expr=f'pk in {int_ids}',
                output_fields=["*"]
            )
            logger.debug(f"get_doc_by_ids ids={int_ids}, returned={len(data_list)}")
            for data in data_list:
                text = data.pop("text", "")
                metadata = data.get("metadata", {})
                if isinstance(metadata, str):
                    import json
                    metadata = json.loads(metadata)
                result.append(Document(page_content=text, metadata=metadata))
        else:
            logger.warning(f"get_doc_by_ids: milvus.col is None, ids={ids}")
        return result

    def del_doc_by_ids(self, ids: list[str]) -> bool:
        int_ids = [int(i) for i in ids]
        self.milvus.col.delete(expr=f'pk in {int_ids}')

    def do_init(self):
        self._load_milvus()

    def do_drop_kb(self):
        if self.milvus.col:
            self.milvus.col.release()
            self.milvus.col.drop()
        self.milvus = None  # 置 None，让后续 _load_milvus 能重建（do_clear_vs → do_init 路径）

    def do_search(self, query: str, top_k: int, score_threshold: float,
                  version: str | None = None,
                  tags: list[str] | None = None):
        self._load_milvus()
        embed_func = EmbeddingsFunAdapter(self.embed_model)
        embeddings = embed_func.embed_query(query)
        # P2 统一 filter 构造：version（指定）or 默认当前版本 + tags 叠加
        # - version 传值：精确匹配该 label（覆盖默认）
        # - version=None：默认查当前所有文件的当前 label（build_files_version_map
        #   带 TTL 缓存），filter in 列表，避免命中旧版本 chunk
        #   P1-1 修复：加 `or file_version_label is null` 让无 label 文档也通过，
        #   与客户端 _matches_version_tags 的"无 label 保留"语义对齐
        # - 查映射失败/空库：fail-open 不过滤（filter_expr=None）
        filter_expr = None
        if version is not None:
            esc = _escape_milvus_str(version)
            filter_expr = f'metadata["file_version_label"] == "{esc}"'
        else:
            try:
                from server.knowledge_base.file_version import build_files_version_map
                cur_map = build_files_version_map(self.kb_name)  # 内部走 TTL 缓存
                cur_labels = [l for l in cur_map.values() if l]
                if cur_labels:
                    labels_str = ", ".join(f'"{_escape_milvus_str(l)}"' for l in cur_labels)
                    filter_expr = (f'(metadata["file_version_label"] in [{labels_str}]) '
                                   f'or (metadata["file_version_label"] is null)')
            except Exception as e:
                logger.warning(f"[milvus] do_search 查当前版本映射失败（fail-open 不过滤）: {e}")
        # tags 过滤叠加（OR 语义：json_contains 检测 metadata["tags"] 数组）
        if tags:
            tag_clauses = []
            for t in tags:
                esc = _escape_milvus_str(t)
                tag_clauses.append(f'json_contains(metadata["tags"], "{esc}")')
            tags_or = " or ".join(tag_clauses)
            filter_expr = tags_or if filter_expr is None else f"({filter_expr}) and ({tags_or})"
        docs = self.milvus.similarity_search_with_score_by_vector(
            embeddings, top_k, filter=filter_expr)
        return score_threshold_process(score_threshold, top_k, docs)

    def do_add_doc(self, docs: list[Document], **kwargs) -> list[dict]:
        # P0-3 修复：为 Milvus 构造专用副本，不原地修改原 docs。
        # 原地改 doc.metadata 会污染调用方（base.add_doc 在 _transactional_milvus_insert
        # 后仍用 docs 构造 doc_infos 写 MySQL），导致 MySQL file_doc.meta_data 存嵌套结构
        # {"source":..., "metadata":{...}}。改为用副本让 Milvus 写入正常（metadata 列仍
        # 存扁平原始 metadata），原 docs 保持扁平，MySQL 存扁平。
        #
        # 背景：langchain Milvus 未传 metadata_field，走"模式 A"（按 schema 列名从
        # doc.metadata 取值），schema 有 metadata(JSON) 列，需 doc.metadata 含 'metadata'
        # key 才能填充该列，故需嵌套一份原 metadata 到 'metadata' key。
        #
        # file_version_label 必须放进嵌套的 m['metadata']（写入 Milvus metadata JSON 列），
        # 不能放顶层 m——顶层非 schema 列的 key 会被 Mode A 丢弃（见 langchain
        # milvus.py add_texts：`if key in keys: insert_dict...`），且放顶层会为新
        # collection 意外创建 file_version_label 标量列，违反"不改 schema 标量列"规划。
        file_version_label = kwargs.get("file_version_label")
        tags = kwargs.get("tags")
        milvus_docs = []
        for d in docs:
            m = dict(d.metadata)                   # 浅拷贝原 metadata（值多为 str/int，足够）
            nested = dict(d.metadata)             # 嵌套一份填 Milvus metadata JSON 列
            if isinstance(file_version_label, str) and file_version_label:
                nested["file_version_label"] = file_version_label   # 版本标签（与 dual-mcp 同字段，String）
            if isinstance(tags, list) and tags:
                clean_tags = [t for t in tags if isinstance(t, str) and t.strip()]
                if clean_tags:
                    nested["tags"] = clean_tags
            m['metadata'] = nested
            m.pop(self.milvus._text_field, None)     # text/vector 是 Milvus 自动管理的列  # pylint: disable=protected-access
            m.pop(self.milvus._vector_field, None)  # pylint: disable=protected-access
            milvus_docs.append(Document(page_content=d.page_content, metadata=m))

        ids = self.milvus.add_documents(milvus_docs)
        # Bug 4 修复：zip 在 ids 数 != docs 数或 ids 含 None 时静默截断 + 错位
        # （如 ids=[1,None,3] 过滤 None 后 pks=[1,3]，PK3 错位映射 doc1）。
        # 显式校验长度一致且无 None，不一致抛错避免 doc_infos 错位返回。
        if len(ids) != len(docs) or any(i is None for i in ids):
            raise RuntimeError(
                f"do_add_doc 写入异常：ids 数({len(ids)}) != docs 数({len(docs)})"
                f"或含 None PK，拒绝错位返回 doc_infos"
            )
        # 用原 docs（未污染）构造 doc_infos，MySQL 存扁平 metadata
        doc_infos = [{"id": id, "metadata": doc.metadata} for id, doc in zip(ids, docs, strict=False)]
        return doc_infos

    def do_add_doc_with_embeddings(self, docs: list[Document],
                                   embeddings: list[list[float]]) -> list[int]:
        """用预计算的 embeddings 写入 Milvus，返回 PK 列表。

        langchain Milvus 类无 add_embeddings 方法（实测 langchain 0.2 仅
        add_documents / add_texts，且均内部 embed），故通过临时替换
        embedding_func 属性为返回预计算向量的 stub 复用 add_documents 流程，
        兼容所有 collection schema。全成功返回 PK 列表；中途失败抛
        VectorizeError(milvus_insert, partial_pks=已写)。
        """
        inserted_pks = []
        try:
            # langchain Milvus 内部用 self.embedding_func（非 embedding_function），
            # 替换错属性名会导致 stub 不生效，langchain 重新 embed 全部文本（串行，极慢）。
            original_ef = getattr(self.milvus, 'embedding_func', None)
            # Bug 3 修复：改用下标索引消费预计算向量，替代 iter + next。
            # 原方案 _emb_iter = iter(embeddings) + next() 是单次流式消费，
            # 若 langchain add_documents 内部重试 embed_documents 或并发调用，
            # 会重复消费导致 StopIteration（PEP 479 转 RuntimeError）或向量与
            # doc 静默错位（检索全错且无报错）。新方案用可随机访问列表 + 游标 +
            # 边界校验；embed_query 不消费预计算向量（add_documents 只应走
            # embed_documents，调 embed_query 视为路径异常，fail fast）。
            state = {
                "embeddings": list(embeddings),  # 备份为可随机访问列表
                "cursor": 0,                     # 已消费游标
                "total": len(embeddings),
            }

            class _PreComputedEF:
                """返回预计算向量的 embedding stub（下标索引，防重入错位）。"""
                @staticmethod
                def embed_documents(texts):
                    n = len(texts)
                    start = state["cursor"]
                    end = start + n
                    if end > state["total"]:
                        # 消费超过预计算向量数：说明 langchain 内部重试/重入了
                        # embed_documents，原迭代器方案会 StopIteration 或错位。
                        # 抛错让上层 VectorizeError 捕获，避免静默向量错位。
                        raise RuntimeError(
                            f"预计算向量不足：请求 {n} 个（游标 {start}->{end}），"
                            f"仅剩余 {state['total'] - start} 个。"
                            f"疑似 langchain add_documents 内部重试 embed_documents"
                        )
                    batch = state["embeddings"][start:end]
                    state["cursor"] = end
                    return batch
                @staticmethod
                def embed_query(text):
                    # add_documents 只应走 embed_documents；若被调 embed_query
                    # 说明 langchain 走了非预期路径，不应消费预计算向量（会错位）
                    raise RuntimeError(
                        "预计算向量模式下不应调用 embed_query（add_documents "
                        "应走 embed_documents），疑似 langchain 调用路径异常"
                    )
                async def aembed_documents(self, texts):
                    return self.embed_documents(texts)
                async def aembed_query(self, text):
                    return self.embed_query(text)

            self.milvus.embedding_func = _PreComputedEF()
            try:
                # P0-3 修复：为 Milvus 构造专用副本，不原地修改原 docs（同 do_add_doc）。
                # 原地改会污染调用方 base.add_doc，导致 MySQL meta_data 存嵌套结构。
                # file_version_label 放进嵌套 m['metadata']（写入 Milvus metadata JSON 列），
                # 不能放顶层——顶层非 schema 列的 key 会被 langchain Mode A 丢弃，
                # 且会为新 collection 意外创建 file_version_label 标量列。
                file_version_label = getattr(self, "_current_file_version_label", None)
                tags = getattr(self, "_current_tags", None)
                milvus_docs = []
                for d in docs:
                    m = dict(d.metadata)
                    nested = dict(d.metadata)
                    if isinstance(file_version_label, str) and file_version_label:
                        nested["file_version_label"] = file_version_label   # 版本标签（同 do_add_doc）
                    if isinstance(tags, list) and tags:
                        clean_tags = [t for t in tags if isinstance(t, str) and t.strip()]
                        if clean_tags:
                            nested["tags"] = clean_tags
                    m['metadata'] = nested
                    m.pop(self.milvus._text_field, None)  # pylint: disable=protected-access
                    m.pop(self.milvus._vector_field, None)  # pylint: disable=protected-access
                    milvus_docs.append(Document(page_content=d.page_content, metadata=m))
                ids = self.milvus.add_documents(milvus_docs)
                inserted_pks = [int(i) for i in ids if i is not None]
                logger.info(f"[tx] {getattr(self, '_current_file_name', 'unknown')} "
                            f"milvus insert OK: {len(docs)} entities -> "
                            f"{len(inserted_pks)} pks")
                return inserted_pks
            finally:
                self.milvus.embedding_func = original_ef
        except Exception as e:
            raise VectorizeError(
                stage="milvus_insert",
                file_name=getattr(self, '_current_file_name', 'unknown'),
                detail=str(e),
                partial_pks=inserted_pks,
            ) from e

    def do_delete_doc(self, kb_file: KnowledgeFile, **kwargs):
        id_list = list_file_num_docs_id_by_kb_name_and_file_name(kb_file.kb_name, kb_file.filename)
        # 空列表守卫：MySQL 无记录（首次上传/已清）时 expr='pk in []'
        # 在部分 pymilvus 版本会报错，直接跳过 delete
        if not id_list:
            return
        if self.milvus.col:
            self.milvus.col.delete(expr=f'pk in {id_list}')

    def do_clear_vs(self):
        if self.milvus.col:
            self.do_drop_kb()
            self.do_init()

    def _embedding_dim(self) -> int:
        """向量维度：取 embedding 模型配置的 embedding_dim，兜底 1024。

        历史上硬编码 2560（qwen3-embedding 的维度），与 text-embedding-v3 不匹配，
        导致新建 collection 维度错、检索报 dimension mismatch。
        """
        try:
            from configs import EMBEDDING_API_CONFIG
            cfg = EMBEDDING_API_CONFIG.get(getattr(self, "embed_model", ""), {})
            d = cfg.get("embedding_dim")
            if d:
                return int(d)
        except Exception as e:
            logger.debug(f"[milvus] get embedding_dim from config failed, use default 1024: {e}")
            pass
        return 1024

    def _load_milvus(self):
        # 幂等懒加载：已初始化则直接返回，避免 do_search 每次调用都重新 connect +
        # 重新实例化 Milvus（多线程并发搜索时 self.milvus 被反复覆盖，非线程安全 + 资源浪费）。
        # do_clear_vs → do_drop_kb 会置 self.milvus = None，此处能正确重建。
        _MILVUS_KWARGS_KEY = 'milvus_kwargs'
        if self.milvus is not None:
            return
        from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections

        connections.connect(**kbs_config.get("milvus"))

        # 检查 collection 是否存在
        try:
            collection = Collection(name=self.kb_name)
            collection.load()
            exists = True
        except Exception:
            exists = False

        if not exists:
            # 创建新 collection 时直接传入 schema
            dim = self._embedding_dim()
            fields = [
                FieldSchema(name="pk", dtype=DataType.INT64, is_primary=True, auto_id=True),
                FieldSchema(name="text", dtype=DataType.VARCHAR, max_length=65535),
                FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=dim),
                FieldSchema(name="source", dtype=DataType.VARCHAR, max_length=500),
                FieldSchema(name="metadata", dtype=DataType.JSON),
            ]
            schema = CollectionSchema(fields=fields)
            collection = Collection(name=self.kb_name, schema=schema)  # ← 创建时传入 schema

            # 创建索引
            index_params = kbs_config.get(_MILVUS_KWARGS_KEY, {}).get("index_params", {})
            collection.create_index(field_name="vector", index_params=index_params)
            collection.load()

        # 加载 Milvus
        self.milvus = Milvus(
            embedding_function=EmbeddingsFunAdapter(self.embed_model),
            collection_name=self.kb_name,
            connection_args=kbs_config.get("milvus"),
            auto_id=True,
            index_params=kbs_config.get(_MILVUS_KWARGS_KEY)["index_params"],
            search_params=kbs_config.get(_MILVUS_KWARGS_KEY)["search_params"]
        )

    def _transactional_milvus_insert(self, docs: list[Document],
                                     embeddings: list[list[float]],
                                     file_name: str,
                                     file_version_label: str = None,
                                     tags=None) -> list[int]:
        """事务化写入 Milvus：记录已写 PK，失败时通过 VectorizeError.partial_pks 触发回滚。"""
        self._current_file_name = file_name
        self._current_file_version_label = file_version_label
        self._current_tags = tags
        inserted_pks = []
        try:
            inserted_pks = self.do_add_doc_with_embeddings(docs, embeddings)
            return inserted_pks
        except VectorizeError:
            raise
        except Exception as e:
            raise VectorizeError(
                stage="milvus_insert", file_name=file_name,
                detail=str(e), partial_pks=inserted_pks,
            ) from e

    def _rollback_pks(self, pks: list[int], file_name: str):
        """回滚已写入 Milvus 的 PK（best-effort，失败仅 CRITICAL 告警）。"""
        if not pks or not self.milvus.col:
            return
        try:
            self.milvus.col.delete(expr=f'pk in {pks}')
            logger.info(f"[tx] {file_name} 回滚 {len(pks)} 个向量成功")
        except Exception as e:
            logger.critical(
                f"[tx] {file_name} ROLLBACK FAILED: PKs={pks} still in Milvus, "
                f"manual cleanup required: {e}")


if __name__ == '__main__':
    # 测试建表使用
    from server.db.base import Base, engine

    Base.metadata.create_all(bind=engine)
    milvusService = MilvusKBService("test")

    logger.info("{}", milvusService.get_doc_by_ids(["444022434274215486"]))
