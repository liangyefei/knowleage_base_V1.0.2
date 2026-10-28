package com.hisilicon.knowledge.platform.knowledge.dict.domain.entity;

/**
 * 字典类型 ID 值对象
 *
 * @param value UUID 字符串
 */
public record DictTypeId(String value) {

    public DictTypeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字典类型 ID 不能为空");
        }
    }

    public static DictTypeId of(String value) {
        return new DictTypeId(value);
    }
}
