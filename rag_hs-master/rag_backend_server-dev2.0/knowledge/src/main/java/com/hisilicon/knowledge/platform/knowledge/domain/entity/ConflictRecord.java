package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 冲突记录（聚合根）。
 * <p>需求简化：无状态机。同 kb 同指纹唯一（uk_conflict_records_kb_fp），
 * 再次命中仅刷新 lastSeenAt。Badcase 一次性提交守卫保留。</p>
 */
public class ConflictRecord {

    private final ConflictRecordId id;
    private final String kbId;
    private final ConflictScanJobId firstJobId;
    private final ConflictScanJobId lastSeenJobId;
    private final String fingerprint;
    private final String topic;
    private final ConflictType type;
    private final String rawType;
    private final ConflictSeverity severity;
    private final String summary;
    private final int locationCount;
    private final String scopeLabel;
    private final LocalDateTime detectedStartedAt;
    private final LocalDateTime detectedFinishedAt;
    private final LocalDateTime lastSeenAt;
    private final ConflictBadcaseStatus badcaseStatus;
    private final String badcaseTicketId;
    private final String badcaseSubmittedBy;
    private final LocalDateTime badcaseSubmittedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    // ── V1.2 模型端新增字段 ──
    private final Double confidence;
    private final String suggestion;
    private final String reason;
    private final String conflictPoint;
    private final String oldSourceFile;
    private final Double similarity;

    private ConflictRecord(Builder b) {
        this.id = b.id;
        this.kbId = b.kbId;
        this.firstJobId = b.firstJobId;
        this.lastSeenJobId = b.lastSeenJobId;
        this.fingerprint = b.fingerprint;
        this.topic = b.topic;
        this.type = b.type;
        this.rawType = b.rawType;
        this.severity = b.severity;
        this.summary = b.summary;
        this.locationCount = b.locationCount;
        this.scopeLabel = b.scopeLabel;
        this.detectedStartedAt = b.detectedStartedAt;
        this.detectedFinishedAt = b.detectedFinishedAt;
        this.lastSeenAt = b.lastSeenAt;
        this.badcaseStatus = b.badcaseStatus;
        this.badcaseTicketId = b.badcaseTicketId;
        this.badcaseSubmittedBy = b.badcaseSubmittedBy;
        this.badcaseSubmittedAt = b.badcaseSubmittedAt;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
        this.confidence = b.confidence;
        this.suggestion = b.suggestion;
        this.reason = b.reason;
        this.conflictPoint = b.conflictPoint;
        this.oldSourceFile = b.oldSourceFile;
        this.similarity = b.similarity;
    }

    /**
     * 工厂：首次发现，新建冲突。
     */
    public static ConflictRecord newlyDetected(String kbId, ConflictScanJobId firstJobId, String fingerprint,
                                              String topic, ConflictType type, String rawType,
                                              ConflictSeverity severity, String summary,
                                              int locationCount, String scopeLabel,
                                              LocalDateTime detectedStartedAt, LocalDateTime detectedFinishedAt) {
        if (kbId == null || kbId.isBlank()) throw new IllegalArgumentException("kbId required");
        if (firstJobId == null) throw new IllegalArgumentException("firstJobId required");
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("fingerprint required");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic required");
        if (type == null) throw new IllegalArgumentException("type required");
        LocalDateTime now = LocalDateTime.now();
        return new Builder()
                .id(new ConflictRecordId(IdGenerator.conflictRecord()))
                .kbId(kbId).firstJobId(firstJobId).lastSeenJobId(firstJobId)
                .fingerprint(fingerprint).topic(topic).type(type).rawType(rawType)
                .severity(severity == null ? ConflictSeverity.MEDIUM : severity)
                .summary(summary)
                .locationCount(locationCount).scopeLabel(scopeLabel)
                .detectedStartedAt(detectedStartedAt).detectedFinishedAt(detectedFinishedAt)
                .lastSeenAt(now)
                .badcaseStatus(ConflictBadcaseStatus.NONE)
                .createdAt(now).updatedAt(now)
                .build();
    }

    /** 被同一指纹再次命中，刷新 lastSeen。 */
    public ConflictRecord reseen(ConflictScanJobId seenJobId) {
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .lastSeenJobId(seenJobId).lastSeenAt(now).updatedAt(now).build();
    }

    /** 提报 Badcase 守卫：已提交则拒绝，否则返回 SUBMITTED 态。 */
    public ConflictRecord markBadcaseSubmitted(String ticketId, String submittedBy) {
        if (this.badcaseStatus == ConflictBadcaseStatus.SUBMITTED) {
            throw new IllegalStateException("badcase already submitted");
        }
        if (ticketId == null || ticketId.isBlank()) throw new IllegalArgumentException("ticketId required");
        if (submittedBy == null || submittedBy.isBlank()) throw new IllegalArgumentException("submittedBy required");
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .badcaseStatus(ConflictBadcaseStatus.SUBMITTED)
                .badcaseTicketId(ticketId)
                .badcaseSubmittedBy(submittedBy)
                .badcaseSubmittedAt(now)
                .updatedAt(now)
                .build();
    }

