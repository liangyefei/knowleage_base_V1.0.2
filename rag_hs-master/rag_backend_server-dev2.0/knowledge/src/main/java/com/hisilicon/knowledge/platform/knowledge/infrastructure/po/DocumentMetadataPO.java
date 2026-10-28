package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 文档元数据表
 * <p>
 * key-value 扩展模式，存储文档维度的 ABAC 属性及其他扩展属性。
 * 包括但不限于：
 * - doc_type（文档类型：代码/设计文档/FAQ/…）
 * - sensitivity（敏感等级）
 * - data_region（数据地域）
 * - compliance（合规标签）
 * - chip_model（芯片型号，如 CV610、CV620）
 * - software_version（软件版本）
 * - author（作者）
 * - scenario（适用场景）
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "document_metadata", uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_metadata_document_meta", columnNames = {"document_id", "meta_key"})
})
public class DocumentMetadataPO {

    @Id
    private String id;

    /** 文档 ID，关联 documents.id */
    @Column(name = "document_id")
    private String documentId;

    /** 属性键，如 doc_type、sensitivity、chip_model */
    @Column(name = "meta_key")
    private String metaKey;

    /** 属性值 */
    @Column(name = "meta_value")
    private String metaValue;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getMetaKey() {
        return metaKey;
    }

    public void setMetaKey(String metaKey) {
        this.metaKey = metaKey;
    }

    public String getMetaValue() {
        return metaValue;
    }

    public void setMetaValue(String metaValue) {
        this.metaValue = metaValue;
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
