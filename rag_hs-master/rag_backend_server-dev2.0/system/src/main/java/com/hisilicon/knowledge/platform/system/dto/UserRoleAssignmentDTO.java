package com.hisilicon.knowledge.platform.system.dto;

import java.util.List;

/**
 * 用户角色授权结果
 *
 * @param username          用户名
 * @param roleKeys          授权后的角色标识列表（已包含自动补回的 community）
 * @param communityRestored 本次请求是否自动补回了内置 community 角色
 */
public record UserRoleAssignmentDTO(
        String username,
        List<String> roleKeys,
        boolean communityRestored) {
}
