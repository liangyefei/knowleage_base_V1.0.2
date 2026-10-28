package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * Badcase 工单（本地占位；独立工单系统后续设计，远程对接时经 external_ref 回填）。
 */
public class ConflictBadcaseTicket {

    public static final int DESCRIPTION_MAX_LENGTH = 1000;

    private final String id;
    private final ConflictRecordId recordId;
    private final String kbId;
    private final String topicSnapshot;
    private final ConflictType typeSnapshot;
    private final String description;
    private final String status;
    private final String externalRef;
    private final String submittedBy;
    private final LocalDateTime createdAt;

    private ConflictBadcaseTicket(Builder b) {
        this.id = b.id;
        this.recordId = b.recordId;
        this.kbId = b.kbId;
        this.topicSnapshot = b.topicSnapshot;
        this.typeSnapshot = b.typeSnapshot;
        this.description = b.description;
        this.status = b.status;
        this.externalRef = b.externalRef;
        this.submittedBy = b.submittedBy;
        this.createdAt = b.createdAt;
    }

    public static ConflictBadcaseTicket create(ConflictRecordId recordId, String kbId,
                                               String topicSnapshot, ConflictType typeSnapshot,
                                               String description, String submittedBy) {
        if (recordId == null) throw new IllegalArgumentException("recordId required");
        if (kbId == null || kbId.isBlank()) throw new IllegalArgumentException("kbId required");
        if (topicSnapshot == null || topicSnapshot.isBlank()) throw new IllegalArgumentException("topicSnapshot required");
        if (typeSnapshot == null) throw new IllegalArgumentException("typeSnapshot required");
        if (description == null || description.isBlank() || description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("description must be 1~" + DESCRIPTION_MAX_LENGTH + " chars");
        }
        if (submittedBy == null || submittedBy.isBlank()) throw new IllegalArgumentException("submittedBy required");
        LocalDateTime now = LocalDateTime.now();
        return new Builder()
                .recordId(recordId).kbId(kbId).topicSnapshot(topicSnapshot).typeSnapshot(typeSnapshot)
                .description(description).submittedBy(submittedBy)
                .status("SUBMITTED").createdAt(now)
                .build();
    }

    public String getId() { return id; }
    public ConflictRecordId getRecordId() { return recordId; }
    public String getKbId() { return kbId; }
    public String getTopicSnapshot() { return topicSnapshot; }
    public ConflictType getTypeSnapshot() { return typeSnapshot; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public String getSubmittedBy() { return submittedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public static class Builder {
        private String id;
        private ConflictRecordId recordId;
        private String kbId;
        private String topicSnapshot;
        private ConflictType typeSnapshot;
        private String description;
        private String status;
        private String externalRef;
        private String submittedBy;
        private LocalDateTime createdAt;

        public Builder recordId(ConflictRecordId v) { this.recordId = v; return this; }
        public Builder kbId(String v) { this.kbId = v; return this; }
        public Builder topicSnapshot(String v) { this.topicSnapshot = v; return this; }
        public Builder typeSnapshot(ConflictType v) { this.typeSnapshot = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder externalRef(String v) { this.externalRef = v; return this; }
        public Builder submittedBy(String v) { this.submittedBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }

        public ConflictBadcaseTicket build() {
            if (id == null) id = IdGenerator.conflictTicket();
            if (status == null) status = "SUBMITTED";
            return new ConflictBadcaseTicket(this);
        }
    }
}
