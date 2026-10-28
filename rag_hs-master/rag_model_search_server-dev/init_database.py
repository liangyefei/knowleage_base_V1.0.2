#!/usr/bin/env python3
import os
import sys
from datetime import datetime, timezone

import nltk

# ==================== 导入配置 ====================
from configs import DEFAULT_VS_TYPE, KB_ROOT_PATH

try:
    from configs import EMBEDDING_MODEL, NLTK_DATA_PATH
except ImportError:
    EMBEDDING_MODEL = "text2vec-large-chinese"
    NLTK_DATA_PATH = os.path.join(os.path.dirname(KB_ROOT_PATH), "nltk_data")

from configs.logger_manager import logger

nltk.data.path = [NLTK_DATA_PATH] + nltk.data.path

sys.path.append(".")

# ==================== 导入现有方法（不要直接导入Loader！） ====================
from server.knowledge_base.kb_service.base import KBServiceFactory
from server.knowledge_base.migrate import create_tables, reset_tables
from server.knowledge_base.utils import (
    SUPPORTED_EXTS,  # ← 支持的文件扩展名列表
    KnowledgeFile,
    get_doc_path,
    get_loader,  # ← 使用这个来获取Loader
    get_LoaderClass,  # ← 使用这个来获取类名
    list_files_from_folder,
)


def clear_knowledge_base(kb_name: str):
    logger.info(f"清空知识库: {kb_name}")

    # 1. 删除 Milvus collection
    try:
        from pymilvus import Collection, connections

        from configs import kbs_config

        connections.connect(**kbs_config.get("milvus"))
        collection = Collection(name=kb_name)
        # 尝试加载，如果能加载说明存在
        try:
            collection.load()
            collection.drop()
            logger.info(f"✅ 已删除 Milvus collection: {kb_name}")
        except Exception:
            logger.info(f"Milvus collection {kb_name} 不存在，跳过删除")
    except Exception as e:
        logger.warning(f"删除 Milvus collection 失败: {e}")

    # 2. 重置数据库表
    reset_tables()
    logger.info("✅ 数据库表已重置")

    # 3. 重建表
    create_tables()
    logger.info("✅ 数据库表已重建")


def process_files_with_custom_loader(kb_name: str):
    kb = KBServiceFactory.get_service(kb_name, DEFAULT_VS_TYPE, EMBEDDING_MODEL)
    if not kb.exists():
        kb.create_kb()
        logger.info(f"✅ 知识库已创建: {kb_name}")

    doc_path = get_doc_path(kb_name)
    files = list_files_from_folder(kb_name)

    if not files:
        logger.warning(f"知识库 {kb_name} 下没有文件")
        return

    logger.info(f"找到 {len(files)} 个文件")

    for filename in files:
        file_path = os.path.join(doc_path, filename)
        logger.info(f"处理文件: {filename}")

        ext = os.path.splitext(filename)[-1].lower()
        if ext not in SUPPORTED_EXTS:
            logger.warning(f"不支持的文件类型: {filename}")
            continue

        loader_name = get_LoaderClass(ext)
        if loader_name is None:
            logger.warning(f"未找到对应的Loader: {filename}")
            continue

        try:
            loader = get_loader(loader_name, file_path)
            docs = loader.load()  # ← 现在直接用 load() 就可以了！
            logger.info(f"  🔍 docs 数量: {len(docs)}")

            if not docs:
                logger.warning(f"文件加载为空: {filename}")
                continue

            logger.info(f"  ✅ 解析完成，共 {len(docs)} 个片段")

            kb_file = KnowledgeFile(filename=filename, knowledge_base_name=kb_name)

            for i, doc in enumerate(docs):
                doc.metadata['chunk_index'] = i
                doc.metadata['total_chunks'] = len(docs)
                kb.add_doc(kb_file=kb_file, docs=[doc])
                if (i + 1) % 5 == 0 or i + 1 == len(docs):
                    logger.info(f"    ✅ 已保存片段 {i + 1}/{len(docs)}")

            logger.info(f"  ✅ 文件 {filename} 全部保存完成")

        except Exception as e:
            logger.error(f"❌ 处理失败 {filename}: {e}")
            import traceback
            traceback.print_exc()

    kb.save_vector_store()
    logger.info(f"✅ 知识库 {kb_name} 处理完成")


def main():
    KB_NAME = "init_knowledge_base"

    start_time = datetime.now(timezone.utc)
    logger.info("=" * 70)
    logger.info("知识库初始化工具（使用注册机制）")
    logger.info("=" * 70)
    logger.info(f"知识库名称: {KB_NAME}")
    logger.info(f"知识库路径: {KB_ROOT_PATH}")
    logger.info(f"向量库类型: {DEFAULT_VS_TYPE}")
    logger.info(f"嵌入模型: {EMBEDDING_MODEL}")
    logger.info("=" * 70)

    clear_knowledge_base(KB_NAME)
    process_files_with_custom_loader(KB_NAME)

    end_time = datetime.now(timezone.utc)
    logger.info(f"\n⏱️ 总计用时： {end_time - start_time}")
    logger.info("=" * 70)


if __name__ == "__main__":
    main()
