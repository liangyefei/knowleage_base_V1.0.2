package com.hisilicon.knowledge.platform.mcp.auth;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.string.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Redis 版用量存储（生产实现，构建期 {@code mcp-gateway.auth.store=redis} 选中）。
 *
 * <p>Redis 与 iam 同实例（iam 写吊销键与账号限额覆盖，网关只读/自写统计）。</p>
 *
 * <p>键设计（方案 §计量）：</p>
 * <ul>
 *   <li>{@code mcp:revoked:{jti}} = "1"，TTL=token 剩余有效期 —— iam 吊销时写，网关读；</li>
 *   <li>{@code mcp:usage:{username}:{yyyyMMdd}:calls / :chars} —— 网关 INCR，TTL 8 天；</li>
 *   <li>{@code mcp:quota:{username}}（hash：dailyCalls / dailyChars）—— iam 写，网关读；</li>
 *   <li>{@code mcp:token-lastused:{jti}} —— 网关写时间戳，iam 列表接口读展示。</li>
 * </ul>
 *
 * <p>故障语义（有意区分）：吊销检查失败 →Uni failure（filter fail-closed 401）；
 * 用量/限额查询失败 → degraded 快照（放行）；计量失败 → 吞掉。</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "mcp-gateway.auth.store", stringValue = "redis", enableIfMissing = true)
public class RedisMcpUsageStore implements McpUsageStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMcpUsageStore.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReactiveRedisDataSource redis;

    @Inject
    public RedisMcpUsageStore(ReactiveRedisDataSource redis) {
        this.redis = redis;
    }

    @Override
    public Uni<Boolean> isRevoked(String jti) {
        // get 是只读探活：连接失败会以 Uni failure 上抛，由 filter fail-closed
        return redis.string(String.class).get(revokedKey(jti)).map(v -> v != null);
    }

    @Override
    public Uni<UsageSnapshot> usage(String username, long defaultDailyCalls, long defaultDailyChars) {
        String day = LocalDate.now(ZONE).toString();
        String callsKey = usageKey(username, day, "calls");
        String charsKey = usageKey(username, day, "chars");

        var strings = redis.string(String.class);
        var hashes = redis.hash(String.class, String.class, String.class);
        var keys = redis.key();

        Uni<String> callsU = strings.get(callsKey);
        Uni<String> charsU = strings.get(charsKey);
        Uni<Map<String, String>> quotaU = hashes.hgetall(quotaKey(username));

        return Uni.combine().all().unis(callsU, charsU, quotaU)
                .asTuple()
                .map(tuple -> {
                    long calls = parseLong(tuple.getItem1(), 0);
                    long chars = parseLong(tuple.getItem2(), 0);
                    Map<String, String> quota = tuple.getItem3();
                    long limitCalls = quota != null ? parseLong(quota.get("dailyCalls"), defaultDailyCalls)
                            : defaultDailyCalls;
                    long limitChars = quota != null ? parseLong(quota.get("dailyChars"), defaultDailyChars)
                            : defaultDailyChars;
                    return new UsageSnapshot(calls, chars, limitCalls, limitChars,
                            calls >= limitCalls || chars >= limitChars, false);
                })
                .onFailure().recoverWithItem(t -> {
                    // 限额/统计读失败 fail-open：可用性优先（与吊销 fail-closed 相反，方案既定）
                    log.warn("MCP usage/quota read failed for '{}', allowing request (degraded): {}",
                            username, t.getMessage());
                    return new UsageSnapshot(0, 0, defaultDailyCalls, defaultDailyChars, false, true);
                });
    }

    @Override
    public Uni<Void> record(String username, String jti, long charsDelta) {
        String day = LocalDate.now(ZONE).toString();
        var strings = redis.string(String.class);
        var keys = redis.key();

        String callsKey = usageKey(username, day, "calls");
        String charsKey = usageKey(username, day, "chars");

        // TTL 只在键首次创建时设置（INCR 返回 1），避免每次调用都续期导致滚动窗口失真
        Uni<Long> incrCalls = strings.incrby(callsKey, 1)
                .invoke(v -> {
                    if (v == 1) {
                        keys.expire(callsKey, USAGE_TTL).subscribe().with(ok -> {
                        }, e -> log.warn("Failed to set TTL on {}: {}", callsKey, e.getMessage()));
                    }
                });
        Uni<Long> incrChars = strings.incrby(charsKey, charsDelta)
                .invoke(v -> {
                    if (v == 1) {
                        keys.expire(charsKey, USAGE_TTL).subscribe().with(ok -> {
                        }, e -> log.warn("Failed to set TTL on {}: {}", charsKey, e.getMessage()));
                    }
                });
        String lastUsedKey = "mcp:token-lastused:" + jti;

        return Uni.combine().all().unis(incrCalls, incrChars)
                .discardItems()
                .chain(() -> strings.set(lastUsedKey, String.valueOf(System.currentTimeMillis()),
                        new SetArgs().ex(Duration.ofDays(9))))
                .replaceWithVoid()
                .onFailure().invoke(t -> log.warn("MCP usage record failed for '{}': {}", username, t.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private static String revokedKey(String jti) {
        return "mcp:revoked:" + jti;
    }

    private static String usageKey(String username, String day, String field) {
        return "mcp:usage:" + username + ":" + day + ":" + field;
    }

    private static String quotaKey(String username) {
        return "mcp:quota:" + username;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
