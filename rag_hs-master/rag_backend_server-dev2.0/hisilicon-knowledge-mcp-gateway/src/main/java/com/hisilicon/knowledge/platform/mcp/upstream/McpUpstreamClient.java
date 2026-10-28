package com.hisilicon.knowledge.platform.mcp.upstream;

import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游 MCP 服务客户端：按 JSON-RPC 2.0 调 {@code initialize} / {@code tools/list} / {@code tools/call}。
 *
 * <p><b>为什么用 WebClient 而不是 Rest Client</b>：上游地址是**运行期配置**（可增减、可切内网），
 * {@code @RegisterRestClient} 需要编译期固定 baseUri；且 MCP 是同一个端点上的 JSON-RPC，
 * 没有 REST 资源路径可言。</p>
 *
 * <p><b>实测得到的三个上游行为（本类据此实现，勿按"标准 REST"直觉改写）</b>：</p>
 * <ol>
 *   <li>错误也返回 <b>HTTP 200</b>，错误信息在响应体的 JSON-RPC {@code error} 字段里
 *       （如未知工具返回 {@code -32601}）。<b>不能只看状态码判成败。</b></li>
 *   <li>响应是纯 {@code application/json}，**没有 SSE 流**（GET 该端点返回 405）。</li>
 *   <li>协议版本上游不敏感：9800 对 {@code 2024-11-05} 与 {@code 2025-06-18} 都接受并原样回显。
 *       因此这里按 {@code 2025-03-26} 协商（网关对外支持版本与对上游协商版本**分别维护**）。</li>
 * </ol>
 *
 * <p>{@code initialize} 返回的 {@code Mcp-Session-Id} 会被记住并在后续请求回带：
 * 9800/9801 会下发 session，9802/9803 不下发（无 session 也能调，故为可选）。</p>
 */
