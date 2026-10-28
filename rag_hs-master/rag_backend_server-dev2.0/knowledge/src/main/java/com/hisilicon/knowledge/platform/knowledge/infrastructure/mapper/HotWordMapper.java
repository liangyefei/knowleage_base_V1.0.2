package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordEntity;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.HotWordPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity（热门词）
 */
@Mapper
public interface HotWordMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordId.of(po.getId()))")
    HotWordEntity toDomain(HotWordPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    HotWordPO toPO(HotWordEntity entity);
}
