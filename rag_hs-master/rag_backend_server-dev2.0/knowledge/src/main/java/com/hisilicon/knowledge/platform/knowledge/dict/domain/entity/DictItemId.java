package com.hisilicon.knowledge.platform.knowledge.dict.domain.entity;

/**
 * 字典条目 ID 值对象
 *
 * @param value UUID 字符串
 */
public record DictItemId(String value) {

    public DictItemId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字典条目 ID 不能为空");
        }
    }

    public static DictItemId of(String value) {
        return new DictItemId(value);
    }
}
