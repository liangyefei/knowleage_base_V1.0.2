package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 覆盖式设置用户角色命令
 *
 * @param roleIds 目标角色 ID 列表（必填；允许空列表 —— 空列表时仍会保留内置 community 角色）
 */
public record SetUserRolesCommand(
        @NotNull(message = "角色 ID 列表不能为空")
        List<String> roleIds) {

    public SetUserRolesCommand {
        roleIds = roleIds == null ? null : List.copyOf(roleIds);
    }
}
