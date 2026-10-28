package com.hisilicon.knowledge.platform;

import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;
import com.hisilicon.knowledge.platform.mcp.registry.ToolRegistry;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hisilicon-knowledge-mcp-gateway 独立启动冒烟：证明网关在**最小依赖**的 classpath 上能独立启动，
 * 且不含任何业务能力路由（反向隔离）。
 *
 * <p>网关被单独拆出的前提是"它不依赖其余业务域"——本测试是这条前提的回归防线：
 * 一旦谁把 {@code system} / {@code knowledge} / {@code llm} 拉进本服务，下面的 404 断言会立刻失败。</p>
 *
 * <p>⚠️ 路径不要带 {@code /hisilicon-knowledge-mcp} 前缀：Quarkus 测试框架会把
 * {@code quarkus.http.root-path} 设为 RestAssured 的 basePath，写全前缀会变成双层前缀而全 404。</p>
 */
@QuarkusTest
class McpGatewayServiceSmokeTest {

    /** 官方扩展的核心 Bean：注入成功即证明 MCP 协议层已在 3.39.2 上正确装配 */
    @Inject
    ToolManager toolManager;

    /** 网关自己的聚合注册表 */
    @Inject
    ToolRegistry toolRegistry;

    /** 上游配置绑定：注入成功即证明 ConfigMapping 生效 */
    @Inject
    McpGatewayConfig config;

    /** 对外前缀：与 core / iam 必须都不同 */
    @ConfigProperty(name = "quarkus.http.root-path")
    String rootPath;

    @Test
    void mcpProtocolLayerIsWired() {
        assertNotNull(toolManager, "官方 MCP ToolManager 应可注入（证明扩展与 Quarkus 平台版本兼容）");
        assertNotNull(toolRegistry, "网关的聚合注册表应可注入");
    }

    @Test
    void upstreamConfigIsBound() {
        // 诊断用：确认 %test 段生效（profiles 应含 test；upstreams 应全部是 stub-*，
        // 一旦看到 139.9.104.64 说明 %test 没盖住 base 的索引列表）
        System.out.println("[DIAG] profiles=" + java.util.Arrays.toString(
                io.quarkus.runtime.configuration.ConfigUtils.getProfiles().toArray()));
        System.out.println("[DIAG] upstreams=" + config.upstreams().stream()
                .map(u -> u.name() + "@" + u.url()).toList());

        // ⚠️ 断言"没有任何真实 dev 上游混进来"，而不是断言具体条数：
        //    条数会随 base 段上游增减而变，但"测试绝不指向 139.9.104.64"是恒定要求。
        assertTrue(config.upstreams().stream().allMatch(u -> u.name().startsWith("stub-")),
                "测试期的上游必须全是 stub，不能混入真实 dev 上游，实际: "
                        + config.upstreams().stream().map(McpGatewayConfig.Upstream::name).toList());
        assertTrue(config.upstreams().stream().noneMatch(u -> u.url().contains("139.9.104.64")),
                "测试绝不指向 dev 环境真实上游（否则测试依赖外网、结果不可复现）");
        assertEquals("__", config.nameSeparator(), "默认前缀分隔符应为双下划线");
    }

    @Test
    void adminStatusEndpointIsServedHere() {
        String body = given().get("/gateway/status")
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("toolCount"), "自检接口应返回聚合状态，实际: " + body);
        assertTrue(body.contains("stub-alpha"), "自检接口应列出配置的上游，实际: " + body);
    }

    @Test
    void businessRoutesAreAbsentHere() {
        assertEquals(404, given().get("/system/dict/types/search").statusCode(),
                "字典路由属于 hisilicon-knowledge-core-service，不应出现在网关");
        assertEquals(404, given().get("/chat/conversations").statusCode(),
                "问答路由属于 hisilicon-knowledge-core-service，不应出现在网关");
        assertEquals(404, given().get("/user/login").statusCode(),
                "登录路由属于 hisilicon-knowledge-iam-service，不应出现在网关");
    }

    /**
     * 对外前缀必须与 core、iam **两两不同**。
     *
     * <p>测试/生产经华为云 APIG 接入，APIG 按<strong>前缀</strong>转发到 CCE 内的 Service；
     * 三个服务共用前缀时 APIG 上无法只靠前缀区分。这条断言把"前缀必须不同"钉成回归防线。</p>
     */
    @Test
    void rootPathIsDistinctFromOtherServices() {
        assertEquals("/hisilicon-knowledge-mcp", rootPath, "网关的对外前缀应为本服务专用前缀");
        assertNotEquals("/hisilicon-knowledge", rootPath,
                "网关不能与 core 共用 /hisilicon-knowledge");
        assertNotEquals("/hisilicon-knowledge-iam", rootPath,
                "网关不能与 iam 共用 /hisilicon-knowledge-iam");
    }

    /**
     * 探针端口与业务端口分离：开启 {@code quarkus.management} 后，{@code /q/health} 从业务端口
     * 剥离到独立管理端口（生产 9000、测试期 9009）。**CCE 的探针必须指 :9000/q/health**，
     * 否则业务端口 404 → Pod 反复重启。
     */
    @Test
    void healthIsNotServedOnBusinessPort() {
        assertEquals(404, given().get("/q/health").statusCode(),
                "开启 quarkus.management 后 /q/health 只在管理端口（9009/9000）提供");
    }
}