package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.IdGenerator;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryEntry;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ReviewHistoryPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;

/**
 * 审核历史仓储实现
 */
@ApplicationScoped
public class ReviewHistoryRepositoryImpl implements ReviewHistoryRepository {

    private final ReviewHistoryPanacheRepository panacheRepo;

    public ReviewHistoryRepositoryImpl(ReviewHistoryPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Void> save(ReviewHistoryEntry entry) {
        ReviewHistoryPO po = new ReviewHistoryPO();
        po.setId(IdGenerator.reviewHistory());
        po.setDocumentId(entry.documentId());
        po.setReviewerId(entry.reviewerId());
        po.setAction(entry.action());
        po.setFromStatus(entry.fromStatus());
        po.setToStatus(entry.toStatus());
        po.setVersion(entry.version());
        po.setComment(entry.comment());
        po.setCreatedAt(LocalDateTime.now());
        return panacheRepo.persist(po).replaceWithVoid();
    }
}
