package com.hisilicon.knowledge.platform.llm.domain.entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 没帮助原因枚举
 * <p>仅 UNHELPFUL 反馈携带；存储为逗号分隔多选（沿用 kb_ids 惯例），按用户勾选顺序去重保存。</p>
 */
public enum FeedbackReason {

    /** 回答错误 */
    WRONG_ANSWER,

    /** 答非所问 */
    OFF_TOPIC,

    /** 结果不相关 */
    IRRELEVANT,

    /** 代码错误 */
    CODE_ERROR,

    /** 信息过时 */
    OUTDATED;

    /**
     * 解析逗号分隔原因串，全部合法时返回去重后的逗号分隔规范串
     *
     * @return 规范串；null/空输入、含非法值或解析后为空返回 empty（调用方据此判错）
     */
    public static Optional<String> normalize(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Optional.empty();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String token : commaSeparated.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (Arrays.stream(values()).noneMatch(r -> r.name().equals(value))) {
                return Optional.empty();
            }
            ordered.add(value);
        }
        return ordered.isEmpty() ? Optional.empty() : Optional.of(String.join(",", ordered));
    }
}
