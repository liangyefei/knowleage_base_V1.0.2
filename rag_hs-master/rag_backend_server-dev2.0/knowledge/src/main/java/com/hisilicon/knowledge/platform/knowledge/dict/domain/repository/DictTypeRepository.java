package com.hisilicon.knowledge.platform.knowledge.dict.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import io.smallrye.mutiny.Uni;

/**
 * 字典类型仓储接口
 */
public interface DictTypeRepository {

    /**
     * 按 ID 查询字典类型（不过滤状态，软删记录也可读）
     */
    Uni<DictTypeEntity> findById(DictTypeId id);

    /**
     * 按类型编码查询活跃字典类型（status=1，用于唯一性校验）
     */
    Uni<DictTypeEntity> findByCode(String typeCode);

    /**
     * 持久化字典类型
     */
    Uni<DictTypeEntity> persist(DictTypeEntity entity);

    /**
     * 更新字典类型（merge 持久化）
     */
    Uni<DictTypeEntity> update(DictTypeEntity entity);
}
