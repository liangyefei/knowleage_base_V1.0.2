package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * Badcase 工单处理状态（四态）。
 * <p>初始 PENDING；任意状态可改为任意目标状态（见设计文档 §3.4）。</p>
 */
public enum BadcaseStatus {
    /** 待处理（初始态） */
    PENDING("待处理"),
    /** 处理中 */
    PROCESSING("处理中"),
    /** 已处理 */
    RESOLVED("已处理"),
    /** 不予处理 */
    REJECTED("不予处理");

    private final String label;

    BadcaseStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：识别不到返回 null。
     */
    public static BadcaseStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
