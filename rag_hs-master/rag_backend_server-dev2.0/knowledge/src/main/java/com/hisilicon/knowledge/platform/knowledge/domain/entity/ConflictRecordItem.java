package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 冲突证据片段快照。
 * <p>模型回调落库时快照写入；文档事后删除/版本覆盖不级联影响（§3.5）。</p>
 */
public class ConflictRecordItem {

    private final String id;
    private final ConflictRecordId recordId;
    private final String documentId;
    private final String docNameSnapshot;
    private final String versionSnapshot;
    private final String publishStatusSnapshot;
    private final Integer chunkIndex;
    private final String pageNumbersJson;
    private final String titlesPath;
    private final Integer lineHint;
    private final String excerpt;
    private final boolean baseline;
    private final LocalDateTime chunkUpdatedAt;
    private final int sortNo;
    private final LocalDateTime createdAt;

    private ConflictRecordItem(Builder b) {
        this.id = b.id;
        this.recordId = b.recordId;
        this.documentId = b.documentId;
        this.docNameSnapshot = b.docNameSnapshot;
        this.versionSnapshot = b.versionSnapshot;
        this.publishStatusSnapshot = b.publishStatusSnapshot;
        this.chunkIndex = b.chunkIndex;
        this.pageNumbersJson = b.pageNumbersJson;
        this.titlesPath = b.titlesPath;
        this.lineHint = b.lineHint;
        this.excerpt = b.excerpt;
        this.baseline = b.baseline;
        this.chunkUpdatedAt = b.chunkUpdatedAt;
        this.sortNo = b.sortNo;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() { return new Builder(); }

    public String getId() { return id; }
    public ConflictRecordId getRecordId() { return recordId; }
    public String getDocumentId() { return documentId; }
    public String getDocNameSnapshot() { return docNameSnapshot; }
    public String getVersionSnapshot() { return versionSnapshot; }
    public String getPublishStatusSnapshot() { return publishStatusSnapshot; }
    public Integer getChunkIndex() { return chunkIndex; }
    public String getPageNumbersJson() { return pageNumbersJson; }
    public String getTitlesPath() { return titlesPath; }
    public Integer getLineHint() { return lineHint; }
    public String getExcerpt() { return excerpt; }
    public boolean isBaseline() { return baseline; }
    public LocalDateTime getChunkUpdatedAt() { return chunkUpdatedAt; }
    public int getSortNo() { return sortNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public static class Builder {
        private String id;
        private ConflictRecordId recordId;
        private String documentId;
        private String docNameSnapshot;
        private String versionSnapshot;
        private String publishStatusSnapshot;
        private Integer chunkIndex;
        private String pageNumbersJson;
        private String titlesPath;
        private Integer lineHint;
        private String excerpt;
        private boolean baseline;
        private LocalDateTime chunkUpdatedAt;
        private int sortNo;
        private LocalDateTime createdAt;

        public Builder recordId(ConflictRecordId v) { this.recordId = v; return this; }
        public Builder documentId(String v) { this.documentId = v; return this; }
        public Builder docNameSnapshot(String v) { this.docNameSnapshot = v; return this; }
        public Builder versionSnapshot(String v) { this.versionSnapshot = v; return this; }
        public Builder publishStatusSnapshot(String v) { this.publishStatusSnapshot = v; return this; }
        public Builder chunkIndex(Integer v) { this.chunkIndex = v; return this; }
        public Builder pageNumbersJson(String v) { this.pageNumbersJson = v; return this; }
        public Builder titlesPath(String v) { this.titlesPath = v; return this; }
        public Builder lineHint(Integer v) { this.lineHint = v; return this; }
        public Builder excerpt(String v) { this.excerpt = v; return this; }
        public Builder baseline(boolean v) { this.baseline = v; return this; }
        public Builder chunkUpdatedAt(LocalDateTime v) { this.chunkUpdatedAt = v; return this; }
        public Builder sortNo(int v) { this.sortNo = v; return this; }

        public ConflictRecordItem build() {
            if (id == null) id = IdGenerator.conflictItem();
            if (recordId == null) throw new IllegalStateException("recordId required");
            if (docNameSnapshot == null || docNameSnapshot.isBlank()) throw new IllegalStateException("docNameSnapshot required");
            if (publishStatusSnapshot == null || publishStatusSnapshot.isBlank()) throw new IllegalStateException("publishStatusSnapshot required");
            if (excerpt == null || excerpt.isBlank()) throw new IllegalStateException("excerpt required");
            if (createdAt == null) createdAt = LocalDateTime.now();
            ConflictRecordItem item = new ConflictRecordItem(this);
            return item;
        }
    }
}
