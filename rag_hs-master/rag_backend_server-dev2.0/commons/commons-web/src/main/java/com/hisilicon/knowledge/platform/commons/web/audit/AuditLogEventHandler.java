package com.hisilicon.knowledge.platform.commons.web.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计日志异步事件消费者。
 * <p>
 * 接收 {@link AuditLogInterceptor} 通过 {@code Event.fireAsync()} 发射的
 * {@link AuditLogEntry}，在当前阶段仅做 SLF4J 日志记录。
 * </p>
 *
 * <h3>后续待实现</h3>
 * <pre>{@code
 * // 生产就绪时的完整流程：
 * 1. 将 entry 放入 LinkedBlockingQueue<AuditLogEntry>（有界容量 10000）
 * 2. @Scheduled(every="5s") 每 5 秒批量提取
 * 3. 批量写入：
 *    a. databasePersister.batchInsert(entries)  → audit_logs 表
 *    b. ltsPersister.batchSync(entries)         → 华为云 LTS
 * 4. 队列满时丢弃最旧条目并告警，防止 OOM
 * }</pre>
 */
@ApplicationScoped
public class AuditLogEventHandler {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    /**
     * 异步消费审计事件。
     * <p>
     * 该方法在 CDI 异步线程池中执行，不影响业务主线程。
     * </p>
     *
     * @param entry 审计日志条目
     */
    void onAuditEvent(@ObservesAsync AuditLogEntry entry) {
        try {
            // 当前阶段：仅记录到日志文件
            // TODO: 后续替换为内存缓冲 → 批量写 DB + 同步 LTS
            auditLog.info("[AUDIT] operatorId={}, operation={}, resourceType={}, resourceId={}, clientIp={}, details={}",
                    entry.operatorId(),
                    entry.operation(),
                    entry.resourceType(),
                    entry.resourceId() != null ? entry.resourceId() : "-",
                    entry.clientIp(),
                    entry.details());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 审计事件消费失败绝不影响业务（仅捕获具体异常面，不吞 Error/未知异常）
            auditLog.warn("Failed to consume audit event: {}", e.getMessage(), e);
        }
    }
}
