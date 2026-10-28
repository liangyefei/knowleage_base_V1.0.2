package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 类别 ID 值对象
 *
 * @param value 类别 ID 字符串（Milvus 合法格式，由 {@link IdGenerator#cat()} 生成）
 */
public record CategoryId(String value) {

    public CategoryId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("类别 ID 不能为空");
        }
    }

    public static CategoryId of(String value) {
        return new CategoryId(value);
    }
}
