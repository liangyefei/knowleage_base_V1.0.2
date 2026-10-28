package com.hisilicon.knowledge.platform.mcp.registry;

import com.hisilicon.knowledge.platform.mcp.auth.McpCallGate;
import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;
import com.hisilicon.knowledge.platform.mcp.upstream.McpUpstreamClient;
import com.hisilicon.knowledge.platform.mcp.upstream.McpUpstreamException;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工具注册表：启动时**并发**拉取所有上游的 {@code tools/list}，把每个工具以
 * {@code 上游名__工具名} 注册进官方 {@link ToolManager}（MCP 协议层由扩展承担）。
 *
 * <p><b>三条设计口径</b>（对应《MCP 网关对接方案》§4 阶段 1）：</p>
 * <ol>
 *   <li><b>失败隔离</b>：单个上游拉取失败/超时只跳过该上游，其余工具照常注册，
 *       端点不被拖死；失败的上游记入 {@link #degradedUpstreams()}，可在
 *       {@code /mcp/gateway/status} 看到。</li>
 *   <li><b>不缓存清单</b>：上游工具变更无通知，缓存会让 agent 侧 schema 漂移。
 *       故只在启动拉取 + 提供 {@link #refresh()} 手动刷新（上游升级版本后调一次即可，不必发版）。</li>
 *   <li><b>schema 原样透传</b>：不改写上游 {@code inputSchema}，避免与上游定义漂移。</li>
 * </ol>
 *
 * <p>注册是**幂等可重入**的：{@link #refresh()} 先移除上次注册的工具再重新注册，
 * 避免上游升级后残留旧工具。</p>
 */
@ApplicationScoped
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 主备路由描述的来源标注（区别于单上游路由的 {@link #buildDescription} 标注） */
    private static final String FAILOVER_ORIGIN =
            "[via MCP gateway | failover route | call uses first reachable upstream by priority]";

    /** 暴露名（含前缀）→ 路由 */
    private final ConcurrentMap<String, ToolRoute> routes = new ConcurrentHashMap<>();

    /** 已注册的**裸名别名**（刷新前也要一并移除，否则上游缩工具后会残留） */
    private final Set<String> aliasedNames = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 主备路由表：原始工具名 → 按 priority 排好序的候选（供状态接口展示与调用期 failover） */
    private final ConcurrentMap<String, List<ToolRoute>> failoverRoutes = new ConcurrentHashMap<>();

    /** 已成功拉取过工具的上游名 */
    private final Set<String> healthyUpstreams = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 拉取失败的上游名（degraded） */
    private final Set<String> degraded = Collections.synchronizedSet(new LinkedHashSet<>());

    private final McpGatewayConfig config;
    private final McpUpstreamClient upstreamClient;
    private final ToolManager toolManager;
    private final McpCallGate callGate;

    @Inject
    public ToolRegistry(McpGatewayConfig config, McpUpstreamClient upstreamClient, ToolManager toolManager,
                        McpCallGate callGate) {
        this.config = config;
        this.upstreamClient = upstreamClient;
        this.toolManager = toolManager;
        this.callGate = callGate;
    }

    /**
     * 启动时装载（可配置关闭：{@code mcp-gateway.load-on-startup=false}）。
     *
     * <p>装载是异步的：**不能阻塞启动**——否则任一上游慢会让 pod 起不来、探针失败。
     * 订阅后立即返回，装载在后台线程完成。</p>
     *
     * @param event 启动事件（Quarkus 生命周期）
     */
    void onStart(@Observes StartupEvent event) {
        if (!config.loadOnStartup()) {
            log.info("MCP gateway upstream loading is disabled (mcp-gateway.load-on-startup=false)");
            return;
        }
        refresh().subscribe().with(
                count -> log.info("MCP gateway aggregated {} upstream tool(s) from {} upstream(s); "
                                + "{} alias route(s) registered; degraded={}",
                        count, healthyUpstreams.size(), aliasedNames.size(), degraded),
                failure -> log.error("MCP gateway initial tool loading failed unexpectedly", failure));
    }

    /**
     * 重新拉取并注册全部上游工具。可安全重复调用。
     *
     * @return 本次注册成功的工具总数
     */
    public Uni<Integer> refresh() {
        List<McpGatewayConfig.Upstream> upstreams = config.upstreams();
        if (upstreams == null || upstreams.isEmpty()) {
            log.warn("No MCP upstream configured under 'mcp-gateway.upstreams'");
            return Uni.createFrom().item(0);
        }
        // 并发拉取各上游：任一个慢/挂不阻塞其余（失败隔离的第一层）
        List<Uni<UpstreamTools>> fetches = new ArrayList<>(upstreams.size());
        for (McpGatewayConfig.Upstream upstream : upstreams) {
            fetches.add(fetchOne(upstream));
        }
        return Uni.combine().all().unis(fetches)
                .with(list -> {
                    List<UpstreamTools> results = new ArrayList<>(list.size());
                    for (Object item : list) {
                        if (item instanceof UpstreamTools tools) {
                            results.add(tools);
                        }
                    }
                    return results;
                })
                .map(this::applyAll);
    }

    /**
     * 拉取单个上游；失败时**吞掉异常**并返回空的 {@code UpstreamTools}（记 degraded）。
     *
     * <p>这里的宽 catch 是**有意的边界兜底**（项目规范 §3 获准场景：审计/统计类消费失败不影响主流程）：
     * 网关的核心职责是"其余上游仍可用"，任何一个上游的失败都不允许冒泡成整体失败。</p>
     */
    private Uni<UpstreamTools> fetchOne(McpGatewayConfig.Upstream upstream) {
        return upstreamClient.listTools(upstream)
                .map(tools -> new UpstreamTools(upstream, tools, null))
                .onFailure().recoverWithItem(t -> new UpstreamTools(upstream, new JsonArray(), t));
    }

    /** 先清空上次注册，再按本次结果整体注册 */
    private synchronized Integer applyAll(List<UpstreamTools> results) {
        unregisterAll();
        healthyUpstreams.clear();
        degraded.clear();

        int registered = 0;
        for (UpstreamTools result : results) {
            if (result.failure() != null) {
                degraded.add(result.upstream().name());
                log.warn("Upstream '{}' is degraded (tools unavailable): {}",
                        result.upstream().name(), result.failure().getMessage());
                continue;
            }
            int count = registerUpstream(result.upstream(), result.tools());
            registered += count;
            healthyUpstreams.add(result.upstream().name());
            log.info("Registered {} tool(s) from upstream '{}'", count, result.upstream().name());
        }
        // 别名（不带前缀的裸名）必须在全部上游注册完后统一处理：
        // "是否唯一命中"只有在看全所有上游才能判定（方案 §4 阶段1 第 3 条）
        int aliasCount = registerAliases();
        // 只把**真实上游工具数**作为注册结果上报：别名是同一批工具的第二入口，
        // 把它也计入会让"上游到底聚合了多少工具"失真（自检接口 toolCount 即真实数）。
        log.info("Alias routing registered for {} upstream tool name(s)", aliasCount);
        return registered;
    }

    /**
     * 注册**不带前缀**的别名工具，落实方案 §4「不带前缀的工具（唯一命中时）直接路由；多义时报错并列出候选」。
     *
     * <p>为什么必须注册别名工具、而不能在 handler 里兜底：MCP 的 {@code tools/call} 在
     * <b>扩展层</b>就按工具名派发了，名字不存在直接回 {@code -32602}，根本走不到我们的 handler。
     * 所以"允许裸名调用"这件事必须在<b>注册阶段</b>表达出来。</p>
     *
     * <p><b>别名会出现在 {@code tools/list} 里（实测确认这是个二选一，无法两全）</b>：
     * 官方扩展的 {@code ToolFilter} 同时作用于 {@code tools/list} <b>与</b> {@code tools/call} 派发——
     * 把别名设为不可见后，调用它会被拒（报 {@code Invalid tool name}），即"隐藏但可调用"表达不出来。
     * 因此只能在两者间取舍：</p>
     * <ul>
     *   <li><b>保留别名并列出（当前选择）</b>：列表长度 = 上游工具数 × 2 左右（dev 环境 20 → 39），
     *       换来 agent 用裸名也能直接调通（很常见），多义时还能拿到候选清单；</li>
     *   <li>去掉别名：列表干净（正好 20），但裸名调用直接报 {@code -32602}，agent 需自己猜前缀。</li>
     * </ul>
     * <p>选择前者的理由：网关的职责是"让 agent 无痛用上模型侧工具"，兼容性优先于列表整洁；
     * 且多义裸名返回候选清单本身就是一份有效的自愈指引。若后续确认列表冗余更有害，
     * 只需删掉本方法与其调用即可回到"仅前缀名"的形态。</p>
     *
     * @return 本次注册的别名工具数
     */
    private int registerAliases() {
        Map<String, List<ToolRoute>> byOriginalName = new LinkedHashMap<>();
        for (ToolRoute route : routes.values()) {
            byOriginalName.computeIfAbsent(route.originalName(), k -> new ArrayList<>()).add(route);
        }

        int aliasCount = 0;
        for (Map.Entry<String, List<ToolRoute>> entry : byOriginalName.entrySet()) {
            String originalName = entry.getKey();
            List<ToolRoute> candidates = entry.getValue();
            if (candidates.size() > 1) {
                registerFailoverAlias(originalName, candidates);
                aliasCount++;
            }
            // 唯一命中：注册别名指向同一个路由（不同上游同名不会走到这里）
            else {
                ToolRoute route = candidates.get(0);
                registerAlias(originalName, route);
                aliasCount++;
            }
        }
        return aliasCount;
    }

    /** 唯一命中：别名直接复用同一路由的转发逻辑 */
    private void registerAlias(String alias, ToolRoute route) {
        aliasedNames.add(alias);
        toolManager.newTool(alias)
                .setDescription(route.description())
                .setInputSchema(route.inputSchema() == null ? defaultInputSchema() : route.inputSchema())
                .setAsyncHandler(args -> forward(route, args))
                .addHint(io.quarkiverse.mcp.server.TransportHint.STREAMABLE_HTTP_SKIP_SSE_INIT)
                .register();
    }

    /** 多义（同名工具存在于多个上游）：注册**主备 failover** 别名——按 priority 选主，主不通自动走备 */
    private void registerFailoverAlias(String originalName, List<ToolRoute> candidates) {
        // 按 priority 升序排主备顺序；同 priority 按配置声明序（稳定排序，candidates 本身来自
        // config.upstreams() 的遍历序，天然携带声明序）
        List<ToolRoute> ordered = new ArrayList<>(candidates);
        ordered.sort(java.util.Comparator.comparingInt(r -> r.upstream().priority()));
        List<String> orderNames = ordered.stream().map(ToolRoute::exposedName).toList();

        String description = "Failover route for upstream tool '" + originalName + "'"
                + " (provided by multiple upstreams); primary: " + orderNames.get(0)
                + (orderNames.size() > 1 ? "; fallbacks: " + String.join(", ", orderNames.subList(1, orderNames.size())) : "")
                + ". " + FAILOVER_ORIGIN;
        aliasedNames.add(originalName);
        toolManager.newTool(originalName)
                .setDescription(description)
                .setInputSchema(ordered.get(0).inputSchema() == null
                        ? defaultInputSchema() : ordered.get(0).inputSchema())
                .setAsyncHandler(args -> callWithFailover(originalName, ordered, args))
                .addHint(io.quarkiverse.mcp.server.TransportHint.STREAMABLE_HTTP_SKIP_SSE_INIT)
                .register();
        failoverRoutes.put(originalName, List.copyOf(ordered));
        log.info("Failover route registered for '{}': {}",
                originalName, ordered.stream().map(r -> r.upstream().name() + "(p" + r.upstream().priority() + ")").toList());
    }

    /**
     * 主备调用链：按序尝试候选，**仅传输层失败**（连不上/超时/非 2xx）才切下一个；
     * 上游业务应答（JSON-RPC error，如"工具不在允许列表"）**不切**——上游已应答，
     * 切备大概率同样失败，且会掩盖真实问题。
     *
     * <p>装载期隔离：degraded 的上游直接跳过（其 tools/list 已失败，工具状态未知）；
     * 全部候选都不可用时仍按序试一遍（degraded 集合可能过期），最终回工具级错误并列出尝试结果。</p>
     */
    private Uni<ToolResponse> callWithFailover(String originalName, List<ToolRoute> ordered,
                                               ToolManager.ToolArguments args) {
        JsonObject arguments = toJsonObject(args.args());
        List<ToolRoute> live = ordered.stream()
                .filter(r -> !degraded.contains(r.upstream().name()))
                .toList();
        List<ToolRoute> targets = live.isEmpty() ? ordered : live;
        return failoverChain(originalName, targets, arguments, new ArrayList<>());
    }

    /** 递归主备链：头部成功即返回；传输层失败则记下原因并递归剩余候选 */
    private Uni<ToolResponse> failoverChain(String originalName, List<ToolRoute> remaining,
                                            JsonObject arguments, List<String> failures) {
        ToolRoute head = remaining.get(0);
        List<ToolRoute> rest = remaining.subList(1, remaining.size());
        return upstreamClient.callTool(head.upstream(), head.originalName(), arguments)
                .map(result -> toToolResponse(head, result))
                .onFailure(failure -> failure instanceof McpUpstreamException m && m.getJsonRpcCode() == null)
                .recoverWithUni(failure -> {
                    failures.add(head.upstream().name() + ": " + failure.getMessage());
                    log.warn("Failover: upstream '{}' unavailable for tool '{}', trying next candidate: {}",
                            head.upstream().name(), originalName,
                            rest.isEmpty() ? "<none>" : rest.get(0).upstream().name());
                    if (rest.isEmpty()) {
                        return Uni.createFrom().item(ToolResponse.error(
                                "MCP gateway: tool '" + originalName + "' failed on all upstreams. Attempts: "
                                        + String.join("; ", failures)));
                    }
                    return failoverChain(originalName, rest, arguments, failures);
                });
    }

    /**
     * 注册单个上游的全部工具。
     *
     * <p>同上游内工具重名（极罕见）时后者覆盖前者并告警；跨上游同名**不会**冲突——
     * 暴露名自带上游前缀。</p>
     */
    private int registerUpstream(McpGatewayConfig.Upstream upstream, JsonArray tools) {
        int count = 0;
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.getJsonObject(i);
            if (tool == null) {
                continue;
            }
            String originalName = tool.getString("name");
            if (originalName == null || originalName.isBlank()) {
                log.warn("Upstream '{}' returned a tool without a name at index {}, skipped",
                        upstream.name(), i);
                continue;
            }
            String exposedName = ToolRoute.exposedName(config.nameSeparator(), upstream.name(), originalName);
            Object inputSchema = tool.getValue("inputSchema");
            String description = buildDescription(upstream, tool, originalName);

            ToolRoute route = new ToolRoute(exposedName, upstream, originalName, description, inputSchema);
            if (routes.put(exposedName, route) != null) {
                log.warn("Duplicate exposed tool name '{}' within upstream '{}', the later one wins",
                        exposedName, upstream.name());
            }
            registerWithManager(route);
            count++;
        }
        return count;
    }

    /**
     * 组装工具描述：保留上游原文，并注明真实来源，便于 agent 与排障定位。
     *
     * <p>前缀名对 agent 是黑盒，把上游与原始名写进描述后，agent 能理解工具来源；
     * 排障时也不用反查配置。</p>
     */
    private String buildDescription(McpGatewayConfig.Upstream upstream, JsonObject tool, String originalName) {
        String upstreamDescription = tool.getString("description");
        String origin = "[via MCP gateway | upstream: " + upstream.name() + " | tool: " + originalName + "]";
        if (upstreamDescription == null || upstreamDescription.isBlank()) {
            return origin;
        }
        return upstreamDescription + " " + origin;
    }

    /** 用官方 ToolManager 的编程式 API 注册（协议层、JSON-RPC 编解码全由扩展负责） */
    private void registerWithManager(ToolRoute route) {
        ToolManager.ToolDefinition definition = toolManager.newTool(route.exposedName())
                .setDescription(route.description())
                // 原样透传上游 schema；缺失时给一个宽松的对象 schema，避免 agent 侧看到 null
                .setInputSchema(route.inputSchema() == null ? defaultInputSchema() : route.inputSchema())
                .setAsyncHandler(args -> forward(route, args))
                // 上游是纯 JSON、无 SSE；跳过 SSE 初始化以免每次调用都挂一条无用的流
                .addHint(io.quarkiverse.mcp.server.TransportHint.STREAMABLE_HTTP_SKIP_SSE_INIT);
        definition.register();
    }

    /** 上游未提供 inputSchema 时的兜底：接受任意对象，保持工具可调用 */
    private JsonObject defaultInputSchema() {
        return new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject())
                .put("additionalProperties", true);
    }

    /**
     * 转发调用到上游。**这里是 token 限额执行与计量的唯一收口点**（方案 §计量）：
     * 超限请求不转发（回工具级 JSON-RPC error，保持 MCP 协议语义）；
     * 成功应答按响应字符数计量（chars/4 粗估 token，不求精确）。
     */
    private Uni<ToolResponse> forward(ToolRoute route, ToolManager.ToolArguments args) {
        if (callGate.overQuota()) {
            log.info("Tool call '{}' blocked: daily quota exhausted", route.exposedName());
            // 文案由 McpCallGate 按 Accept-Language 本地化，并带上限值与重置规则
            return Uni.createFrom().item(ToolResponse.error(callGate.exceededMessage()));
        }
        JsonObject arguments = toJsonObject(args.args());
        return upstreamClient.callTool(route.upstream(), route.originalName(), arguments)
                .map(result -> {
                    ToolResponse response = toToolResponse(route, result);
                    callGate.record(estimateChars(result));
                    return response;
                })
                .onFailure().recoverWithItem(t -> toErrorResponse(route, t));
    }

    /** 计量口径：响应字符数（单文本取正文长度，否则整个 result 的 JSON 长度）——与 serializeResult 同源 */
    private long estimateChars(JsonObject result) {
        Object content = result.getValue("content");
        if (content instanceof JsonArray array
                && array.size() == 1 && array.getValue(0) instanceof JsonObject single
                && "text".equals(single.getString("type")) && single.getString("text") != null) {
            return single.getString("text").length();
        }
        return result.encode().length();
    }

    /**
     * 把上游 {@code tools/call} 的 result 转成 {@link ToolResponse}。
     *
     * <p>MCP 的工具结果主体是 {@code content} 数组；这里把它序列化后原样带出，
     * **不解析、不改写**（方案 §1.3：不缓存、不改写工具结果）。
     * 上游自身标记的 {@code isError} 也会被保留，让 agent 看到真实的工具级失败。</p>
     */
    private ToolResponse toToolResponse(ToolRoute route, JsonObject result) {
        boolean isError = Boolean.TRUE.equals(result.getBoolean("isError"));
        String text = serializeResult(result);
        return isError ? ToolResponse.error(text) : ToolResponse.success(text);
    }

    /** 上游内容数组优先原样透传；没有 content 时退化为整个 result 的 JSON */
    private String serializeResult(JsonObject result) {
        Object content = result.getValue("content");
        if (content instanceof JsonArray array) {
            if (array.size() == 1 && array.getValue(0) instanceof JsonObject single
                    && "text".equals(single.getString("type")) && single.containsKey("text")) {
                // 单文本结果（上游绝大多数工具如此）：直接给正文，避免 agent 侧多剥一层 JSON
                return single.getString("text");
            }
            return array.encode();
        }
        return result.encode();
    }

    /** 上游调用失败：回一个**工具级错误结果**而不是抛协议异常，agent 才能看到原因并自行决定重试 */
    private ToolResponse toErrorResponse(ToolRoute route, Throwable failure) {
        String detail;
        if (failure instanceof McpUpstreamException upstreamFailure) {
            detail = "upstream '" + upstreamFailure.getUpstreamName() + "' failed"
                    + (upstreamFailure.getJsonRpcCode() == null
                            ? "" : " (JSON-RPC code " + upstreamFailure.getJsonRpcCode() + ")")
                    + ": " + failure.getMessage();
        } else {
            detail = failure.getMessage();
        }
        log.warn("Tool call '{}' failed: {}", route.exposedName(), detail);
        return ToolResponse.error("MCP gateway failed to call tool '" + route.exposedName() + "': " + detail);
    }

    /** {@code args()} 是 {@code Map<String,Object>}，转成 Vert.x JsonObject；null 视为空参数 */
    private JsonObject toJsonObject(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return new JsonObject();
        }
        try {
            return new JsonObject(args);
        } catch (RuntimeException e) {
            // 参数里含非 JSON 兼容类型时（理论上不该发生），退化为逐项 toString，保证调用仍可发出
            JsonObject fallback = new JsonObject();
            args.forEach((k, v) -> fallback.put(k, v == null ? null : String.valueOf(v)));
            return fallback;
        }
    }

    /** 移除本网关注册过的全部工具（含裸名别名），刷新前调用，避免上游缩减工具后残留 */
    private void unregisterAll() {
        for (String exposedName : routes.keySet()) {
            removeQuietly(exposedName);
        }
        routes.clear();
        for (String alias : aliasedNames) {
            removeQuietly(alias);
        }
        aliasedNames.clear();
        failoverRoutes.clear();
    }

    /** 边界兜底：移除失败不应中断装载流程（规范 §3 获准场景） */
    private void removeQuietly(String toolName) {
        try {
            toolManager.removeTool(toolName);
        } catch (RuntimeException e) {
            log.warn("Failed to remove stale tool '{}'", toolName, e);
        }
    }

    /** @return 当前暴露名 → 路由的只读快照 */
    public Map<String, ToolRoute> routes() {
        return Map.copyOf(routes);
    }

    /** @return 已成功拉取工具的上游名 */
    public Set<String> healthyUpstreams() {
        return Set.copyOf(healthyUpstreams);
    }

    /** @return 拉取失败（degraded）的上游名 */
    public Set<String> degradedUpstreams() {
        return Set.copyOf(degraded);
    }

    /** @return 已注册的工具数 */
    public int toolCount() {
        return routes.size();
    }

    /**
     * 按暴露名精确查找路由。
     *
     * @param exposedName 暴露名
     * @return 命中的路由
     */
    public Optional<ToolRoute> findExact(String exposedName) {
        return Optional.ofNullable(routes.get(exposedName));
    }

    /**
     * 按**上游侧原始工具名**查找路由（对应方案 §4「不带前缀的工具（唯一命中时）直接路由」）。
     *
     * @param originalName 上游侧原始工具名
     * @return 唯一命中时返回该路由；无命中为空；多义时抛
     *         {@link com.hisilicon.knowledge.platform.mcp.upstream.McpUpstreamException} 语义的歧义错误
     */
    public List<ToolRoute> findByOriginalName(String originalName) {
        List<ToolRoute> matched = new ArrayList<>();
        for (ToolRoute route : routes.values()) {
            if (route.originalName().equals(originalName)) {
                matched.add(route);
            }
        }
        return matched;
    }

    /**
     * 主备路由表快照：原始工具名 → 按 priority 升序的候选（首个为主，其余为备）。
     *
     * @return 只读快照
     */
    public Map<String, List<ToolRoute>> failoverRoutes() {
        return Map.copyOf(failoverRoutes);
    }

    /** 单个上游的拉取结果（成功带 tools，失败带 failure） */
    private record UpstreamTools(McpGatewayConfig.Upstream upstream, JsonArray tools, Throwable failure) {
    }
}