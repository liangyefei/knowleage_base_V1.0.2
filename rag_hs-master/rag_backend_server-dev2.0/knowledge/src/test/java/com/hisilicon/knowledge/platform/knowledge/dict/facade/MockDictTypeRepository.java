package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.repository.DictTypeRepositoryImpl;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link DictTypeRepositoryImpl}
 * <p>通过静态字段控制返回结果；@BeforeEach 重置。</p>
 */
public class MockDictTypeRepository extends DictTypeRepositoryImpl {

    /** findById 返回的实体（null = 不存在） */
    private static volatile DictTypeEntity entityById;

    /** findByCode 返回的实体（null = 不存在） */
    private static volatile DictTypeEntity entityByCode;

    /** persist 捕获的实体 */
    private static volatile DictTypeEntity persistedEntity;

    /** update 捕获的实体 */
    private static volatile DictTypeEntity updatedEntity;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDictTypeRepository() {
        super(null);
    }

    public static void setEntityById(DictTypeEntity entity) { entityById = entity; }
    public static void setEntityByCode(DictTypeEntity entity) { entityByCode = entity; }
    public static DictTypeEntity getPersistedEntity() { return persistedEntity; }
    public static DictTypeEntity getUpdatedEntity() { return updatedEntity; }

    public static void reset() {
        entityById = null;
        entityByCode = null;
        persistedEntity = null;
        updatedEntity = null;
    }

    @Override
    public Uni<DictTypeEntity> findById(DictTypeId id) {
        DictTypeEntity e = entityById;
        return Uni.createFrom().item(e);
    }

    @Override
    public Uni<DictTypeEntity> findByCode(String typeCode) {
        DictTypeEntity e = entityByCode;
        return Uni.createFrom().item(e);
    }

    @Override
    public Uni<DictTypeEntity> persist(DictTypeEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<DictTypeEntity> update(DictTypeEntity entity) {
        updatedEntity = entity;
        return Uni.createFrom().item(entity);
    }
}
