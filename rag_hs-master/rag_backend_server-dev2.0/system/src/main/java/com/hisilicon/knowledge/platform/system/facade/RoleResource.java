package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.system.command.AdminGuard;
import com.hisilicon.knowledge.platform.system.command.ChangeRoleStatusCommand;
import com.hisilicon.knowledge.platform.system.command.CreateRoleCommand;
import com.hisilicon.knowledge.platform.system.command.RoleCommandService;
import com.hisilicon.knowledge.platform.system.command.UpdateRoleCommand;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleId;
import com.hisilicon.knowledge.platform.system.dto.RoleDTO;
import com.hisilicon.knowledge.platform.system.query.RoleQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
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
 * 角色管理 REST 接口（{@code /system/roles}）。
 *
 * <p>权限口径：全部接口要求登录（{@link Authenticated}）；<b>写操作</b>
 * （POST/PUT/PATCH/DELETE）额外经 {@link AdminGuard#requireAdmin()} 校验 sys_admin 角色，
 * 读操作只要求登录。内置角色（is_preset=true）禁止改状态、禁止删除，role_key 创建后不可变。</p>
 */
@Path("/system/roles")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class RoleResource {

    private static final Logger logger = LoggerFactory.getLogger(RoleResource.class);

    /** 接口层允许的禁用值：0=禁用（落库口径），2=禁用（兼容值） */
    private static final short API_STATUS_DISABLED = 0;
    private static final short API_STATUS_DISABLED_COMPAT = 2;
    private static final short API_STATUS_ENABLED = 1;

    private final RoleCommandService roleCommandService;
    private final RoleQueryService roleQueryService;
    private final AdminGuard adminGuard;

    @Context
    HttpHeaders httpHeaders;

    public RoleResource(RoleCommandService roleCommandService,
                        RoleQueryService roleQueryService,
                        AdminGuard adminGuard) {
        this.roleCommandService = roleCommandService;
        this.roleQueryService = roleQueryService;
        this.adminGuard = adminGuard;
    }

    /**
     * 角色列表（仅要求登录）
     *
     * @return role_key / role_name / description / is_preset / status
     */
    @GET
    public Uni<ResponseResult<List<RoleDTO>>> list() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("List roles request, locale={}", locale);
        return roleQueryService.listRoles()
                .map(roles -> ResponseResult.ok(roles, locale));
    }

    /**
     * 新建角色（管理员；role_key 唯一，先查后插）
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> create(
            @Valid @NotNull(message = "{app.common.body.notnull}") CreateRoleCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Create role request, roleKey={}, locale={}", command.roleKey(), locale);
        return adminGuard.requireAdmin()
                .chain(() -> roleCommandService.create(command))
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 修改角色名称/描述（管理员；role_key 不允许修改，携带不同值将被拒绝）
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> update(
            @PathParam("id") String id,
            @Valid @NotNull(message = "{app.common.body.notnull}") UpdateRoleCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Update role request, id={}, locale={}", id, locale);
        return adminGuard.requireAdmin()
                .chain(() -> roleCommandService.update(RoleId.of(id), command))
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 变更角色状态（管理员；body: {"status":1} 正常 / {"status":0 或 2} 禁用；内置角色禁止）
     */
    @PATCH
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> changeStatus(
            @PathParam("id") String id,
            @Valid @NotNull(message = "{app.common.body.notnull}") ChangeRoleStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        Short status = command.status();
        if (status == null || (status.intValue() != API_STATUS_ENABLED
                && status.intValue() != API_STATUS_DISABLED
                && status.intValue() != API_STATUS_DISABLED_COMPAT)) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidStatusValue());
        }
        logger.info("Change role status request, id={}, status={}, locale={}", id, status, locale);
        return adminGuard.requireAdmin()
                .chain(() -> roleCommandService.changeStatus(RoleId.of(id), status))
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除角色（管理员；内置角色禁止删除）
     */
    @DELETE
    @Path("/{id}")
    public Uni<ResponseResult<Void>> delete(@PathParam("id") String id) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Delete role request, id={}, locale={}", id, locale);
        return adminGuard.requireAdmin()
                .chain(() -> roleCommandService.delete(RoleId.of(id)))
                .map(v -> ResponseResult.ok(null, locale));
    }
}
