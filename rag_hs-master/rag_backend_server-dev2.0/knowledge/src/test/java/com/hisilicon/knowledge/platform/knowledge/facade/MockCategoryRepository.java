package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 手动 Mock — 替换 {@link CategoryRepositoryImpl}
 * <p>通过静态字段控制查询/持久化行为。</p>
 */
public class MockCategoryRepository extends CategoryRepositoryImpl {

    /** null = ID 不存在，非 null = ID 已存在（返回该实体，用于父类别校验） */
    private static volatile CategoryEntity entityById;

    /** 按 ID 精确返回（优先于 entityById），用于深度校验等需要祖先链的场景 */
    private static volatile Map<String, CategoryEntity> entityMap;

    /** null = 同级名称不存在，非 null = 同名已存在（返回该实体） */
    private static volatile CategoryEntity siblingEntity;

    /** findAllByKbId 的返回列表（null = 空列表） */
    private static volatile List<CategoryEntity> allCategories;

    /** 记录最近一次 persist 的实体，用于断言入库字段 */
    private static volatile CategoryEntity persistedEntity;

    /** 记录最近一次 update 的实体，用于断言更新字段 */
    private static volatile CategoryEntity updatedEntity;

    /** 记录所有 update 调用，用于级联删除等批量断言 */
    private static volatile List<CategoryEntity> updatedEntities = new ArrayList<>();

    /** maxSortOrder 返回预设值（null = 无同级，service +1 后为 1，首个类别从 1 开始） */
    private static volatile Integer maxSortOrderPreset;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockCategoryRepository() {
        super(null);
    }

    public static void setEntityById(CategoryEntity entity) {
        entityById = entity;
    }

    public static void setEntityMap(Map<String, CategoryEntity> map) {
        entityMap = map;
    }

    public static void setSiblingEntity(CategoryEntity entity) {
        siblingEntity = entity;
    }

    public static void setAllCategories(List<CategoryEntity> categories) {
        allCategories = categories;
    }

    public static void setMaxSortOrder(Integer maxSortOrder) {
        maxSortOrderPreset = maxSortOrder;
    }

    public static CategoryEntity getPersistedEntity() {
        return persistedEntity;
    }

    public static CategoryEntity getUpdatedEntity() {
        return updatedEntity;
    }

    public static List<CategoryEntity> getUpdatedEntities() {
        return updatedEntities;
    }

    public static void resetUpdatedEntities() {
        updatedEntities = new ArrayList<>();
    }

    @Override
    public Uni<CategoryEntity> findById(CategoryId id) {
        Map<String, CategoryEntity> map = entityMap;
        if (map != null && map.containsKey(id.value())) {
            return Uni.createFrom().item(map.get(id.value()));
        }
        CategoryEntity entity = entityById;
        if (entity == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<CategoryEntity> findByName(String kbId, String parentId, String name) {
        CategoryEntity entity = siblingEntity;
        // 与仓储实现一致：只返回未删除（status=1）的记录
        if (entity == null || !CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<CategoryEntity> persist(CategoryEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<CategoryEntity> update(CategoryEntity entity) {
        updatedEntity = entity;
        updatedEntities.add(entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<Integer> maxSortOrder(String kbId, String parentId) {
        return Uni.createFrom().item(maxSortOrderPreset);
    }

    @Override
    public Uni<List<CategoryEntity>> findAllByKbId(String kbId) {
        List<CategoryEntity> categories = allCategories;
        if (categories == null) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.createFrom().item(categories);
    }
}
