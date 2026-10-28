package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import io.smallrye.mutiny.Uni;
import com.hisilicon.knowledge.platform.system.domain.repository.McpGatewayStore;
import io.vertx.mutiny.redis.client.RedisAPI;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MCP 网关共享状态的 Redis 适配器（iam 侧写、网关侧读）。
 *
 * <p>键与网关 {@code hisilicon-knowledge-mcp-gateway} 的 {@code RedisMcpUsageStore} <b>必须逐字一致</b>：
 * 两边是独立部署单元，只靠 Redis 约定耦合，没有共享代码可以强制。</p>
 *
 * <ul>
 *   <li>{@code mcp:revoked:{jti}} = "1"，TTL = 令牌剩余有效期 —— 本类写，网关读（fail-closed）；</li>
 *   <li>{@code mcp:quota:{username}}（hash：dailyCalls / dailyChars）—— 本类写，网关读；</li>
 *   <li>{@code mcp:usage:{username}:{yyyyMMdd}:calls / :chars} —— 网关写，本类读（管理员查用量）；</li>
 *   <li>{@code mcp:token-lastused:{jti}} = epoch millis —— 网关写，本类读（列表展示）。</li>
 * </ul>
 *
 * <p>日期口径固定 Asia/Shanghai，与网关同源；否则跨时区会把一天的用量切成两半。</p>
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpGatewayRedisRepository implements McpGatewayStore {

    private static final Logger logger = LoggerFactory.getLogger(McpGatewayRedisRepository.class);

    /** 与网关 RedisMcpUsageStore 一致的统计时区 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String KEY_REVOKED_PREFIX = "mcp:revoked:";
    private static final String KEY_USAGE_PREFIX = "mcp:usage:";
    private static final String KEY_QUOTA_PREFIX = "mcp:quota:";
    private static final String KEY_LAST_USED_PREFIX = "mcp:token-lastused:";

    private static final String FIELD_DAILY_CALLS = "dailyCalls";
    private static final String FIELD_DAILY_CHARS = "dailyChars";
    private static final String USAGE_FIELD_CALLS = "calls";
    private static final String USAGE_FIELD_CHARS = "chars";

    private static final String REVOKED_VALUE = "1";

    /** TTL 下限：令牌临近过期时也要留 1 秒，否则注销位会立刻消失 */
    private static final long MIN_TTL_SECONDS = 1L;

    private final RedisAPI redis;

    @Inject
    public McpGatewayRedisRepository(RedisAPI redis) {
        this.redis = redis;
    }

    /**
     * 写吊销位，让网关在下次请求时立即拒绝该令牌。
     *
     * @param jti        令牌 jti
     * @param remaining  令牌剩余有效期（过期后键已无意义，TTL 与之对齐避免堆积）
     * @return 完成信号
     */
    public Uni<Void> revoke(String jti, Duration remaining) {
        long seconds = Math.max(remaining.getSeconds(), MIN_TTL_SECONDS);
        logger.info("Revoke MCP token jti={}, ttlSeconds={}", jti, seconds);
        return redis.setex(KEY_REVOKED_PREFIX + jti, String.valueOf(seconds), REVOKED_VALUE)
                .replaceWithVoid();
    }

    /**
     * 整体替换账号级限额覆盖：{@code null} 的维度不写入（回落网关默认值）。
     *
     * <p>先删后写保证「取消覆盖」可达；管理员操作频率极低，两次往返的窗口可以接受。</p>
     *
     * @param username   账号
     * @param dailyCalls 调用次数上限（null = 不覆盖）
     * @param dailyChars 字符数上限（null = 不覆盖）
     * @return 完成信号
     */
    public Uni<Void> replaceQuota(String username, Long dailyCalls, Long dailyChars) {
        List<String> args = new ArrayList<>(5);
        args.add(KEY_QUOTA_PREFIX + username);
        if (dailyCalls != null) {
            args.add(FIELD_DAILY_CALLS);
            args.add(String.valueOf(dailyCalls));
        }
        if (dailyChars != null) {
            args.add(FIELD_DAILY_CHARS);
            args.add(String.valueOf(dailyChars));
        }
        boolean empty = args.size() == 1;
        logger.info("Replace MCP quota username={}, dailyCalls={}, dailyChars={}, empty={}",
                username, dailyCalls, dailyChars, empty);
        return deleteQuota(username)
                .chain(() -> empty ? Uni.createFrom().voidItem() : redis.hmset(args).replaceWithVoid());
    }

    /**
     * 清除账号级限额覆盖（该账号回落到网关全局默认值）。
     *
     * @param username 账号
     * @return 完成信号
     */
    public Uni<Void> deleteQuota(String username) {
        return redis.del(List.of(KEY_QUOTA_PREFIX + username)).replaceWithVoid();
    }

    /**
     * 读账号级调用次数上限覆盖。
     *
     * @param username 账号
     * @return 覆盖值，未设置返回 {@code Optional.empty()}
     */
    public Uni<Optional<Long>> findDailyCallsOverride(String username) {
        return findHashLong(KEY_QUOTA_PREFIX + username, FIELD_DAILY_CALLS);
    }

    /**
     * 读账号级字符数上限覆盖。
     *
     * @param username 账号
     * @return 覆盖值，未设置返回 {@code Optional.empty()}
     */
    public Uni<Optional<Long>> findDailyCharsOverride(String username) {
        return findHashLong(KEY_QUOTA_PREFIX + username, FIELD_DAILY_CHARS);
    }

    /**
     * 读某账号某日的用量（网关写入的计数器）。
     *
     * @param username 账号
     * @param day      统计日（yyyy-MM-dd，Asia/Shanghai）
     * @return 次数与字符数
     */
    public Uni<UsageCounters> findUsage(String username, String day) {
        Uni<Optional<String>> callsUni = getString(usageKey(username, day, USAGE_FIELD_CALLS));
        Uni<Optional<String>> charsUni = getString(usageKey(username, day, USAGE_FIELD_CHARS));
        return Uni.combine().all().unis(callsUni, charsUni).asTuple()
                .map(tuple -> new UsageCounters(
                        parseLong(tuple.getItem1(), 0L),
                        parseLong(tuple.getItem2(), 0L)));
    }

    /**
     * 读令牌最近一次被网关使用的时间。
     *
     * @param jti 令牌 jti
     * @return 最近使用时间，从未使用返回 {@code Optional.empty()}
     */
    public Uni<Optional<LocalDateTime>> findLastUsed(String jti) {
        return getString(KEY_LAST_USED_PREFIX + jti)
                .map(item -> item.map(McpGatewayRedisRepository::toDateTime));
    }

    /**
     * 当前统计日（Asia/Shanghai）。
     *
     * @return yyyy-MM-dd
     */
    public String today() {
        return LocalDate.now(ZONE).toString();
    }

    private Uni<Optional<Long>> findHashLong(String key, String field) {
        return redis.hget(key, field)
                .map(resp -> resp == null ? Optional.empty() : parseOptionalLong(resp.toString()));
    }

    private Uni<Optional<String>> getString(String key) {
        return redis.get(key)
                .map(resp -> resp == null ? Optional.empty() : Optional.of(resp.toString()));
    }

    private static String usageKey(String username, String day, String field) {
        return KEY_USAGE_PREFIX + username + ":" + day + ":" + field;
    }

    private static long parseLong(Optional<String> raw, long fallback) {
        return raw.map(text -> parseOptionalLong(text).orElse(fallback)).orElse(fallback);
    }

    private static Optional<Long> parseOptionalLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            // 计数器被外部改成非数字时按「无数据」处理，不用坏值参与限额判定
            logger.warn("Malformed MCP usage counter value '{}', treated as absent", raw);
            return Optional.empty();
        }
    }

    private static LocalDateTime toDateTime(String epochMillis) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(epochMillis.trim())), ZONE);
        } catch (NumberFormatException e) {
            logger.warn("Malformed MCP last-used timestamp '{}', ignored", epochMillis);
            return null;
        }
    }
}