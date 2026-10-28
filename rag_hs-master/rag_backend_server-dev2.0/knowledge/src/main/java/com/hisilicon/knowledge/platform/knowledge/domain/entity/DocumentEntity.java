package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 领域层文档实体
 * <p>不可变设计，状态变更通过工厂方法返回新实体。</p>
 * <p>双重状态模型：
 * <ul>
 *   <li>{@code status}（生命周期，人工驱动）：DRAFT → REVIEWING → PUBLISHED → ARCHIVED / DEPRECATED</li>
 *   <li>{@code processStatus}（模型处理，回调驱动）：PENDING → PROCESSING → COMPLETED / FAILED</li>
 * </ul>
 * 文档真正可检索 = PUBLISHED + COMPLETED。</p>
 */
public class DocumentEntity {

    // ========== 生命周期状态 ==========

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 状态：审核中 */
    public static final String STATUS_REVIEWING = "REVIEWING";
    /** 状态：已发布 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 状态：已归档 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 状态：已废弃 */
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    // ========== 模型处理状态 ==========

    /** 模型处理：待处理 */
    public static final String PROCESS_PENDING = "PENDING";
    /** 模型处理：处理中 */
    public static final String PROCESS_PROCESSING = "PROCESSING";
    /** 模型处理：处理成功 */
    public static final String PROCESS_COMPLETED = "COMPLETED";
    /** 模型处理：处理失败（可重试） */
    public static final String PROCESS_FAILED = "FAILED";

    // ========== 文档类型分类 ==========

    /** 文档类型：文档类（pdf/doc/docx/xls/xlsx/md/txt/html/csv） */
    public static final String DOC_TYPE_DOCUMENT = "DOCUMENT";
    /** 文档类型：代码类（c/cpp/h/hpp/py/ts/ets/java） */
    public static final String DOC_TYPE_CODE = "CODE";
    /** 文档类型：配置文件类（ini/dtsi/xml/sh/yaml/yml） */
    public static final String DOC_TYPE_CONFIG = "CONFIG";
    /** 文档类型：其他（makefile 等） */
    public static final String DOC_TYPE_OTHER = "OTHER";

    private final DocumentId id;
    private final String kbId;
    private final String categoryId;
    private final String title;
    private final String status;
    private final String processStatus;
    private final String processError;
    private final Integer chunkCount;
    private final String processErrorCode;
    private final LocalDateTime processedAt;
    private final String currentVersion;
    private final String publishedVersion;
    private final Boolean hasPublishedVersion;
    private final String filePath;
    private final Long fileSize;
    private final String mimeType;
    private final String docType;
    private final String reviewedBy;
    private final String reviewComment;
    private final LocalDateTime reviewedAt;
    private final String createdBy;
    private final Boolean inheritParent;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String author;
    private final String tags;
    private final String remark;

    private DocumentEntity(Builder builder) {
        this.id = builder.id;
        this.kbId = builder.kbId;
        this.categoryId = builder.categoryId;
        this.title = builder.title;
        this.status = builder.status;
        this.processStatus = builder.processStatus;
        this.processError = builder.processError;
        this.chunkCount = builder.chunkCount;
        this.processErrorCode = builder.processErrorCode;
        this.processedAt = builder.processedAt;
        this.currentVersion = builder.currentVersion;
        this.publishedVersion = builder.publishedVersion;
        this.hasPublishedVersion = builder.hasPublishedVersion;
        this.filePath = builder.filePath;
        this.fileSize = builder.fileSize;
        this.mimeType = builder.mimeType;
        this.docType = builder.docType;
        this.reviewedBy = builder.reviewedBy;
        this.reviewComment = builder.reviewComment;
        this.reviewedAt = builder.reviewedAt;
        this.createdBy = builder.createdBy;
        this.inheritParent = builder.inheritParent;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.author = builder.author;
        this.tags = builder.tags;
        this.remark = builder.remark;
    }

