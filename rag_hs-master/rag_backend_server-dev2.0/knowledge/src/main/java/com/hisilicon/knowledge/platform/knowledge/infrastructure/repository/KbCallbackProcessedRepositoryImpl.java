package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.repository.KbCallbackProcessedRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KbCallbackProcessedPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;

/**
 * 回调幂等去重仓储实现
 */
@ApplicationScoped
public class KbCallbackProcessedRepositoryImpl implements KbCallbackProcessedRepository {

    private final KbCallbackProcessedPanacheRepository panacheRepo;

    public KbCallbackProcessedRepositoryImpl(KbCallbackProcessedPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Boolean> existsByMessageId(String messageId) {
        return panacheRepo.count("messageId = ?1", messageId)
                .map(c -> c != null && c > 0);
    }

    @Override
    public Uni<Void> record(String messageId, String documentId, String kbId,
                            String eventType, String status) {
        KbCallbackProcessedPO po = new KbCallbackProcessedPO();
        po.setMessageId(messageId);
        po.setDocumentId(documentId);
        po.setKbId(kbId);
        po.setEventType(eventType);
        po.setStatus(status);
        po.setReceivedAt(LocalDateTime.now());
        return panacheRepo.persist(po).replaceWithVoid();
    }
}
