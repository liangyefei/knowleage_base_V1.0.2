package com.hisilicon.knowledge.platform.mcp.auth;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版用量存储（仅 %test 构建期选中：{@code mcp-gateway.auth.store=memory}）。
 *
 * <p>为什么不用 Redis Dev Services：本仓库全局 {@code quarkus.devservices.enabled=false}
 * 且网关测试刻意脱离 Docker 依赖（模块 AGENTS.md 口径）；鉴权/计量/限额的**语义**用内存实现
 * 即可完整覆盖，Redis 传输层细节由生产环境部署验证承担。</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "mcp-gateway.auth.store", stringValue = "memory")
public class MemoryMcpUsageStore implements McpUsageStore {

    private final Map<String, Boolean> revoked = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> chars = new ConcurrentHashMap<>();
    private final Map<String, long[]> quotas = new ConcurrentHashMap<>(); // [calls, chars]，-1 表示未覆盖用默认

    @Override
    public Uni<Boolean> isRevoked(String jti) {
        return Uni.createFrom().item(revoked.getOrDefault(jti, false));
    }

    @Override
    public Uni<UsageSnapshot> usage(String username, long defaultDailyCalls, long defaultDailyChars) {
        String day = today();
        long c = calls.getOrDefault(day + ":" + username, new AtomicLong()).get();
        long h = chars.getOrDefault(day + ":" + username, new AtomicLong()).get();
        long[] q = quotas.get(username);
        // 语义与 RedisMcpUsageStore 对齐：字段被设置即以它为准（0 = 全部拒绝），未设置才用默认值
        long limitCalls = q != null && q[0] >= 0 ? q[0] : defaultDailyCalls;
        long limitChars = q != null && q[1] >= 0 ? q[1] : defaultDailyChars;
        return Uni.createFrom().item(new UsageSnapshot(c, h, limitCalls, limitChars,
                c >= limitCalls || h >= limitChars, false));
    }

    @Override
    public Uni<Void> record(String username, String jti, long charsDelta) {
        String day = today();
        calls.computeIfAbsent(day + ":" + username, k -> new AtomicLong()).incrementAndGet();
        chars.computeIfAbsent(day + ":" + username, k -> new AtomicLong()).addAndGet(charsDelta);
        return Uni.createFrom().voidItem();
    }

    /** 测试钩子：直接置吊销（生产由 iam 写 Redis） */
    public void revoke(String jti) {
        revoked.put(jti, true);
    }

    /** 测试钩子：按账号覆盖限额（{@code -1} = 用默认值；{@code 0} = 全部拒绝） */
    public void setQuota(String username, long dailyCalls, long dailyChars) {
        quotas.put(username, new long[]{dailyCalls, dailyChars});
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
