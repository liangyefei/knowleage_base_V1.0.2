package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveTagRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手动 Mock — 替换 {@link SensitiveTagRepositoryImpl}
 * <p>按标签名（textLabel）与单记录两种口径均支持：导入多分类按名称查标签。</p>
 */
public class MockSensitiveTagRepository extends SensitiveTagRepositoryImpl {

    private static volatile SensitiveTagEntity entityById;
    /** 标签名 → 标签（findActiveByLabel 内存口径） */
    private static final Map<String, SensitiveTagEntity> entitiesByLabel = new LinkedHashMap<>();
    private static volatile SensitiveTagEntity persistedEntity;
    private static volatile SensitiveTagEntity updatedEntity;
    private static volatile List<SensitiveTagEntity> activeTags = List.of();

    public MockSensitiveTagRepository() {
        super(null);
    }

    public static void setEntityById(SensitiveTagEntity entity) { entityById = entity; }

    public static void setEntityByLabel(SensitiveTagEntity entity) {
        entitiesByLabel.put(entity.getTextLabel(), entity);
    }

    public static void setActiveTags(List<SensitiveTagEntity> tags) { activeTags = tags; }

    public static SensitiveTagEntity getPersistedEntity() { return persistedEntity; }

    public static SensitiveTagEntity getUpdatedEntity() { return updatedEntity; }

    public static void reset() {
        entityById = null;
        entitiesByLabel.clear();
        persistedEntity = null;
        updatedEntity = null;
        activeTags = List.of();
    }

    @Override
    public Uni<SensitiveTagEntity> findById(SensitiveTagId id) {
        return Uni.createFrom().item(entityById);
    }

    @Override
    public Uni<SensitiveTagEntity> findActiveByLabel(String textLabel) {
        return Uni.createFrom().item(entitiesByLabel.get(textLabel));
    }

    @Override
    public Uni<List<SensitiveTagEntity>> listActive() {
        return Uni.createFrom().item(activeTags);
    }

    @Override
    public Uni<SensitiveTagEntity> persist(SensitiveTagEntity entity) {
        persistedEntity = entity;
        entitiesByLabel.put(entity.getTextLabel(), entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<SensitiveTagEntity> update(SensitiveTagEntity entity) {
        updatedEntity = entity;
        entitiesByLabel.put(entity.getTextLabel(), entity);
        return Uni.createFrom().item(entity);
    }
}
