package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 导入批次 ID 值对象
 *
 * @param value 批次 ID 字符串（由 {@link IdGenerator#uploadSession()} 生成，tus 会话以其归集）
 */
public record ImportBatchId(String value) {

    public ImportBatchId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("导入批次 ID 不能为空");
        }
    }

    public static ImportBatchId of(String value) {
        return new ImportBatchId(value);
    }
}
