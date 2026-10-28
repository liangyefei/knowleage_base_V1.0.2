package com.hisilicon.knowledge.platform.commons.web.audit;

import java.time.LocalDateTime;

/**
 * 审计日志条目 — 不可变 DTO。
 * <p>
 * 由 {@link AuditLogInterceptor} 构建，通过 CDI 异步事件投递给
 * {@link AuditLogEventHandler} 处理。
 * </p>
 *
 * @param operatorId   操作人 ID（从 JWT sub 提取）
 * @param operationTime 操作时间
 * @param operation     操作类型
 * @param resourceType  目标资源类型
 * @param resourceId    目标资源 ID（可为空，如登录操作无明确资源 ID）
 * @param details       操作详情 JSON（方法参数序列化，敏感字段已掩码）
 * @param clientIp      客户端 IP
 */
public record AuditLogEntry(
        String operatorId,
        LocalDateTime operationTime,
        AuditLogOperation operation,
        String resourceType,
        String resourceId,
        String details,
        String clientIp
) {
}
