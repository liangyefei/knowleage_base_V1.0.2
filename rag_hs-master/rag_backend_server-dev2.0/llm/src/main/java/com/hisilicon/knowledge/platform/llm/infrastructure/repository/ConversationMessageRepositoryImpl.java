package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.mapper.ConversationMessageMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 对话消息仓储实现
 */
@ApplicationScoped
public class ConversationMessageRepositoryImpl implements ConversationMessageRepository {

    private final ConversationMessagePanacheRepository panacheRepo;
    private final ConversationMessageMapper mapper = ConversationMessageMapper.INSTANCE;

    public ConversationMessageRepositoryImpl(ConversationMessagePanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<ConversationMessageEntity> findById(String messageId) {
        return panacheRepo.findById(messageId)
                .onItem().transform(po -> po != null ? mapper.toDomain(po) : null);
    }

    @Override
    public Uni<ConversationMessageEntity> persist(ConversationMessageEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<List<ConversationMessageEntity>> findByConversationId(ConversationId conversationId, int offset, int limit) {
        return panacheRepo.find("conversationId = ?1 order by createdAt asc", conversationId.value())
                .page(offset / Math.max(limit, 1), limit)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Long> countByConversationId(ConversationId conversationId) {
        return panacheRepo.count("conversationId = ?1", conversationId.value());
    }

    @Override
    public Uni<List<ConversationMessageEntity>> findRecent(ConversationId conversationId, int limit) {
        // 取最近 N 条：先按时间倒序取 N 条，再在内存反转成时间正序
        return panacheRepo.find("conversationId = ?1 order by createdAt desc", conversationId.value())
                .page(0, limit)
                .list()
                .map(pos -> {
                    List<ConversationMessageEntity> entities = new java.util.ArrayList<>(
                            pos.stream().map(mapper::toDomain).toList());
                    java.util.Collections.reverse(entities);
                    return entities;
                });
    }

    @Override
    public Uni<Void> deleteByConversationId(ConversationId conversationId) {
        return panacheRepo.delete("conversationId = ?1", conversationId.value())
                .map(ignored -> null);
    }
}
