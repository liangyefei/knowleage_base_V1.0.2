package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 敏感词标签表（kb_sensitive_tags）
 */
@Entity
@Table(name = "kb_sensitive_tags")
public class SensitiveTagPO {

    @Id
    private String id;

    /** 文本标签（展示名，如"涉政"），未删除记录内唯一 */
    @Column(name = "text_label", nullable = false)
    private String textLabel;

    /** 标签标识（机器标识，如 POLITICAL） */
    @Column(name = "label_identification", nullable = false)
    private String labelIdentification;

    /** 说明 */
    @Column(name = "remarks")
    private String remarks;

    /** 0=删除, 1=启用, 2=禁用 */
    @Column(name = "status", nullable = false)
    private Short status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTextLabel() {
        return textLabel;
    }

    public void setTextLabel(String textLabel) {
        this.textLabel = textLabel;
    }

    public String getLabelIdentification() {
        return labelIdentification;
    }

    public void setLabelIdentification(String labelIdentification) {
        this.labelIdentification = labelIdentification;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
