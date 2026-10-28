package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 类别仓储接口
 */
public interface CategoryRepository {

    /**
     * 按 ID 查询类别
     */
    Uni<CategoryEntity> findById(CategoryId id);

    /**
     * 按同级名称查询类别（用于同级名称唯一性校验）
     * <p>只查未删除（status=1）的记录；parentId 为 null 表示根类别层级。</p>
     */
    Uni<CategoryEntity> findByName(String kbId, String parentId, String name);

    /**
     * 查询同级最大排序号（用于创建时 sortOrder = 同级最大 + 1）
     * <p>parentId 为 null 表示根类别层级；无同级活跃记录时返回 {@code null}。</p>
     */
    Uni<Integer> maxSortOrder(String kbId, String parentId);

    /**
     * 持久化类别
     */
    Uni<CategoryEntity> persist(CategoryEntity entity);

    /**
     * 更新类别（merge 持久化）
     */
    Uni<CategoryEntity> update(CategoryEntity entity);

    /**
     * 查询知识库下所有未删除类别（用于树形结构校验：深度、循环引用）
     */
    Uni<List<CategoryEntity>> findAllByKbId(String kbId);
}
