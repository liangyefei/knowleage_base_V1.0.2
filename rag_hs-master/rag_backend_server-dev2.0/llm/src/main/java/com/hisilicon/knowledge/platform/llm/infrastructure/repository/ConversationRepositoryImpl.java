package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.mapper.ConversationMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 会话仓储实现
 */
@ApplicationScoped
public class ConversationRepositoryImpl implements ConversationRepository {

    private final ConversationPanacheRepository panacheRepo;
    private final ConversationMapper mapper = ConversationMapper.INSTANCE;

    public ConversationRepositoryImpl(ConversationPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<ConversationEntity> findById(ConversationId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<ConversationEntity> persist(ConversationEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<ConversationEntity> update(ConversationEntity entity) {
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<List<ConversationEntity>> findActiveByAccount(String account, int offset, int limit) {
        return panacheRepo.find("account = ?1 and status = 'ACTIVE' order by pinned desc, createdAt desc", account)
                .page(offset / Math.max(limit, 1), limit)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Long> countActiveByAccount(String account) {
        return panacheRepo.count("account = ?1 and status = 'ACTIVE'", account);
    }

    @Override
    public Uni<Void> delete(ConversationId id) {
        return panacheRepo.deleteById(id.value())
                .map(ignored -> null);
    }
}
