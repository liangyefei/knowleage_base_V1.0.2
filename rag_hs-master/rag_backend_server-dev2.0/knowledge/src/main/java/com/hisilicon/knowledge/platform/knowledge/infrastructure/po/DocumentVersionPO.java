package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 文档版本表
 * <p>
 * 存储文档所有历史版本的文件信息。
 * 新版本上传时，旧版本自动归档到本表。
 * 检索默认指向当前最新版本（documents.current_version）。
 * 支持版本间 diff 对比。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "document_versions")
@IdClass(DocumentVersionPO.DocumentVersionPK.class)
public class DocumentVersionPO {

    @Id
    @Column(name = "document_id")
    private String documentId;

    @Id
    @Column(name = "version_number")
    private String versionNumber;

    /** SFS 存储路径 */
    private String filePath;

    /** 文件大小（bytes） */
    private Long fileSize;

    /** MIME 类型 */
    private String mimeType;

    /** 变更说明 */
    @Column(name = "change_summary")
    private String changeSummary;

    /** 该版本是否曾经审核发布过（可检索追溯依据） */
    @Column(name = "was_published")
    private Boolean wasPublished;

    /**
     * 版本行状态（版本级归档/废弃，文档生命周期不变）：
     * PUBLISHED-已发布在线；ARCHIVED-已归档（版本级下线）；DEPRECATED-已废弃。
     * 版本覆盖新归档的版本恒为 PUBLISHED（原 ACTIVE，migration_015 已迁移）。
     */
    @Column(name = "status")
    private String status;

    /** 上传人用户 ID */
    @Column(name = "created_by")
    private String createdBy;

    private LocalDateTime createdAt;

    /**
     * 联合主键类
     */
    public static class DocumentVersionPK implements Serializable {

        private static final long serialVersionUID = 1L;

        private String documentId;

        private String versionNumber;

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getVersionNumber() {
            return versionNumber;
        }

        public void setVersionNumber(String versionNumber) {
            this.versionNumber = versionNumber;
        }

        public DocumentVersionPK() {}

        public DocumentVersionPK(String documentId, String versionNumber) {
            this.documentId = documentId;
            this.versionNumber = versionNumber;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DocumentVersionPK that)) return false;
            return Objects.equals(documentId, that.documentId)
                    && Objects.equals(versionNumber, that.versionNumber);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentId, versionNumber);
        }
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(String versionNumber) {
        this.versionNumber = versionNumber;
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

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public Boolean getWasPublished() {
        return wasPublished;
    }

    public void setWasPublished(Boolean wasPublished) {
        this.wasPublished = wasPublished;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
