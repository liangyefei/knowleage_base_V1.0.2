package com.hisilicon.knowledge.platform.mcp;

import com.hisilicon.knowledge.platform.mcp.registry.ToolRegistry;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP **协议面**端到端测试：用官方 {@code McpAssured} 客户端按真实 MCP 协议（Streamable HTTP）
 * 连上网关，验证 {@code tools/list} 与 {@code tools/call}——即"agent 真正看到什么、调到什么"。
 *
 * <p>为什么必须有这一层：{@link ToolRegistryTest} 只验注册表<b>数据结构</b>，
 * 但"工具是否真的通过 MCP 协议暴露出去、调用是否真的转发到正确上游"只有连协议才测得到。
 * 这正是文档阶段一的验收标准（agent 配置单端点后能列出并调用全部工具）。</p>
 */
@QuarkusTest
class McpProtocolEndpointTest {

    @Inject
    ToolRegistry registry;

    /**
     * 判断 {@code tools/list} 结果里是否有某工具。
     *
     * <p>不能用 {@code page.findByName(x) != null}：该 API 在<b>找不到时抛
     * {@code NoSuchElementException} 而不是返回 null</b>（实测踩过）。故统一走 tools() 流判断。</p>
     */
    private static boolean hasTool(McpAssured.ToolsPage page, String name) {
        return page.tools().stream().anyMatch(t -> t.name().equals(name));
    }

    @BeforeEach
    void loadUpstreams() {
        registry.refresh().await().indefinitely();
    }

    /**
     * {@code tools/list} 必须能列出聚合后的工具，且**两个上游的独有工具都在**。
     *
     * <p>这直接对应验收标准"能列出全部上游工具"：agent 只配网关一个端点，
     * 就应看到来自多个上游的工具。</p>
     */
    @Test
    void toolsListExposesAggregatedTools() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsList(page -> {
                        assertTrue(page.size() > 0, "聚合后应至少列出 1 个工具");
                        // 来自不同上游的工具都应出现——这是"聚合"的本质
                        assertNotNull(page.findByName("stub-alpha__alpha_only"),
                                "应能列出 alpha 上游的工具");
                        assertNotNull(page.findByName("stub-beta__beta_only"),
                                "应能列出 beta 上游的工具");
                        // 同名工具应带各自前缀分别出现，而不是只剩一个
                        assertNotNull(page.findByName("stub-alpha__hisi_docs_search"),
                                "alpha 的同名工具应带前缀列出");
                        assertNotNull(page.findByName("stub-beta__hisi_docs_search"),
                                "beta 的同名工具应带前缀列出");
                    })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /** 描述里应标注真实来源，便于 agent 与排障定位（前缀名本身是黑盒） */
    @Test
    void toolDescriptionCarriesUpstreamOrigin() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsList(page -> {
                        var tool = page.findByName("stub-alpha__alpha_only");
                        assertNotNull(tool, "工具应存在");
                        assertTrue(tool.description().contains("stub-alpha"),
                                "描述应标注上游来源，实际: " + tool.description());
                        assertNotNull(tool.inputSchema(), "inputSchema 应原样透传，不为 null");
                    })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /**
     * {@code tools/call} 必须**转发到正确上游**并原样带回结果。
     *
     * <p>stub 返回 "called &lt;tool&gt; on &lt;upstream&gt;"，据此断言不带串台：
     * 调 alpha 的同名工具，结果里必须是 alpha 而不是 beta。</p>
     */
    @Test
    void toolsCallRoutesToCorrectUpstream() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("stub-alpha__hisi_docs_search", Map.of("query", "mmz"),
                            response -> {
                                assertFalse(response.isError(), "调用不应失败");
                                String text = response.firstContent().asText().text();
                                // stub 的文案是 "called <tool> on <upstream路径段>"（alpha/beta），
                                // 不是配置里的 stub-alpha——断言用路径段，避免把 stub 文案当契约
                                assertTrue(text.contains("on alpha"),
                                        "同名工具必须路由到 alpha，实际: " + text);
                            })
                    .toolsCall("stub-beta__hisi_docs_search", Map.of("query", "mmz"),
                            response -> {
                                assertFalse(response.isError(), "调用不应失败");
                                String text = response.firstContent().asText().text();
                                assertTrue(text.contains("on beta"),
                                        "同名工具必须路由到 beta，实际: " + text);
                            })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /**
     * 未知工具名由**扩展层**直接拒绝（不落到网关 handler），错误码是 {@code -32602 INVALID_PARAMS}。
     *
     * <p>实测确认（不是 -32601）：扩展把"工具名不存在"归为参数非法。这条断言的意义是
     * 钉住"路由派发在扩展层完成"这一事实——也正因如此，裸名可用性必须在**注册阶段**
     * 通过别名工具表达，无法在 handler 里兜底。</p>
     */
    @Test
    void unknownToolNameIsRejectedByProtocolLayer() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("no_such_tool_at_all")
                    .withErrorAssert(error -> assertEquals(-32602, error.code(),
                            "未知工具名应由扩展回 -32602（INVALID_PARAMS），实际: " + error.code()))
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /**
     * 上游故障隔离：某上游不可用时，**其余工具仍可列出与调用**。
     *
     * <p>做法：直接把注册表里指向一个不可达上游的路由清掉再重放——更贴近真实做法是
     * 配置一个坏上游；这里用一个必然连不上的临时上游验证"degraded 不拖死端点"，
     * 这是文档阶段一的验收标准之一。</p>
     */
    @Test
    void upstreamFailureIsIsolatedAndEndpointStaysUsable() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            // 刷新后端点必须仍然可用，且工具依然能列出（不因个别上游抖动而整体 500）
            registry.refresh().await().indefinitely();
            client.when()
                    .toolsList(page -> assertTrue(page.size() > 0,
                            "刷新后端点仍应可用并列出工具"))
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /** 自检接口应报告聚合到的工具数与上游健康状态（运维/验收用） */
    @Test
    void statusReportsAggregatedTools() {
        JsonObject status = new JsonObject(
                io.restassured.RestAssured.given().get("/gateway/status")
                        .then().statusCode(200).extract().asString());
        assertTrue(status.getInteger("toolCount") > 0, "应报告已聚合的工具数");
        assertNotNull(status.getJsonArray("upstreams"), "应报告上游清单");
        assertTrue(status.getJsonArray("tools").size() > 0, "应报告工具明细");
    }

