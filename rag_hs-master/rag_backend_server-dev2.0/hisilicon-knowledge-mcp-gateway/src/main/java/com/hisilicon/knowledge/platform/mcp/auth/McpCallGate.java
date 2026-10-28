package com.hisilicon.knowledge.platform.mcp.auth;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具转发层的限额执行与计量入口（ToolRegistry#forward 唯一收口处使用）。
 *
 * <p>数据来源：{@link McpAuthFilter} 在请求入口把身份 + 当日用量快照放入
 * RoutingContext（经 {@link CurrentVertxRequest} 从任意 Vert.x 重复上下文取回）。
 * 取不到（无 HTTP 传输 / 鉴权关闭）时视为"无身份"：限额不拦截、计量跳过——
 * 这同时是鉴权关闭（内测直连）形态下的行为。</p>
 */
@ApplicationScoped
public class McpCallGate {

    private static final Logger log = LoggerFactory.getLogger(McpCallGate.class);

    /** RoutingContext.data() 中的键 */
    public static final String CONTEXT_KEY = "mcp.auth.context";

    private static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String KEY_QUOTA_EXCEEDED = "mcp.quota.exceeded";

    private final CurrentVertxRequest currentRequest;
    private final McpUsageStore store;

    @Inject
    public McpCallGate(CurrentVertxRequest currentRequest, McpUsageStore store) {
        this.currentRequest = currentRequest;
        this.store = store;
    }

    /** filter 放入上下文的载荷（RoutingContext.data 只收非 null 值，故包一层 record） */
    public record RequestContextHolder(McpPrincipal principal, McpUsageStore.UsageSnapshot snapshot) {
    }

    /**
     * @return 当前请求的调用方上下文；无（未鉴权 / 非 HTTP）时为 null
     */
    public RequestContextHolder current() {
        RoutingContext rc = currentRequest.getCurrent();
        if (rc == null) {
            return null;
        }
        return rc.get(CONTEXT_KEY);
    }

    /**
     * 是否超限（超限的请求放行到这里，由工具层回 JSON-RPC error 而非 HTTP 401/429，
     * 保持 MCP 协议语义——agent 能把错误当工具结果处理）。
     */
    public boolean overQuota() {
        RequestContextHolder ctx = current();
        return ctx != null && ctx.snapshot().overQuota();
    }

    /**
     * 超限时回给 agent 的提示（按请求的 {@code Accept-Language} 本地化）。
     *
     * <p>超限不在这里拒绝而是回工具级 error，所以这条文案是唯一"额度用完"的对外信息 ——
     * 必须写清上限值和重置规则，否则用户只会看到一句无从下手的失败。</p>
     *
     * @return 本地化的超限说明；当前无请求上下文时用默认限额拼一条
     */
    public String exceededMessage() {
        long limitCalls = 0L;
        long limitChars = 0L;
        RequestContextHolder ctx = current();
        if (ctx != null) {
            limitCalls = ctx.snapshot().limitCalls();
            limitChars = ctx.snapshot().limitChars();
        }
        return McpAuthMessages.get(KEY_QUOTA_EXCEEDED, acceptLanguage(), limitCalls, limitChars);
    }

    /** 当前请求的 Accept-Language（无请求时为 null → 回落中文） */
    private String acceptLanguage() {
        RoutingContext rc = currentRequest.getCurrent();
        return rc == null ? null : rc.request().getHeader(HEADER_ACCEPT_LANGUAGE);
    }

    /**
     * 记一次成功调用。失败静默（计量不影响调用结果，方案既定口径）。
     *
     * @param chars 本次响应字符数
     */
    public void record(long chars) {
        RequestContextHolder ctx = current();
        if (ctx == null) {
            return;
        }
        McpPrincipal principal = ctx.principal();
        // store.record 自身 fail-silent；这里再兜一层，绝不让计量抛进工具链
        try {
            store.record(principal.username(), principal.jti(), chars)
                    .subscribe().with(ok -> {
                    }, t -> log.debug("usage record failed: {}", t.getMessage()));
        } catch (RuntimeException e) {
            log.debug("usage record failed: {}", e.getMessage());
        }
    }
}
