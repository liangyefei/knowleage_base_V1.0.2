package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveLexiconPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：敏感词词库 PO ↔ Domain Entity
 */
@Mapper
public interface SensitiveLexiconMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId.of(po.getId()))")
    @Mapping(target = "tagId", expression = "java(com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId.of(po.getTagId()))")
    SensitiveLexiconEntity toDomain(SensitiveLexiconPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    @Mapping(target = "tagId", expression = "java(entity.getTagId().value())")
    SensitiveLexiconPO toPO(SensitiveLexiconEntity entity);
}
