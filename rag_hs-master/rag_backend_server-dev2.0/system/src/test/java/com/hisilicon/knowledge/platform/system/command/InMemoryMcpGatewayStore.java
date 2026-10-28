package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.system.domain.repository.McpGatewayStore;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link McpGatewayStore} 的内存实现（测试用）。
 *
 * <p>只保留断言需要的最小语义：谁被吊销了、谁的限额被覆盖成什么、用量计数是多少。
 * 键名/序列化等细节归 Redis 实现负责，这里不重复验证。</p>
 */
public class InMemoryMcpGatewayStore implements McpGatewayStore {

    private final Set<String> revoked = new HashSet<>();
    private final Map<String, Long[]> quotas = new HashMap<>();
    private final Map<String, UsageCounters> usage = new HashMap<>();
    private final Map<String, LocalDateTime> lastUsed = new HashMap<>();

    @Override
    public Uni<Void> revoke(String jti, Duration remaining) {
        revoked.add(jti);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> replaceQuota(String username, Long dailyCalls, Long dailyChars) {
        if (dailyCalls == null && dailyChars == null) {
            quotas.remove(username);
        } else {
            Long[] existing = quotas.getOrDefault(username, new Long[]{null, null});
            quotas.put(username, new Long[]{
                    dailyCalls == null ? existing[0] : dailyCalls,
                    dailyChars == null ? existing[1] : dailyChars});
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> deleteQuota(String username) {
        quotas.remove(username);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Optional<Long>> findDailyCallsOverride(String username) {
        Long[] quota = quotas.get(username);
        return Uni.createFrom().item(Optional.ofNullable(quota == null ? null : quota[0]));
    }

    @Override
    public Uni<Optional<Long>> findDailyCharsOverride(String username) {
        Long[] quota = quotas.get(username);
        return Uni.createFrom().item(Optional.ofNullable(quota == null ? null : quota[1]));
    }

    @Override
    public Uni<UsageCounters> findUsage(String username, String day) {
        return Uni.createFrom().item(
                usage.getOrDefault(username + "|" + day, new UsageCounters(0L, 0L)));
    }

    @Override
    public Uni<Optional<LocalDateTime>> findLastUsed(String jti) {
        return Uni.createFrom().item(Optional.ofNullable(lastUsed.get(jti)));
    }

    @Override
    public String today() {
        return LocalDate.now().toString();
    }

    public boolean isRevoked(String jti) {
        return revoked.contains(jti);
    }

    public int revokedCount() {
        return revoked.size();
    }

    public void putUsage(String username, String day, long calls, long chars) {
        usage.put(username + "|" + day, new UsageCounters(calls, chars));
    }

    public void putLastUsed(String jti, LocalDateTime time) {
        lastUsed.put(jti, time);
    }
}