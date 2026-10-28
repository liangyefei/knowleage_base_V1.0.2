package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.ConversationRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动 Mock — 替换 {@link ConversationRepositoryImpl}
 */
public class MockConversationRepository extends ConversationRepositoryImpl {

    /** null = 不存在 */
    private static volatile ConversationEntity entityById;
    /** 最近一次 update 的实体 */
    private static volatile ConversationEntity updatedEntity;
    /** 最近一次 persist 的实体 */
    private static volatile ConversationEntity persistedEntity;
    /** findActiveByAccount 返回列表 */
    private static volatile List<ConversationEntity> activeList;
    /** countActiveByAccount 返回值 */
    private static volatile long activeCount;
    /** 最近一次删除的 ID */
    private static volatile String deletedId;

    public MockConversationRepository() {
        super(null);
    }

    public static void setEntityById(ConversationEntity entity) {
        entityById = entity;
    }

    public static ConversationEntity getUpdatedEntity() {
        return updatedEntity;
    }

    public static ConversationEntity getPersistedEntity() {
        return persistedEntity;
    }

    public static void setActiveList(List<ConversationEntity> list) {
        activeList = list;
    }

    public static void setActiveCount(long count) {
        activeCount = count;
    }

    public static String getDeletedId() {
        return deletedId;
    }

    public static void reset() {
        entityById = null;
        updatedEntity = null;
        persistedEntity = null;
        activeList = null;
        activeCount = 0;
        deletedId = null;
    }

    @Override
    public Uni<ConversationEntity> findById(ConversationId id) {
        if (entityById == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entityById);
    }

    @Override
    public Uni<ConversationEntity> persist(ConversationEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<ConversationEntity> update(ConversationEntity entity) {
        updatedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<List<ConversationEntity>> findActiveByAccount(String account, int offset, int limit) {
        if (activeList == null) {
            return Uni.createFrom().item(List.of());
        }
        List<ConversationEntity> result = new ArrayList<>(activeList);
        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Long> countActiveByAccount(String account) {
        return Uni.createFrom().item(activeCount);
    }

    @Override
    public Uni<Void> delete(ConversationId id) {
        deletedId = id.value();
        return Uni.createFrom().voidItem();
    }
}
