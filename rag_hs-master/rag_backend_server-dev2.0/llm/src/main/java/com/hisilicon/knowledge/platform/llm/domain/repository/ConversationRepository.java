package com.hisilicon.knowledge.platform.llm.domain.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 会话仓储接口
 */
public interface ConversationRepository {

    /**
     * 按 ID 查询会话（不过滤状态，由服务层判断）
     */
    Uni<ConversationEntity> findById(ConversationId id);

    /**
     * 持久化会话
     */
    Uni<ConversationEntity> persist(ConversationEntity entity);

    /**
     * 更新会话（merge 持久化）
     */
    Uni<ConversationEntity> update(ConversationEntity entity);

    /**
     * 分页查询账号下的活跃会话（created_at 降序）
     */
    Uni<List<ConversationEntity>> findActiveByAccount(String account, int offset, int limit);

    /**
     * 统计账号下的活跃会话数
     */
    Uni<Long> countActiveByAccount(String account);

    /**
     * 物理删除会话
     */
    Uni<Void> delete(ConversationId id);
}
