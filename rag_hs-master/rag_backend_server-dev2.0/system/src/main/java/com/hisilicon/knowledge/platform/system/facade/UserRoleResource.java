package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.system.command.AdminGuard;
import com.hisilicon.knowledge.platform.system.command.RoleCommandService;
import com.hisilicon.knowledge.platform.system.command.SetUserRolesCommand;
import com.hisilicon.knowledge.platform.system.dto.RoleDTO;
import com.hisilicon.knowledge.platform.system.dto.UserRoleAssignmentDTO;
import com.hisilicon.knowledge.platform.system.query.RoleQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用户角色授权 REST 接口（{@code /system/users/{username}/roles}）。
 *
 * <p>GET 只要求登录；PUT 为管理员操作（{@link AdminGuard}），覆盖式设置用户角色，
 * 内置 community 角色始终保留（缺失时自动补回，并在返回体中体现）。</p>
 */
@Path("/system/users/{username}/roles")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserRoleResource {

    private static final Logger logger = LoggerFactory.getLogger(UserRoleResource.class);

    private final RoleCommandService roleCommandService;
    private final RoleQueryService roleQueryService;
    private final AdminGuard adminGuard;

    @Context
    HttpHeaders httpHeaders;

    public UserRoleResource(RoleCommandService roleCommandService,
                            RoleQueryService roleQueryService,
                            AdminGuard adminGuard) {
        this.roleCommandService = roleCommandService;
        this.roleQueryService = roleQueryService;
        this.adminGuard = adminGuard;
    }

    /**
     * 查询用户当前角色列表（仅要求登录）
     *
     * @param username 用户名
     * @return 角色列表
     */
    @GET
    public Uni<ResponseResult<List<RoleDTO>>> list(@PathParam("username") String username) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("List user roles request, username={}, locale={}", username, locale);
        return roleQueryService.listUserRoles(username)
                .map(roles -> ResponseResult.ok(roles, locale));
    }

    /**
     * 覆盖式设置用户角色（管理员）。
     *
     * <p>body: {@code {"roleIds":["..."]}}；事务内先删旧关联再插新关联。
     * 请求中未包含内置 community 角色时自动补回，返回体
     * {@link UserRoleAssignmentDTO#communityRestored()} 为 true。</p>
     *
     * @param username 用户名
     * @param command  授权命令
     * @return 授权结果
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<UserRoleAssignmentDTO>> setRoles(
            @PathParam("username") String username,
            @Valid @NotNull(message = "{app.common.body.notnull}") SetUserRolesCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Set user roles request, username={}, roleCount={}, locale={}",
                username, command.roleIds().size(), locale);
        return adminGuard.requireAdmin()
                .chain(() -> roleCommandService.setUserRoles(username, command))
                .map(result -> ResponseResult.ok(result, locale));
    }
}
