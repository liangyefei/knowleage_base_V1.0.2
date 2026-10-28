package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.system.command.AdminGuard;
import com.hisilicon.knowledge.platform.system.command.McpTokenCommandService;
import com.hisilicon.knowledge.platform.system.command.SetMcpQuotaCommand;
import com.hisilicon.knowledge.platform.system.dto.McpQuotaDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenDTO;
import com.hisilicon.knowledge.platform.system.dto.McpUsageDTO;
import com.hisilicon.knowledge.platform.system.query.McpTokenQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MCP 令牌与用量的管理员接口（{@code /system/mcp-tokens/admin}）。
 *
 * <p>用途：sys_admin 排查/处置某个账号的 MCP 访问 —— 看它有哪些令牌、当天用掉多少调用与字符、
 * 单独调整它的每日限额、在账号泄露时直接吊销指定令牌。</p>
 *
 * <p>所有接口都经 {@link AdminGuard#requireAdmin()}，非 sys_admin 一律
 * {@code ROLE_ADMIN_REQUIRED}。</p>
 *
 * <p>路径刻意带 {@code /admin} 前缀：自助侧 {@code /system/mcp-tokens/{jti}} 与
 * {@code /{username}/{jti}} 段数不同，加静态前缀后不存在歧义。</p>
 *
 * @author hisilicon
 */
@Path("/system/mcp-tokens/admin")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class McpTokenAdminResource {

    private static final Logger logger = LoggerFactory.getLogger(McpTokenAdminResource.class);

    private final McpTokenCommandService mcpTokenCommandService;
    private final McpTokenQueryService mcpTokenQueryService;
    private final AdminGuard adminGuard;

    @Context
    HttpHeaders httpHeaders;

    public McpTokenAdminResource(McpTokenCommandService mcpTokenCommandService,
                                 McpTokenQueryService mcpTokenQueryService,
                                 AdminGuard adminGuard) {
        this.mcpTokenCommandService = mcpTokenCommandService;
        this.mcpTokenQueryService = mcpTokenQueryService;
        this.adminGuard = adminGuard;
    }

    /**
     * 指定账号的令牌列表（不存在账号时返回空列表，不报 404 —— 管理员常在确认账号拼写）。
     *
     * @param username 账号
     * @return 令牌元数据列表
     */
    @GET
    @Path("/{username}")
    public Uni<ResponseResult<List<McpTokenDTO>>> list(@PathParam("username") String username) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Admin list MCP tokens request, username={}, locale={}", username, locale);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.listForUser(username))
                .map(tokens -> ResponseResult.ok(tokens, locale));
    }

    /**
     * 指定账号某日的 MCP 用量。
     *
     * @param username 账号
     * @param date     统计日 yyyy-MM-dd，空则取今天
     * @return 用量快照（含生效限额与是否超限）
     */
    @GET
    @Path("/{username}/usage")
    public Uni<ResponseResult<McpUsageDTO>> usage(@PathParam("username") String username,
                                                  @QueryParam("date") String date) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Admin MCP usage request, username={}, date={}, locale={}", username, date, locale);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.usageForUser(username, date))
                .map(usage -> ResponseResult.ok(usage, locale));
    }

    /**
     * 查看指定账号的限额覆盖（两个维度都为 null 表示走网关默认值）。
     *
     * @param username 账号
     * @return 限额覆盖
     */
    @GET
    @Path("/{username}/quota")
    public Uni<ResponseResult<McpQuotaDTO>> quota(@PathParam("username") String username) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Admin MCP quota request, username={}, locale={}", username, locale);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.quota(username))
                .map(quota -> ResponseResult.ok(quota, locale));
    }

    /**
     * 设置指定账号的每日限额（整体替换语义：null 维度不覆盖；两维都传 null 等于清除覆盖）。
     *
     * @param username 账号
     * @param command  限额值，0 表示当天全部拒绝
     * @return 生效后的限额覆盖
     */
    @PUT
    @Path("/{username}/quota")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<McpQuotaDTO>> setQuota(@PathParam("username") String username,
                                                     @Valid SetMcpQuotaCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Admin set MCP quota request, username={}, locale={}", username, locale);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenCommandService.setQuota(username, command))
                .map(quota -> ResponseResult.ok(quota, locale));
    }

    /**
     * 吊销指定账号的指定令牌（立即生效）。
     *
     * @param username 账号
     * @param jti      令牌标识
     * @return 空响应
     */
    @DELETE
    @Path("/{username}/{jti}")
    public Uni<ResponseResult<Void>> revoke(@PathParam("username") String username,
                                            @PathParam("jti") String jti) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Admin revoke MCP token request, username={}, jti={}, locale={}",
                username, jti, locale);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenCommandService.revokeForUser(username, jti))
                .map(v -> ResponseResult.ok(null, locale));
    }
}