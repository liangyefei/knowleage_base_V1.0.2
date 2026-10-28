package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity
 */
@Mapper
public interface KnowledgeBaseMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId.of(po.getId()))")
    KnowledgeBaseEntity toDomain(KnowledgeBasePO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    KnowledgeBasePO toPO(KnowledgeBaseEntity entity);
}
