package com.hisilicon.knowledge.platform.mcp;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用的**假上游 MCP 服务**：在同一个 Quarkus 测试实例里模拟上游 JSON-RPC 端点。
 *
 * <p>为什么自己写而不是用 WireMock：本模块没有 WireMock 依赖，且这里要模拟的是
 * MCP 的 JSON-RPC 语义（{@code initialize} 回 session、错误一律 HTTP 200），
 * 一个 JAX-RS 资源就够，零新增测试依赖。</p>
 *
 * <p><b>刻意复刻 dev 环境的真实特征</b>（这些特征决定了网关的实现，必须被测到）：</p>
 * <ul>
 *   <li>两个上游都暴露 {@code hisi_docs_search}——<b>真实存在</b>的名字冲突
 *       （dev 环境 9802 与 9803 同时提供该工具），用来验证前缀命名空间与主备路由；</li>
 *   <li>错误也返回 <b>HTTP 200</b> + JSON-RPC {@code error}，不是 4xx/5xx；</li>
 *   <li>{@code initialize} 不下发 session id（对应 dev 的 9802/9803）。</li>
 * </ul>
 *
 * <p><b>故障注入（kill switch）</b>：{@link #DISABLED} 里的路径段会让该 stub **直接回 HTTP 500**
 * ——这是**传输层**失败（与 JSON-RPC 业务错误区分），用于测试主备路由的调用期 failover
 * 与装载期 degraded 隔离。测试用例负责在 finally 里清理，避免污染同实例的其他用例。</p>
 */
@Path("/stub")
public class StubUpstreamResource {

    /** 被禁用的 stub 路径段（如 "alpha"/"beta"）：命中即回 500（模拟上游宕机/网络不通） */
    public static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

    /** alpha 的工具：含一个与 beta 同名的 hisi_docs_search，以及一个 alpha 独有工具 */
    private static final Map<String, JsonObject> ALPHA_TOOLS = Map.of(
            "hisi_docs_search", tool("hisi_docs_search", "Search docs (alpha variant)"),
            "alpha_only", tool("alpha_only", "Tool that only alpha provides"));

    /** beta 的工具：与 alpha 同名的 hisi_docs_search + beta 独有工具 */
    private static final Map<String, JsonObject> BETA_TOOLS = Map.of(
            "hisi_docs_search", tool("hisi_docs_search", "Search docs (beta variant)"),
            "beta_only", tool("beta_only", "Tool that only beta provides"));

    private static JsonObject tool(String name, String description) {
        return new JsonObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", new JsonObject()
                        .put("type", "object")
                        .put("properties", new JsonObject()
                                .put("query", new JsonObject().put("type", "string")))
                        .put("required", new JsonArray().add("query")));
    }

    /**
     * 模拟上游 MCP 端点，按 JSON-RPC method 分发。
     *
     * @param upstream 上游路径段（alpha / beta）
     * @param body     JSON-RPC 请求体
     * @return JSON-RPC 响应；被禁用时 HTTP 500（传输层失败，触发网关 failover）
     */
    @POST
    @Path("/{upstream}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handle(@PathParam("upstream") String upstream, JsonObject body) {
        if (DISABLED.contains(upstream)) {
            // 500 = 传输层失败（区别于 JSON-RPC error 的 HTTP 200），网关应切下一个主备候选
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
        String method = body.getString("method");
        if (method == null) {
            return Response.ok(error(body, -32600, "missing method")).build();
        }
        JsonObject payload = switch (method) {
            case "initialize" -> result(body, new JsonObject()
                    .put("protocolVersion", "2025-03-26")
                    .put("capabilities", new JsonObject().put("tools", new JsonObject()))
                    .put("serverInfo", new JsonObject().put("name", "stub-" + upstream).put("version", "test")));
            case "notifications/initialized" -> new JsonObject();
            case "tools/list" -> result(body, new JsonObject()
                    .put("tools", new JsonArray(toolsOf(upstream).values().stream().toList())));
            case "tools/call" -> toolsCall(upstream, body);
            default -> error(body, -32601, "Unknown method: " + method);
        };
        return Response.ok(payload).build();
    }

    private Map<String, JsonObject> toolsOf(String upstream) {
        return "beta".equals(upstream) ? BETA_TOOLS : ALPHA_TOOLS;
    }

    /**
     * 工具调用：未知工具回 {@code -32601}（与 dev 环境 9802 的
     * 「工具 xxx 不在允许列表」同语义），已知工具回一段可断言的文本。
     *
     * <p>回文本里带上路径段名与工具名，测试据此断言<b>转发确实落到了正确的上游</b>——
     * 这是前缀命名空间与主备路由都要靠它来验证的关键。</p>
     */
    private JsonObject toolsCall(String upstream, JsonObject body) {
        JsonObject params = body.getJsonObject("params");
        String name = params == null ? null : params.getString("name");
        if (name == null || !toolsOf(upstream).containsKey(name)) {
            return error(body, -32601, "Tool " + name + " is not allowed");
        }
        return result(body, new JsonObject()
                .put("content", new JsonArray().add(new JsonObject()
                        .put("type", "text")
                        .put("text", "called " + name + " on " + upstream))));
    }

    /** 构造 JSON-RPC 成功响应（回带请求 id） */
    private JsonObject result(JsonObject request, JsonObject result) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", request.getValue("id"))
                .put("result", result);
    }

    /** 构造 JSON-RPC 错误响应；**HTTP 状态仍是 200**（复刻上游真实行为） */
    private JsonObject error(JsonObject request, int code, String message) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", request.getValue("id"))
                .put("error", new JsonObject().put("code", code).put("message", message));
    }
}