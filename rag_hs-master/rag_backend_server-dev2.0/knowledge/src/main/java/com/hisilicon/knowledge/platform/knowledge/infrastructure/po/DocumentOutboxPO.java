package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 事务发件箱表（document_outbox）
 * <p>
 * Transactional Outbox 模式：业务事务（如文档级联删除）与待发消息
 * 在同一个 DB 事务内写入，relay 定时任务扫描 PENDING 记录发送到
 * DMS Kafka 后标记 SENT。至少一次投递，消费者按 documentId 幂等。
 * </p>
 */
@Entity
@Table(name = "document_outbox")
public class DocumentOutboxPO {

    /** 待发送 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已发送 */
    public static final String STATUS_SENT = "SENT";
    /** 已作废（生命周期回退如版本覆盖/草稿重传后，旧内容周期的事件不再投递） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    private String id;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "kb_id")
    private String kbId;

    @Column(name = "category_id")
    private String categoryId;

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private String status;

    @Column(name = "retry_count")
    private Integer retryCount;

    /** 下次重试时间：发送失败后按 retryCount 退避计算。null/已到点 → 可被 relay 扫描；未到点 → 跳过（同 KB 保序，头部未发则后续全部等待）。 */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
