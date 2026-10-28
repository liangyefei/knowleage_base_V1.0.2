package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 文档表
 * <p>
 * 文档是权限控制的最小粒度资源。文件物理内容存储于华为云 SFS。
 * 文档具有双重状态（正交、独立流转）：
 * <ul>
 *   <li>status（生命周期状态，人工驱动）：DRAFT → REVIEWING → PUBLISHED → ARCHIVED / DEPRECATED</li>
 *   <li>processStatus（模型处理状态，模型服务回调驱动）：PENDING → PROCESSING → COMPLETED / FAILED</li>
 * </ul>
 * 文档真正可检索 = status=PUBLISHED 且 processStatus=COMPLETED。
 * <p>
 * 扩展属性（文档类型、敏感等级、数据地域、合规标签、芯片型号、软件版本等）
 * 存储在 document_metadata 表。版本历史存储在 document_versions 表。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "documents")
// 只更新真正发生变化的列。documents 行存在多路径并发写（模型回调事务改 process_status/process_error/
// process_error_code/chunk_count/processed_at/status，用户操作改 title/status/remark/审核字段）：
// 默认行为是"有一列脏就写全部列"，会把本事务读取时刻的旧值回写到并发已被改动的列上（整行后写者胜）；
// 动态更新把语义收窄为"每列各自最后写入者胜"，避免回调覆盖用户编辑、或用户编辑把 COMPLETED 回退成 PENDING。
// 注意：本注解不处理"两条路径同时改同一列"（如回调 FAILED 打回 status 与审核同时改 status）——
// 那类竞态仍由业务守卫负责（终态守卫 / 最新回调胜 / DRAFT+PENDING 陈旧回调守卫）。
@DynamicUpdate
public class DocumentPO {

    @Id
    private String id;

    /** 所属知识库 ID，关联 knowledge_bases.id */
    @Column(name = "kb_id")
    private String kbId;

    /** 所属类别 ID，关联 categories.id（NULL=知识库根目录） */
    @Column(name = "category_id")
    private String categoryId;

    /** 文档标题（原始文件名去扩展名） */
    private String title;

    /**
     * 生命周期状态（人工驱动）：
     * DRAFT      - 草稿
     * REVIEWING  - 审核中
     * PUBLISHED  - 已发布
     * ARCHIVED   - 已归档
     * DEPRECATED - 已废弃
     */
    private String status;

    /**
     * 模型处理状态（模型服务回调驱动）：
     * PENDING    - 待处理（上传/版本覆盖时重置）
     * PROCESSING - 处理中
     * COMPLETED  - 处理成功（切片+向量化完成）
     * FAILED     - 处理失败
     */
    @Column(name = "process_status")
    private String processStatus;

    /** 模型处理失败原因（processStatus=FAILED 时记录） */
    @Column(name = "process_error")
    private String processError;

    /** 文档切片数量（模型回调 PROCESSING_COMPLETED 时回传） */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 模型处理失败错误码（processStatus=FAILED 时回传，如 404/500） */
    @Column(name = "process_error_code")
    private String processErrorCode;

    /** 模型处理完成/失败时间（回调时回传） */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 当前最新版本号（varchar，由上传元数据提供） */
    @Column(name = "current_version")
    private String currentVersion;

    /** 最近一次审核通过的版本号（可空，用于追溯已发布内容） */
    @Column(name = "published_version")
    private String publishedVersion;

    /**
     * 是否存在已发布历史版本（语义 = document_versions 存在该文档的归档版本，旧版保持可检索）。
     * 已发布文档被覆盖修订时置 TRUE；覆盖后新版审核通过保持 TRUE（旧版仍归档可检索），
     * 仅首次发布（此前无归档版）为 FALSE。
     */
    @Column(name = "has_published_version")
    private Boolean hasPublishedVersion;

    /**
     * 最新版本的 SFS 存储路径（反范式化，便于列表查询）。
     * 完整版本历史以 file_path 存储在 document_versions 表。
     */
    @Column(name = "file_path")
    private String filePath;

    /** 最新版本文件大小（bytes） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 最新版本 MIME 类型 */
    @Column(name = "mime_type")
    private String mimeType;

    /**
     * 文档类型分类（按扩展名归类，上传时写入）：
     * DOCUMENT - 文档类 / CODE - 代码类 / CONFIG - 配置文件类 / OTHER - 其他
     */
    @Column(name = "doc_type")
    private String docType;

    /** 审核人用户 ID（approve/reject 时记录） */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** 审核备注 / 驳回原因 */
    @Column(name = "review_comment")
    private String reviewComment;

    /** 审核时间 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 文档作者（自由文本，不同于 created_by 操作员） */
    private String author;

    /** 文档标签，逗号分隔多值（如 GPIO,UART,I2C） */
    private String tags;

    /** 文档备注（自由文本，最长 500 字符） */
    private String remark;

    /** 创建者用户 ID */
    @Column(name = "created_by")
    private String createdBy;

    /** 是否继承父级 ACL，默认 true */
    @Column(name = "inherit_parent")
    private Boolean inheritParent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(String processStatus) {
        this.processStatus = processStatus;
    }

    public String getProcessError() {
        return processError;
    }

    public void setProcessError(String processError) {
        this.processError = processError;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getProcessErrorCode() {
        return processErrorCode;
    }

    public void setProcessErrorCode(String processErrorCode) {
        this.processErrorCode = processErrorCode;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(String publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public Boolean getHasPublishedVersion() {
        return hasPublishedVersion;
    }

    public void setHasPublishedVersion(Boolean hasPublishedVersion) {
        this.hasPublishedVersion = hasPublishedVersion;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getInheritParent() {
        return inheritParent;
    }

    public void setInheritParent(Boolean inheritParent) {
        this.inheritParent = inheritParent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