    /**
     * 领域工厂：创建新文档（首次上传，指定 ID）。
     * <p>初始状态：status=DRAFT、processStatus=PENDING（等待模型服务处理）、
     * currentVersion=spec.version（由上传元数据提供）、hasPublishedVersion=false、inheritParent=true。</p>
     * <p>当调用方需在实体落库前就持有 ID（如存储路径 {@code kb/{kbId}/{categoryId}/{documentId}}
     * 要与实体主键一致）时使用本工厂，避免生成两个不同的 ID 造成路径与 DB 主键失配。
     * {@code documentId} 应由 {@link IdGenerator#doc()} 生成。</p>
     *
     * @param documentId 预先生成的文档 ID
     * @param kbId       所属知识库 ID
     * @param categoryId 所属类别 ID（可为空，表示知识库根目录）
     * @param spec       文件与展示属性（标题/路径/大小/MIME/docType/作者/标签/备注/版本）
     * @param createdBy  创建者用户 ID
     * @return 新文档实体
     */
    public static DocumentEntity create(String documentId, String kbId, String categoryId,
                                        DocumentFileSpec spec, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(documentId))
                .kbId(kbId)
                .categoryId(categoryId)
                .title(spec.title())
                .status(STATUS_DRAFT)
                .processStatus(PROCESS_PENDING)
                .currentVersion(spec.version())
                .publishedVersion(null)
                .hasPublishedVersion(false)
                .filePath(spec.filePath())
                .fileSize(spec.fileSize())
                .mimeType(spec.mimeType())
                .docType(spec.docType())
                .createdBy(createdBy)
                .author(spec.author())
                .tags(spec.tags())
                .remark(spec.remark())
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ========== 状态机（不可变，返回新实体；非法转换抛 IllegalStateException） ==========

    /**
     * 提交审核：DRAFT → REVIEWING
     * <p>仅草稿状态可提交。不修改 processStatus（模型处理在审核通过后触发）。
     * 清空上次流转遗留的审核人/审核意见/审核时间——历史驳回意见不带入新一轮审核。</p>
     *
     * @return 审核中的文档实体
     * @throws IllegalStateException 当前状态不是 DRAFT 时
     */
    public DocumentEntity submitReview() {
        if (!STATUS_DRAFT.equals(this.status)) {
            throw new IllegalStateException("仅草稿状态可提交审核，当前状态: " + this.status);
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_REVIEWING)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(null)
                .reviewComment(null)
                .reviewedAt(null)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 编辑者撤回审核：REVIEWING → DRAFT
     * <p>仅审核中状态可撤回。清空审核人/审核备注/审核时间（审核尚未完成）。</p>
     *
     * @return 回到草稿的文档实体
     * @throws IllegalStateException 当前状态不是 REVIEWING 时
     */
    public DocumentEntity cancelReview() {
        if (!STATUS_REVIEWING.equals(this.status)) {
            throw new IllegalStateException("仅审核中状态可撤回，当前状态: " + this.status);
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_DRAFT)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(null)
                .reviewComment(null)
                .reviewedAt(null)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 审核通过：REVIEWING → PUBLISHED
     * <p>记录审核人/审核时间；清空历史驳回意见（reviewComment=null），写入本次审核信息；
     * 已发布版本号推进为当前版本号；hasPublishedVersion 语义 =
     * 「document_versions 存在该文档的归档历史版本」：版本覆盖后重审通过（此前为 TRUE，
     * 覆盖事务已归档旧版）保持 TRUE——旧版仍归档在 document_versions 且业务要求旧版保持可检索，
     * 不再清除；仅首次发布（此前无归档版，为 FALSE）维持 FALSE。不修改 processStatus——模型处理由
     * 审核通过后发布的 DOCUMENT_PUBLISHED 事件驱动。</p>
     *
     * @param reviewer 审核人用户 ID
     * @return 已发布文档实体
     * @throws IllegalStateException 当前状态不是 REVIEWING 时
     */
    public DocumentEntity approve(String reviewer) {
        if (!STATUS_REVIEWING.equals(this.status)) {
            throw new IllegalStateException("仅审核中状态可批准，当前状态: " + this.status);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean keepPublishedHistory = Boolean.TRUE.equals(this.hasPublishedVersion);
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_PUBLISHED)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.currentVersion)
                .hasPublishedVersion(keepPublishedHistory)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(reviewer)
                .reviewComment(null)
                .reviewedAt(now)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(now)
                .build();
    }

    /**
     * 审核驳回：REVIEWING → DRAFT
     * <p>记录驳回原因到 reviewComment。文档回到草稿，编辑者可修改后重新提交。</p>
     *
     * @param reviewer 审核人用户 ID
     * @param reason   驳回原因（必填，由 Facade 层校验非空）
     * @return 回到草稿的文档实体
     * @throws IllegalStateException 当前状态不是 REVIEWING 时
     */
    public DocumentEntity reject(String reviewer, String reason) {
        if (!STATUS_REVIEWING.equals(this.status)) {
            throw new IllegalStateException("仅审核中状态可驳回，当前状态: " + this.status);
        }
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_DRAFT)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(reviewer)
                .reviewComment(reason)
                .reviewedAt(now)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(now)
                .build();
    }

