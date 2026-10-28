package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 敏感词词库表（kb_sensitive_lexicons）
 */
@Entity
@Table(name = "kb_sensitive_lexicons")
public class SensitiveLexiconPO {

    @Id
    private String id;

    /** 所属标签 id（kb_sensitive_tags.id） */
    @Column(name = "tag_id", nullable = false)
    private String tagId;

    /** 敏感词 */
    @Column(name = "word", nullable = false)
    private String word;

    /** 状态：1=启用 / 2=停用（词为硬删，无 0）；仅双启用（标签+词）参与 DFA 匹配 */
    @Column(name = "status", nullable = false)
    private Short status;

    private LocalDateTime createdAt;

    /** 创建人账号，取 JWT sub（OperatorIdHolder）；仅新增/导入时写入，编辑不变更；存量行为 null */
    @Column(name = "created_by")
    private String createdBy;

    /** 备注，可空；编辑接口全量替换 */
    @Column(name = "remarks")
    private String remarks;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
