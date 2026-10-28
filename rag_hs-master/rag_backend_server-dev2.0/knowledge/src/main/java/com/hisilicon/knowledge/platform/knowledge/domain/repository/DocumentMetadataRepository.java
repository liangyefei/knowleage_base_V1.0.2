package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 文档元数据仓储接口（document_metadata 表）
 * <p>上传/覆盖时全量替换：先删该文档旧元数据，再插入新集合。</p>
 */
public interface DocumentMetadataRepository {

    /**
     * 全量替换文档元数据
     *
     * @param documentId 文档 ID
     * @param metadata   meta_key → meta_value 映射（空 Map 或 null = 清空）
     * @return 完成信号
     */
    Uni<Void> replaceAll(String documentId, Map<String, String> metadata);
}
