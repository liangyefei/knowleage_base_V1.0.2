package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictScanJobRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.ConflictScanJobMapper;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictScanJobPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.Optional;

/**
 * 检测任务仓储实现。
 */
@ApplicationScoped
public class ConflictScanJobRepositoryImpl implements ConflictScanJobRepository {

    private final ConflictScanJobPanacheRepository panacheRepo;
    private final ConflictScanJobMapper mapper = Mappers.getMapper(ConflictScanJobMapper.class);

    public ConflictScanJobRepositoryImpl(ConflictScanJobPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Optional<ConflictScanJob>> findById(ConflictScanJobId id) {
        return panacheRepo.findById(id.value())
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Boolean> existsRunningManualByScopeFingerprint(String scopeFingerprint) {
        if (scopeFingerprint == null) {
            return Uni.createFrom().item(Boolean.FALSE);
        }
        return panacheRepo.count("scopeFingerprint = ?1 and status in ?2",
                        scopeFingerprint, java.util.List.of("QUEUED", "RUNNING"))
                .map(count -> count > 0);
    }

    @Override
    public Uni<Optional<ConflictScanJob>> findRunning() {
        return panacheRepo.findRunning().map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Optional<ConflictScanJob>> findNextQueued() {
        return panacheRepo.findNextQueued().map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Long> countByBatchNoPrefix(String prefix) {
        return panacheRepo.count("batchNo like ?1", prefix + "%");
    }

    @Override
    public Uni<ConflictScanJob> persist(ConflictScanJob entity) {
        return panacheRepo.persist(mapper.toPO(entity)).map(po -> entity);
    }

    @Override
    public Uni<ConflictScanJob> merge(ConflictScanJob entity) {
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }
}
