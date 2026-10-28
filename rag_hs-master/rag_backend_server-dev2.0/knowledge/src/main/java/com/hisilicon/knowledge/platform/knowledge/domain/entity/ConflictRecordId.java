package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 冲突记录 ID 值对象。
 *
 * @param value 冲突记录 ID 字符串（{@code cr} + 32hex，由 {@link IdGenerator#conflictRecord()} 生成）
 */
public record ConflictRecordId(String value) {

    public ConflictRecordId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Conflict record id must not be blank");
        }
    }

    public static ConflictRecordId of(String value) {
        return new ConflictRecordId(value);
    }
}
