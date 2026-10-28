package com.hisilicon.knowledge.platform.mcp.auth;

import io.smallrye.mutiny.Uni;

import java.time.Duration;

/**
 * MCP 用量/吊销存储抽象。
 *
 * <p>生产实现是 {@link RedisMcpUsageStore}（Redis，与 iam 同实例）；
 * 测试实现是 {@link MemoryMcpUsageStore}（内存，测试不依赖 Docker/Redis）。
 * 通过**构建期**属性 {@code mcp-gateway.auth.store}（redis|memory）二选一
 * （{@code @IfBuildProperty}），保证任一环境只有一个 bean。</p>
 */
public interface McpUsageStore {

    /**
     * token 是否已被吊销。
     *
     * <p>实现必须能区分"未吊销"与"存储故障"：调用方对故障 fail-closed（回 401），
     * 与限额读失败的 fail-open 有意相反。</p>
     *
     * @param jti token 唯一标识
     * @return true=已吊销；存储故障以 Uni failure 表达
     */
    Uni<Boolean> isRevoked(String jti);

    /**
     * 查询账号当日用量与生效限额。
     *
     * <p>查询失败时返回带 {@code degraded=true} 的快照（fail-open 放行），
     * 不让统计故障阻断调用。</p>
     *
     * @param username          账号
     * @param defaultDailyCalls 全局默认日调用次数限额
     * @param defaultDailyChars 全局默认日响应字符限额
     * @return 当日快照（含是否超限）
     */
    Uni<UsageSnapshot> usage(String username, long defaultDailyCalls, long defaultDailyChars);

    /**
     * 记一次成功工具调用（次数 +1、字符量累加、last_used 更新）。
     *
     * <p>实现必须吞掉自身故障（计量失败不影响调用结果）。</p>
     *
     * @param username 账号
     * @param jti      token 唯一标识
     * @param chars    本次响应字符数（粗估 token = chars/4）
     * @return 完成信号
     */
    Uni<Void> record(String username, String jti, long chars);

    /**
     * 当日用量快照。
     *
     * @param calls       已调用次数
     * @param chars       已累计响应字符数
     * @param limitCalls  生效的日调用限额（账号覆盖 &gt; 全局默认）
     * @param limitChars  生效的日字符限额
     * @param overQuota   是否超限
     * @param degraded    存储查询是否降级（降级时一律放行）
     */
    record UsageSnapshot(long calls, long chars, long limitCalls, long limitChars,
                         boolean overQuota, boolean degraded) {
    }

    /** 存储键的 TTL 约定：统计键保留 8 天（覆盖"近 7 日"管理诉求） */
    Duration USAGE_TTL = Duration.ofDays(8);
}
