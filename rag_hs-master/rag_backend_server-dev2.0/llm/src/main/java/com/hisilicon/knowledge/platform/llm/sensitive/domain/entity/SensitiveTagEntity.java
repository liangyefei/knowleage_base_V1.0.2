package com.hisilicon.knowledge.platform.llm.sensitive.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层敏感词标签实体
 * <p>对应 {@code kb_sensitive_tags} 表。一个标签代表一类敏感词（如"涉政"），
 * 携带文本标签（展示名）与标签标识（机器标识）。删除为软删除（status=0），
 * 级联硬删该标签下全部词库记录。</p>
 */
public class SensitiveTagEntity {

    /** 启用：参与敏感词缓存与匹配 */
    public static final Short STATUS_ACTIVE = 1;

    /** 禁用：保留数据、可再启用，不参与匹配 */
    public static final Short STATUS_DISABLED = 2;

    /** 软删除 */
    public static final Short STATUS_DELETED = 0;

    private final SensitiveTagId id;
    private final String textLabel;
    private final String labelIdentification;
    private final String remarks;
    private final Short status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private SensitiveTagEntity(Builder builder) {
        this.id = builder.id;
        this.textLabel = builder.textLabel;
        this.labelIdentification = builder.labelIdentification;
        this.remarks = builder.remarks;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新标签（初始启用）
     */
    public static SensitiveTagEntity create(String textLabel, String labelIdentification, String remarks) {
        return create(textLabel, labelIdentification, remarks, STATUS_ACTIVE);
    }

    /**
     * 领域工厂：创建新标签（指定启用状态，仅允许 1=启用 / 2=禁用）
     *
     * @throws IllegalStateException 目标状态非 1/2
     */
    public static SensitiveTagEntity create(String textLabel, String labelIdentification, String remarks, Short status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalStateException("敏感词标签创建状态不合法: " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        return SensitiveTagEntity.builder()
                .id(SensitiveTagId.of(UUID.randomUUID().toString()))
                .textLabel(textLabel)
                .labelIdentification(labelIdentification)
                .remarks(remarks)
                .status(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：编辑（全量替换文本标签/标识/说明），ID 与创建时间不变
     */
    public SensitiveTagEntity update(String textLabel, String labelIdentification, String remarks) {
        return update(textLabel, labelIdentification, remarks, this.status);
    }

    /**
     * 领域行为：编辑（全量替换文本标签/标识/说明/状态），ID 与创建时间不变。
     * 目标状态仅允许 1=启用 / 2=禁用（0=删除走 {@link #delete()}，编辑不可达删除态）。
     *
     * @throws IllegalStateException 目标状态非 1/2
     */
    public SensitiveTagEntity update(String textLabel, String labelIdentification, String remarks, Short status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalStateException("敏感词标签编辑状态不合法: " + status);
        }
        return SensitiveTagEntity.builder()
                .id(this.id)
                .textLabel(textLabel)
                .labelIdentification(labelIdentification)
                .remarks(remarks)
                .status(status)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：切换启用/禁用（1↔2）；软删记录不可走此通道
     *
     * @throws IllegalStateException 目标状态非 1/2
     */
    public SensitiveTagEntity changeStatus(Short targetStatus) {
        if (!STATUS_ACTIVE.equals(targetStatus) && !STATUS_DISABLED.equals(targetStatus)) {
            throw new IllegalStateException("敏感词标签状态切换目标不合法: " + targetStatus);
        }
        return SensitiveTagEntity.builder()
                .id(this.id)
                .textLabel(this.textLabel)
                .labelIdentification(this.labelIdentification)
                .remarks(this.remarks)
                .status(targetStatus)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：软删除（status=0），保留记录供审计
     */
    public SensitiveTagEntity delete() {
        return SensitiveTagEntity.builder()
                .id(this.id)
                .textLabel(this.textLabel)
                .labelIdentification(this.labelIdentification)
                .remarks(this.remarks)
                .status(STATUS_DELETED)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public SensitiveTagId getId() { return id; }
    public String getTextLabel() { return textLabel; }
    public String getLabelIdentification() { return labelIdentification; }
    public String getRemarks() { return remarks; }
    public Short getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private SensitiveTagId id;
        private String textLabel;
        private String labelIdentification;
        private String remarks;
        private Short status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(SensitiveTagId id) { this.id = id; return this; }
        public Builder textLabel(String textLabel) { this.textLabel = textLabel; return this; }
        public Builder labelIdentification(String labelIdentification) { this.labelIdentification = labelIdentification; return this; }
        public Builder remarks(String remarks) { this.remarks = remarks; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public SensitiveTagEntity build() {
            if (id == null) throw new IllegalStateException("敏感词标签 ID 不能为空");
            if (textLabel == null || textLabel.isBlank()) throw new IllegalStateException("文本标签不能为空");
            if (labelIdentification == null || labelIdentification.isBlank()) throw new IllegalStateException("标签标识不能为空");
            return new SensitiveTagEntity(this);
        }
    }
}
