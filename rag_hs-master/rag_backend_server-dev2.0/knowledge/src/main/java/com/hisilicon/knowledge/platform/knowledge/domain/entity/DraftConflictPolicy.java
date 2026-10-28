package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 草稿态同名冲突策略
 * <p>complete 落库时，对 DRAFT 态同名文件按策略处理；REVIEWING/PUBLISHED/ARCHIVED 一律 SKIP 不允许覆盖。
 * 批次级 {@code draftConflictPolicy} 默认，tus create 的 {@code Upload-Metadata.draftPolicy} 可逐文件覆盖。</p>
 */
public enum DraftConflictPolicy {

    /** 覆盖草稿态同名文件（复用 documentId，旧文件移 trash） */
    OVERRIDE,

    /** 忽略草稿态同名文件（不覆盖，临时文件移 trash） */
    SKIP;

    /**
     * 容错解析：null/空白 → {@code defaultValue}；非法值 → 默认 {@code OVERRIDE}。
     */
    public static DraftConflictPolicy fromString(String value, DraftConflictPolicy defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return DraftConflictPolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OVERRIDE;
        }
    }
}
