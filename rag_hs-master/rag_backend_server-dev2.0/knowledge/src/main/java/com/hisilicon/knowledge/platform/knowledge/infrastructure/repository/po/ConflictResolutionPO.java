package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 冲突处理记录 PO。
 */
@Entity
@Table(name = "conflict_resolutions")
public class ConflictResolutionPO {

    @Id
    public String id;

    @Column(name = "record_id", nullable = false, length = 36)
    public String recordId;

    @Column(name = "action", nullable = false, length = 12)
    public String action;

    @Column(name = "remark", nullable = false, length = 1000)
    public String remark;

    @Column(name = "handled_by", nullable = false, length = 50)
    public String handledBy;

    @Column(name = "handled_by_name", length = 100)
    public String handledByName;

    @Column(name = "handled_at", nullable = false)
    public LocalDateTime handledAt;
}
