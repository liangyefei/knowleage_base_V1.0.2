package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.util.Objects;

/**
 * 文档 ID 值对象
 */
public class DocumentId {

    private final String value;

    private DocumentId(String value) {
        this.value = value;
    }

    public static DocumentId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        return new DocumentId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentId that)) return false;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