@ApplicationScoped
public class McpUpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(McpUpstreamClient.class);

    /** 与上游协商的协议版本（网关对外支持的版本另由扩展决定，两者分别维护，见方案 §2） */
    private static final String UPSTREAM_PROTOCOL_VERSION = "2025-03-26";

    /** JSON-RPC 错误码：方法不存在（上游用它表达"工具不在允许列表"） */
    public static final int JSONRPC_METHOD_NOT_FOUND = -32601;

    private static final String HEADER_SESSION_ID = "mcp-session-id";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    private final WebClient webClient;
    private final McpGatewayConfig config;

    /** 上游会话 id 缓存（上游不下发 session 时为空，不影响调用） */
    private final java.util.concurrent.ConcurrentMap<String, String> sessionIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong requestIds = new AtomicLong();

    @Inject
    public McpUpstreamClient(@Upstream WebClient webClient, McpGatewayConfig config) {
        this.webClient = webClient;
        this.config = config;
    }

    /**
     * 拉取某上游的工具清单。
     *
     * <p>先 {@code initialize} 建会话，再 {@code notifications/initialized}，最后 {@code tools/list}。
     * 任一步失败即整体失败（由调用方做**单上游隔离**：跳过该上游，不影响其余）。</p>
     *
     * @param upstream 上游配置
     * @return 该上游暴露的工具数组（原始 JSON，schema 不改写）
     */
    public Uni<JsonArray> listTools(McpGatewayConfig.Upstream upstream) {
        return initialize(upstream)
                .chain(() -> notifyInitialized(upstream))
                .chain(() -> call(upstream, "tools/list", new JsonObject()))
                .map(result -> {
                    JsonArray tools = result.getJsonArray("tools");
                    if (tools == null) {
                        // 上游无 tools 能力时返回空数组而非 null，避免调用方到处判空
                        return new JsonArray();
                    }
                    return tools;
                });
    }

    /**
     * 调用上游工具。
     *
     * @param upstream   上游配置
     * @param toolName   上游侧的**原始**工具名（已剥掉网关前缀）
     * @param arguments  调用参数
     * @return 上游响应中的 {@code result} 对象
     */
    public Uni<JsonObject> callTool(McpGatewayConfig.Upstream upstream, String toolName, JsonObject arguments) {
        JsonObject params = new JsonObject()
                .put("name", toolName)
                .put("arguments", arguments == null ? new JsonObject() : arguments);
        return call(upstream, "tools/call", params);
    }

    /** 是否已与某上游建立会话（供状态展示；不下发 session 的上游恒为 false，属正常） */
    public boolean hasSession(String upstreamName) {
        return sessionIds.containsKey(upstreamName);
    }

    private Uni<JsonObject> initialize(McpGatewayConfig.Upstream upstream) {
        JsonObject params = new JsonObject()
                .put("protocolVersion", UPSTREAM_PROTOCOL_VERSION)
                .put("capabilities", new JsonObject())
                .put("clientInfo", new JsonObject()
                        .put("name", "hisilicon-knowledge-mcp-gateway")
                        .put("version", "1.0"));
        return call(upstream, "initialize", params).map(result -> {
            String sessionId = sessionIds.get(upstream.name());
            String negotiated = result.getString("protocolVersion");
            log.info("Upstream '{}' initialized, negotiated protocolVersion={}, session={}",
                    upstream.name(), negotiated, sessionId == null ? "<none>" : sessionId);
            return result;
        });
    }

    /**
     * 发送 {@code notifications/initialized}。
     *
     * <p>这是**通知**（无 id、无响应体）：按 MCP 规范服务器应回 202/204 或 200 空体。
     * 上游实现不一，因此**只要 HTTP 成功即视为通过**，不解析响应体——
     * 若对通知强解析 JSON-RPC 响应，会在"200 空体"的上游上误判失败。</p>
     */
    private Uni<Void> notifyInitialized(McpGatewayConfig.Upstream upstream) {
        JsonObject notification = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized");
        return send(upstream, notification).map(resp -> (Void) null);
    }

    /**
     * 发一个带 id 的 JSON-RPC 请求并取 {@code result}。
     *
     * <p>把「HTTP 层成败」与「JSON-RPC 层成败」分开判定：上游错误一律 HTTP 200，
     * 必须解析响应体的 {@code error} 字段（实测行为 1）。</p>
     */
    private Uni<JsonObject> call(McpGatewayConfig.Upstream upstream, String method, JsonObject params) {
        long id = requestIds.incrementAndGet();
        JsonObject request = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params);
        return send(upstream, request).map(resp -> extractResult(upstream, method, resp));
    }

    /** 解析 JSON-RPC 响应体：优先取 {@code error} 判失败，再取 {@code result} */
    private JsonObject extractResult(McpGatewayConfig.Upstream upstream, String method, JsonObject body) {
        if (body == null) {
            throw new McpUpstreamException(upstream.name(),
                    "empty response body for method " + method);
        }
        JsonObject error = body.getJsonObject("error");
        if (error != null) {
            Integer code = error.getInteger("code");
            String message = error.getString("message");
            throw new McpUpstreamException(upstream.name(), method,
                    code == null ? -32603 : code, message == null ? "unknown upstream error" : message);
        }
        JsonObject result = body.getJsonObject("result");
        if (result == null) {
            // 有 result 但非对象（如 JSON-RPC 批量/异常形态）时，包一层以免调用方 NPE
            Object raw = body.getValue("result");
            if (raw == null) {
                throw new McpUpstreamException(upstream.name(),
                        "response has neither 'result' nor 'error' for method " + method);
            }
            return new JsonObject().put("value", raw);
        }
        return result;
    }

    /** 发送请求：HTTP 成功即取 body 转 JSON；HTTP 失败抛 {@link McpUpstreamException} */
    private Uni<JsonObject> send(McpGatewayConfig.Upstream upstream, JsonObject payload) {
        var request = webClient.postAbs(upstream.url())
                .putHeader("Content-Type", "application/json")
                // 明确只要 JSON：实测上游是纯 JSON 响应；同时带上 SSE 以便兼容未来改为流式的上游
                .putHeader("Accept", "application/json, text/event-stream")
                .putHeader(HEADER_PROTOCOL_VERSION, UPSTREAM_PROTOCOL_VERSION);
        String sessionId = sessionIds.get(upstream.name());
        if (sessionId != null) {
            request.putHeader(HEADER_SESSION_ID, sessionId);
        }
        return request
                .sendJsonObject(payload)
                .map(resp -> {
                    captureSessionId(upstream, resp);
                    return toJson(upstream, resp);
                })
                .onFailure().transform(t -> McpUpstreamException.from(upstream.name(), t));
    }

    /** 记住 initialize 下发的 session id（可选：9802/9803 不下发） */
    private void captureSessionId(McpGatewayConfig.Upstream upstream, HttpResponse<Buffer> resp) {
        String sessionId = resp.getHeader(HEADER_SESSION_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            sessionIds.put(upstream.name(), sessionId);
        }
    }

    private JsonObject toJson(McpGatewayConfig.Upstream upstream, HttpResponse<Buffer> resp) {
        int status = resp.statusCode();
        String body = resp.bodyAsString();
        if (status < 200 || status >= 300) {
            throw new McpUpstreamException(upstream.name(),
                    "upstream returned HTTP " + status + " for " + upstream.url());
        }
        if (body == null || body.isBlank()) {
            // 通知类请求的正常形态（202/204 或 200 空体）
            return new JsonObject();
        }
        try {
            return new JsonObject(body);
        } catch (RuntimeException e) {
            throw new McpUpstreamException(upstream.name(),
                    "upstream returned a non-JSON body (len=" + body.length() + ")", e);
        }
    }
}