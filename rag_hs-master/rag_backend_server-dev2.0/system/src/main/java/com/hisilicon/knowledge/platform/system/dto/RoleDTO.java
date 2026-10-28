package com.hisilicon.knowledge.platform.system.dto;

/**
 * 角色视图对象
 *
 * @param id          角色主键（修改/删除/授权时使用）
 * @param roleKey     角色标识（唯一、不可变，如 sys_admin）
 * @param roleName    角色名称
 * @param description 描述
 * @param isPreset    是否内置角色：true=内置（禁止改状态、禁止删除）
 * @param status      状态：0=禁用 / 1=正常
 */
public record RoleDTO(
        String id,
        String roleKey,
        String roleName,
        String description,
        Boolean isPreset,
        Short status) {
}
