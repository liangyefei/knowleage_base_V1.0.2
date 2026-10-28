package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Badcase 问题类型枚举。
 * <p>来源与问题类型的映射关系是业务硬编码，不做数据库配置表（见设计文档 §3.2）。</p>
 */
public enum BadcaseProblemType {
    // ===== 页面问答 / MCP 调用问答 共用 7 种 =====
    /** 回答错误 */
    WRONG_ANSWER("回答错误"),
    /** 代码错误 */
    CODE_ERROR("代码错误"),
    /** 答非所问 */
    IRRELEVANT_ANSWER("答非所问"),
    /** 信息过时 */
    OUTDATED_INFO("信息过时"),
    /** 结果不相关 */
    IRRELEVANT_RESULT("结果不相关"),
    /** 涉敏未标记 */
    SENSITIVE_NOT_FLAGGED("涉敏未标记"),
    /** 未涉敏标记 */
    NOT_SENSITIVE_FLAGGED("未涉敏标记"),

    // ===== 冲突检测 5 种 =====
    /** 事实冲突 */
    FACT_CONFLICT("事实冲突"),
    /** 语义冲突 */
    SEMANTIC_CONFLICT("语义冲突"),
    /** 版本冲突 */
    VERSION_CONFLICT("版本冲突"),
    /** 跨模态冲突 */
    CROSS_MODAL_CONFLICT("跨模态冲突"),
    /** 近重复冲突 */
    NEAR_DUPLICATE_CONFLICT("近重复冲突"),

    // ===== 知识切片 1 种 =====
    /** 切片不准确 */
    INACCURATE_SLICE("切片不准确");

    private final String label;

    BadcaseProblemType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    private static final Map<BadcaseSource, List<BadcaseProblemType>> SOURCE_TYPES;

    static {
        Map<BadcaseSource, List<BadcaseProblemType>> m = new EnumMap<>(BadcaseSource.class);
        List<BadcaseProblemType> qa = Collections.unmodifiableList(Arrays.asList(
                WRONG_ANSWER, CODE_ERROR, IRRELEVANT_ANSWER,
                OUTDATED_INFO, IRRELEVANT_RESULT, SENSITIVE_NOT_FLAGGED, NOT_SENSITIVE_FLAGGED));
        m.put(BadcaseSource.PAGE_QA, qa);
        m.put(BadcaseSource.MCP_QA, qa);
        m.put(BadcaseSource.CONFLICT_DETECTION, Collections.unmodifiableList(Arrays.asList(
                FACT_CONFLICT, SEMANTIC_CONFLICT, VERSION_CONFLICT,
                CROSS_MODAL_CONFLICT, NEAR_DUPLICATE_CONFLICT)));
        m.put(BadcaseSource.KNOWLEDGE_SLICE, Collections.singletonList(INACCURATE_SLICE));
        SOURCE_TYPES = Collections.unmodifiableMap(m);
    }

    /**
     * 返回某来源对应的问题类型列表（不可变）。
     */
    public static List<BadcaseProblemType> typesOf(BadcaseSource source) {
        return SOURCE_TYPES.getOrDefault(source, Collections.emptyList());
    }

    /**
     * 校验 source + problemType 组合是否合法。
     */
    public static boolean isValid(BadcaseSource source, BadcaseProblemType problemType) {
        if (source == null || problemType == null) {
            return false;
        }
        return typesOf(source).contains(problemType);
    }

    /**
     * 宽松解析：识别不到返回 null。
     */
    public static BadcaseProblemType fromCode(String code) {
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
