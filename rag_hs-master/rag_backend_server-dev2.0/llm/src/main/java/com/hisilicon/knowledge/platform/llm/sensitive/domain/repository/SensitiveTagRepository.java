package com.hisilicon.knowledge.platform.llm.sensitive.domain.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 敏感词标签仓储接口
 */
public interface SensitiveTagRepository {

    /**
     * 查询全部启用标签（status=1），供缓存重建回填标签信息
     */
    Uni<List<SensitiveTagEntity>> listActive();

    /**
     * 按 ID 查询标签（不过滤状态，软删记录也可读）
     */
    Uni<SensitiveTagEntity> findById(SensitiveTagId id);

    /**
     * 按文本标签查询未删除标签（status != 0，用于唯一性校验）
     */
    Uni<SensitiveTagEntity> findActiveByLabel(String textLabel);

    /**
     * 持久化标签
     */
    Uni<SensitiveTagEntity> persist(SensitiveTagEntity entity);

    /**
     * 更新标签（merge 持久化）
     */
    Uni<SensitiveTagEntity> update(SensitiveTagEntity entity);
}
