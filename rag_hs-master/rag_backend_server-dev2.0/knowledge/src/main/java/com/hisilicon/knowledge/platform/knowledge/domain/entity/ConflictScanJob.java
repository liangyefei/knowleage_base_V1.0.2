package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 冲突检测任务（批次）。
 * <p>不可变领域实体，状态流转返回新实例。对应表 {@code conflict_scan_jobs}。</p>
 *
 * <p>新模型端契约下的状态机：QUEUED（排队）→ RUNNING（已提交模型端，正在轮询）→
 * SUCCEEDED / FAILED。{@code modelTaskId} 是模型端返回的 task_id，轮询时用。</p>
 */
public class ConflictScanJob {

    private final ConflictScanJobId id;
    private final String batchNo;
    private final String kbId;
    private final ScanTriggerType triggerType;
    private final ScanMode mode;
    private final String scopeNodesJson;
    private final String scopeFingerprint;
    private final ScanJobStatus status;
    private final int totalDocuments;
    private final int scannedChunks;
    private final int newConflictCount;
    private final int hitConflictCount;
    private final int failedItems;
    private final String error;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** 模型端 task_id（提交后回填）。 */
    private final String modelTaskId;
    /** 本次提交给模型端的文件路径列表 JSON（便于重试/审计）。 */
    private final String filePathsJson;
    /** chunk 进度（模型端 done_chunks）。 */
    private final Integer progressDone;
    /** chunk 进度（模型端 total_chunks）。 */
    private final Integer progressTotal;
    /** 模型端返回的冲突总数。 */
    private final Integer conflictCount;

    private ConflictScanJob(Builder b) {
        this.id = b.id;
        this.batchNo = b.batchNo;
        this.kbId = b.kbId;
        this.triggerType = b.triggerType;
        this.mode = b.mode;
        this.scopeNodesJson = b.scopeNodesJson;
        this.scopeFingerprint = b.scopeFingerprint;
        this.status = b.status;
        this.totalDocuments = b.totalDocuments;
        this.scannedChunks = b.scannedChunks;
        this.newConflictCount = b.newConflictCount;
        this.hitConflictCount = b.hitConflictCount;
        this.failedItems = b.failedItems;
        this.error = b.error;
        this.startedAt = b.startedAt;
        this.finishedAt = b.finishedAt;
        this.createdBy = b.createdBy;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
        this.modelTaskId = b.modelTaskId;
        this.filePathsJson = b.filePathsJson;
        this.progressDone = b.progressDone;
        this.progressTotal = b.progressTotal;
        this.conflictCount = b.conflictCount;
    }

