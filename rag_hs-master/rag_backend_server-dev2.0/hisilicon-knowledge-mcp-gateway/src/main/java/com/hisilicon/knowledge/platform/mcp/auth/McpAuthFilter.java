package com.hisilicon.knowledge.platform.mcp.auth;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.PlatformJwtVerifier;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MCP 入站鉴权 filter（Vert.x 平台层，先于全部路由执行）。
 *
 * <p><b>为什么不是 ContainerRequestFilter</b>：MCP 端点 {@code /mcp} 由
 * quarkus-mcp-server-http 扩展以原生 Vert.x 路由挂载，不是 JAX-RS 资源，
 * REST 层过滤器根本拦不到。Quarkus 的平台级 filter（{@code io.quarkus.vertx.http}
 * 的 {@link Filters} 扩展点）注册在业务路由之前，是唯一正确的拦截层。</p>
 *
 * <p><b>注册方式（实测踩坑）</b>：Quarkus 3.39.2 <b>不会</b>自动把实现了
 * {@code Filter} 接口的 CDI bean 挂到路由上 —— {@code VertxHttpProcessor#finalizeRouter}
 * 只消费 {@code FilterBuildItem}，扩展内没有任何 build step 扫描用户 {@code Filter} bean。
 * 唯一受支持的机制是观察 {@link Filters} 事件并调用 {@code register(handler, priority)}
 * （见官方 {@code Filters} javadoc 的用法示例）。早期写成
 * {@code implements Filter} + {@code @ApplicationScoped} 时 filter 静默不生效，
 * 全部请求直接 200 放行，测试里连一行日志都打不出来。</p>
 *
 * <p>校验序（方案 §鉴权）：</p>
 * <ol>
 *   <li>非保护路径（探针 / 根 / 未启用鉴权）直接放行；</li>
 *   <li>{@code Authorization: Bearer} → {@link PlatformJwtVerifier#verify(String)}
 *       （RS256 验签 + exp），再自行区分 {@code type} 是否为 {@code mcp}；</li>
 *   <li>Redis 吊销检查 —— <b>fail-closed</b>：Redis 故障回 503（token 泄漏风险优先）；</li>
 *   <li>限额检查 —— 结果（含超限标记）放入 RoutingContext，超限<b>不在这里拒绝</b>，
 *       放行到工具层回 JSON-RPC 工具级 error（保持 MCP 协议语义，见 McpCallGate）；</li>
 *   <li>{@code /gateway/**} 管理面额外要求 roles 含 sys_admin（403）。</li>
 * </ol>
 *
 * <p><b>失败响应</b>：HTTP 401/403/503 + {@code {"code","error","message"}}，其中
 * {@code message} 按 {@code Accept-Language} 本地化（{@link McpAuthMessages}），
 * 401 另带标准 {@code WWW-Authenticate: Bearer error=... error_description=...}，
 * 让 MCP 客户端能把"该怎么做"直接展示给用户。不实现 MCP OAuth 元数据（路线 B 留二期）。</p>
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    /** 平台 filter 优先级：需早于业务路由，且晚于 CORS(300)/安全层即可 */
    private static final int PRIORITY = 100;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String CLAIM_JTI = "jti";

    /** 期望的令牌类型（签发侧 {@code McpToken.TYPE_MCP}，system 模块） */
    private static final String EXPECTED_TYPE = "mcp";

    /** 401 挑战的 realm，便于客户端区分是哪个服务拒绝的 */
    private static final String REALM = "hisilicon-knowledge-mcp";

    /** 受保护的 MCP 端点结尾段（root-path 由 Quarkus 挂在子路由，见 matchesProtectedPath） */
    private static final String MCP_SUFFIX = "/mcp";

    /** 管理面结尾段 */
    private static final String ADMIN_SUFFIX = "/gateway";

    // ========== 错误码（稳定、机器可读；message 才是给人看的本地化文案） ==========

    private static final String CODE_MISSING = "MCP_TOKEN_MISSING";
    private static final String CODE_INVALID = "MCP_TOKEN_INVALID";
    private static final String CODE_EXPIRED = "MCP_TOKEN_EXPIRED";
    private static final String CODE_TYPE_MISMATCH = "MCP_TOKEN_TYPE_MISMATCH";
    private static final String CODE_CLAIMS_MISSING = "MCP_TOKEN_CLAIMS_MISSING";
    private static final String CODE_REVOKED = "MCP_TOKEN_REVOKED";
    private static final String CODE_FORBIDDEN = "MCP_FORBIDDEN";
    private static final String CODE_BACKEND_UNAVAILABLE = "MCP_AUTH_BACKEND_UNAVAILABLE";

    // ========== 消息键（messages/mcp_*.properties） ==========

    private static final String KEY_MISSING = "mcp.auth.missing";
    private static final String KEY_INVALID = "mcp.auth.invalid";
    private static final String KEY_EXPIRED = "mcp.auth.expired";
    private static final String KEY_TYPE_MISMATCH = "mcp.auth.typeMismatch";
    private static final String KEY_CLAIMS_MISSING = "mcp.auth.claimsMissing";
    private static final String KEY_REVOKED = "mcp.auth.revoked";
    private static final String KEY_FORBIDDEN = "mcp.auth.forbidden";
    private static final String KEY_BACKEND_UNAVAILABLE = "mcp.auth.backendUnavailable";

    private final PlatformJwtVerifier verifier;
    private final McpUsageStore store;
    private final String rootPath;
    private final boolean enabled;
    private final boolean adminGuard;

    private final long defaultDailyCallsLimit;
    private final long defaultDailyCharsLimit;

    @Inject
    public McpAuthFilter(PlatformJwtVerifier verifier,
                         McpUsageStore store,
                         @ConfigProperty(name = "quarkus.http.root-path") String rootPath,
                         @ConfigProperty(name = "mcp-gateway.auth.enabled", defaultValue = "true") boolean enabled,
                         @ConfigProperty(name = "mcp-gateway.auth.admin-guard", defaultValue = "true")
                         boolean adminGuard,
                         @ConfigProperty(name = "mcp-gateway.quota.daily-calls", defaultValue = "2000")
                         long defaultDailyCallsLimit,
                         @ConfigProperty(name = "mcp-gateway.quota.daily-chars", defaultValue = "20000000")
                         long defaultDailyCharsLimit) {
        this.verifier = verifier;
        this.store = store;
        this.rootPath = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        this.enabled = enabled;
        this.adminGuard = adminGuard;
        this.defaultDailyCallsLimit = defaultDailyCallsLimit;
        this.defaultDailyCharsLimit = defaultDailyCharsLimit;
    }

    /** 一次鉴权失败（状态码 + 稳定错误码 + 本地化消息键） */
    private record AuthFailure(int status, String code, String messageKey) {

        /** OAuth 风格的机器可读错误（WWW-Authenticate 用） */
        String wireError() {
            return status == 403 ? "insufficient_scope" : "invalid_token";
        }
    }

    /** 鉴权结果：成功带身份，失败带 {@link AuthFailure}，二者互斥 */
    private record AuthOutcome(McpPrincipal principal, AuthFailure failure) {

        static AuthOutcome ok(McpPrincipal principal) {
            return new AuthOutcome(principal, null);
        }

        static AuthOutcome fail(AuthFailure failure) {
            return new AuthOutcome(null, failure);
        }
    }

    /**
     * 平台 filter 注册点：Quarkus 在路由 finalize 之前发出 {@link Filters} 事件，
     * 这里把 {@link #accept} 挂成优先级 {@link #PRIORITY} 的全局 filter。
     * 用 {@code @Observes} 而非 {@code implements Filter}（后者在本版本静默失效，见类注释）。
     */
    void register(@Observes Filters filters) {
        filters.register(this::accept, PRIORITY);
    }

    public void accept(RoutingContext rc) {
        if (!enabled) {
            rc.next();
            return;
        }
        String path = rc.normalizedPath();
        boolean mcpEndpoint = matchesProtectedPath(path, MCP_SUFFIX);
        boolean adminEndpoint = adminGuard && matchesProtectedPath(path, ADMIN_SUFFIX);
        if (!mcpEndpoint && !adminEndpoint) {
            rc.next();
            return;
        }

        String language = rc.request().getHeader(HEADER_ACCEPT_LANGUAGE);
        AuthOutcome outcome = authenticate(rc, adminEndpoint);
        if (outcome.failure() != null) {
            deny(rc, outcome.failure(), language);
            return;
        }
        enforceRevocationAndQuota(rc, outcome.principal(), language);
    }

    /** 纯本地校验：Authorization 头 → 验签 → type=sub/jti → 管理面角色 */
    private AuthOutcome authenticate(RoutingContext rc, boolean adminEndpoint) {
        String header = rc.request().getHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)
                || header.substring(BEARER_PREFIX.length()).isBlank()) {
            log.info("MCP request rejected: missing or malformed Authorization header");
            return AuthOutcome.fail(new AuthFailure(401, CODE_MISSING, KEY_MISSING));
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        JwtClaims claims;
        try {
            claims = verifier.verify(token);
        } catch (BusinessException e) {
            return AuthOutcome.fail(classifyVerificationFailure(e));
        }

        Object rawType = claims.getClaimValue(PlatformJwtVerifier.CLAIM_TYPE);
        if (!EXPECTED_TYPE.equals(rawType)) {
            log.info("MCP token rejected: type is '{}', expected '{}'", rawType, EXPECTED_TYPE);
            return AuthOutcome.fail(new AuthFailure(401, CODE_TYPE_MISMATCH, KEY_TYPE_MISMATCH));
        }

        String username;
        Object rawJti;
        try {
            username = claims.getSubject();
            rawJti = claims.getClaimValue(CLAIM_JTI);
        } catch (MalformedClaimException e) {
            log.info("MCP token rejected: malformed sub claim ({})", e.getMessage());
            return AuthOutcome.fail(new AuthFailure(401, CODE_CLAIMS_MISSING, KEY_CLAIMS_MISSING));
        }
        if (username == null || username.isBlank() || !(rawJti instanceof String jti) || jti.isBlank()) {
            // MCP 专用 token 必带 jti（签发侧保证）；缺 jti 视为无效，杜绝计量/吊销旁路
            log.info("MCP token rejected: missing sub/jti claims");
            return AuthOutcome.fail(new AuthFailure(401, CODE_CLAIMS_MISSING, KEY_CLAIMS_MISSING));
        }

        List<String> roles = rolesOf(claims);
        boolean admin = roles.contains(McpPrincipal.ROLE_SYS_ADMIN);
        if (adminEndpoint && !admin) {
            log.info("MCP request rejected: user {} lacks {}", username, McpPrincipal.ROLE_SYS_ADMIN);
            return AuthOutcome.fail(new AuthFailure(403, CODE_FORBIDDEN, KEY_FORBIDDEN));
        }
        return AuthOutcome.ok(new McpPrincipal(username, jti, admin));
    }

    /**
     * 把平台验签异常映射为可区分的失败类型 —— 用户最需要区分的就是"过期了该重新签发"
     * 和"令牌根本不对"，笼统回一句 invalid 等于把排查成本丢给用户。
     */
    private AuthFailure classifyVerificationFailure(BusinessException e) {
        ErrorCodeEnum errorCode = e.getErrorCodeEnum();
        log.info("MCP token rejected: {}", e.getMessage());
        if (errorCode == ErrorCodeEnum.OIDC_TOKEN_EXPIRED) {
            return new AuthFailure(401, CODE_EXPIRED, KEY_EXPIRED);
        }
        if (errorCode == ErrorCodeEnum.SYSTEM_ERROR_B0601) {
            // 验签公钥缺失/不可解析：服务端配置问题，不是用户令牌问题
            log.error("MCP auth misconfigured: {}", e.getMessage());
            return new AuthFailure(503, CODE_BACKEND_UNAVAILABLE, KEY_BACKEND_UNAVAILABLE);
        }
        return new AuthFailure(401, CODE_INVALID, KEY_INVALID);
    }

    /** 需要 I/O 的两步：吊销 fail-closed，限额检查降级放行（结果交给工具层执行） */
    private void enforceRevocationAndQuota(RoutingContext rc, McpPrincipal principal, String language) {
        store.isRevoked(principal.jti())
                .chain(revoked -> {
                    if (Boolean.TRUE.equals(revoked)) {
                        log.info("MCP token jti={} (user {}) is revoked", principal.jti(), principal.username());
                        deny(rc, new AuthFailure(401, CODE_REVOKED, KEY_REVOKED), language);
                        return Uni.createFrom().voidItem();
                    }
                    return store.usage(principal.username(), defaultDailyCallsLimit, defaultDailyCharsLimit)
                            .map(snapshot -> {
                                rc.put(McpCallGate.CONTEXT_KEY,
                                        new McpCallGate.RequestContextHolder(principal, snapshot));
                                rc.next();
                                return null;
                            });
                })
                .subscribe().with(ok -> {
                }, t -> {
                    // 吊销/用量存储不可用：fail-closed（宁可拒绝，也不放过可能已被注销的令牌）
                    log.error("MCP auth chain failed, denying: {}", t.getMessage());
                    deny(rc, new AuthFailure(503, CODE_BACKEND_UNAVAILABLE, KEY_BACKEND_UNAVAILABLE), language);
                });
    }

    private void deny(RoutingContext rc, AuthFailure failure, String language) {
        if (rc.response().ended()) {
            return;
        }
        String message = McpAuthMessages.get(failure.messageKey(), language);
        HttpServerResponse response = rc.response()
                .setStatusCode(failure.status())
                .putHeader("Content-Type", "application/json; charset=utf-8");
        if (failure.status() == 401) {
            response.putHeader("WWW-Authenticate", "Bearer realm=\"" + REALM
                    + "\", error=\"" + failure.wireError()
                    + "\", error_description=\"" + headerSafe(message) + "\"");
        }
        response.end("{\"code\":\"" + failure.code() + "\",\"error\":\"" + failure.wireError()
                + "\",\"message\":\"" + headerSafe(message) + "\"}");
    }

    /** JSON 字符串与 HTTP 头值的兜底转义（消息来自本地资源，仍不假设其安全） */
    private static String headerSafe(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    private static List<String> rolesOf(JwtClaims claims) {
        Object raw = claims.getClaimValue(McpPrincipal.CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.split(","));
        }
        return List.of();
    }

    /**
     * 受保护路径判定：先把 {@code root-path} 前缀剥掉再比较，绝对前缀与纯结尾段两种形态都能命中。
     *
     * <p>实测：Vert.x 平台 filter 拿到的 {@code normalizedPath()} <b>包含</b> root-path
     * （例如 {@code /hisilicon-knowledge-mcp/gateway/status}）。早期写成纯后缀比较
     * （{@code endsWith("/gateway")}）时，管理面 {@code /gateway/status} 因路径以
     * {@code /status} 结尾而<b>不命中</b>，非管理员即可读管理接口 —— 鉴权旁路。
     * 故改为：剥 root-path → 要求 {@code equals(suffix)} 或 {@code startsWith(suffix + "/")}。</p>
     */
    private boolean matchesProtectedPath(String path, String suffix) {
        String p = path;
        if (!rootPath.isEmpty() && p.startsWith(rootPath)) {
            p = p.substring(rootPath.length());
        }
        if (p.isEmpty()) {
            p = "/";
        }
        return p.equals(suffix) || p.startsWith(suffix + "/");
    }
}