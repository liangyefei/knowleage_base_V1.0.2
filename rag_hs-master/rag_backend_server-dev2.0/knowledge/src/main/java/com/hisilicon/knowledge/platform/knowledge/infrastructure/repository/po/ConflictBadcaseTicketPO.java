package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Badcase 工单 PO（本地占位）。
 */
@Entity
@Table(name = "conflict_badcase_tickets")
public class ConflictBadcaseTicketPO {

    @Id
    public String id;

    @Column(name = "record_id", nullable = false, length = 36)
    public String recordId;

    @Column(name = "kb_id", nullable = false, length = 36)
    public String kbId;

    @Column(name = "topic_snapshot", nullable = false, length = 500)
    public String topicSnapshot;

    @Column(name = "type_snapshot", nullable = false, length = 20)
    public String typeSnapshot;

    @Column(name = "description", nullable = false, length = 1000)
    public String description;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "SUBMITTED";

    @Column(name = "external_ref", length = 64)
    public String externalRef;

    @Column(name = "submitted_by", nullable = false, length = 50)
    public String submittedBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
