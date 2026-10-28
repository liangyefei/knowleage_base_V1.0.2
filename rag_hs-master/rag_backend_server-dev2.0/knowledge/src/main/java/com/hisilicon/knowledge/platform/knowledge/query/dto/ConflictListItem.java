package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 冲突列表项 DTO。
 * <p>需求简化：无 status 字段。</p>
 */
public record ConflictListItem(
        String id,
        String topic,
        String type,
        String severity,
        Integer locationCount,
        LocalDateTime detectedStartedAt,
        LocalDateTime detectedFinishedAt,
        boolean badcaseSubmitted,
        String badcaseSubmittedByName) {
}
