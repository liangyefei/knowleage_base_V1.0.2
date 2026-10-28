package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.repository.DictItemRepositoryImpl;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link DictItemRepositoryImpl}
 * <p>通过静态字段控制返回结果；@BeforeEach 重置。</p>
 */
public class MockDictItemRepository extends DictItemRepositoryImpl {

    /** findById 返回的实体（null = 不存在） */
    private static volatile DictItemEntity entityById;

    /** findByTypeAndValue 返回的实体（null = 不存在） */
    private static volatile DictItemEntity entityByTypeValue;

    /** maxSortOrder 返回的最大排序号（null = 无条目） */
    private static volatile Integer presetMaxSortOrder;

    /** persist 捕获的实体 */
    private static volatile DictItemEntity persistedEntity;

    /** update 捕获的实体 */
    private static volatile DictItemEntity updatedEntity;

    /** softDeleteByTypeCode 捕获的类型编码（null = 未调用） */
    private static volatile String lastSoftDeletedTypeCode;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDictItemRepository() {
        super(null);
    }

    public static void setEntityById(DictItemEntity entity) { entityById = entity; }
    public static void setEntityByTypeValue(DictItemEntity entity) { entityByTypeValue = entity; }
    public static void setPresetMaxSortOrder(Integer max) { presetMaxSortOrder = max; }
    public static DictItemEntity getPersistedEntity() { return persistedEntity; }
    public static DictItemEntity getUpdatedEntity() { return updatedEntity; }
    public static String getLastSoftDeletedTypeCode() { return lastSoftDeletedTypeCode; }

    public static void reset() {
        entityById = null;
        entityByTypeValue = null;
        presetMaxSortOrder = null;
        persistedEntity = null;
        updatedEntity = null;
        lastSoftDeletedTypeCode = null;
    }

    @Override
    public Uni<DictItemEntity> findById(DictItemId id) {
        DictItemEntity e = entityById;
        return Uni.createFrom().item(e);
    }

    @Override
    public Uni<DictItemEntity> findByTypeAndValue(String typeCode, String value) {
        DictItemEntity e = entityByTypeValue;
        return Uni.createFrom().item(e);
    }

    @Override
    public Uni<Integer> maxSortOrder(String typeCode) {
        return Uni.createFrom().item(presetMaxSortOrder);
    }

    @Override
    public Uni<DictItemEntity> persist(DictItemEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<DictItemEntity> update(DictItemEntity entity) {
        updatedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<Integer> softDeleteByTypeCode(String typeCode) {
        lastSoftDeletedTypeCode = typeCode;
        return Uni.createFrom().item(1);
    }

}
