package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Badcase 工单 PO（llm 侧轻量映射，仅用于洞察提交 badcase 时写入）。
 * <p>主表定义见 docs/sql/migration_020_badcase_module.sql；llm 不直接依赖 knowledge 模块 PO。</p>
 */
@Entity
@Table(name = "badcase_tickets")
public class InsightBadcaseTicketPO {

    @Id
    private String id;

    @Column(name = "ticket_no", nullable = false, length = 20)
    private String ticketNo;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "problem_type", nullable = false, length = 40)
    private String problemType;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "kb_id", length = 36)
    private String kbId;

    @Column(name = "source_payload", columnDefinition = "text")
    private String sourcePayload;

    @Column(name = "submitted_by", nullable = false, length = 50)
    private String submittedBy;

    @Column(name = "submitted_by_name", length = 100)
    private String submittedByName;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProblemType() { return problemType; }
    public void setProblemType(String problemType) { this.problemType = problemType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getSourcePayload() { return sourcePayload; }
    public void setSourcePayload(String sourcePayload) { this.sourcePayload = sourcePayload; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public String getSubmittedByName() { return submittedByName; }
    public void setSubmittedByName(String submittedByName) { this.submittedByName = submittedByName; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
