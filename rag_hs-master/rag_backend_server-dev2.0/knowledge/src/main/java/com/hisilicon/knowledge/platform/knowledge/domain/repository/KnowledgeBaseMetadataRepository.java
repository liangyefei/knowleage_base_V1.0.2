package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 知识库元数据仓储接口（knowledge_base_metadata 表）
 * <p>key-value 扩展模式，存储知识库维度的 ABAC 属性及其他扩展属性
 * （如所属组织 org、默认敏感等级 default_sensitivity 等）。</p>
 */
public interface KnowledgeBaseMetadataRepository {

    /**
     * 全量替换知识库元数据
     *
     * @param kbId     知识库 ID
     * @param metadata meta_key → meta_value 映射（空 Map 或 null = 清空）
     * @return 完成信号
     */
    Uni<Void> replaceAll(String kbId, Map<String, String> metadata);
}
