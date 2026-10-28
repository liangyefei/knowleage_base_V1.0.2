package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity
 */
@Mapper
public interface CategoryMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId.of(po.getId()))")
    CategoryEntity toDomain(CategoryPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    CategoryPO toPO(CategoryEntity entity);
}
