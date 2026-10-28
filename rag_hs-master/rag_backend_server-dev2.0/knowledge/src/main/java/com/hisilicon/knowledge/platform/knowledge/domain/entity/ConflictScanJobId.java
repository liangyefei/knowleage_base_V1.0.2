package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 检测任务 ID 值对象。
 *
 * @param value 检测任务 ID 字符串（{@code cj} + 32hex，由 {@link IdGenerator#conflictJob()} 生成）
 */
public record ConflictScanJobId(String value) {

    public ConflictScanJobId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Conflict scan job id must not be blank");
        }
    }

    public static ConflictScanJobId of(String value) {
        return new ConflictScanJobId(value);
    }
}
