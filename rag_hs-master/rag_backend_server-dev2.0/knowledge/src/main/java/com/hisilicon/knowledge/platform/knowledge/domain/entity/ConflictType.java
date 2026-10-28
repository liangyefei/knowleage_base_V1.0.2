package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 冲突类型（平台侧枚举，与 BadcaseProblemType 的 5 种冲突类型对齐）。
 * <p>模型端回传 C1~C5，落库时由 {@link #fromModelValue(String)} 映射：</p>
 * <ul>
 *   <li>C1 事实冲突 → {@link #FACT_CONFLICT}</li>
 *   <li>C2 语义冲突 → {@link #SEMANTIC_CONFLICT}</li>
 *   <li>C3 版本冲突 → {@link #VERSION_CONFLICT}</li>
 *   <li>C4 跨模态冲突 → {@link #CROSS_MODAL_CONFLICT}</li>
 *   <li>C5 近重复冲突 → {@link #NEAR_DUPLICATE_CONFLICT}</li>
 * </ul>
 * <p>原始模型值保留在 {@code conflict_records.raw_type}。</p>
 */
public enum ConflictType {
    /** 事实冲突（模型端 C1） */
    FACT_CONFLICT,
    /** 语义冲突（模型端 C2） */
    SEMANTIC_CONFLICT,
    /** 版本冲突（模型端 C3） */
    VERSION_CONFLICT,
    /** 跨模态冲突（模型端 C4） */
    CROSS_MODAL_CONFLICT,
    /** 近重复冲突（模型端 C5） */
    NEAR_DUPLICATE_CONFLICT;

    /**
     * 模型端 C1~C5 → 平台枚举映射；识别不到兜底 SEMANTIC_CONFLICT。
     */
    public static ConflictType fromModelValue(String raw) {
        if (raw == null) {
            return SEMANTIC_CONFLICT;
        }
        return switch (raw.trim().toUpperCase()) {
            case "C1" -> FACT_CONFLICT;
            case "C2" -> SEMANTIC_CONFLICT;
            case "C3" -> VERSION_CONFLICT;
            case "C4" -> CROSS_MODAL_CONFLICT;
            case "C5" -> NEAR_DUPLICATE_CONFLICT;
            default -> SEMANTIC_CONFLICT;
        };
    }
}
