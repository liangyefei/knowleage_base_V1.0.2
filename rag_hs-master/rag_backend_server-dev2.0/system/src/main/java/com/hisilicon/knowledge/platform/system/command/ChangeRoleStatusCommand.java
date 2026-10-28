package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.NotNull;

/**
 * 变更角色状态命令
 *
 * @param status 目标状态：1=正常 / 0=禁用 / 2=禁用（兼容值，落库归一为 0；合法性由接口层校验）
 */
public record ChangeRoleStatusCommand(
        @NotNull(message = "角色状态不能为空")
        Short status) {
}
