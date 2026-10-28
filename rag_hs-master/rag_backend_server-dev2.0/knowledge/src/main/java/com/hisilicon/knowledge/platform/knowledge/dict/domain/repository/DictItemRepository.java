package com.hisilicon.knowledge.platform.knowledge.dict.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import io.smallrye.mutiny.Uni;

/**
 * 字典条目仓储接口
 */
public interface DictItemRepository {

    /**
     * 按 ID 查询字典条目（不过滤状态，软删记录也可读）
     */
    Uni<DictItemEntity> findById(DictItemId id);

    /**
     * 按类型编码 + 存储值查询活跃条目（status=1，用于 value 唯一性校验）
     */
    Uni<DictItemEntity> findByTypeAndValue(String typeCode, String value);

    /**
     * 查询同类型下最大排序号（用于创建时 sortOrder = 最大 + 1）
     * <p>仅统计活跃条目；无活跃条目时返回 {@code null}。</p>
     */
    Uni<Integer> maxSortOrder(String typeCode);

    /**
     * 持久化字典条目
     */
    Uni<DictItemEntity> persist(DictItemEntity entity);

    /**
     * 更新字典条目（merge 持久化）
     */
    Uni<DictItemEntity> update(DictItemEntity entity);

    /**
     * 级联软删除某类型下全部未删除条目（活跃+禁用；类型删除时调用）
     */
    Uni<Integer> softDeleteByTypeCode(String typeCode);
}
