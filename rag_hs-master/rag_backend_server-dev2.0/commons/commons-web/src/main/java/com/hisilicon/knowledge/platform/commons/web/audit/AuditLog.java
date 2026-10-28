package com.hisilicon.knowledge.platform.commons.web.audit;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解 — 标记需要记录审计日志的方法。
 * <p>
 * 配合 {@link AuditLogInterceptor} 使用，被标记的方法在调用时自动记录操作人、
 * 操作类型、资源类型、客户端 IP 等信息。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @AuditLog(operation = AuditLogOperation.CREATE, resourceType = "KB")
 * public Uni<Void> createKb(String operatorId, CreateKbCommand command, String locale) { ... }
 *
 * @AuditLog(operation = AuditLogOperation.LOGIN, resourceType = "USER")
 * public Uni<TokenDTO> login(LoginCommand command, String locale) { ... }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型 */
    @Nonbinding
    AuditLogOperation operation() default AuditLogOperation.CREATE;

    /** 目标资源类型（如 "USER", "KB", "DOCUMENT", "ROLE", "ACL", "POLICY"） */
    @Nonbinding
    String resourceType() default "";

    /** 可选描述，覆盖自动生成的描述 */
    @Nonbinding
    String description() default "";
}
