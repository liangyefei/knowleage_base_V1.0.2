package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改角色命令（仅名称与描述）。
 *
 * <p>{@code roleKey} 仅用于识别“试图修改角色标识”的请求：若请求携带了与当前值不同的
 * role_key，应用层直接拒绝（role_key 一经创建不可变）。</p>
 *
 * @param roleKey     角色标识（可选；与现值不同则拒绝本次修改）
 * @param roleName    角色名称（必填、≤50）
 * @param description 描述（可选、≤200）
 */
public record UpdateRoleCommand(
        String roleKey,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 50, message = "角色名称长度不能超过 50")
        String roleName,

        @Size(max = 200, message = "角色描述长度不能超过 200")
        String description) {
}
