package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：Document PO ↔ Domain Entity
 */
@Mapper
public interface DocumentMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId.of(po.getId()))")
    DocumentEntity toDomain(DocumentPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    DocumentPO toPO(DocumentEntity entity);
}
