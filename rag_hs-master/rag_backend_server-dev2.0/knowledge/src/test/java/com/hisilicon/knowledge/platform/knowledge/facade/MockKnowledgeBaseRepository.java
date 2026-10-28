package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBaseRepositoryImpl;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link KnowledgeBaseRepositoryImpl}
 * <p>通过静态字段控制查询/持久化行为。</p>
 */
public class MockKnowledgeBaseRepository extends KnowledgeBaseRepositoryImpl {

    /** null = 名称不存在，非 null = 名称已存在（返回该实体） */
    private static volatile KnowledgeBaseEntity existingEntity;

    /** null = ID 不存在，非 null = ID 已存在（返回该实体） */
    private static volatile KnowledgeBaseEntity entityById;

    /** 记录最近一次 persist 的实体，用于断言入库字段 */
    private static volatile KnowledgeBaseEntity persistedEntity;

    /** 记录最近一次 update 的实体，用于断言更新字段 */
    private static volatile KnowledgeBaseEntity updatedEntity;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockKnowledgeBaseRepository() {
        super(null);
    }

    public static void setExistingEntity(KnowledgeBaseEntity entity) {
        existingEntity = entity;
    }

    public static void setEntityById(KnowledgeBaseEntity entity) {
        entityById = entity;
    }

    public static KnowledgeBaseEntity getPersistedEntity() {
        return persistedEntity;
    }

    public static KnowledgeBaseEntity getUpdatedEntity() {
        return updatedEntity;
    }

    public static void resetUpdatedEntity() {
        updatedEntity = null;
    }

    public static void resetPersistedEntity() {
        persistedEntity = null;
    }

    @Override
    public Uni<KnowledgeBaseEntity> findById(KnowledgeBaseId id) {
        KnowledgeBaseEntity entity = entityById;
        if (entity == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<KnowledgeBaseEntity> findByIdForUpdate(KnowledgeBaseId id) {
        // 单测无真实锁：与 findById 同语义
        return findById(id);
    }

    @Override
    public Uni<KnowledgeBaseEntity> findByName(String name) {
        KnowledgeBaseEntity entity = existingEntity;
        // 与仓储实现一致：只返回未删除（status=1）的记录
        if (entity == null || !KnowledgeBaseEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<KnowledgeBaseEntity> persist(KnowledgeBaseEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<KnowledgeBaseEntity> update(KnowledgeBaseEntity entity) {
        updatedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    /** 最近一次 markProcessing 的 KB ID（relay 发送 KB_CREATED 成功后调用） */
    private static volatile String markedProcessingKbId;
    private static volatile int markProcessingCallCount;

    public static void resetMarkProcessing() {
        markedProcessingKbId = null;
        markProcessingCallCount = 0;
    }

    public static String getMarkedProcessingKbId() {
        return markedProcessingKbId;
    }

    public static int getMarkProcessingCallCount() {
        return markProcessingCallCount;
    }

    @Override
    public Uni<Void> markProcessing(KnowledgeBaseId id) {
        markedProcessingKbId = id.value();
        markProcessingCallCount++;
        return Uni.createFrom().voidItem();
    }
}
