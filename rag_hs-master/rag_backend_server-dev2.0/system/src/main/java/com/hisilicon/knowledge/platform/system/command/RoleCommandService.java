package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleId;
import com.hisilicon.knowledge.platform.system.domain.repository.RoleRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleRepository;
import com.hisilicon.knowledge.platform.system.dto.UserRoleAssignmentDTO;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色命令服务（应用服务层）。
 *
 * <p>业务规则集中在这里：</p>
 * <ul>
 *   <li>role_key 唯一（先查后插）且创建后不可变；</li>
 *   <li>内置角色（is_preset=true）不允许改状态、不允许删除；</li>
 *   <li>用户授权覆盖式写入，且内置 community 角色必须始终保留（缺失时自动补回）。</li>
 * </ul>
 */
@ApplicationScoped
public class RoleCommandService {

    private static final Logger logger = LoggerFactory.getLogger(RoleCommandService.class);

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public RoleCommandService(RoleRepository roleRepository,
                              UserRoleRepository userRoleRepository) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 新建角色（role_key 唯一性校验：先查后插，冲突返回业务错误）
     *
     * @param command 创建命令
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> create(CreateRoleCommand command) {
        String roleKey = command.roleKey().trim();
        return roleRepository.existsByRoleKey(roleKey)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        logger.warn("Create role rejected, role key already exists: roleKey={}", roleKey);
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0429));
                    }
                    return roleRepository.persist(
                            RoleEntity.create(roleKey, command.roleName(), command.description()));
                })
                .replaceWithVoid();
    }

    /**
     * 修改角色名称/描述（role_key 不可变：携带了不同 role_key 直接拒绝）
     *
     * @param id      角色 ID
     * @param command 修改命令
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> update(RoleId id, UpdateRoleCommand command) {
        return findExisting(id)
                .flatMap(role -> {
                    if (command.roleKey() != null && !command.roleKey().isBlank()
                            && !command.roleKey().trim().equals(role.getRoleKey())) {
                        logger.warn("Update role rejected, role key is immutable: id={}, current={}, requested={}",
                                id.value(), role.getRoleKey(), command.roleKey());
                        return Uni.createFrom().failure(
                                new BusinessException(ErrorCodeEnum.USER_ERROR_A0402, "角色标识不允许修改"));
                    }
                    role.rename(command.roleName(), command.description());
                    return roleRepository.merge(role);
                })
                .replaceWithVoid();
    }

    /**
     * 变更角色状态（内置角色禁止，抛 A0403）
     *
     * @param id     角色 ID
     * @param status 目标状态（接口层允许 0/1/2，2 归一为 0）
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> changeStatus(RoleId id, Short status) {
        Short targetStatus = normalizeStatus(status);
        return findExisting(id)
                .flatMap(role -> {
                    if (role.isBuiltin()) {
                        logger.warn("Change role status rejected, builtin role is immutable: id={}, roleKey={}",
                                id.value(), role.getRoleKey());
                        return Uni.createFrom().failure(
                                new BusinessException(ErrorCodeEnum.ROLE_BUILTIN_IMMUTABLE));
                    }
                    role.changeStatus(targetStatus);
                    return roleRepository.merge(role);
                })
                .replaceWithVoid();
    }

    /**
     * 删除角色（内置角色禁止，抛 A0403；先清理 user_roles 孤儿关联）
     *
     * @param id 角色 ID
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> delete(RoleId id) {
        return findExisting(id)
                .flatMap(role -> {
                    if (role.isBuiltin()) {
                        logger.warn("Delete role rejected, builtin role is immutable: id={}, roleKey={}",
                                id.value(), role.getRoleKey());
                        return Uni.createFrom().failure(
                                new BusinessException(ErrorCodeEnum.ROLE_BUILTIN_IMMUTABLE));
                    }
                    return userRoleRepository.deleteByRoleId(id.value())
                            .flatMap(v -> roleRepository.deleteById(id));
                });
    }

    /**
     * 覆盖式设置用户角色（事务内先删旧关联再插新关联）。
     *
     * <p>约束：</p>
     * <ol>
     *   <li>用户必须存在；</li>
     *   <li>roleIds 必须全部存在且 status=1，否则抛 A0430；</li>
     *   <li>内置 community 角色必须保留：请求里没有时自动补回，并在返回值
     *       {@link UserRoleAssignmentDTO#communityRestored()} 中体现。</li>
     * </ol>
     *
     * @param username 用户名
     * @param command  授权命令
     * @return 授权结果（含是否补回 community）
     */
    @WithTransaction
    public Uni<UserRoleAssignmentDTO> setUserRoles(String username, SetUserRolesCommand command) {
        List<String> requestedIds = command.roleIds().stream()
                .filter(roleId -> roleId != null && !roleId.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        List<RoleId> requestedRoleIds = requestedIds.stream().map(RoleId::of).toList();

        return userRoleRepository.findUserIdByUsername(username)
                .flatMap(userId -> {
                    if (userId.isEmpty()) {
                        logger.warn("Set user roles rejected, user not found: username={}", username);
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0201));
                    }
                    return roleRepository.findEnabledByIds(requestedRoleIds)
                            .flatMap(roles -> {
                                if (roles.size() != requestedRoleIds.size()) {
                                    logger.warn("Set user roles rejected, role missing or disabled: username={}, "
                                            + "requested={}, enabled={}", username, requestedRoleIds.size(), roles.size());
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.USER_ERROR_A0430, "角色不存在或已停用"));
                                }
                                return assignRoles(username, userId.get(), roles);
                            });
                });
    }

    // ======================== 内部工具 ========================

    private Uni<UserRoleAssignmentDTO> assignRoles(String username, String userId, List<RoleEntity> requestedRoles) {
        return roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY)
                .map(community -> {
                    Map<String, RoleEntity> selected = new LinkedHashMap<>();
                    requestedRoles.forEach(role -> selected.put(role.getId().value(), role));

                    boolean communityRestored = keepCommunityRole(username, community, selected);
                    return Map.entry(selected, communityRestored);
                })
                .flatMap(selection -> userRoleRepository.replaceUserRoles(userId, selection.getKey().keySet())
                        .map(v -> new UserRoleAssignmentDTO(
                                username,
                                selection.getKey().values().stream().map(RoleEntity::getRoleKey).toList(),
                                selection.getValue())));
    }

    /**
     * 保证 community 角色在授权结果中：缺失则自动补回（返回 true）。
     *
     * @return 本次是否自动补回 community
     */
    private boolean keepCommunityRole(String username,
                                      Optional<RoleEntity> community,
                                      Map<String, RoleEntity> selected) {
        if (community.isEmpty()) {
            // 迁移脚本未执行/角色被硬删除时无法补回：不阻塞授权，仅告警（数据修复由 migration_024 兜底）
            logger.warn("Builtin community role not initialized, skip retention: username={}", username);
            return false;
        }
        RoleEntity communityRole = community.get();
        boolean enabled = communityRole.getStatus() != null
                && RoleEntity.STATUS_ENABLED.intValue() == communityRole.getStatus().intValue();
        if (!enabled) {
            logger.warn("Builtin community role is disabled, cannot retain it: username={}", username);
            return false;
        }
        if (selected.containsKey(communityRole.getId().value())) {
            return false;
        }
        selected.put(communityRole.getId().value(), communityRole);
        logger.info("Builtin community role auto-restored for user: username={}", username);
        return true;
    }

    private Uni<RoleEntity> findExisting(RoleId id) {
        return roleRepository.findById(id)
                .map(optional -> optional.orElseThrow(() -> {
                    logger.warn("Role not found: id={}", id.value());
                    return new BusinessException(ErrorCodeEnum.USER_ERROR_A0430, "角色不存在");
                }));
    }

    /**
     * 状态归一：落库口径固定为 0=禁用 / 1=正常；接口层兼容 2=禁用。
     */
    private static Short normalizeStatus(Short status) {
        if (status == null) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0410, "角色状态不能为空");
        }
        if (status.intValue() == RoleEntity.STATUS_ENABLED.intValue()) {
            return RoleEntity.STATUS_ENABLED;
        }
        if (status.intValue() == RoleEntity.STATUS_DISABLED.intValue()
                || status.intValue() == RoleEntity.API_STATUS_DISABLED_COMPAT.intValue()) {
            return RoleEntity.STATUS_DISABLED;
        }
        throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, "角色状态仅允许 0（禁用）或 1（正常）");
    }
}
