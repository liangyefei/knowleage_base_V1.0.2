package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 审计日志持久化对象 — 对应 audit_logs 表。
 * <p>
 * 审计日志<strong>不可删除、不可修改</strong>，用于安全审计和合规追溯。
 * 当前为预留表结构，后续实现时配合 {@code AuditLogPanacheRepository} 完成写入。
 * </p>
 *
 * <h3>索引策略</h3>
 * <ul>
 *   <li>主键 — id</li>
 *   <li>复合索引 — idx_audit_time (operator_id, operation_time DESC) — 支持按操作人+时间查询</li>
 *   <li>复合索引 — idx_audit_resource (resource_type, resource_id) — 支持按资源追溯</li>
 * </ul>
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_time", columnList = "operator_id, operation_time DESC"),
        @Index(name = "idx_audit_resource", columnList = "resource_type, resource_id")
})
public class AuditLogPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人 ID */
    @Column(name = "operator_id", nullable = false, length = 128)
    private String operatorId;

    /** 操作时间 */
    @Column(name = "operation_time", nullable = false)
    private LocalDateTime operationTime;

    /** 操作类型（枚举名，如 LOGIN, CREATE, DELETE 等） */
    @Column(name = "operation", nullable = false, length = 32)
    private String operation;

    /** 目标资源类型（如 "USER", "KB", "DOCUMENT", "ROLE", "ACL", "POLICY"） */
    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    /** 目标资源 ID */
    @Column(name = "resource_id", length = 128)
    private String resourceId;

    /** 操作详情（JSON，含请求参数摘要和判定结果） */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /** 客户端 IP */
    @Column(name = "client_ip", length = 64)
    private String clientIp;

    /** 记录创建时间（仅插入时设置，不可更新） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ======================== 无参构造 ========================

    public AuditLogPO() {
    }

    // ======================== Getters & Setters ========================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public LocalDateTime getOperationTime() {
        return operationTime;
    }

    public void setOperationTime(LocalDateTime operationTime) {
        this.operationTime = operationTime;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
