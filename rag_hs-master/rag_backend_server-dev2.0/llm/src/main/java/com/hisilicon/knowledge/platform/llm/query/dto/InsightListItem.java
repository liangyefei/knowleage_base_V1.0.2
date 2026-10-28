package com.hisilicon.knowledge.platform.llm.query.dto;

import java.time.LocalDateTime;

/**
 * 问答洞察列表项。
 */
public record InsightListItem(
        String id,
        String question,
        String answer,
        String category,
        String categoryLabel,
        Integer confidence,
        Boolean isSensitive,
        String hitWords,
        String rating,
        LocalDateTime createdAt) {
}
