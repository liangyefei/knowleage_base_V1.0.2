package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.CategoryMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 类别仓储实现
 */
@ApplicationScoped
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryPanacheRepository panacheRepo;
    private final CategoryMapper mapper = Mappers.getMapper(CategoryMapper.class);

    public CategoryRepositoryImpl(CategoryPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<CategoryEntity> findById(CategoryId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<CategoryEntity> findByName(String kbId, String parentId, String name) {
        // 只查未删除（status=1），软删除的记录不占用同级名称，允许重建同名类别
        if (parentId == null) {
            return panacheRepo.find("kbId = ?1 and parentId is null and status = 1 and name = ?2",
                            kbId, name)
                    .firstResult()
                    .map(mapper::toDomain);
        }
        return panacheRepo.find("kbId = ?1 and parentId = ?2 and status = 1 and name = ?3",
                        kbId, parentId, name)
                .firstResult()
                .map(mapper::toDomain);
    }

    @Override
    public Uni<Integer> maxSortOrder(String kbId, String parentId) {
        // 只统计未删除（status=1）的同级类别；SQL max 聚合无行时返回一行 null（调用方 +1 后为 0）
        if (parentId == null) {
            return panacheRepo.getSession()
                    .flatMap(session -> session
                            .createQuery("select max(sortOrder) from CategoryPO "
                                            + "where kbId = ?1 and parentId is null and status = 1",
                                    Integer.class)
                            .setParameter(1, kbId)
                            .getSingleResult());
        }
        return panacheRepo.getSession()
                .flatMap(session -> session
                        .createQuery("select max(sortOrder) from CategoryPO "
                                        + "where kbId = ?1 and parentId = ?2 and status = 1",
                                Integer.class)
                        .setParameter(1, kbId)
                        .setParameter(2, parentId)
                        .getSingleResult());
    }

    @Override
    public Uni<CategoryEntity> persist(CategoryEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<CategoryEntity> update(CategoryEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<List<CategoryEntity>> findAllByKbId(String kbId) {
        return panacheRepo.find("kbId = ?1 and status = 1", kbId)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }
}
