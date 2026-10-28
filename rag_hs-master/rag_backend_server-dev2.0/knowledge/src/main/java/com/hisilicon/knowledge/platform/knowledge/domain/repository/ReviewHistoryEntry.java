package com.hisilicon.knowledge.platform.knowledge.domain.repository;

/**
 * 审核历史写入命令（封装 {@link ReviewHistoryRepository#save} 的入参，避免 7 参方法签名）
 *
 * @param documentId 文档 ID
 * @param reviewerId 操作人用户 ID
 * @param action     生命周期动作（SUBMIT_REVIEW / CANCEL_REVIEW / APPROVE / REJECT / ARCHIVE / DEPRECATE 等）
 * @param fromStatus 变更前状态
 * @param toStatus   变更后状态
 * @param version    动作发生时文档的版本号（current_version 快照）
 * @param comment    审核意见/操作说明（可空）
 */
public record ReviewHistoryEntry(String documentId, String reviewerId, String action,
                                 String fromStatus, String toStatus, String version, String comment) {
}
