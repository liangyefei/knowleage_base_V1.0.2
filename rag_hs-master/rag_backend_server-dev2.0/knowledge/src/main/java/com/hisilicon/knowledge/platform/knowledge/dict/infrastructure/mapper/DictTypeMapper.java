package com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.mapper;

import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：字典类型 PO ↔ Domain Entity
 */
@Mapper
public interface DictTypeMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId.of(po.getId()))")
    DictTypeEntity toDomain(DictTypePO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    DictTypePO toPO(DictTypeEntity entity);
}
