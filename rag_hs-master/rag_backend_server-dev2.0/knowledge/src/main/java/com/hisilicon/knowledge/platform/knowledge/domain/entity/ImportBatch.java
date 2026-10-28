package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 导入批次聚合根（纯 POJO，不可变）
 * <p>批次不预创建文件清单，仅作 upload_sessions 聚合视图。状态由聚合统计推导：
 * 全部终态且无 FAILED/ABORTED → COMPLETED；存在 FAILED → PARTIAL；用户中止 → ABORTED。</p>
 */
public class ImportBatch {

    private final ImportBatchId id;
    private final String kbId;
    private final String categoryId;
    private final String batchName;
    private final long totalFiles;
    private final long totalSize;
    private final ImportBatchStatus status;
    private final ImportFileMetadata defaultMetadata;
    private final DraftConflictPolicy draftConflictPolicy;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private ImportBatch(Builder builder) {
        this.id = builder.id;
        this.kbId = builder.kbId;
        this.categoryId = builder.categoryId;
        this.batchName = builder.batchName;
        this.totalFiles = builder.totalFiles;
        this.totalSize = builder.totalSize;
        this.status = builder.status;
        this.defaultMetadata = builder.defaultMetadata;
        this.draftConflictPolicy = builder.draftConflictPolicy;
        this.createdBy = builder.createdBy;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /** 领域工厂入参（6 项字段收敛为单一入参） */
    public record BatchSeed(String kbId, String categoryId, String batchName,
                            ImportFileMetadata defaultMetadata,
                            DraftConflictPolicy draftConflictPolicy, String createdBy) {
    }

    /**
     * 领域工厂：创建新批次（status=UPLOADING，totalFiles/totalSize 初始 0）
     */
    public static ImportBatch create(BatchSeed seed) {
        LocalDateTime now = LocalDateTime.now();
        return ImportBatch.builder()
                .id(ImportBatchId.of(IdGenerator.uploadSession()))
                .kbId(seed.kbId())
                .categoryId(seed.categoryId())
                .batchName(seed.batchName())
                .totalFiles(0L)
                .totalSize(0L)
                .status(ImportBatchStatus.UPLOADING)
                .defaultMetadata(seed.defaultMetadata())
                .draftConflictPolicy(seed.draftConflictPolicy())
                .createdBy(seed.createdBy())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 累加文件数与字节数（create 会话时维护冗余字段） */
    public ImportBatch addFile(long size) {
        return toBuilder()
                .totalFiles(totalFiles + 1)
                .totalSize(totalSize + size)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 用户中止 */
    public ImportBatch abort() {
        return toBuilder()
                .status(ImportBatchStatus.ABORTED)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public ImportBatchId getId() { return id; }
    public String getKbId() { return kbId; }
    public String getCategoryId() { return categoryId; }
    public String getBatchName() { return batchName; }
    public long getTotalFiles() { return totalFiles; }
    public long getTotalSize() { return totalSize; }
    public ImportBatchStatus getStatus() { return status; }
    public ImportFileMetadata getDefaultMetadata() { return defaultMetadata; }
    public DraftConflictPolicy getDraftConflictPolicy() { return draftConflictPolicy; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .batchName(this.batchName)
                .totalFiles(this.totalFiles)
                .totalSize(this.totalSize)
                .status(this.status)
                .defaultMetadata(this.defaultMetadata)
                .draftConflictPolicy(this.draftConflictPolicy)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt);
    }

    public static class Builder {
        private ImportBatchId id;
        private String kbId;
        private String categoryId;
        private String batchName;
        private long totalFiles;
        private long totalSize;
        private ImportBatchStatus status;
        private ImportFileMetadata defaultMetadata;
        private DraftConflictPolicy draftConflictPolicy;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(ImportBatchId id) { this.id = id; return this; }
        public Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public Builder categoryId(String categoryId) { this.categoryId = categoryId; return this; }
        public Builder batchName(String batchName) { this.batchName = batchName; return this; }
        public Builder totalFiles(long totalFiles) { this.totalFiles = totalFiles; return this; }
        public Builder totalSize(long totalSize) { this.totalSize = totalSize; return this; }
        public Builder status(ImportBatchStatus status) { this.status = status; return this; }
        public Builder defaultMetadata(ImportFileMetadata defaultMetadata) { this.defaultMetadata = defaultMetadata; return this; }
        public Builder draftConflictPolicy(DraftConflictPolicy draftConflictPolicy) { this.draftConflictPolicy = draftConflictPolicy; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public ImportBatch build() {
            if (id == null) throw new IllegalStateException("批次 ID 不能为空");
            if (kbId == null || kbId.isBlank()) throw new IllegalStateException("所属知识库 ID 不能为空");
            if (batchName == null || batchName.isBlank()) throw new IllegalStateException("批次名称不能为空");
            if (status == null) throw new IllegalStateException("批次状态不能为空");
            if (draftConflictPolicy == null) throw new IllegalStateException("草稿冲突策略不能为空");
            return new ImportBatch(this);
        }
    }
}
