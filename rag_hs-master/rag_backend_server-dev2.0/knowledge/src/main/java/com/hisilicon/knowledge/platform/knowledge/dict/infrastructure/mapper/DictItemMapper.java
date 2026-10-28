package com.hisilicon.knowledge.platform.knowledge.dict.infrastructure.mapper;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：字典条目 PO ↔ Domain Entity
 */
@Mapper
public interface DictItemMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId.of(po.getId()))")
    DictItemEntity toDomain(DictItemPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    DictItemPO toPO(DictItemEntity entity);
}
