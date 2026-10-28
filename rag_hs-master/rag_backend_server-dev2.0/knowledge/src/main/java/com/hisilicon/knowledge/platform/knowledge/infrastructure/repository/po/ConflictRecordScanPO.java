package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 冲突-批次多对多关联 PO。
 */
@Entity
@Table(name = "conflict_record_scans")
public class ConflictRecordScanPO {

    @Id
    public String id;

    @Column(name = "record_id", nullable = false, length = 36)
    public String recordId;

    @Column(name = "job_id", nullable = false, length = 36)
    public String jobId;

    @Column(name = "seen_at", nullable = false)
    public LocalDateTime seenAt;
}
