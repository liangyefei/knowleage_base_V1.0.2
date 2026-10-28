package com.hisilicon.knowledge.platform.mcp.facade;

import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;
import com.hisilicon.knowledge.platform.mcp.registry.ToolRegistry;
import com.hisilicon.knowledge.platform.mcp.registry.ToolRoute;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关运维/自检接口（非 MCP 协议面，属于平台 REST 面）。
 *
 * <p>用途有两个：</p>
 * <ol>
 *   <li><b>对接验收</b>：列出网关聚合到的工具清单与上游健康状态，
 *       与 dev 环境 4 个上游直连结果逐项对比（对接方案阶段一的验收动作）；</li>
 *   <li><b>运行期排障</b>：哪个上游 degraded、聚合了多少工具，一眼可见。</li>
 * </ol>
 *
 * <p><b>为什么直接返回 Map 而不是 {@code ResponseResult}</b>：{@code ResponseResult} 在
 * {@code commons-web}，而该模块会把 Redis / Hibernate Validator / MapStruct 一并拉进网关的
 * 运行时 classpath——网关是转发层，不需要这些。本模块的口径是**依赖最小化**
 * （见模块 AGENTS.md）。故此处用纯 JSON Map；MCP 协议面由扩展提供，不受影响。</p>
 *
 * <p>阶段 2 加鉴权后，{@code /refresh} 这类写操作应要求管理员身份。</p>
 */
@Path("/gateway")
public class GatewayAdminResource {

    private final ToolRegistry registry;
    private final McpGatewayConfig config;

    @Inject
    public GatewayAdminResource(ToolRegistry registry, McpGatewayConfig config) {
        this.registry = registry;
        this.config = config;
    }

    /**
     * 网关自检：上游健康、聚合工具数、全部工具的暴露名与来源、主备路由顺序。
     *
     * @return 聚合状态
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toolCount", registry.toolCount());
        body.put("healthyUpstreams", registry.healthyUpstreams());
        body.put("degradedUpstreams", registry.degradedUpstreams());
        body.put("upstreams", upstreamStatus());
        body.put("tools", toolSummaries());
        body.put("failoverRoutes", failoverSummary());
        return body;
    }

    /**
     * 手动重新拉取上游工具清单。
     *
     * <p>上游升级版本、增减工具后调一次即可，**不需要重启网关、更不需要发版**
     * （方案 §5「上游工具清单变更无通知」的对策：不缓存清单 + 可刷新）。</p>
     *
     * @return 刷新后注册的工具总数与上游健康状态
     */
    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Map<String, Object>> refresh() {
        return registry.refresh().map(count -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("registeredToolCount", count);
            body.put("healthyUpstreams", registry.healthyUpstreams());
            body.put("degradedUpstreams", registry.degradedUpstreams());
            return body;
        });
    }

    /** 配置的上游清单及其当前健康状态 */
    private List<Map<String, Object>> upstreamStatus() {
        return config.upstreams().stream().map(upstream -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", upstream.name());
            item.put("url", upstream.url());
            item.put("healthy", registry.healthyUpstreams().contains(upstream.name()));
            item.put("degraded", registry.degradedUpstreams().contains(upstream.name()));
            return item;
        }).toList();
    }

    /** 工具清单（暴露名 / 上游 / 上游原始名），供对比验收使用 */
    private List<Map<String, Object>> toolSummaries() {
        return registry.routes().values().stream()
                .sorted(Comparator.comparing(ToolRoute::exposedName))
                .map(route -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("exposedName", route.exposedName());
                    item.put("upstream", route.upstream().name());
                    item.put("originalName", route.originalName());
                    return item;
                })
                .toList();
    }

    /**
     * 主备路由清单：裸名 → 按 priority 升序的候选暴露名（首个为主，其余为备）。
     *
     * <p>验收与排障用：一眼可见"某个重名工具到底谁主谁备"。</p>
     */
    private List<Map<String, Object>> failoverSummary() {
        return registry.failoverRoutes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tool", entry.getKey());
                    item.put("order", entry.getValue().stream().map(ToolRoute::exposedName).toList());
                    return item;
                })
                .toList();
    }
}