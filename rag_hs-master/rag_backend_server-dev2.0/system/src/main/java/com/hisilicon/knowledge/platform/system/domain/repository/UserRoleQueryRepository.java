package com.hisilicon.knowledge.platform.system.domain.repository;

import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 用户-角色只读查询仓储接口。
 *
 * <p>供 JWT 签发（{@code roles} claim）与管理员判定使用。本接口只读 ——
 * 角色的增删改与用户授权归角色管理侧实现，两边共用 {@code user_roles} / {@code roles} 两张表。</p>
 */
public interface UserRoleQueryRepository {

    /**
     * 查询用户的全部有效角色标识（{@code roles.role_key}，仅 {@code status = 1} 的角色）。
     *
     * @param username 用户名（即 JWT subject）
     * @return 角色标识列表；用户无角色时返回空列表（非 null）
     */
    Uni<List<String>> findRoleKeysByUsername(String username);

    /**
     * 判断用户是否拥有指定角色标识（{@code roles.role_key}，仅 {@code status = 1} 的角色）。
     *
     * @param username 用户名
     * @param roleKey  角色标识，如 {@code sys_admin}
     * @return 拥有返回 {@code true}
     */
    Uni<Boolean> hasRole(String username, String roleKey);
}
