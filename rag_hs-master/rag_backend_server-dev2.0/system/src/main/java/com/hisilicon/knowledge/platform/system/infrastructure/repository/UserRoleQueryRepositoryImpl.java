package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 用户-角色只读查询仓储实现（{@code user_roles} join {@code roles}）。
 *
 * <p>角色管理侧与 JWT 签发侧共用本实现；查询口径固定为
 * “用户当前绑定角色的 role_key，且 roles.status = 1”。</p>
 */
@ApplicationScoped
public class UserRoleQueryRepositoryImpl implements UserRoleQueryRepository {

    /**
     * 按用户名查询有效角色标识：users → user_roles → roles，只取 status=1 的角色。
     * <p>集合结果按 role_key 升序，便于日志与断言稳定。</p>
     */
    private static final String ROLE_KEYS_BY_USERNAME_QUERY = """
            select r.roleKey from RolePO r
            where r.status = :enabledStatus
              and r.id in (
                select ur.roleId from UserRolePO ur
                where ur.userId in (
                  select u.id from UserPO u where u.username = :username
                )
              )
            order by r.roleKey
            """;

    private final RolePanacheRepository rolePanacheRepository;

    public UserRoleQueryRepositoryImpl(RolePanacheRepository rolePanacheRepository) {
        this.rolePanacheRepository = rolePanacheRepository;
    }

    @Override
    @WithSession
    public Uni<List<String>> findRoleKeysByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return rolePanacheRepository.getSession()
                .flatMap(session -> session.createQuery(ROLE_KEYS_BY_USERNAME_QUERY, String.class)
                        .setParameter("username", username)
                        .setParameter("enabledStatus", RoleEntity.STATUS_ENABLED)
                        .getResultList());
    }

    /**
     * {@inheritDoc}
     * <p>复用 {@link #findRoleKeysByUsername(String)} 的同一查询，避免两处口径漂移。</p>
     */
    @Override
    @WithSession
    public Uni<Boolean> hasRole(String username, String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return Uni.createFrom().item(Boolean.FALSE);
        }
        return findRoleKeysByUsername(username).map(roleKeys -> roleKeys.contains(roleKey));
    }
}
