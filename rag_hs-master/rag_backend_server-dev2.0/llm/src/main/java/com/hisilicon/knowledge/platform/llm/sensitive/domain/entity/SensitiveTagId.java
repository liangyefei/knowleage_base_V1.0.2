package com.hisilicon.knowledge.platform.llm.sensitive.domain.entity;

import java.util.Objects;

/**
 * 敏感词标签 ID 值对象
 */
public record SensitiveTagId(String value) {

    public SensitiveTagId {
        Objects.requireNonNull(value, "SensitiveTagId 不能为空");
    }

    public static SensitiveTagId of(String value) {
        return new SensitiveTagId(value);
    }
}
