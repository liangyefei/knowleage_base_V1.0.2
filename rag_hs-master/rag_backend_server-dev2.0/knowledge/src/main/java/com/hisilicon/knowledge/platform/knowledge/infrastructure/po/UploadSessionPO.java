package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * tus 上传会话表（upload_sessions）
 * <p>一个文件一条会话，分片追加写 {@code .uploads/{id}}，complete 即按用途落库：
 * BATCH_IMPORT → documents(DRAFT)/zip 解压；DOC_EDIT → DRAFT 换文件；VERSION_OVERRIDE → 新版本覆盖。
 * {@code metadata} 为 create 时解析后的逐文件元数据（已通过字典校验 fail-closed）。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "upload_sessions")
public class UploadSessionPO {

    @Id
    private String id;

    /** 所属批次（BATCH_IMPORT 用途必填；doc 用途为 NULL，DDL migration_013 已置列可空） */
    @Column(name = "batch_id")
    private String batchId;

    /** 会话用途：BATCH_IMPORT / DOC_EDIT / VERSION_OVERRIDE（缺省 BATCH_IMPORT） */
    @Column(name = "purpose")
    private String purpose;

    /** doc 用途的目标文档 ID（DOC_EDIT / VERSION_OVERRIDE）；BATCH_IMPORT 为 NULL */
    @Column(name = "target_document_id")
    private String targetDocumentId;

    @Column(name = "kb_id")
    private String kbId;

    @Column(name = "category_id")
    private String categoryId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_title")
    private String fileTitle;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "upload_length")
    private Long uploadLength;

    @Column(name = "upload_offset")
    private Long uploadOffset;

    @Column(name = "draft_policy")
    private String draftPolicy;

    /** create 时解析后的逐文件元数据（documentMetadata + version/author/tags/remark，已校验） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private ImportFileMetadata metadata;

    private String status;

    @Column(name = "document_id")
    private String documentId;

    private String error;

    /** zip 异步解压已处理条目数（进度；单文件会话恒 0） */
    @Column(name = "entries_done")
    private Integer entriesDone;

    /** zip 解压跳过的条目数（不支持类型/同名等，COMPLETED 终态携带；单文件会话恒 0） */
    @Column(name = "skipped_count")
    private Integer skippedCount;

    @Column(name = "temp_path")
    private String tempPath;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_by")
    private String createdBy;

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

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getTargetDocumentId() {
        return targetDocumentId;
    }

    public void setTargetDocumentId(String targetDocumentId) {
        this.targetDocumentId = targetDocumentId;
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileTitle() {
        return fileTitle;
    }

    public void setFileTitle(String fileTitle) {
        this.fileTitle = fileTitle;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getUploadLength() {
        return uploadLength;
    }

    public void setUploadLength(Long uploadLength) {
        this.uploadLength = uploadLength;
    }

    public Long getUploadOffset() {
        return uploadOffset;
    }

    public void setUploadOffset(Long uploadOffset) {
        this.uploadOffset = uploadOffset;
    }

    public String getDraftPolicy() {
        return draftPolicy;
    }

    public void setDraftPolicy(String draftPolicy) {
        this.draftPolicy = draftPolicy;
    }

    public ImportFileMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ImportFileMetadata metadata) {
        this.metadata = metadata;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getEntriesDone() {
        return entriesDone;
    }

    public void setEntriesDone(Integer entriesDone) {
        this.entriesDone = entriesDone;
    }

    public Integer getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(Integer skippedCount) {
        this.skippedCount = skippedCount;
    }

    public String getTempPath() {
        return tempPath;
    }

    public void setTempPath(String tempPath) {
        this.tempPath = tempPath;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
