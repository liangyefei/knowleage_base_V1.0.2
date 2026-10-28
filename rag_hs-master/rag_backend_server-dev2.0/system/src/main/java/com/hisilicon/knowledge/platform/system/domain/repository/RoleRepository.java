package com.hisilicon.knowledge.platform.system.domain.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleId;
import io.smallrye.mutiny.Uni;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储接口（领域层）。
 *
 * <p>只负责角色本体（{@code roles} 表）的读写；用户-角色关联见 {@link UserRoleRepository}。</p>
 */
public interface RoleRepository {

    /**
     * 按主键查询角色
     *
     * @param id 角色 ID
     * @return 角色；不存在返回空 Optional
     */
    Uni<Optional<RoleEntity>> findById(RoleId id);

    /**
     * 按角色标识查询角色（含预设/自定义角色）
     *
     * @param roleKey 角色标识，如 {@code community}
     * @return 角色；不存在返回空 Optional
     */
    Uni<Optional<RoleEntity>> findByRoleKey(String roleKey);

    /**
     * 角色标识是否已存在（唯一性校验）
     *
     * @param roleKey 角色标识
     * @return 存在返回 true
     */
    Uni<Boolean> existsByRoleKey(String roleKey);

    /**
     * 按 ID 批量查询“存在且启用中（status=1）”的角色，用于用户授权校验
     *
     * @param ids 角色 ID 集合
     * @return 命中的启用角色（不存在的、已禁用的不在结果中）
     */
    Uni<List<RoleEntity>> findEnabledByIds(Collection<RoleId> ids);

    /**
     * 新建角色
     *
     * @param entity 角色实体
     * @return 已持久化的角色
     */
    Uni<RoleEntity> persist(RoleEntity entity);

    /**
     * 更新角色（全字段合并）
     *
     * @param entity 角色实体
     * @return 已合并的角色
     */
    Uni<RoleEntity> merge(RoleEntity entity);

    /**
     * 按主键删除角色
     *
     * @param id 角色 ID
     * @return 完成
     */
    Uni<Void> deleteById(RoleId id);
}
