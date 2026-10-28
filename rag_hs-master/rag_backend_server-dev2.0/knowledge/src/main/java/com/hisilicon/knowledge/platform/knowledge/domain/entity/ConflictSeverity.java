package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 冲突严重级别（模型回传，缺省 MEDIUM）。
 */
public enum ConflictSeverity {
    HIGH, MEDIUM, LOW;

    public static ConflictSeverity fromModelValue(String raw) {
        if (raw == null) {
            return MEDIUM;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
