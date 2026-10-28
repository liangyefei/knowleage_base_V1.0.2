package com.hisilicon.knowledge.platform.mcp;

import com.hisilicon.knowledge.platform.mcp.registry.ToolRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合注册表测试：前缀命名空间、同名工具消歧、失败隔离、刷新幂等。
 *
 * <p>上游用 {@link StubUpstreamResource}（同进程假上游），**不依赖 dev 环境真实上游**，
 * 因此可离线、可重复跑。</p>
 *
 * <p>用例刻意复刻 dev 环境的真实冲突：两个 stub 都提供 {@code hisi_docs_search}
 * ——与 dev 里 9802 / 9803 同时提供该工具同形。</p>
 */
@QuarkusTest
class ToolRegistryTest {

    @Inject
    ToolRegistry registry;

    @BeforeEach
    void loadUpstreams() {
        // 测试配置里 load-on-startup=false，此处显式装载，避免启动竞态
        registry.refresh().await().indefinitely();
    }

    @Test
    void aggregatesToolsFromAllUpstreamsWithPrefix() {
        // alpha: hisi_docs_search + alpha_only；beta: hisi_docs_search + beta_only
        Set<String> exposed = registry.routes().keySet();

        assertTrue(exposed.contains("stub-alpha__alpha_only"),
                "上游独有工具应带前缀注册，实际: " + exposed);
        assertTrue(exposed.contains("stub-beta__beta_only"),
                "上游独有工具应带前缀注册，实际: " + exposed);
    }

    /**
     * 同名工具必须**分别**带上各自上游前缀，不能互相覆盖。
     *
     * <p>这是前缀命名空间存在的唯一理由——dev 环境 {@code hisi_docs_search} 同时存在于
     * 9802 与 9803，没有前缀就会丢工具或串台。</p>
     */
    @Test
    void sameToolNameOnTwoUpstreamsIsNamespacedNotCollapsed() {
        assertTrue(registry.findExact("stub-alpha__hisi_docs_search").isPresent(),
                "alpha 的同名工具应注册为带前缀名");
        assertTrue(registry.findExact("stub-beta__hisi_docs_search").isPresent(),
                "beta 的同名工具应注册为带前缀名");

        assertEquals("stub-alpha",
                registry.findExact("stub-alpha__hisi_docs_search").orElseThrow().upstream().name());
        assertEquals("stub-beta",
                registry.findExact("stub-beta__hisi_docs_search").orElseThrow().upstream().name());
        // 两条路由必须指向不同上游，否则就是串台
        assertFalse(registry.findExact("stub-alpha__hisi_docs_search").orElseThrow().upstream().name()
                        .equals(registry.findExact("stub-beta__hisi_docs_search").orElseThrow().upstream().name()),
                "同名工具的两条路由必须指向不同上游");
    }

    /** 保留上游原始工具名：转发时要剥前缀用原始名 */
    @Test
    void routeKeepsOriginalUpstreamToolName() {
        assertEquals("hisi_docs_search",
                registry.findExact("stub-alpha__hisi_docs_search").orElseThrow().originalName());
    }

    /** 两个上游都健康时应都记入 healthy，而不是 degraded */
    @Test
    void healthyUpstreamsAreTracked() {
        // 测试期上游全是 stub-*（见主配置 %test 段）；断言"全部健康且无 degraded"
        assertTrue(registry.healthyUpstreams().containsAll(Set.of("stub-alpha", "stub-beta")),
                "两个 stub 上游都应健康，实际: " + registry.healthyUpstreams());
        assertTrue(registry.healthyUpstreams().stream().allMatch(n -> n.startsWith("stub-")),
                "健康上游应全是 stub（不能混入真实 dev 上游），实际: " + registry.healthyUpstreams());
        assertTrue(registry.degradedUpstreams().isEmpty(),
                "不应有 degraded 上游，实际: " + registry.degradedUpstreams());
    }

    /**
     * 同名工具在**裸名下是歧义的**：{@code findByOriginalName} 应返回多条路由，
     * 注册为**主备 failover** 别名（不再是报候选的错误别名）。
     */
    @Test
    void findByOriginalNameReportsAmbiguity() {
        var matches = registry.findByOriginalName("hisi_docs_search");
        // alpha 与 beta 都提供该工具；gamma/delta 复用同一 stub 端点，故至少 2 条
        assertTrue(matches.size() >= 2,
                "同名工具应命中多条路由（说明裸名有歧义），实际: " + matches.size());
    }

    /**
     * 主备顺序必须按 **priority 升序**：测试配置 beta(1) → delta(2) → alpha(3) → gamma(4)。
     *
     * <p>对应生产口径："hisi_docs_search 以 9803（priority 1）为主，9802（priority 2）为备"。</p>
     */
    @Test
    void failoverOrderFollowsPriority() {
        var order = registry.failoverRoutes().get("hisi_docs_search");
        assertNotNull(order, "重名工具应有主备路由");
        var names = order.stream().map(r -> r.upstream().name()).toList();
        assertEquals(List.of("stub-beta", "stub-delta", "stub-alpha", "stub-gamma"), names,
                "主备顺序应按 priority 升序，实际: " + names);
    }

    /** 刷新必须幂等：重复 refresh 不应让工具数翻倍（先移除再注册），主备表同步重建 */
    @Test
    void refreshIsIdempotent() {
        int first = registry.toolCount();
        registry.refresh().await().indefinitely();
        assertEquals(first, registry.toolCount(),
                "重复刷新后工具数应保持不变（避免残留/重复注册）");
        assertTrue(registry.failoverRoutes().containsKey("hisi_docs_search"),
                "刷新后主备路由应重建");
    }
}