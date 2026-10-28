package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecord;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictRecordRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.ConflictRecordMapper;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.Optional;

/**
 * 冲突记录仓储实现。
 */
@ApplicationScoped
public class ConflictRecordRepositoryImpl implements ConflictRecordRepository {

    private final ConflictRecordPanacheRepository panacheRepo;
    private final ConflictRecordMapper mapper = Mappers.getMapper(ConflictRecordMapper.class);

    public ConflictRecordRepositoryImpl(ConflictRecordPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Optional<ConflictRecord>> findById(ConflictRecordId id) {
        return panacheRepo.findById(id.value())
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Optional<ConflictRecord>> findByKbAndFingerprint(String kbId, String fingerprint) {
        return panacheRepo.find("kbId = ?1 and fingerprint = ?2", kbId, fingerprint)
                .firstResult()
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<ConflictRecord> persist(ConflictRecord entity) {
        return panacheRepo.persist(mapper.toPO(entity)).map(po -> entity);
    }

    @Override
    public Uni<ConflictRecord> merge(ConflictRecord entity) {
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }
}
