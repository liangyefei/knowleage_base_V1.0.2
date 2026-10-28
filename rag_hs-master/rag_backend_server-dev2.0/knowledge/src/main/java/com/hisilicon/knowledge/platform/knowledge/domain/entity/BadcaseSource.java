package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * Badcase 来源枚举。
 */
public enum BadcaseSource {
    /** 页面问答 */
    PAGE_QA("页面问答"),
    /** MCP 调用问答 */
    MCP_QA("MCP 调用问答"),
    /** 冲突检测 */
    CONFLICT_DETECTION("冲突检测"),
    /** 知识切片 */
    KNOWLEDGE_SLICE("知识切片");

    private final String label;

    BadcaseSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：识别不到返回 null（由调用方决定如何处理）。
     */
    public static BadcaseSource fromCode(String code) {
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
