package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 冲突记录（聚合根）PO。
 */
@Entity
@Table(name = "conflict_records")
public class ConflictRecordPO {

    @Id
    public String id;

    @Column(name = "kb_id", nullable = false, length = 36)
    public String kbId;

    @Column(name = "first_job_id", nullable = false, length = 36)
    public String firstJobId;

    @Column(name = "last_seen_job_id", length = 36)
    public String lastSeenJobId;

    @Column(name = "fingerprint", nullable = false, length = 128)
    public String fingerprint;

    @Column(name = "topic", nullable = false, length = 500)
    public String topic;

    @Column(name = "type", nullable = false, length = 20)
    public String type;

    @Column(name = "raw_type", length = 50)
    public String rawType;

    @Column(name = "severity", nullable = false, length = 10)
    public String severity = "MEDIUM";

    @Column(name = "summary", columnDefinition = "text")
    public String summary;

    @Column(name = "location_count", nullable = false)
    public Integer locationCount = 0;

    @Column(name = "scope_label", length = 100)
    public String scopeLabel;

    @Column(name = "detected_started_at")
    public LocalDateTime detectedStartedAt;

    @Column(name = "detected_finished_at")
    public LocalDateTime detectedFinishedAt;

    @Column(name = "last_seen_at")
    public LocalDateTime lastSeenAt;

    @Column(name = "badcase_status", nullable = false, length = 12)
    public String badcaseStatus = "NONE";

    @Column(name = "badcase_ticket_id", length = 36)
    public String badcaseTicketId;

    @Column(name = "badcase_submitted_by", length = 50)
    public String badcaseSubmittedBy;

    @Column(name = "badcase_submitted_at")
    public LocalDateTime badcaseSubmittedAt;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    // ── V1.2 模型端新增字段（C1–C5 冲突）──
    /** LLM 置信度 0~1。 */
    @Column(name = "confidence")
    public Double confidence;

    /** 建议动作：KEEP_NEW/KEEP_OLD/MERGE/COEXIST。 */
    @Column(name = "suggestion", length = 20)
    public String suggestion;

    /** 判定理由。 */
    @Column(name = "reason", columnDefinition = "text")
    public String reason;

    /** 一句话冲突点。 */
    @Column(name = "conflict_point", columnDefinition = "text")
    public String conflictPoint;

    /** 库内源文件名。 */
    @Column(name = "old_source_file", length = 500)
    public String oldSourceFile;

    /** 粗筛相似度。 */
    @Column(name = "similarity")
    public Double similarity;
}
