package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 知识库元数据表
 * <p>
 * key-value 扩展模式，存储知识库维度的 ABAC 属性及其他扩展属性
 * （如所属组织 org、默认敏感等级 default_sensitivity 等）。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "knowledge_base_metadata", uniqueConstraints = {
        @UniqueConstraint(name = "uk_knowledge_base_metadata_kb_meta", columnNames = {"kb_id", "meta_key"})
})
public class KnowledgeBaseMetadataPO {

    @Id
    private String id;

    /** 知识库 ID，关联 knowledge_bases.id */
    @Column(name = "kb_id")
    private String kbId;

    /** 属性键，如 org、default_sensitivity */
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

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
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
