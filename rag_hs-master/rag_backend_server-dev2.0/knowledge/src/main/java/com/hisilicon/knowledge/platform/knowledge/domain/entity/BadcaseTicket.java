package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Badcase 工单（聚合根）。
 * <p>四态状态机（任意状态可改任意目标状态，见设计文档 §3.4）。</p>
 */
public class BadcaseTicket {

    public static final int DESCRIPTION_MAX_LENGTH = 1000;
    public static final int REMARK_MAX_LENGTH = 1000;

    private final BadcaseTicketId id;
    private final String ticketNo;
    private final BadcaseSource source;
    private final BadcaseProblemType problemType;
    private final String description;
    private final BadcaseStatus status;
    private final String kbId;
    private final String kbNameSnapshot;
    private final List<String> relatedDocIds;
    private final List<String> relatedDocNames;
    private final Map<String, Object> sourcePayload;
    private final String latestHandlingRemark;
    private final String latestHandledBy;
    private final String latestHandledByName;
    private final LocalDateTime latestHandledAt;
    private final String submittedBy;
    private final String submittedByName;
    private final LocalDateTime submittedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private BadcaseTicket(Builder b) {
        this.id = b.id;
        this.ticketNo = b.ticketNo;
        this.source = b.source;
        this.problemType = b.problemType;
        this.description = b.description;
        this.status = b.status;
        this.kbId = b.kbId;
        this.kbNameSnapshot = b.kbNameSnapshot;
        this.relatedDocIds = b.relatedDocIds;
        this.relatedDocNames = b.relatedDocNames;
        this.sourcePayload = b.sourcePayload;
        this.latestHandlingRemark = b.latestHandlingRemark;
        this.latestHandledBy = b.latestHandledBy;
        this.latestHandledByName = b.latestHandledByName;
        this.latestHandledAt = b.latestHandledAt;
        this.submittedBy = b.submittedBy;
        this.submittedByName = b.submittedByName;
        this.submittedAt = b.submittedAt;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
    }

    /**
     * 工厂：新建待处理工单。
     */
    public static BadcaseTicket create(BadcaseSource source, BadcaseProblemType problemType,
                                       String description, String kbId, String kbNameSnapshot,
                                       List<String> relatedDocIds, List<String> relatedDocNames,
                                       Map<String, Object> sourcePayload,
                                       String submittedBy, String submittedByName) {
        if (source == null) throw new IllegalArgumentException("source required");
        if (problemType == null) throw new IllegalArgumentException("problemType required");
        if (!BadcaseProblemType.isValid(source, problemType)) {
            throw new IllegalArgumentException("invalid source+problemType: " + source + "/" + problemType);
        }
        if (description == null || description.isBlank() || description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("description must be 1~" + DESCRIPTION_MAX_LENGTH + " chars");
        }
        if (submittedBy == null || submittedBy.isBlank()) throw new IllegalArgumentException("submittedBy required");
        LocalDateTime now = LocalDateTime.now();
        return new Builder()
                .id(new BadcaseTicketId(IdGenerator.badcaseTicket()))
                .ticketNo(generateTicketNo(now))
                .source(source).problemType(problemType).description(description)
                .kbId(kbId).kbNameSnapshot(kbNameSnapshot)
                .relatedDocIds(relatedDocIds).relatedDocNames(relatedDocNames)
                .sourcePayload(sourcePayload)
                .status(BadcaseStatus.PENDING)
                .submittedBy(submittedBy).submittedByName(submittedByName)
                .submittedAt(now).createdAt(now).updatedAt(now)
                .build();
    }

