package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * tus 上传会话聚合根（纯 POJO，不可变）
 * <p>封装 offset 推进、状态流转、complete 判定。临时文件相对路径 {@code .uploads/{id}}，
 * 由调用方据 {@link #getId()} 拼装（与 {@code LocalFileStorageService.UPLOADS_DIR} 一致）。</p>
 * <p>状态机：CREATED → UPLOADING → COMPLETED/SKIPPED/FAILED/ABORTED/EXPIRED；终态不可回退。</p>
 * <p>用途（{@link UploadSessionPurpose}）：BATCH_IMPORT（batchId 必填）/ DOC_EDIT、
 * VERSION_OVERRIDE（batchId 必为 null，targetDocumentId 必填）。</p>
 */
public class UploadSession {

    private final UploadSessionId id;
    private final String batchId;
    /** 会话用途（决定 create 校验与 complete 落库分流；缺省 BATCH_IMPORT） */
    private final UploadSessionPurpose purpose;
    /** doc 用途的目标文档 ID（DOC_EDIT / VERSION_OVERRIDE）；batch-import 为 null */
    private final String targetDocumentId;
    private final String kbId;
    private final String categoryId;
    private final String fileName;
    private final String fileTitle;
    private final String mimeType;
    private final long uploadLength;
    private final long uploadOffset;
    private final DraftConflictPolicy draftPolicy;
    private final ImportFileMetadata metadata;
    private final UploadSessionStatus status;
    private final String documentId;
    private final String error;
    /** zip 异步解压已处理条目数（进度，单文件会话不用） */
    private final int entriesDone;
    /** zip 解压跳过的条目数（不支持类型/同名等，落 COMPLETED 终态时携带；单文件会话恒 0） */
    private final int skippedCount;
    private final String tempPath;
    private final LocalDateTime expiresAt;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private UploadSession(Builder builder) {
        this.id = builder.id;
        this.batchId = builder.batchId;
        this.purpose = builder.purpose == null ? UploadSessionPurpose.BATCH_IMPORT : builder.purpose;
        this.targetDocumentId = builder.targetDocumentId;
        this.kbId = builder.kbId;
        this.categoryId = builder.categoryId;
        this.fileName = builder.fileName;
        this.fileTitle = builder.fileTitle;
        this.mimeType = builder.mimeType;
        this.uploadLength = builder.uploadLength;
        this.uploadOffset = builder.uploadOffset;
        this.draftPolicy = builder.draftPolicy;
        this.metadata = builder.metadata;
        this.status = builder.status;
        this.documentId = builder.documentId;
        this.error = builder.error;
        this.entriesDone = builder.entriesDone;
        this.skippedCount = builder.skippedCount;
        this.tempPath = builder.tempPath;
        this.expiresAt = builder.expiresAt;
        this.createdBy = builder.createdBy;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 会话创建入参（工厂参数对象：批量导入与文档用途共用的 11 项基础字段）。
     * <p>批量导入用 {@code batchId/draftPolicy}；文档用途用 {@code purpose/targetDocumentId}，
     * 其余按用途取用或忽略（见两个工厂）。</p>
     */
    public record Seed(UploadSessionPurpose purpose, String batchId, String targetDocumentId,
                       String kbId, String categoryId, String fileName, String fileTitle,
                       String mimeType, long uploadLength, DraftConflictPolicy draftPolicy,
                       ImportFileMetadata metadata, String createdBy, LocalDateTime expiresAt) {
    }

    /**
     * 领域工厂：创建新会话（status=CREATED，offset=0）。batch-import 用途（purpose=BATCH_IMPORT）。
     */
    public static UploadSession create(Seed seed) {
        String sessionId = IdGenerator.uploadSession();
        LocalDateTime now = LocalDateTime.now();
        return UploadSession.builder()
                .id(UploadSessionId.of(sessionId))
                .batchId(seed.batchId())
                .kbId(seed.kbId())
                .categoryId(seed.categoryId())
                .fileName(seed.fileName())
                .fileTitle(seed.fileTitle())
                .mimeType(seed.mimeType())
                .uploadLength(seed.uploadLength())
                .uploadOffset(0L)
                .draftPolicy(seed.draftPolicy())
                .metadata(seed.metadata())
                .status(UploadSessionStatus.CREATED)
                .tempPath(".uploads/" + sessionId)
                .expiresAt(seed.expiresAt())
                .createdBy(seed.createdBy())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域工厂：文档用途会话（DOC_EDIT / VERSION_OVERRIDE，status=CREATED，offset=0）。
     * <p>不隶属导入批次（batchId=null）；目标文档 {@code targetDocumentId} 必填；
     * draftPolicy 不消费（doc 用途无同名分流语义），置 OVERRIDE 仅满足列非空习惯。</p>
     */
    public static UploadSession createForDocument(Seed seed) {
        if (seed.purpose() == null || seed.purpose() == UploadSessionPurpose.BATCH_IMPORT) {
            throw new IllegalArgumentException("createForDocument 仅接受 DOC_EDIT / VERSION_OVERRIDE");
        }
        if (seed.targetDocumentId() == null || seed.targetDocumentId().isBlank()) {
            throw new IllegalArgumentException("文档用途会话必须携带目标文档 ID");
        }
        String sessionId = IdGenerator.uploadSession();
        LocalDateTime now = LocalDateTime.now();
        return UploadSession.builder()
                .id(UploadSessionId.of(sessionId))
                .purpose(seed.purpose())
                .targetDocumentId(seed.targetDocumentId())
                .kbId(seed.kbId())
                .categoryId(seed.categoryId())
                .fileName(seed.fileName())
                .fileTitle(seed.fileTitle())
                .mimeType(seed.mimeType())
                .uploadLength(seed.uploadLength())
                .uploadOffset(0L)
                .draftPolicy(DraftConflictPolicy.OVERRIDE)
                .metadata(seed.metadata())
                .status(UploadSessionStatus.CREATED)
                .tempPath(".uploads/" + sessionId)
                .expiresAt(seed.expiresAt())
                .createdBy(seed.createdBy())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 推进 offset（收到一片 PATCH），状态置 UPLOADING。终态会话不可推进。
     */
    public UploadSession appendChunkLen(long len) {
        if (status.isTerminal()) {
            throw new IllegalStateException("会话已终态，不可再上传: status=" + status);
        }
        return toBuilder()
                .uploadOffset(uploadOffset + len)
                .status(UploadSessionStatus.UPLOADING)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 传完判定：offset == length */
    public boolean isComplete() {
        return uploadOffset == uploadLength;
    }

    /** zip 传完转后台解压（PROCESSING），PATCH 即返 204；单文件不经此态 */
    public UploadSession markProcessing() {
        return toBuilder()
                .status(UploadSessionStatus.PROCESSING)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** zip 解压每处理一个条目推进进度（entriesDone + 1） */
    public UploadSession incrementEntriesDone() {
        return toBuilder()
                .entriesDone(entriesDone + 1)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 落库成功（skippedCount=0，单文件/无跳过） */
    public UploadSession markComplete(String documentId) {
        return markComplete(documentId, 0);
    }

    /**
     * 落库成功，携带 zip 解压跳过的条目数（不支持类型等）。
     * <p>zip 全/部分条目跳过（无致命失败）→ COMPLETED + skippedCount=N，
     * 供批次进度 {@code toProgress.ignored} 聚合，而非进 SKIPPED 终态（避免被误判为"报错"）。</p>
     */
    public UploadSession markComplete(String documentId, int skippedCount) {
        return markComplete(documentId, skippedCount, null);
    }

    /**
     * 落库成功，携带跳过条目数与结果摘要。
     * <p>zip 部分条目业务性跳过（不支持类型/重名/空文件）→ COMPLETED + skippedCount + 摘要写入 error 列——
     * error 列语义扩展为「结果摘要」（批次进度 resultSummaries 透出），不再与 FAILED 强绑定；
     * summary 为 null 时显式清列（顺带清除早前误判残留）。</p>
     */
    public UploadSession markComplete(String documentId, int skippedCount, String resultSummary) {
        return toBuilder()
                .status(UploadSessionStatus.COMPLETED)
                .documentId(documentId)
                .skippedCount(skippedCount)
                .error(resultSummary)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 同名跳过（草稿 SKIP 或命中 REVIEWING/PUBLISHED/ARCHIVED） */
    public UploadSession markSkipped() {
        return toBuilder()
                .status(UploadSessionStatus.SKIPPED)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 落库失败 */
    public UploadSession markFailed(String error) {
        return toBuilder()
                .status(UploadSessionStatus.FAILED)
                .error(error)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 用户中止 */
    public UploadSession abort() {
        return toBuilder()
                .status(UploadSessionStatus.ABORTED)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 会话超期 */
    public UploadSession expire() {
        return toBuilder()
                .status(UploadSessionStatus.EXPIRED)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public boolean isActive() {
        return !status.isTerminal();
    }

    // ========== getters ==========

    public UploadSessionId getId() { return id; }
    public String getBatchId() { return batchId; }
    public UploadSessionPurpose getPurpose() { return purpose; }
    public String getTargetDocumentId() { return targetDocumentId; }
    public String getKbId() { return kbId; }
    public String getCategoryId() { return categoryId; }
    public String getFileName() { return fileName; }
    public String getFileTitle() { return fileTitle; }
    public String getMimeType() { return mimeType; }
    public long getUploadLength() { return uploadLength; }
    public long getUploadOffset() { return uploadOffset; }
    public DraftConflictPolicy getDraftPolicy() { return draftPolicy; }
    public ImportFileMetadata getMetadata() { return metadata; }
    public UploadSessionStatus getStatus() { return status; }
    public String getDocumentId() { return documentId; }
    public String getError() { return error; }
    public int getEntriesDone() { return entriesDone; }
    public int getSkippedCount() { return skippedCount; }
    public String getTempPath() { return tempPath; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder()
                .id(this.id)
                .batchId(this.batchId)
                .purpose(this.purpose)
                .targetDocumentId(this.targetDocumentId)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .fileName(this.fileName)
                .fileTitle(this.fileTitle)
                .mimeType(this.mimeType)
                .uploadLength(this.uploadLength)
                .uploadOffset(this.uploadOffset)
                .draftPolicy(this.draftPolicy)
                .metadata(this.metadata)
                .status(this.status)
                .documentId(this.documentId)
                .error(this.error)
                .entriesDone(this.entriesDone)
                .skippedCount(this.skippedCount)
                .tempPath(this.tempPath)
                .expiresAt(this.expiresAt)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt);
    }

    public static class Builder {
        private UploadSessionId id;
        private String batchId;
        private UploadSessionPurpose purpose;
        private String targetDocumentId;
        private String kbId;
        private String categoryId;
        private String fileName;
        private String fileTitle;
        private String mimeType;
        private long uploadLength;
        private long uploadOffset;
        private DraftConflictPolicy draftPolicy;
        private ImportFileMetadata metadata;
        private UploadSessionStatus status;
        private String documentId;
        private String error;
        private int entriesDone;
        private int skippedCount;
        private String tempPath;
        private LocalDateTime expiresAt;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(UploadSessionId id) { this.id = id; return this; }
        public Builder batchId(String batchId) { this.batchId = batchId; return this; }
        public Builder purpose(UploadSessionPurpose purpose) { this.purpose = purpose; return this; }
        public Builder targetDocumentId(String targetDocumentId) { this.targetDocumentId = targetDocumentId; return this; }
        public Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public Builder categoryId(String categoryId) { this.categoryId = categoryId; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder fileTitle(String fileTitle) { this.fileTitle = fileTitle; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder uploadLength(long uploadLength) { this.uploadLength = uploadLength; return this; }
        public Builder uploadOffset(long uploadOffset) { this.uploadOffset = uploadOffset; return this; }
        public Builder draftPolicy(DraftConflictPolicy draftPolicy) { this.draftPolicy = draftPolicy; return this; }
        public Builder metadata(ImportFileMetadata metadata) { this.metadata = metadata; return this; }
        public Builder status(UploadSessionStatus status) { this.status = status; return this; }
        public Builder documentId(String documentId) { this.documentId = documentId; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder entriesDone(int entriesDone) { this.entriesDone = entriesDone; return this; }
        public Builder skippedCount(int skippedCount) { this.skippedCount = skippedCount; return this; }
        public Builder tempPath(String tempPath) { this.tempPath = tempPath; return this; }
        public Builder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public UploadSession build() {
            if (id == null) throw new IllegalStateException("会话 ID 不能为空");
            UploadSessionPurpose effective = purpose == null ? UploadSessionPurpose.BATCH_IMPORT : purpose;
            if (effective == UploadSessionPurpose.BATCH_IMPORT
                    && (batchId == null || batchId.isBlank())) {
                throw new IllegalStateException("导入会话所属批次 ID 不能为空");
            }
            if (effective != UploadSessionPurpose.BATCH_IMPORT
                    && (targetDocumentId == null || targetDocumentId.isBlank())) {
                throw new IllegalStateException("文档用途会话必须携带目标文档 ID");
            }
            if (kbId == null || kbId.isBlank()) throw new IllegalStateException("所属知识库 ID 不能为空");
            if (fileName == null || fileName.isBlank()) throw new IllegalStateException("文件名不能为空");
            if (fileTitle == null || fileTitle.isBlank()) throw new IllegalStateException("文档标题不能为空");
            if (status == null) throw new IllegalStateException("状态不能为空");
            if (tempPath == null || tempPath.isBlank()) throw new IllegalStateException("临时路径不能为空");
            return new UploadSession(this);
        }
    }
}
