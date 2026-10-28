package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 检测任务进度 DTO。
 */
public record ConflictJobProgress(
        String jobId,
        String batchNo,
        String status,
        Integer totalDocuments,
        Integer scannedChunks,
        Integer newConflictCount,
        Integer hitConflictCount,
        Integer failedItems,
        String error) {
}
