package com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictTypeRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.mapper.DictTypeMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

/**
 * 字典类型仓储实现
 */
@ApplicationScoped
public class DictTypeRepositoryImpl implements DictTypeRepository {

    private final DictTypePanacheRepository panacheRepo;
    private final DictTypeMapper mapper = Mappers.getMapper(DictTypeMapper.class);

    public DictTypeRepositoryImpl(DictTypePanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<DictTypeEntity> findById(DictTypeId id) {
        return panacheRepo.findById(id.value())
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<DictTypeEntity> findByCode(String typeCode) {
        return panacheRepo.find("typeCode = ?1 and status = 1", typeCode)
                .firstResult()
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<DictTypeEntity> persist(DictTypeEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<DictTypeEntity> update(DictTypeEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }
}
