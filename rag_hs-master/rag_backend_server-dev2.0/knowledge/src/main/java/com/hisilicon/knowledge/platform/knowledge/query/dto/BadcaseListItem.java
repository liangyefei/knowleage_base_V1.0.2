package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * Badcase 列表项。
 */
public record BadcaseListItem(
        String id,
        String ticketNo,
        String source,
        String sourceLabel,
        String problemType,
        String problemTypeLabel,
        String description,
        String status,
        String statusLabel,
        String kbId,
        String kbName,
        String submittedByName,
        LocalDateTime submittedAt,
        String latestHandlingRemark,
        String latestHandledByName,
        LocalDateTime latestHandledAt
) {
}
