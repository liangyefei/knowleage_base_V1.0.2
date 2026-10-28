package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.MessageFeedbackRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.mapper.MessageFeedbackMapper;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.MessageFeedbackPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 回答反馈仓储实现
 */
@ApplicationScoped
public class MessageFeedbackRepositoryImpl implements MessageFeedbackRepository {

    private final MessageFeedbackPanacheRepository panacheRepo;
    private final MessageFeedbackMapper mapper = MessageFeedbackMapper.INSTANCE;

    public MessageFeedbackRepositoryImpl(MessageFeedbackPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<MessageFeedbackEntity> findByMessageAndUser(String messageId, String userId) {
        return panacheRepo.find("messageId = ?1 and userId = ?2", messageId, userId)
                .firstResult()
                .onItem().transform(po -> po != null ? mapper.toDomain(po) : null);
    }

    @Override
    public Uni<List<MessageFeedbackEntity>> findByMessageIdsAndUser(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return panacheRepo.find("messageId in ?1 and userId = ?2", messageIds, userId)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<MessageFeedbackEntity> persist(MessageFeedbackEntity entity) {
        MessageFeedbackPO po = mapper.toPO(entity);
        return panacheRepo.findById(entity.getId())
                .onItem().transformToUni(existing -> {
                    if (existing != null) {
                        // UPSERT 更新分支：改托管实例字段，避免对 detached 副本 persist 重复插入
                        existing.setRating(po.getRating());
                        existing.setReasons(po.getReasons());
                        existing.setComment(po.getComment());
                        existing.setUpdatedAt(po.getUpdatedAt());
                        return Uni.createFrom().item(existing);
                    }
                    return panacheRepo.persist(po);
                })
                .map(ignored -> entity);
    }
}
