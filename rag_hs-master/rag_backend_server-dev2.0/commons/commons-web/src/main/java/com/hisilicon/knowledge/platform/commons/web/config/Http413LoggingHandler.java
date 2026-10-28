package com.hisilicon.knowledge.platform.commons.web.config;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * HTTP 413（请求体超限）统一响应 + 日志处理器
 * <p>{@code quarkus.http.limits.max-body-size} 超限时，请求在 Vert.x 层（JAX-RS 之前）
 * 被 vertx-web BodyHandler 拒绝（{@code context.fail(413)}），此时 {@code @ServerRequestFilter}、
 * ExceptionMapper、Resource 方法均不可达，Quarkus 默认兜底只返回空 body 且不打日志。
 * 本处理器在请求链最前（order=-1000，早于 BodyHandler）做两件事：</p>
 * <ul>
 *   <li><b>提前拦截</b>：请求头 Content-Length 超过 {@code quarkus.http.limits.max-body-size}
 *       时直接返回统一 JSON {@code {code, message, data}}（HTTP 413），与项目业务错误格式一致；
 *       message 按 Accept-Language 头解析中英文。</li>
 *   <li><b>兜底日志</b>：Content-Length 缺失/谎报（chunked 等）场景仍由 BodyHandler 拦截，
 *       此时 body 结束时记录 WARN 日志（含 URI/Content-Length/客户端地址），便于部署后排查。</li>
 * </ul>
 * <p><b>为什么不用 {@code router.errorHandler(413, ...)} / failure handler（均已实测无效）</b>：
 * Vert.x 的 {@code fail(int)} 只设置 statusCode、不设置 failure（RoutingContextImpl.fail(int)），
 * 状态错误处理器 map 只在 {@code unhandledFailure}（失败链走完后）查询；而失败路由链上
 * RESTEasy/QuarkusErrorHandler（{@code route().last().failureHandler()}）会先于自定义失败处理器
 * 结束响应（{@code failure==null} 时静默 {@code setStatusCode().end()}），自定义 failure handler
 * 即使 order=-1000 也轮不到（2026-08 实测）。因此只能在 BodyHandler 之前自行拦截。</p>
 */
@ApplicationScoped
public class Http413LoggingHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http413LoggingHandler.class);

    private final Optional<MemorySize> maxBodySize;

    public Http413LoggingHandler(
            @ConfigProperty(name = "quarkus.http.limits.max-body-size")
            Optional<MemorySize> maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    void register(@Observes StartupEvent event, Router router) {
        router.route().order(-1000).handler(ctx -> {
            // 1) 提前拦截：Content-Length 明确超限 → 直接返回统一 JSON（不进入 BodyHandler）
            if (maxBodySize.isPresent()) {
                String contentLengthHeader = ctx.request().getHeader("Content-Length");
                if (contentLengthHeader != null && !contentLengthHeader.isBlank()) {
                    try {
                        long contentLength = Long.parseLong(contentLengthHeader.trim());
                        long limit = maxBodySize.get().asLongValue();
                        if (contentLength > limit) {
                            HttpServerRequest request = ctx.request();
                            String message = appMessage(request).httpBodyTooLarge(formatBytes(limit));
                            logger.warn("HTTP 413 request body too large: method={}, uri={}, contentLength={}, remoteAddress={}, userAgent={}",
                                    request.method(), request.uri(), contentLengthHeader,
                                    request.remoteAddress(), request.headers().get("User-Agent"));
                            ctx.response().setStatusCode(413)
                                    .putHeader("Content-Type", "application/json")
                                    .end("{\"code\":\"A0715\",\"message\":\"" + message + "\",\"data\":null}");
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // Content-Length 头非法：交给 BodyHandler 正常处理
                    }
                }
            }

            // 2) 兜底：body 结束打日志（Content-Length 缺失/谎报场景由 BodyHandler 拦截）
            ctx.addBodyEndHandler(v -> {
                if (ctx.response().getStatusCode() == 413) {
                    HttpServerRequest request = ctx.request();
                    logger.warn("HTTP 413 request body too large: method={}, uri={}, contentLength={}, remoteAddress={}, userAgent={}",
                            request.method(),
                            request.uri(),
                            request.headers().get("Content-Length"),
                            request.remoteAddress(),
                            request.headers().get("User-Agent"));
                }
            });
            ctx.next();
        });
    }

    /** 按 Accept-Language 头解析消息类（不支持的 locale 回退中文） */
    private AppMessages appMessage(HttpServerRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        try {
            if (acceptLanguage != null && !acceptLanguage.isBlank()) {
                String first = acceptLanguage.split(",")[0].trim();
                return LocaleUtil.getAppMessage(first);
            }
        } catch (IllegalArgumentException e) {
            // 不支持的语言回退中文
        }
        return LocaleUtil.getAppMessage("zh-CN");
    }

    /** 字节数 → 人类可读格式（1G/512MB/10KB），用于限制值展示 */
    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return (bytes / (1024L * 1024 * 1024)) + "G";
        }
        if (bytes >= 1024L * 1024) {
            return (bytes / (1024L * 1024)) + "MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + "KB";
        }
        return bytes + "B";
    }
}
