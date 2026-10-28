package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ImportBatchRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.ImportBatchMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 导入批次仓储实现（import_batches 表）
 */
@ApplicationScoped
public class ImportBatchRepositoryImpl implements ImportBatchRepository {

    private final ImportBatchPanacheRepository panacheRepo;
    private final ImportBatchMapper mapper = Mappers.getMapper(ImportBatchMapper.class);

    public ImportBatchRepositoryImpl(ImportBatchPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<ImportBatch> findById(ImportBatchId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<ImportBatch> persist(ImportBatch entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<ImportBatch> update(ImportBatch entity) {
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<List<ImportBatch>> findByCreatedBy(String createdBy) {
        return panacheRepo.find("createdBy = ?1 order by createdAt desc", createdBy)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }
}
