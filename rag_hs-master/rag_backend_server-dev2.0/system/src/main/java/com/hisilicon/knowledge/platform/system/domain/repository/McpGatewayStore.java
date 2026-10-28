package com.hisilicon.knowledge.platform.system.domain.repository;

import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MCP 网关共享状态的端口（吊销位、限额覆盖、用量计数、最近使用时间）。
 *
 * <p>为什么要有这个接口：这些数据不在本服务的数据库里，而是与 MCP 网关通过 Redis 约定的
 * 共享状态。放在 {@code domain.repository} 而不是让应用服务直接依赖
 * {@code infrastructure.repository} 的具体实现，一是维持"领域只依赖端口"的分层，
 * 二是测试里换内存实现就不用造假的 RedisAPI。</p>
 *
 * <p>键口径由基础设施实现负责（与网关 {@code RedisMcpUsageStore} 逐字一致），
 * 端口这一层只表达语义。</p>
 *
 * @author hisilicon
 */
public interface McpGatewayStore {

    /**
     * 写吊销位，让网关在下次请求时立即拒绝该令牌。
     *
     * @param jti       令牌 jti
     * @param remaining 令牌剩余有效期（TTL 与之对齐，避免过期键堆积）
     * @return 完成信号
     */
    Uni<Void> revoke(String jti, Duration remaining);

    /**
     * 整体替换账号级限额覆盖：{@code null} 的维度不写入（回落网关默认值）。
     *
     * @param username   账号
     * @param dailyCalls 调用次数上限（null = 不覆盖）
     * @param dailyChars 字符数上限（null = 不覆盖）
     * @return 完成信号
     */
    Uni<Void> replaceQuota(String username, Long dailyCalls, Long dailyChars);

    /**
     * 清除账号级限额覆盖（回落到网关全局默认值）。
     *
     * @param username 账号
     * @return 完成信号
     */
    Uni<Void> deleteQuota(String username);

    /**
     * 读账号级调用次数上限覆盖。
     *
     * @param username 账号
     * @return 覆盖值；未设置返回 {@code Optional.empty()}
     */
    Uni<Optional<Long>> findDailyCallsOverride(String username);

    /**
     * 读账号级字符数上限覆盖。
     *
     * @param username 账号
     * @return 覆盖值；未设置返回 {@code Optional.empty()}
     */
    Uni<Optional<Long>> findDailyCharsOverride(String username);

    /**
     * 读某账号某日的用量（网关写入的计数器）。
     *
     * @param username 账号
     * @param day      统计日（yyyy-MM-dd）
     * @return 次数与字符数
     */
    Uni<UsageCounters> findUsage(String username, String day);

    /**
     * 读令牌最近一次被网关使用的时间。
     *
     * @param jti 令牌 jti
     * @return 最近使用时间；从未使用返回 {@code Optional.empty()}
     */
    Uni<Optional<LocalDateTime>> findLastUsed(String jti);

    /**
     * 当前统计日。
     *
     * @return yyyy-MM-dd
     */
    String today();

    /**
     * 某账号某日的用量计数。
     *
     * @param calls 调用次数
     * @param chars 返回字符数
     */
    record UsageCounters(long calls, long chars) {
    }
}