package com.hisilicon.knowledge.platform.system.domain.repository;

import io.smallrye.mutiny.Uni;

import java.util.Collection;
import java.util.Optional;

/**
 * 用户-角色关联仓储接口（领域层，写侧）。
 *
 * <p>只读角色标识查询见 {@link UserRoleQueryRepository}；本接口负责用户授权时的关联维护。</p>
 */
public interface UserRoleRepository {

    /**
     * 按用户名查用户主键
     *
     * @param username 用户名（JWT subject）
     * @return 用户 ID；不存在返回空 Optional
     */
    Uni<Optional<String>> findUserIdByUsername(String username);

    /**
     * 删除某角色的全部用户关联（角色删除时清理孤儿关联）
     *
     * @param roleId 角色 ID
     * @return 完成
     */
    Uni<Void> deleteByRoleId(String roleId);

    /**
     * 覆盖式替换用户的角色集合（事务内由调用方保证）：先删旧关联，再插入新关联。
     *
     * @param userId  用户 ID
     * @param roleIds 目标角色 ID 集合（已去重、已校验）
     * @return 完成
     */
    Uni<Void> replaceUserRoles(String userId, Collection<String> roleIds);
}
