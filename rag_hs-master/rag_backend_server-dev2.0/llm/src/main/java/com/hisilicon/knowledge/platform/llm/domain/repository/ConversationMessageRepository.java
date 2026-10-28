package com.hisilicon.knowledge.platform.llm.domain.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 对话消息仓储接口
 */
public interface ConversationMessageRepository {

    /**
     * 按 id 查询单条消息（反馈目标校验用；不存在返回 null）
     */
    Uni<ConversationMessageEntity> findById(String messageId);

    /**
     * 持久化消息
     */
    Uni<ConversationMessageEntity> persist(ConversationMessageEntity entity);

    /**
     * 分页查询会话消息（created_at 升序）
     */
    Uni<List<ConversationMessageEntity>> findByConversationId(ConversationId conversationId, int offset, int limit);

    /**
     * 统计会话消息数
     */
    Uni<Long> countByConversationId(ConversationId conversationId);

    /**
     * 查询会话最近 N 条消息（created_at 升序，用于模型服务 history）
     */
    Uni<List<ConversationMessageEntity>> findRecent(ConversationId conversationId, int limit);

    /**
     * 物理删除会话全部消息
     */
    Uni<Void> deleteByConversationId(ConversationId conversationId);
}