    /**
     * 状态变更（统一处理：任意目标状态 + 备注）。
     * <p>不做流转守卫，由前端下拉约束 + 后端枚举校验保证（见设计文档 §3.4）。</p>
     */
    public BadcaseTicket changeStatus(BadcaseStatus targetStatus, String remark,
                                      String handledBy, String handledByName) {
        if (targetStatus == null) throw new IllegalArgumentException("targetStatus required");
        if (remark == null || remark.isBlank() || remark.length() > REMARK_MAX_LENGTH) {
            throw new IllegalArgumentException("remark must be 1~" + REMARK_MAX_LENGTH + " chars");
        }
        if (handledBy == null || handledBy.isBlank()) throw new IllegalArgumentException("handledBy required");
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .status(targetStatus)
                .latestHandlingRemark(remark)
                .latestHandledBy(handledBy)
                .latestHandledByName(handledByName)
                .latestHandledAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 移交处理人（不改状态）：直接覆盖 latestHandledBy / latestHandledByName 为新处理人。
     */
    public BadcaseTicket assignTo(String newHandler, String newHandlerName, String remark) {
        if (newHandler == null || newHandler.isBlank()) throw new IllegalArgumentException("handler required");
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .latestHandlingRemark(remark)
                .latestHandledBy(newHandler)
                .latestHandledByName(newHandlerName)
                .latestHandledAt(now)
                .updatedAt(now)
                .build();
    }

    private static String generateTicketNo(LocalDateTime t) {
        // 用 UUID 前 8 位做序号，避免同一分钟内创建多条工单撞 uq_badcase_tickets_no
        String datePart = String.format("%04d%02d%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
        String randPart = IdGenerator.badcaseTicket().substring(2, 10).toUpperCase();
        return "BC-" + datePart + "-" + randPart;
    }

    private Builder copyFromThis() {
        return new Builder()
                .id(this.id).ticketNo(this.ticketNo).source(this.source).problemType(this.problemType)
                .description(this.description).status(this.status)
                .kbId(this.kbId).kbNameSnapshot(this.kbNameSnapshot)
                .relatedDocIds(this.relatedDocIds).relatedDocNames(this.relatedDocNames)
                .sourcePayload(this.sourcePayload)
                .latestHandlingRemark(this.latestHandlingRemark)
                .latestHandledBy(this.latestHandledBy).latestHandledByName(this.latestHandledByName)
                .latestHandledAt(this.latestHandledAt)
                .submittedBy(this.submittedBy).submittedByName(this.submittedByName)
                .submittedAt(this.submittedAt).createdAt(this.createdAt);
    }

    // getters
    public BadcaseTicketId getId() { return id; }
    public String getTicketNo() { return ticketNo; }
    public BadcaseSource getSource() { return source; }
    public BadcaseProblemType getProblemType() { return problemType; }
    public String getDescription() { return description; }
    public BadcaseStatus getStatus() { return status; }
    public String getKbId() { return kbId; }
    public String getKbNameSnapshot() { return kbNameSnapshot; }
    public List<String> getRelatedDocIds() { return relatedDocIds; }
    public List<String> getRelatedDocNames() { return relatedDocNames; }
    public Map<String, Object> getSourcePayload() { return sourcePayload; }
    public String getLatestHandlingRemark() { return latestHandlingRemark; }
    public String getLatestHandledBy() { return latestHandledBy; }
    public String getLatestHandledByName() { return latestHandledByName; }
    public LocalDateTime getLatestHandledAt() { return latestHandledAt; }
    public String getSubmittedBy() { return submittedBy; }
    public String getSubmittedByName() { return submittedByName; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private BadcaseTicketId id;
        private String ticketNo;
        private BadcaseSource source;
        private BadcaseProblemType problemType;
        private String description;
        private BadcaseStatus status;
        private String kbId;
        private String kbNameSnapshot;
        private List<String> relatedDocIds;
        private List<String> relatedDocNames;
        private Map<String, Object> sourcePayload;
        private String latestHandlingRemark;
        private String latestHandledBy;
        private String latestHandledByName;
        private LocalDateTime latestHandledAt;
        private String submittedBy;
        private String submittedByName;
        private LocalDateTime submittedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(BadcaseTicketId v) { this.id = v; return this; }
        public Builder ticketNo(String v) { this.ticketNo = v; return this; }
        public Builder source(BadcaseSource v) { this.source = v; return this; }
        public Builder problemType(BadcaseProblemType v) { this.problemType = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder status(BadcaseStatus v) { this.status = v; return this; }
        public Builder kbId(String v) { this.kbId = v; return this; }
        public Builder kbNameSnapshot(String v) { this.kbNameSnapshot = v; return this; }
        public Builder relatedDocIds(List<String> v) { this.relatedDocIds = v; return this; }
        public Builder relatedDocNames(List<String> v) { this.relatedDocNames = v; return this; }
        public Builder sourcePayload(Map<String, Object> v) { this.sourcePayload = v; return this; }
        public Builder latestHandlingRemark(String v) { this.latestHandlingRemark = v; return this; }
        public Builder latestHandledBy(String v) { this.latestHandledBy = v; return this; }
        public Builder latestHandledByName(String v) { this.latestHandledByName = v; return this; }
        public Builder latestHandledAt(LocalDateTime v) { this.latestHandledAt = v; return this; }
        public Builder submittedBy(String v) { this.submittedBy = v; return this; }
        public Builder submittedByName(String v) { this.submittedByName = v; return this; }
        public Builder submittedAt(LocalDateTime v) { this.submittedAt = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }

        public BadcaseTicket build() {
            if (status == null) status = BadcaseStatus.PENDING;
            return new BadcaseTicket(this);
        }
    }
}
