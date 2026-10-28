package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.UploadSessionMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

/**
 * tus 上传会话仓储实现（upload_sessions 表）
 */
@ApplicationScoped
public class UploadSessionRepositoryImpl implements UploadSessionRepository {

    private final UploadSessionPanacheRepository panacheRepo;
    private final UploadSessionMapper mapper = Mappers.getMapper(UploadSessionMapper.class);

    public UploadSessionRepositoryImpl(UploadSessionPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<UploadSession> findById(UploadSessionId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<UploadSession> persist(UploadSession entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<UploadSession> update(UploadSession entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<List<UploadSession>> findIncompleteByBatch(String batchId) {
        return panacheRepo.find("batchId = ?1 and status in ('CREATED','UPLOADING')", batchId)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<UploadSession>> findExpiredIncomplete(LocalDateTime now) {
        return panacheRepo.find("status in ('CREATED','UPLOADING') and expiresAt < ?1", now)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Integer> bumpEntriesDone(String sessionId) {
        // bulk update：entries_done = entries_done + 1（实体字段名），避免 fetch 整实体
        return panacheRepo.update("entriesDone = entriesDone + 1, updatedAt = ?1 where id = ?2",
                LocalDateTime.now(), sessionId);
    }

    @Override
    public Uni<List<UploadSession>> findStaleProcessing(LocalDateTime threshold) {
        return panacheRepo.find("status = 'PROCESSING' and updatedAt < ?1", threshold)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<UploadSession>> findActiveByKbId(String kbId) {
        return panacheRepo.find("kbId = ?1 and status in ('CREATED','UPLOADING','PROCESSING')", kbId)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<UploadSession>> findActiveByTargetDocument(String targetDocumentId, String operatorId) {
        return panacheRepo.find("targetDocumentId = ?1 and createdBy = ?2 "
                        + "and status in ('CREATED','UPLOADING')",
                        targetDocumentId, operatorId)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }
}
