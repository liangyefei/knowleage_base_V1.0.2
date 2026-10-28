package com.hisilicon.knowledge.platform.mcp.auth;

/**
 * 已通过平台 JWT 验签 + 吊销检查的 MCP 调用方身份。
 *
 * <p>由 {@link McpAuthFilter} 在请求入口构造，放入 RoutingContext（key 见
 * {@link McpCallGate#CONTEXT_KEY}）；工具转发层（ToolRegistry）经
 * {@link McpCallGate} 读出，用于限额与计量。</p>
 *
 * @param username 平台用户名（JWT sub）
 * @param jti      MCP token 唯一标识（计量与 last_used_at 归属）
 * @param admin    roles 是否含 sys_admin
 */
public record McpPrincipal(String username, String jti, boolean admin) {

    /** roles claim 中系统管理员的角色名 */
    public static final String ROLE_SYS_ADMIN = "sys_admin";

    /** roles claim（平台 JWT 既有 claim，逗号/集合形式由 filter 归一化） */
    public static final String CLAIM_ROLES = "roles";
}