    private Builder copyFromThis() {
        return new Builder()
                .id(this.id).kbId(this.kbId).firstJobId(this.firstJobId).lastSeenJobId(this.lastSeenJobId)
                .fingerprint(this.fingerprint).topic(this.topic).type(this.type).rawType(this.rawType)
                .severity(this.severity).summary(this.summary)
                .locationCount(this.locationCount).scopeLabel(this.scopeLabel)
                .detectedStartedAt(this.detectedStartedAt).detectedFinishedAt(this.detectedFinishedAt)
                .lastSeenAt(this.lastSeenAt)
                .badcaseStatus(this.badcaseStatus).badcaseTicketId(this.badcaseTicketId)
                .badcaseSubmittedBy(this.badcaseSubmittedBy).badcaseSubmittedAt(this.badcaseSubmittedAt)
                .createdAt(this.createdAt)
                .confidence(this.confidence).suggestion(this.suggestion).reason(this.reason)
                .conflictPoint(this.conflictPoint).oldSourceFile(this.oldSourceFile).similarity(this.similarity);
    }

    // getters
    public ConflictRecordId getId() { return id; }
    public String getKbId() { return kbId; }
    public ConflictScanJobId getFirstJobId() { return firstJobId; }
    public ConflictScanJobId getLastSeenJobId() { return lastSeenJobId; }
    public String getFingerprint() { return fingerprint; }
    public String getTopic() { return topic; }
    public ConflictType getType() { return type; }
    public String getRawType() { return rawType; }
    public ConflictSeverity getSeverity() { return severity; }
    public String getSummary() { return summary; }
    public int getLocationCount() { return locationCount; }
    public String getScopeLabel() { return scopeLabel; }
    public LocalDateTime getDetectedStartedAt() { return detectedStartedAt; }
    public LocalDateTime getDetectedFinishedAt() { return detectedFinishedAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public ConflictBadcaseStatus getBadcaseStatus() { return badcaseStatus; }
    public String getBadcaseTicketId() { return badcaseTicketId; }
    public String getBadcaseSubmittedBy() { return badcaseSubmittedBy; }
    public LocalDateTime getBadcaseSubmittedAt() { return badcaseSubmittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Double getConfidence() { return confidence; }
    public String getSuggestion() { return suggestion; }
    public String getReason() { return reason; }
    public String getConflictPoint() { return conflictPoint; }
    public String getOldSourceFile() { return oldSourceFile; }
    public Double getSimilarity() { return similarity; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ConflictRecordId id;
        private String kbId;
        private ConflictScanJobId firstJobId;
        private ConflictScanJobId lastSeenJobId;
        private String fingerprint;
        private String topic;
        private ConflictType type;
        private String rawType;
        private ConflictSeverity severity;
        private String summary;
        private int locationCount;
        private String scopeLabel;
        private LocalDateTime detectedStartedAt;
        private LocalDateTime detectedFinishedAt;
        private LocalDateTime lastSeenAt;
        private ConflictBadcaseStatus badcaseStatus;
        private String badcaseTicketId;
        private String badcaseSubmittedBy;
        private LocalDateTime badcaseSubmittedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Double confidence;
        private String suggestion;
        private String reason;
        private String conflictPoint;
        private String oldSourceFile;
        private Double similarity;

        public Builder id(ConflictRecordId v) { this.id = v; return this; }
        public Builder kbId(String v) { this.kbId = v; return this; }
        public Builder firstJobId(ConflictScanJobId v) { this.firstJobId = v; return this; }
        public Builder lastSeenJobId(ConflictScanJobId v) { this.lastSeenJobId = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder topic(String v) { this.topic = v; return this; }
        public Builder type(ConflictType v) { this.type = v; return this; }
        public Builder rawType(String v) { this.rawType = v; return this; }
        public Builder severity(ConflictSeverity v) { this.severity = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder locationCount(int v) { this.locationCount = v; return this; }
        public Builder scopeLabel(String v) { this.scopeLabel = v; return this; }
        public Builder detectedStartedAt(LocalDateTime v) { this.detectedStartedAt = v; return this; }
        public Builder detectedFinishedAt(LocalDateTime v) { this.detectedFinishedAt = v; return this; }
        public Builder lastSeenAt(LocalDateTime v) { this.lastSeenAt = v; return this; }
        public Builder badcaseStatus(ConflictBadcaseStatus v) { this.badcaseStatus = v; return this; }
        public Builder badcaseTicketId(String v) { this.badcaseTicketId = v; return this; }
        public Builder badcaseSubmittedBy(String v) { this.badcaseSubmittedBy = v; return this; }
        public Builder badcaseSubmittedAt(LocalDateTime v) { this.badcaseSubmittedAt = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }
        public Builder confidence(Double v) { this.confidence = v; return this; }
        public Builder suggestion(String v) { this.suggestion = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder conflictPoint(String v) { this.conflictPoint = v; return this; }
        public Builder oldSourceFile(String v) { this.oldSourceFile = v; return this; }
        public Builder similarity(Double v) { this.similarity = v; return this; }

        public ConflictRecord build() {
            if (id == null) id = new ConflictRecordId(IdGenerator.conflictRecord());
            if (badcaseStatus == null) badcaseStatus = ConflictBadcaseStatus.NONE;
            if (severity == null) severity = ConflictSeverity.MEDIUM;
            LocalDateTime now = LocalDateTime.now();
            if (createdAt == null) createdAt = now;
            if (updatedAt == null) updatedAt = now;
            if (lastSeenAt == null) lastSeenAt = now;
            return new ConflictRecord(this);
        }
    }
}