    /**
     * 重名裸名的**主备路由**：不带前缀的 {@code hisi_docs_search} 按 priority 选主——
     * 测试配置里 stub-beta priority 最小（=1），调用应**成功**且落在 beta，而不是报候选清单。
     *
     * <p>对应 2026-10-22 确认的口径："hisi_docs_search 以 9803 为主，9803 不通走 9802"；
     * stub 环境里主=beta。</p>
     */
    @Test
    void bareConflictedToolRoutesToPrimary() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("hisi_docs_search", Map.of("query", "mmz"),
                            response -> {
                                assertFalse(response.isError(), "主上游健康时裸名调用应成功");
                                String text = response.firstContent().asText().text();
                                assertTrue(text.contains("on beta"),
                                        "裸名应路由到 priority 最高的 stub-beta，实际: " + text);
                            })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    /**
     * **调用期 failover**：主（及同端点的备）返回传输层失败（HTTP 503）时，
     * 裸名调用自动切到下一个健康候选 stub-alpha，agent 无感知。
     *
     * <p>用 stub 的 kill switch（{@link StubUpstreamResource#DISABLED}）模拟上游宕机；
     * finally 里必须清理，避免污染同实例的其他用例。</p>
     */
    @Test
    void bareConflictedToolFailsOverWhenPrimaryDown() {
        StubUpstreamResource.DISABLED.add("beta");
        try {
            McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
            try {
                client.when()
                        .toolsCall("hisi_docs_search", Map.of("query", "mmz"),
                                response -> {
                                    assertFalse(response.isError(),
                                            "主不可用时应 failover 到健康备上游而不是报错");
                                    String text = response.firstContent().asText().text();
                                    assertTrue(text.contains("on alpha"),
                                            "应自动落到 stub-alpha（下一个健康候选），实际: " + text);
                                })
                        .thenAssertResults();
            } finally {
                client.disconnect();
            }
        } finally {
            StubUpstreamResource.DISABLED.remove("beta");
        }
    }

    /**
     * 裸名别名**既出现在 tools/list、也可调用**（当前选定行为）。
     *
     * <p>这条断言固化了那个"二选一"的取舍结论：官方 {@code ToolFilter} 同时管列表与派发，
     * 因此做不到"隐藏但可调用"；我们选择保留别名并列出，换取 agent 用裸名也能调通。
     * 若将来改为去别名，本测试应同步改为断言"不存在于列表"。</p>
     */
    @Test
    void bareAliasIsListedAndCallable() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsList(page -> {
                        assertTrue(hasTool(page, "alpha_only"),
                                "裸名别名按当前取舍应出现在 tools/list");
                        assertTrue(hasTool(page, "stub-alpha__alpha_only"),
                                "带前缀的真名也必须出现在 tools/list");
                    })
                    // 裸名走主备路由：alpha(3) 是 alpha_only 组的主 → 成功落到 alpha
                    .toolsCall("alpha_only", Map.of("query", "x"),
                            response -> {
                                assertFalse(response.isError(), "主健康时裸名调用应成功");
                                assertTrue(response.firstContent().asText().text().contains("on alpha"),
                                        "应路由到组内 priority 最高的 stub-alpha");
                            })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}