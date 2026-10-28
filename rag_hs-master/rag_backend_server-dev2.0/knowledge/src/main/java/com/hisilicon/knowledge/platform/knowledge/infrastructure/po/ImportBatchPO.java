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
 * 导入批次表（import_batches）
 * <p>聚合一次初始化灌库的上千 tus 上传会话，批次进度由 upload_sessions 聚合。
 * {@code default_metadata} 为逐文件元数据默认值（complete 时合并逐文件覆盖后仅写各文档 document_metadata）。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "import_batches")
public class ImportBatchPO {

    @Id
    private String id;

    @Column(name = "kb_id")
    private String kbId;

    @Column(name = "category_id")
    private String categoryId;

    @Column(name = "batch_name")
    private String batchName;

    @Column(name = "total_files")
    private Integer totalFiles;

    @Column(name = "total_size")
    private Long totalSize;

    private String status;

    /** 逐文件元数据默认值（documentMetadata + version/author/tags/remark） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_metadata", columnDefinition = "jsonb")
    private ImportFileMetadata defaultMetadata;

    @Column(name = "draft_conflict_policy")
    private String draftConflictPolicy;

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

    public String getBatchName() {
        return batchName;
    }

    public void setBatchName(String batchName) {
        this.batchName = batchName;
    }

    public Integer getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(Integer totalFiles) {
        this.totalFiles = totalFiles;
    }

    public Long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(Long totalSize) {
        this.totalSize = totalSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public ImportFileMetadata getDefaultMetadata() {
        return defaultMetadata;
    }

    public void setDefaultMetadata(ImportFileMetadata defaultMetadata) {
        this.defaultMetadata = defaultMetadata;
    }

    public String getDraftConflictPolicy() {
        return draftConflictPolicy;
    }

    public void setDraftConflictPolicy(String draftConflictPolicy) {
        this.draftConflictPolicy = draftConflictPolicy;
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
