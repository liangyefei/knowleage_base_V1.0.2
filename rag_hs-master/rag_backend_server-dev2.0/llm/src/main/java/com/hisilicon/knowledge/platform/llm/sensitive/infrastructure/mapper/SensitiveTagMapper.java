package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：敏感词标签 PO ↔ Domain Entity
 */
@Mapper
public interface SensitiveTagMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId.of(po.getId()))")
    SensitiveTagEntity toDomain(SensitiveTagPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    SensitiveTagPO toPO(SensitiveTagEntity entity);
}
