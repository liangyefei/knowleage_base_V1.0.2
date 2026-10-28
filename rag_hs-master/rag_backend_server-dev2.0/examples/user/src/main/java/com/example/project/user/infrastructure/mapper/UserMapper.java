package com.example.project.user.infrastructure.mapper;

import com.example.project.user.domain.entity.UserEntity;
import com.example.project.user.dto.UserDTO;
import com.example.project.user.infrastructure.repository.po.UserPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface UserMapper {
    @Mapping(target = "id", expression = "java(new com.example.project.user.domain.entity.UserId(po.id))")
    UserEntity toDomain(UserPO po);

    @Mapping(target = "password", ignore = true)
    List<UserDTO> toDto(List<UserPO> poList);
}
