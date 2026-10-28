package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 知识库 ID 值对象
 *
 * @param value 知识库 ID 字符串（Milvus 合法格式，由 {@link IdGenerator#kb()} 生成）
 */
public record KnowledgeBaseId(String value) {

    public KnowledgeBaseId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("知识库 ID 不能为空");
        }
    }

    public static KnowledgeBaseId of(String value) {
        return new KnowledgeBaseId(value);
    }
}
