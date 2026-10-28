package com.hisilicon.knowledge.platform.llm.domain.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 回答反馈仓储接口
 */
public interface MessageFeedbackRepository {

    /**
     * 查询用户对某条消息的反馈（一消息一用户一行；无反馈返回 null）
     */
    Uni<MessageFeedbackEntity> findByMessageAndUser(String messageId, String userId);

    /**
     * 按消息 id 批量查询用户反馈（历史回显回填用）
     */
    Uni<List<MessageFeedbackEntity>> findByMessageIdsAndUser(List<String> messageIds, String userId);

    /**
     * 持久化反馈（首次插入 / 更新覆盖共用）
     */
    Uni<MessageFeedbackEntity> persist(MessageFeedbackEntity entity);
}
