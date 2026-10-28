package com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.dict.repository.DictItemPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictItemRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.mapper.DictItemMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.Map;

/**
 * 字典条目仓储实现
 */
@ApplicationScoped
public class DictItemRepositoryImpl implements DictItemRepository {

    private final DictItemPanacheRepository panacheRepo;
    private final DictItemMapper mapper = Mappers.getMapper(DictItemMapper.class);

    public DictItemRepositoryImpl(DictItemPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<DictItemEntity> findById(DictItemId id) {
        return panacheRepo.findById(id.value())
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<DictItemEntity> findByTypeAndValue(String typeCode, String value) {
        return panacheRepo.find("typeCode = ?1 and value = ?2 and status = 1", typeCode, value)
                .firstResult()
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<Integer> maxSortOrder(String typeCode) {
        // 只统计活跃条目；SQL max 聚合无行时返回一行 null（调用方 +1 后为 1）
        return panacheRepo.getSession()
                .flatMap(session -> session
                        .createQuery("select max(sortOrder) from DictItemPO "
                                        + "where typeCode = ?1 and status = 1",
                                Integer.class)
                        .setParameter(1, typeCode)
                        .getSingleResult());
    }

    @Override
    public Uni<DictItemEntity> persist(DictItemEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<DictItemEntity> update(DictItemEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<Integer> softDeleteByTypeCode(String typeCode) {
        // Panache update 用 HQL 属性名（camelCase），软删类型下全部未删除条目（活跃+禁用）
        // status <> 0 覆盖 STATUS_ACTIVE(1) 与 STATUS_DISABLED(2)，已删除(0) 不重复写
        return panacheRepo.update("status = ?1 where typeCode = ?2 and status <> ?3",
                (short) 0, typeCode, (short) 0);
    }

}
