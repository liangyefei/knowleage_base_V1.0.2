package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建角色命令
 *
 * @param roleKey     角色标识（必填、唯一、≤30，创建后不允许修改，如 kb_editor）
 * @param roleName    角色名称（必填、≤50）
 * @param description 描述（可选、≤200）
 */
public record CreateRoleCommand(
        @NotBlank(message = "角色标识不能为空")
        @Size(max = 30, message = "角色标识长度不能超过 30")
        String roleKey,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 50, message = "角色名称长度不能超过 50")
        String roleName,

        @Size(max = 200, message = "角色描述长度不能超过 200")
        String description) {
}
