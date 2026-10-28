package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 模型回调幂等去重表（kb_callback_processed）
 * <p>
 * 以模型回调信封的 messageId 为主键。同一 messageId 的回调只会被处理一次：
 * 消费时同事务查重 + 落库，防御 auto.offset.reset=earliest 重放导致的跨周期状态回退，
 * 兼作回调审计。按 received_at 定期清理过期记录。
 * </p>
 */
@Entity
@Table(name = "kb_callback_processed")
public class KbCallbackProcessedPO {

    /** 模型回调信封 messageId（主键，模型新生成，非原事件 ID） */
    @Id
    @Column(name = "message_id")
    private String messageId;

    /** 文档 ID（KB 级回调为 null） */
    @Column(name = "document_id")
    private String documentId;

    /** 知识库 ID */
    @Column(name = "kb_id")
    private String kbId;

    /** 回调事件类型 PROCESSING_COMPLETED / PROCESSING_FAILED */
    @Column(name = "event_type")
    private String eventType;

    /** 落库的处理状态 COMPLETED / FAILED */
    private String status;

    /** 消费时间（DB 时间，清理依据） */
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }
}
