package com.hisilicon.knowledge.platform.llm.sensitive.domain.entity;

import java.util.Objects;

/**
 * 敏感词词库 ID 值对象
 */
public record SensitiveLexiconId(String value) {

    public SensitiveLexiconId {
        Objects.requireNonNull(value, "SensitiveLexiconId 不能为空");
    }

    public static SensitiveLexiconId of(String value) {
        return new SensitiveLexiconId(value);
    }
}
