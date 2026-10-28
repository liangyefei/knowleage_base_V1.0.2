package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import io.smallrye.mutiny.Uni;

/**
 * 模型回调幂等去重仓储
 * <p>
 * 消费回调时在同一事务内 {@link #existsByMessageId} 查重、处理成功后 {@link #record}
 * 落库 messageId，保证同一 messageId 只被处理一次。
 * </p>
 */
public interface KbCallbackProcessedRepository {

    /** 该 messageId 是否已被处理过（幂等查重） */
    Uni<Boolean> existsByMessageId(String messageId);

    /**
     * 记录一条已处理的回调（兼审计）
     *
     * @param messageId 模型回调信封 messageId
     * @param documentId 文档 ID（KB 级回调为 null）
     * @param kbId       知识库 ID
     * @param eventType  PROCESSING_COMPLETED / PROCESSING_FAILED
     * @param status     落库的处理状态 COMPLETED / FAILED
     */
    Uni<Void> record(String messageId, String documentId, String kbId,
                     String eventType, String status);
}
