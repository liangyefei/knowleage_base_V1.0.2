package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 冲突检测设置（自动开关）PO。
 */
@Entity
@Table(name = "conflict_settings")
public class ConflictSettingsPO {

    @Id
    public String id;

    @Column(name = "kb_id", length = 36)
    public String kbId;

    @Column(name = "auto_enabled", nullable = false)
    public Boolean autoEnabled = false;

    @Column(name = "updated_by", length = 50)
    public String updatedBy;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;
}