    public static ConflictScanJob queue(String batchNo, String kbId, ScanTriggerType triggerType,
                                        ScanMode mode, String scopeNodesJson, String scopeFingerprint,
                                        int totalDocuments, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return builder()
                .id(new ConflictScanJobId(IdGenerator.conflictJob()))
                .batchNo(batchNo)
                .kbId(kbId)
                .triggerType(triggerType)
                .mode(mode)
                .scopeNodesJson(scopeNodesJson)
                .scopeFingerprint(scopeFingerprint)
                .totalDocuments(totalDocuments)
                .status(ScanJobStatus.QUEUED)
                .createdBy(createdBy)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 提交模型端成功，置 RUNNING 并回填 modelTaskId / filePaths。 */
    public ConflictScanJob markRunning(String modelTaskId, String filePathsJson) {
        return copyFromThis()
                .status(ScanJobStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .modelTaskId(modelTaskId)
                .filePathsJson(filePathsJson)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 提交模型端失败置 FAILED。 */
    public ConflictScanJob markFailed(String error) {
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .status(ScanJobStatus.FAILED)
                .error(error)
                .finishedAt(now)
                .updatedAt(now)
                .build();
    }

    /** 轮询中刷新进度（不到终态，仅更新进度字段）。 */
    public ConflictScanJob refreshProgress(Integer done, Integer total, Integer conflictCount) {
        return copyFromThis()
                .progressDone(done)
                .progressTotal(total)
                .conflictCount(conflictCount)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 模型端终态 SUCCEEDED，落库完成后调用。 */
    public ConflictScanJob markSucceeded(int scannedChunks, int newConflicts, int hitConflicts,
                                         int failedItems, int conflictCount) {
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .status(ScanJobStatus.COMPLETED)
                .scannedChunks(scannedChunks)
                .newConflictCount(newConflicts)
                .hitConflictCount(hitConflicts)
                .failedItems(failedItems)
                .conflictCount(conflictCount)
                .progressDone(progressTotal)
                .finishedAt(now)
                .updatedAt(now)
                .build();
    }

    /** 模型端终态 FAILED。 */
    public ConflictScanJob markModelFailed(String error) {
        LocalDateTime now = LocalDateTime.now();
        return copyFromThis()
                .status(ScanJobStatus.FAILED)
                .error(error)
                .finishedAt(now)
                .updatedAt(now)
                .build();
    }

    /** @deprecated 旧回调落库入口，保留兼容；新流程用 {@link #markSucceeded}。 */
    @Deprecated
    public ConflictScanJob markFinished(int scannedChunks, int newConflicts, int hitConflicts, int failedItems) {
        return markSucceeded(scannedChunks, newConflicts, hitConflicts, failedItems, newConflicts + hitConflicts);
    }

    private Builder copyFromThis() {
        return builder()
                .id(this.id).batchNo(this.batchNo).kbId(this.kbId)
                .triggerType(this.triggerType).mode(this.mode)
                .scopeNodesJson(this.scopeNodesJson).scopeFingerprint(this.scopeFingerprint)
                .totalDocuments(this.totalDocuments).scannedChunks(this.scannedChunks)
                .newConflictCount(this.newConflictCount).hitConflictCount(this.hitConflictCount)
                .failedItems(this.failedItems).error(this.error)
                .startedAt(this.startedAt).finishedAt(this.finishedAt)
                .createdBy(this.createdBy).createdAt(this.createdAt)
                .modelTaskId(this.modelTaskId).filePathsJson(this.filePathsJson)
                .progressDone(this.progressDone).progressTotal(this.progressTotal)
                .conflictCount(this.conflictCount);
    }

    // getters
    public ConflictScanJobId getId() { return id; }
    public String getBatchNo() { return batchNo; }
    public String getKbId() { return kbId; }
    public ScanTriggerType getTriggerType() { return triggerType; }
    public ScanMode getMode() { return mode; }
    public String getScopeNodesJson() { return scopeNodesJson; }
    public String getScopeFingerprint() { return scopeFingerprint; }
    public ScanJobStatus getStatus() { return status; }
    public int getTotalDocuments() { return totalDocuments; }
    public int getScannedChunks() { return scannedChunks; }
    public int getNewConflictCount() { return newConflictCount; }
    public int getHitConflictCount() { return hitConflictCount; }
    public int getFailedItems() { return failedItems; }
    public String getError() { return error; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getModelTaskId() { return modelTaskId; }
    public String getFilePathsJson() { return filePathsJson; }
    public Integer getProgressDone() { return progressDone; }
    public Integer getProgressTotal() { return progressTotal; }
    public Integer getConflictCount() { return conflictCount; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ConflictScanJobId id;
        private String batchNo;
        private String kbId;
        private ScanTriggerType triggerType;
        private ScanMode mode;
        private String scopeNodesJson;
        private String scopeFingerprint;
        private ScanJobStatus status;
        private int totalDocuments;
        private int scannedChunks;
        private int newConflictCount;
        private int hitConflictCount;
        private int failedItems;
        private String error;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String modelTaskId;
        private String filePathsJson;
        private Integer progressDone;
        private Integer progressTotal;
        private Integer conflictCount;

        public Builder id(ConflictScanJobId v) { this.id = v; return this; }
        public Builder batchNo(String v) { this.batchNo = v; return this; }
        public Builder kbId(String v) { this.kbId = v; return this; }
        public Builder triggerType(ScanTriggerType v) { this.triggerType = v; return this; }
        public Builder mode(ScanMode v) { this.mode = v; return this; }
        public Builder scopeNodesJson(String v) { this.scopeNodesJson = v; return this; }
        public Builder scopeFingerprint(String v) { this.scopeFingerprint = v; return this; }
        public Builder status(ScanJobStatus v) { this.status = v; return this; }
        public Builder totalDocuments(int v) { this.totalDocuments = v; return this; }
        public Builder scannedChunks(int v) { this.scannedChunks = v; return this; }
        public Builder newConflictCount(int v) { this.newConflictCount = v; return this; }
        public Builder hitConflictCount(int v) { this.hitConflictCount = v; return this; }
        public Builder failedItems(int v) { this.failedItems = v; return this; }
        public Builder error(String v) { this.error = v; return this; }
        public Builder startedAt(LocalDateTime v) { this.startedAt = v; return this; }
        public Builder finishedAt(LocalDateTime v) { this.finishedAt = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }
        public Builder modelTaskId(String v) { this.modelTaskId = v; return this; }
        public Builder filePathsJson(String v) { this.filePathsJson = v; return this; }
        public Builder progressDone(Integer v) { this.progressDone = v; return this; }
        public Builder progressTotal(Integer v) { this.progressTotal = v; return this; }
        public Builder conflictCount(Integer v) { this.conflictCount = v; return this; }

        public ConflictScanJob build() {
            if (id == null) throw new IllegalStateException("job id required");
            if (batchNo == null || batchNo.isBlank()) throw new IllegalStateException("batchNo required");
            if (triggerType == null) throw new IllegalStateException("triggerType required");
            if (mode == null) throw new IllegalStateException("mode required");
            if (status == null) throw new IllegalStateException("status required");
            if (createdBy == null || createdBy.isBlank()) throw new IllegalStateException("createdBy required");
            if (createdAt == null) throw new IllegalStateException("createdAt required");
            return new ConflictScanJob(this);
        }
    }
}
