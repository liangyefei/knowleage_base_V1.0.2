package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理员守卫（最小实现）。
 *
 * <p><b>这不是权限引擎</b>：在 ABAC / 权限模型落地前，角色管理的写操作只做一件事 ——
 * 校验当前操作员是否拥有 {@code sys_admin} 角色（{@code user_roles} join {@code roles}，
 * 要求角色 status=1）。当前用户名取自 {@link OperatorIdHolder}（由 commons-web 的
 * {@code AuthenticationFilter} 在 {@code @Authenticated} 请求进入时写入 JWT subject）。</p>
 *
 * <p>后续接入 abac / 权限模型时，用权限判定（如 {@code role:manage} / {@code user:grant} 权限码）
 * 替换本类实现，并删除对 {@code sys_admin} 角色标识的硬编码；调用点（Resource 层）无需改动。</p>
 */
@ApplicationScoped
public class AdminGuard {

    private static final Logger logger = LoggerFactory.getLogger(AdminGuard.class);

    private final OperatorIdHolder operatorIdHolder;
    private final UserRoleQueryRepository userRoleQueryRepository;

    public AdminGuard(OperatorIdHolder operatorIdHolder,
                      UserRoleQueryRepository userRoleQueryRepository) {
        this.operatorIdHolder = operatorIdHolder;
        this.userRoleQueryRepository = userRoleQueryRepository;
    }

    /**
     * 要求当前操作员是系统管理员（拥有启用的 {@code sys_admin} 角色）。
     *
     * @return 校验通过返回 void item；未登录或非管理员返回失败
     */
    public Uni<Void> requireAdmin() {
        String username = operatorIdHolder.get();
        if (username == null || username.isBlank()) {
            logger.warn("Admin check failed, no operator in context");
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0301));
        }
        return userRoleQueryRepository.hasRole(username, RoleEntity.ADMIN_ROLE_KEY)
                .flatMap(isAdmin -> {
                    if (Boolean.TRUE.equals(isAdmin)) {
                        return Uni.createFrom().voidItem();
                    }
                    logger.warn("Admin check failed, user is not sys_admin: username={}", username);
                    return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.ROLE_ADMIN_REQUIRED));
                });
    }
}
