package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Badcase 详情。
 */
public record BadcaseDetailDTO(
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
        List<RelatedDoc> relatedDocs,
        String submittedByName,
        LocalDateTime submittedAt,
        Map<String, Object> sourcePayload,
        HandlingDTO handling
) {

    /**
     * 涉及文档。
     */
    public record RelatedDoc(String docId, String docName) {
    }

    /**
     * 最新一条处理记录。
     */
    public record HandlingDTO(
            String action,
            String actionLabel,
            String remark,
            String handledByName,
            LocalDateTime handledAt
    ) {
    }
}
