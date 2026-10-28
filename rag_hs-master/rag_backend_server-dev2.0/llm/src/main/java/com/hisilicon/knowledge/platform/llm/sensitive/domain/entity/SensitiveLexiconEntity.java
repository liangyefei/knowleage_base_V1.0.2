package com.hisilicon.knowledge.platform.llm.sensitive.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层敏感词词库实体
 * <p>对应 {@code kb_sensitive_lexicons} 表。词归属于一个标签，参与 DFA 匹配的即本表的 word；
 * 词级启用状态 {@code status}（1=启用/2=停用），仅双启用（标签+词）参与匹配。</p>
 */
public class SensitiveLexiconEntity {

    public static final Short STATUS_ENABLED = 1;
    public static final Short STATUS_DISABLED = 2;

    private final SensitiveLexiconId id;
    private final SensitiveTagId tagId;
    private final String word;
    private final Short status;
    private final LocalDateTime createdAt;
    private final String createdBy;
    private final String remarks;

    private SensitiveLexiconEntity(Builder builder) {
        this.id = builder.id;
        this.tagId = builder.tagId;
        this.word = builder.word;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.createdBy = builder.createdBy;
        this.remarks = builder.remarks;
    }

    /**
     * 领域工厂：创建词库记录（默认启用）
     */
    public static SensitiveLexiconEntity create(SensitiveTagId tagId, String word) {
        return create(tagId, word, STATUS_ENABLED, null, null);
    }

    /**
     * 领域工厂：创建词库记录（指定启用状态 + 创建人 + 备注，手动新增/导入用）
     */
    public static SensitiveLexiconEntity create(SensitiveTagId tagId, String word, Short status,
                                                String createdBy, String remarks) {
        return SensitiveLexiconEntity.builder()
                .id(SensitiveLexiconId.of(UUID.randomUUID().toString()))
                .tagId(tagId)
                .word(word)
                .status(status)
                .createdAt(LocalDateTime.now())
                .createdBy(createdBy)
                .remarks(remarks)
                .build();
    }

    // ========== getters ==========

    public SensitiveLexiconId getId() { return id; }
    public SensitiveTagId getTagId() { return tagId; }
    public String getWord() { return word; }
    public Short getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public String getRemarks() { return remarks; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private SensitiveLexiconId id;
        private SensitiveTagId tagId;
        private String word;
        private Short status;
        private LocalDateTime createdAt;
        private String createdBy;
        private String remarks;

        public Builder id(SensitiveLexiconId id) { this.id = id; return this; }
        public Builder tagId(SensitiveTagId tagId) { this.tagId = tagId; return this; }
        public Builder word(String word) { this.word = word; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder remarks(String remarks) { this.remarks = remarks; return this; }

        public SensitiveLexiconEntity build() {
            if (id == null) throw new IllegalStateException("敏感词 ID 不能为空");
            if (tagId == null) throw new IllegalStateException("敏感词所属标签不能为空");
            if (word == null || word.isBlank()) throw new IllegalStateException("敏感词不能为空");
            if (status == null) throw new IllegalStateException("敏感词状态不能为空");
            return new SensitiveLexiconEntity(this);
        }
    }
}
