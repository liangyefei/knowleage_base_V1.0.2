"""存量 chunk 补 file_version_label 迁移脚本。

P2 前的 chunk 都是当前版本（旧 chunk 已被 delete+add 删过）。
P2 版本共存后，update_doc 改为追加不删，旧版本 chunk 保留在 Milvus，
靠 metadata.file_version_label 区分版本。本脚本对每个 kb 的每个文件，
查 get_current_version 拿当前 label，用 pymilvus query 拿该文件所有
chunk 的 pk + metadata，给 metadata 补 file_version_label 字段后 upsert
整条记录。

技术注意：
- Milvus metadata 是 JSON 字段，pymilvus 不支持嵌套字段部分更新，必须
  query 拿到完整 metadata dict → 在 dict 上补 file_version_label →
  upsert 整条记录（pk + text + source + metadata）。只传 metadata 会
  覆盖丢字段。
- collection 名规则 = kb_name（见 milvus_kb_service.MilvusKBService.
  _load_milvus：collection_name=self.kb_name）。
- 迁移前务必备份 Milvus collection（col.release() 前导出）。
- 若存量 chunk 量大（>10 万），col.query 需分批 cursor 翻页，避免 OOM。

用法:
    python -m script.migrate_chunk_version_label
    # 或在脚本内配置 MILVUS_CONN_CONFIG / kb_col_map 后直接运行
"""
import json
import logging

from pymilvus import Collection, connections, utility

from server.knowledge_base.file_version import get_current_version

logger = logging.getLogger(__name__)


def migrate(kb_name: str, col_name: str):
    """迁移单个 kb 的存量 chunk，补 metadata.file_version_label。

    Args:
        kb_name: 知识库名（用于查 file_version_history 拿当前 label）
        col_name: Milvus collection 名（= kb_name，P2 不改 schema 标量列）
    """
    _PK_KEY = 'pk'
    _SOURCE_KEY = 'source'
    _TEXT_KEY = 'text'
    _METADATA_KEY = 'metadata'
    if not utility.has_collection(col_name):
        logger.warning(f"[migrate] {kb_name} 无 collection {col_name}，跳过")
        return
    col = Collection(col_name)
    # 1. 查所有 chunk 的 pk + source + metadata（分批避免大集合 OOM）
    #    expr='pk >= 0' 匹配所有 INT64 pk；output_fields 含 text 以便 upsert 整条
    rows = col.query(expr='pk >= 0',
                     output_fields=[_PK_KEY, _SOURCE_KEY, _TEXT_KEY, _METADATA_KEY])
    if not rows:
        logger.info(f"[migrate] {kb_name} collection 空，无需迁移")
        return
    # 2. 按 source（文件名）分组
    by_file = {}
    for r in rows:
        by_file.setdefault(r.get(_SOURCE_KEY), []).append(r)
    # 3. 每个文件查当前 label，给该文件所有 chunk 补 metadata.file_version_label
    to_upsert = []
    for file_name, chunks in by_file.items():
        if not file_name:
            continue
        cur_label = get_current_version(kb_name, file_name)
        if not cur_label:
            logger.warning(f"[migrate] {kb_name}/{file_name} 无当前版本，跳过")
            continue
        for c in chunks:
            meta = c.get(_METADATA_KEY) or {}
            if isinstance(meta, str):
                try:
                    meta = json.loads(meta)
                except Exception:
                    meta = {}
            if not isinstance(meta, dict):
                meta = {}
            meta = dict(meta)  # 拷贝避免改原对象
            meta["file_version_label"] = cur_label
            to_upsert.append({
                _PK_KEY: c.get(_PK_KEY),
                _SOURCE_KEY: c.get(_SOURCE_KEY),
                _TEXT_KEY: c.get(_TEXT_KEY, ""),
                _METADATA_KEY: meta,
            })
    # 4. 批量 upsert（pymilvus collection.upsert 接受 list of dict）
    if to_upsert:
        col.upsert(to_upsert)
        logger.info(f"[migrate] {kb_name} 补 label {len(to_upsert)} 条 chunk")
    else:
        logger.info(f"[migrate] {kb_name} 无需迁移的 chunk")
    col.flush()


def migrate_all(kb_col_map_input: dict):
    """遍历所有 kb 执行迁移。

    Args:
        kb_col_map_input: {kb_name: milvus_collection_name} 映射。
                    P2 collection 名 = kb_name，故通常 {kb: kb}。
    """
    for kb, col_name in kb_col_map_input.items():
        try:
            migrate(kb, col_name)
        except Exception as e:
            logger.error(f"[migrate] {kb} 失败: {e}", exc_info=True)


# =============================================================================
# 运行前按实际环境配置以下两项
# =============================================================================
# Milvus 连接配置（从 configs.kbs_config["milvus"] 取，或手填）
# 示例：{"host": "127.0.0.1", "port": "19530", "user": "", "password": "",
#        "db_name": "default"}
MILVUS_CONN_CONFIG = {
}

# kb → collection 名映射。P2 collection 名 = kb_name，故通常 {kb: kb}。
# 填所有需要迁移的 kb 名。
kb_col_map = {
}


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s")
    if not MILVUS_CONN_CONFIG:
        raise SystemExit("请先配置 MILVUS_CONN_CONFIG（Milvus 连接参数）")
    if not kb_col_map:
        raise SystemExit("请先配置 kb_col_map（kb → collection 名映射）")
    connections.connect(alias="default", **MILVUS_CONN_CONFIG)
    migrate_all(kb_col_map)
    logger.info("[migrate] 全部迁移完成")