    /**
     * 归档：PUBLISHED → ARCHIVED
     *
     * @return 已归档文档实体
     * @throws IllegalStateException 当前状态不是 PUBLISHED 时
     */
    public DocumentEntity archive() {
        if (!STATUS_PUBLISHED.equals(this.status)) {
            throw new IllegalStateException("仅已发布状态可归档，当前状态: " + this.status);
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_ARCHIVED)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(this.reviewedBy)
                .reviewComment(this.reviewComment)
                .reviewedAt(this.reviewedAt)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 废弃（软删除）：PUBLISHED → DEPRECATED
     *
     * @return 已废弃文档实体
     * @throws IllegalStateException 当前状态不是 PUBLISHED 时
     */
    public DocumentEntity deprecate() {
        if (!STATUS_PUBLISHED.equals(this.status)) {
            throw new IllegalStateException("仅已发布状态可废弃，当前状态: " + this.status);
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(this.title)
                .status(STATUS_DEPRECATED)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(this.reviewedBy)
                .reviewComment(this.reviewComment)
                .reviewedAt(this.reviewedAt)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 版本覆盖：PUBLISHED → DRAFT（新版本进入审核流）
     * <p>
     * 已发布文档上传新版本后：新文件成为工作版本（DRAFT+PENDING，需重新审核），
     * {@code currentVersion=newVersion}（由元数据提供），{@code hasPublishedVersion=TRUE}（旧版保持可检索）。
     * 旧版本归档由调用方在 {@code document_versions} 表完成。
     * </p>
     *
     * @param spec 新版本文件与展示属性（版本号必填、标题为去扩展名的新文件名）
     * @return 版本覆盖后的文档实体
     * @throws IllegalStateException 当前 status 不是 PUBLISHED 时
     */
    public DocumentEntity overrideWithNewVersion(DocumentFileSpec spec) {
        if (!STATUS_PUBLISHED.equals(this.status)) {
            throw new IllegalStateException("仅已发布文档可版本覆盖，当前状态: " + this.status);
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(spec.title())
                .status(STATUS_DRAFT)
                .processStatus(PROCESS_PENDING)
                .processError(null)
                .currentVersion(spec.version())
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(Boolean.TRUE)
                .filePath(spec.filePath())
                .fileSize(spec.fileSize())
                .mimeType(spec.mimeType())
                .docType(spec.docType())
                .reviewedBy(null)
                .reviewComment(null)
                .reviewedAt(null)
                .createdBy(this.createdBy)
                .author(spec.author())
                .tags(spec.tags())
                .remark(spec.remark())
                .inheritParent(this.inheritParent)
                // 版本覆盖=新内容周期：上传时间按最新上传计
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 改名（文档编辑）
     * <p>仅修改 title 字段，不改其他状态。生命周期状态不变。</p>
     *
     * @param newTitle 新标题
     * @return 改名后的文档实体
     */
    public DocumentEntity editTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalStateException("文档标题不能为空");
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(newTitle)
                .status(this.status)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(this.reviewedBy)
                .reviewComment(this.reviewComment)
                .reviewedAt(this.reviewedAt)
                .createdBy(this.createdBy)
                .author(this.author)
                .tags(this.tags)
                .remark(this.remark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }


    /**
     * 编辑文档属性（title/author/tags/remark）
     * <p>仅修改传入的属性字段，其他字段保持不变。title 为 {@code null} 表示不改
     * （特殊例外，标题不可清空）；author/tags/remark 为 {@code null} 表示清空。
     * 生命周期状态不变。</p>
     *
     * @param newTitle    新标题（{@code null}=不改；非 blank=新标题；空串→IllegalStateException）
     * @param newAuthor   新作者（{@code null}=清空）
     * @param newTags     新标签逗号分隔串（{@code null}=清空）
     * @param newRemark   新备注（{@code null}=清空）
     * @param newVersion  新版本号（{@code null}=不改，沿用现 currentVersion；非空=更新，调用方已查重）
     * @return 编辑属性后的文档实体
     */
    public DocumentEntity editProperties(String newTitle, String newAuthor,
                                         String newTags, String newRemark, String newVersion) {
        String resolvedTitle = newTitle != null ? newTitle : this.title;
        if (resolvedTitle == null || resolvedTitle.isBlank()) {
            throw new IllegalStateException("文档标题不能为空");
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(resolvedTitle)
                .status(this.status)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .currentVersion(newVersion != null ? newVersion : this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .mimeType(this.mimeType)
                .docType(this.docType)
                .reviewedBy(this.reviewedBy)
                .reviewComment(this.reviewComment)
                .reviewedAt(this.reviewedAt)
                .createdBy(this.createdBy)
                .author(newAuthor)
                .tags(newTags)
                .remark(newRemark)
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 编辑文档属性并替换文件（仅 DRAFT；同格式替换，processStatus 重置为 PENDING）
     * <p>用于 {@code POST /kb/documents/edit} 携带新文件的场景：更新 title/author/tags/remark
     * 与文件四字段（filePath/fileSize/mimeType/docType），status 保持 DRAFT，
     * {@code processStatus → PENDING}（新文件需模型重处理），{@code currentVersion}
     * （{@code newVersion} 非 null 则更新为该版本，否则不变）、{@code publishedVersion}/
     * {@code hasPublishedVersion} 不变（DRAFT 原地替换，非版本覆盖）。
     * 同格式约束由调用方在存储前校验（新扩展名 == 现有扩展名）。</p>
     *
     * @param newTitle    新标题（{@code null}=不改，沿用现 title；非 blank=新标题）
     * @param spec 新文件与展示属性（标题 null=沿用原标题；版本号 null=沿用现 currentVersion）
     * @return 替换文件并编辑属性后的文档实体
     */
    public DocumentEntity editWithNewFile(DocumentFileSpec spec) {
        String resolvedTitle = spec.title() != null ? spec.title() : this.title;
        if (resolvedTitle == null || resolvedTitle.isBlank()) {
            throw new IllegalStateException("文档标题不能为空");
        }
        return DocumentEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .categoryId(this.categoryId)
                .title(resolvedTitle)
                .status(STATUS_DRAFT)
                .processStatus(PROCESS_PENDING)
                .processError(null)
                .currentVersion(spec.version() != null ? spec.version() : this.currentVersion)
                .publishedVersion(this.publishedVersion)
                .hasPublishedVersion(this.hasPublishedVersion)
                .filePath(spec.filePath())
                .fileSize(spec.fileSize())
                .mimeType(spec.mimeType())
                .docType(spec.docType())
                .reviewedBy(this.reviewedBy)
                .reviewComment(this.reviewComment)
                .reviewedAt(this.reviewedAt)
                .createdBy(this.createdBy)
                .author(spec.author())
                .tags(spec.tags())
                .remark(spec.remark())
                .inheritParent(this.inheritParent)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public DocumentId getId() { return id; }
    public String getKbId() { return kbId; }
    public String getCategoryId() { return categoryId; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getProcessStatus() { return processStatus; }
    public String getProcessError() { return processError; }
    public Integer getChunkCount() { return chunkCount; }
    public String getProcessErrorCode() { return processErrorCode; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public String getCurrentVersion() { return currentVersion; }
    public String getPublishedVersion() { return publishedVersion; }
    public Boolean getHasPublishedVersion() { return hasPublishedVersion; }
    public String getFilePath() { return filePath; }
    public Long getFileSize() { return fileSize; }
    public String getMimeType() { return mimeType; }
    public String getDocType() { return docType; }
    public String getReviewedBy() { return reviewedBy; }
    public String getReviewComment() { return reviewComment; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public String getCreatedBy() { return createdBy; }
    public Boolean getInheritParent() { return inheritParent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getAuthor() { return author; }
    public String getTags() { return tags; }
    public String getRemark() { return remark; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DocumentId id;
        private String kbId;
        private String categoryId;
        private String title;
        private String status;
        private String processStatus;
        private String processError;
        private Integer chunkCount;
        private String processErrorCode;
        private LocalDateTime processedAt;
        private String currentVersion;
        private String publishedVersion;
        private Boolean hasPublishedVersion;
        private String filePath;
        private Long fileSize;
        private String mimeType;
        private String docType;
        private String reviewedBy;
        private String reviewComment;
        private LocalDateTime reviewedAt;
        private String createdBy;
        private Boolean inheritParent;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String author;
        private String tags;
        private String remark;

        public Builder id(DocumentId id) { this.id = id; return this; }
        public Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public Builder categoryId(String categoryId) { this.categoryId = categoryId; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder processStatus(String processStatus) { this.processStatus = processStatus; return this; }
        public Builder processError(String processError) { this.processError = processError; return this; }
        public Builder chunkCount(Integer chunkCount) { this.chunkCount = chunkCount; return this; }
        public Builder processErrorCode(String processErrorCode) { this.processErrorCode = processErrorCode; return this; }
        public Builder processedAt(LocalDateTime processedAt) { this.processedAt = processedAt; return this; }
        public Builder currentVersion(String currentVersion) { this.currentVersion = currentVersion; return this; }
        public Builder publishedVersion(String publishedVersion) { this.publishedVersion = publishedVersion; return this; }
        public Builder hasPublishedVersion(Boolean hasPublishedVersion) { this.hasPublishedVersion = hasPublishedVersion; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder docType(String docType) { this.docType = docType; return this; }
        public Builder reviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; return this; }
        public Builder reviewComment(String reviewComment) { this.reviewComment = reviewComment; return this; }
        public Builder reviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder inheritParent(Boolean inheritParent) { this.inheritParent = inheritParent; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder author(String author) { this.author = author; return this; }
        public Builder tags(String tags) { this.tags = tags; return this; }
        public Builder remark(String remark) { this.remark = remark; return this; }

        public DocumentEntity build() {
            if (id == null) throw new IllegalStateException("文档 ID 不能为空");
            if (kbId == null || kbId.isBlank()) throw new IllegalStateException("知识库 ID 不能为空");
            if (title == null || title.isBlank()) throw new IllegalStateException("文档标题不能为空");
            return new DocumentEntity(this);
        }
    }
}
