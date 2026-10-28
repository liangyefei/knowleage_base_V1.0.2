package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 冲突处理记录（标记已解决 / 已忽略）。
 */
public class ConflictResolution {

    public static final int REMARK_MAX_LENGTH = 1000;

    private final String id;
    private final ConflictRecordId recordId;
    private final String action;
    private final String remark;
    private final String handledBy;
    private final String handledByName;
    private final LocalDateTime handledAt;

    private ConflictResolution(Builder b) {
        this.id = b.id;
        this.recordId = b.recordId;
        this.action = b.action;
        this.remark = b.remark;
        this.handledBy = b.handledBy;
        this.handledByName = b.handledByName;
        this.handledAt = b.handledAt;
    }

    public static ConflictResolution create(ConflictRecordId recordId, String action, String remark,
                                             String handledBy, String handledByName) {
        if (recordId == null) throw new IllegalArgumentException("recordId required");
        if (!"RESOLVED".equals(action) && !"IGNORED".equals(action)) {
            throw new IllegalArgumentException("action must be RESOLVED or IGNORED");
        }
        if (remark == null || remark.isBlank() || remark.length() > REMARK_MAX_LENGTH) {
            throw new IllegalArgumentException("remark must be 1~" + REMARK_MAX_LENGTH + " chars");
        }
        if (handledBy == null || handledBy.isBlank()) {
            throw new IllegalArgumentException("handledBy required");
        }
        LocalDateTime now = LocalDateTime.now();
        return new Builder()
                .recordId(recordId).action(action).remark(remark)
                .handledBy(handledBy).handledByName(handledByName).handledAt(now)
                .build();
    }

    public String getId() { return id; }
    public ConflictRecordId getRecordId() { return recordId; }
    public String getAction() { return action; }
    public String getRemark() { return remark; }
    public String getHandledBy() { return handledBy; }
    public String getHandledByName() { return handledByName; }
    public LocalDateTime getHandledAt() { return handledAt; }

    public static class Builder {
        private String id;
        private ConflictRecordId recordId;
        private String action;
        private String remark;
        private String handledBy;
        private String handledByName;
        private LocalDateTime handledAt;

        public Builder recordId(ConflictRecordId v) { this.recordId = v; return this; }
        public Builder action(String v) { this.action = v; return this; }
        public Builder remark(String v) { this.remark = v; return this; }
        public Builder handledBy(String v) { this.handledBy = v; return this; }
        public Builder handledByName(String v) { this.handledByName = v; return this; }
        public Builder handledAt(LocalDateTime v) { this.handledAt = v; return this; }

        public ConflictResolution build() {
            if (id == null) id = IdGenerator.conflictResolution();
            return new ConflictResolution(this);
        }
    }
}
