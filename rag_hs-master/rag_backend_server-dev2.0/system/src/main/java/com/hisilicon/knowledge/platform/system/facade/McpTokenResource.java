package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.system.command.AdminGuard;
import com.hisilicon.knowledge.platform.system.command.IssueMcpTokenCommand;
import com.hisilicon.knowledge.platform.system.command.McpTokenCommandService;
import com.hisilicon.knowledge.platform.system.command.SetMcpQuotaCommand;
import com.hisilicon.knowledge.platform.system.dto.McpQuotaDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenIssuedDTO;
import com.hisilicon.knowledge.platform.system.dto.McpUsageDTO;
import com.hisilicon.knowledge.platform.system.query.McpTokenQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * MCP 令牌管理 REST 接口（{@code /system/mcp-tokens}）。
 *
 * <p>权限口径：全部接口要求登录（{@link Authenticated}）。
 * <b>自助</b>接口（签发 / 列表 / 注销自己的 / 查自己的用量）只操作当前登录人，
 * 不接受 username 参数，避免越权；<b>管理</b>接口（带 {@code /{username}/...}）
 * 额外经 {@link AdminGuard#requireAdmin()} 校验 sys_admin。</p>
 *
 * <p>令牌明文只在签发响应里出现一次（{@link McpTokenIssuedDTO#token()}），
 * 列表接口永远不返回明文 —— 库里只存 SHA-256 摘要，服务端也无法还原。</p>
 *
 * <p>「一个账号同时只有一枚有效令牌」的落地：签发即轮换，旧令牌在签发事务内被吊销，
 * 响应里的 {@code replacedJti} 非空时前端必须提示用户更新客户端配置。</p>
 */
@Path("/system/mcp-tokens")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class McpTokenResource {

    private static final Logger logger = LoggerFactory.getLogger(McpTokenResource.class);

    private final McpTokenCommandService mcpTokenCommandService;
    private final McpTokenQueryService mcpTokenQueryService;
    private final AdminGuard adminGuard;

    @Context
    HttpHeaders httpHeaders;

    public McpTokenResource(McpTokenCommandService mcpTokenCommandService,
                            McpTokenQueryService mcpTokenQueryService,
                            AdminGuard adminGuard) {
        this.mcpTokenCommandService = mcpTokenCommandService;
        this.mcpTokenQueryService = mcpTokenQueryService;
        this.adminGuard = adminGuard;
    }

    /**
     * 为当前用户签发 MCP 令牌（签发即轮换，旧令牌立即失效）。
     *
     * @param command 可选 label，用于区分用途（如 "Trae 笔记本"）
     * @return 令牌明文 + 可直接粘贴到客户端的 mcpServers 片段
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<McpTokenIssuedDTO>> issue(@Valid IssueMcpTokenCommand command) {
        String locale = locale();
        logger.info("Issue MCP token request, locale={}", locale);
        return mcpTokenCommandService.issue(command == null ? new IssueMcpTokenCommand(null) : command)
                .map(issued -> ResponseResult.ok(issued, locale));
    }

    /**
     * 当前用户的令牌列表（不含明文）。
     *
     * @return 令牌元数据列表
     */
    @GET
    public Uni<ResponseResult<List<McpTokenDTO>>> list() {
        String locale = locale();
        logger.info("List own MCP tokens request, locale={}", locale);
        return mcpTokenQueryService.listOwn()
                .map(tokens -> ResponseResult.ok(tokens, locale));
    }

    /**
     * 当前用户某日用量。
     *
     * @param date 统计日（yyyy-MM-dd，空则今天）
     * @return 用量快照
     */
    @GET
    @Path("/usage")
    public Uni<ResponseResult<McpUsageDTO>> usageOwn(@QueryParam("date") String date) {
        String locale = locale();
        logger.info("Query own MCP usage request, date={}, locale={}", date, locale);
        return mcpTokenQueryService.usageOwn(date)
                .map(usage -> ResponseResult.ok(usage, locale));
    }

    /**
     * 注销当前用户的一枚令牌。
     *
     * @param jti 令牌标识
     * @return 空结果
     */
    @DELETE
    @Path("/{jti}")
    public Uni<ResponseResult<Void>> revokeOwn(@PathParam("jti") String jti) {
        String locale = locale();
        logger.info("Revoke own MCP token request, jti={}", jti);
        return mcpTokenCommandService.revokeOwn(jti)
                .map(ignored -> ResponseResult.ok(null, locale));
    }

    /**
     * 指定账号的令牌列表（管理员）。
     *
     * @param username 账号
     * @return 令牌元数据列表（不含明文）
     */
    @GET
    @Path("/{username}/tokens")
    public Uni<ResponseResult<List<McpTokenDTO>>> listForUser(@PathParam("username") String username) {
        String locale = locale();
        logger.info("List MCP tokens for username={} (admin)", username);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.listForUser(username))
                .map(tokens -> ResponseResult.ok(tokens, locale));
    }

    /**
     * 指定账号某日用量（管理员）。
     *
     * @param username 账号
     * @param date     统计日（yyyy-MM-dd，空则今天）
     * @return 用量快照
     */
    @GET
    @Path("/{username}/usage")
    public Uni<ResponseResult<McpUsageDTO>> usageForUser(@PathParam("username") String username,
                                                         @QueryParam("date") String date) {
        String locale = locale();
        logger.info("Query MCP usage for username={}, date={} (admin)", username, date);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.usageForUser(username, date))
                .map(usage -> ResponseResult.ok(usage, locale));
    }

    /**
     * 查询指定账号的限额覆盖（管理员）。
     *
     * @param username 账号
     * @return 覆盖值（两维都为 null 表示使用平台默认）
     */
    @GET
    @Path("/{username}/quota")
    public Uni<ResponseResult<McpQuotaDTO>> quota(@PathParam("username") String username) {
        String locale = locale();
        logger.info("Query MCP quota for username={} (admin)", username);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenQueryService.quota(username))
                .map(quota -> ResponseResult.ok(quota, locale));
    }

    /**
     * 设置指定账号的日限额覆盖（管理员，整体替换：null 维度不覆盖，两维都 null 等于清除）。
     *
     * @param username 账号
     * @param command  限额
     * @return 写入后的覆盖状态
     */
    @PUT
    @Path("/{username}/quota")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<McpQuotaDTO>> setQuota(@PathParam("username") String username,
                                                     @Valid @NotNull(message = "{app.common.body.notnull}")
                                                     SetMcpQuotaCommand command) {
        String locale = locale();
        logger.info("Set MCP quota for username={}, dailyCalls={}, dailyChars={} (admin)",
                username, command.dailyCalls(), command.dailyChars());
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenCommandService.setQuota(username, command))
                .map(quota -> ResponseResult.ok(quota, locale));
    }

    /**
     * 吊销指定账号的一枚令牌（管理员，用于风控收权）。
     *
     * @param username 账号
     * @param jti      令牌标识
     * @return 空结果
     */
    @DELETE
    @Path("/{username}/{jti}")
    public Uni<ResponseResult<Void>> revokeForUser(@PathParam("username") String username,
                                                   @PathParam("jti") String jti) {
        String locale = locale();
        logger.info("Revoke MCP token for username={}, jti={} (admin)", username, jti);
        return adminGuard.requireAdmin()
                .chain(() -> mcpTokenCommandService.revokeForUser(username, jti))
                .map(ignored -> ResponseResult.ok(null, locale));
    }

    private String locale() {
        return httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    }
}