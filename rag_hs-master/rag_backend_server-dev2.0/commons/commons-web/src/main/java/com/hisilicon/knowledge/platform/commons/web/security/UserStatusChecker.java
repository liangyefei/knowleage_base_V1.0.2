package com.hisilicon.knowledge.platform.commons.web.security;

/**
 * 用户状态查询端口 — 由业务模块（身份域，如 system）提供实现。
 * <p>
 * {@link AuthenticationFilter} 在令牌基础校验（Bearer 前缀 / exp / sub）通过后，
 * 追加"用户当前状态有效性"校验：禁用 / 删除的用户即使持有效 token 也拒绝。
 * 为避免每请求查库，过滤器优先读 Redis 缓存（key {@code user:status:{username}}，
 * TTL 600 秒），未命中时才调用本端口回查并回填缓存。
 * </p>
 * <p>
 * <b>实现方约定</b>：commons-web 不含数据源依赖，无法提供默认查库实现；
 * 业务模块（如 system）需提供一个 CDI bean，例如：
 * <pre>{@code
 * @ApplicationScoped
 * public class SystemUserStatusChecker implements UserStatusChecker {
 *     public int statusOf(String username) {
 *         // 查询 users 表 status 字段，"1" = 正常
 *     }
 * }
 * }</pre>
 * 无任何实现时过滤器放行并记录 warn 日志（向后兼容），不会导致启动失败。
 * </p>
 */
public interface UserStatusChecker {

    /**
     * 查询用户当前状态。
     *
     * @param username 用户名（与 JWT sub 一致）
     * @return 用户 status 字段值，"1" 表示正常；其他值视为无效（禁用 / 删除），
     *         将被 {@link AuthenticationFilter} 以 401 拒绝
     * @throws RuntimeException 查询失败时由实现方决定；过滤器会兜底放行并记录 warn
     */
    int statusOf(String username);
}
