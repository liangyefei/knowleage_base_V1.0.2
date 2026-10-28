package com.hisilicon.knowledge.platform.commons.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 审计日志 CDI 拦截器。
 * <p>
 * 拦截所有标注了 {@link AuditLog} 的方法，提取操作上下文并通过
 * {@link Event#fireAsync(Object)} 异步分发审计事件给 {@link AuditLogEventHandler}。
 * </p>
 */
@AuditLog
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 100)
public class AuditLogInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditLogInterceptor.class);

    /** 私有 ObjectMapper，避免与 Quarkus 注入的 ObjectMapper 耦合 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** 需要掩码的敏感字段名 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "token", "accessToken", "refreshToken",
            "secret", "apiKey", "credential", "authorization"
    );

    /** 可作为资源 ID 的参数名（G.CTL.01：条件表达式收敛为集合包含判断） */
    private static final Set<String> RESOURCE_ID_PARAM_NAMES = Set.of(
            "resourceId", "id", "kbId", "userId", "roleId", "docId"
    );

    @Inject
    Event<AuditLogEntry> auditEvent;

    @Inject
    HttpServerRequest httpRequest;

    @AroundInvoke
    Object audit(InvocationContext ctx) throws Exception {
        // 1) 提取注解
        AuditLog annotation = ctx.getMethod().getAnnotation(AuditLog.class);
        if (annotation == null) {
            // 类级别注解
            annotation = ctx.getMethod().getDeclaringClass().getAnnotation(AuditLog.class);
        }
        if (annotation == null) {
            return ctx.proceed();
        }

        // 2) 异步发射审计事件（fire-and-forget，不阻塞业务）
        try {
            AuditLogEntry entry = buildEntry(annotation, ctx);
            auditEvent.fireAsync(entry);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 审计失败不应影响业务（只捕获构建条目的具体异常面，不吞 Error/未知异常）
            log.warn("Failed to build audit log entry: {}", e.getMessage(), e);
        }

        // 3) 继续执行原方法
        return ctx.proceed();
    }

    /**
     * 从拦截上下文构建审计日志条目。
     */
    private AuditLogEntry buildEntry(AuditLog annotation, InvocationContext ctx) {
        String operatorId = extractOperatorId();
        String clientIp = extractClientIp();
        String details = buildDetails(ctx);
        String resourceId = extractResourceId(ctx);

        return new AuditLogEntry(
                operatorId,
                LocalDateTime.now(),
                annotation.operation(),
                annotation.resourceType(),
                resourceId,
                annotation.description().isBlank() ? details : annotation.description(),
                clientIp
        );
    }

    /**
     * 从 Authorization 头解析 JWT 获取操作人 ID（sub 字段）。
     */
    private String extractOperatorId() {
        try {
            String auth = httpRequest.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7).trim();
                Optional<String> sub = JwtUtils.extractSubject(token);
                if (sub.isPresent()) {
                    return sub.get();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.debug("Failed to extract operator ID: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * 提取客户端 IP（优先 X-Forwarded-For）。
     */
    private String extractClientIp() {
        try {
            String forwarded = httpRequest.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // 多级代理时取第一个
                return forwarded.split(",")[0].trim();
            }
            // 回退到直连 IP
            return httpRequest.remoteAddress().hostAddress();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "UNKNOWN";
        }
    }

    /**
     * 提取资源 ID（从方法参数中查找名为 resourceId / id / kbId / userId 的参数）。
     */
    private String extractResourceId(InvocationContext ctx) {
        Parameter[] params = ctx.getMethod().getParameters();
        Object[] args = ctx.getParameters();
        if (args == null) return "";

        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            if (name != null && RESOURCE_ID_PARAM_NAMES.contains(name)) {
                return args[i] != null ? args[i].toString() : "";
            }
        }
        return "";
    }

    /**
     * 将方法参数序列化为 JSON，敏感字段已掩码。
     */
    private String buildDetails(InvocationContext ctx) {
        try {
            Parameter[] params = ctx.getMethod().getParameters();
            Object[] args = ctx.getParameters();
            if (args == null || args.length == 0) return "{}";

            Map<String, Object> map = new HashMap<>();
            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                if (paramName == null) {
                    paramName = "arg" + i;
                }
                // 跳过标准注入对象（HttpHeaders, ServerHttpRequest 等）
                if (paramName.startsWith("arg") && args[i] != null
                        && args[i].getClass().getName().startsWith("io.vertx")) {
                    continue;
                }

                if (SENSITIVE_FIELDS.contains(paramName)) {
                    map.put(paramName, "***");
                } else {
                    map.put(paramName, args[i]);
                }
            }
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
