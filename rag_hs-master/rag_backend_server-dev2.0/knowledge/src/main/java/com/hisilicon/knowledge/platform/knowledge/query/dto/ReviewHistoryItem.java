package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 审核历史条目 DTO
 * <p>对应 review_history 表的一条记录，展示文档生命周期中每次状态变更的审计信息。</p>
 *
 * @param id         记录 ID
 * @param action     生命周期动作（SUBMIT_REVIEW / CANCEL_REVIEW / APPROVE / REJECT / ARCHIVE / DEPRECATE 等）
 * @param reviewerId 操作人用户 ID
 * @param fromStatus 变更前状态
 * @param toStatus   变更后状态
 * @param version    动作发生时文档的版本号
 * @param comment     审核意见/操作说明（可空）
 * @param createdAt  动作时间
 */
public record ReviewHistoryItem(String id, String action, String reviewerId,
                                String fromStatus, String toStatus,
                                String version, String comment,
                                LocalDateTime createdAt) {
}
