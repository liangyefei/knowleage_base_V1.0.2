package com.hisilicon.knowledge.platform.system.infrastructure.mapper;

import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.dto.RoleDTO;
import com.hisilicon.knowledge.platform.system.infrastructure.po.RolePO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：角色 PO ↔ Domain Entity ↔ DTO
 */
@Mapper
public interface RoleMapper {

    @Mapping(target = "id",
            expression = "java(com.hisilicon.knowledge.platform.system.domain.entity.RoleId.of(po.getId()))")
    RoleEntity toDomain(RolePO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    RolePO toPO(RoleEntity entity);

    @Mapping(target = "id", source = "id")
    RoleDTO toDto(RolePO po);
}
