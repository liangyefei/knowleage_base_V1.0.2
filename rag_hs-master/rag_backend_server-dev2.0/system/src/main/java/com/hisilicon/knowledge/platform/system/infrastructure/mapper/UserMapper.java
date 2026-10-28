package com.hisilicon.knowledge.platform.system.infrastructure.mapper;

import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity
 */
@Mapper
public interface UserMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.system.domain.entity.UserId.of(po.getId()))")
    UserEntity toDomain(UserPO po);
}
