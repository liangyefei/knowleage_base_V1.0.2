package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 类别元数据表
 * <p>
 * key-value 扩展模式，存储类别维度的扩展属性。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "category_metadata", uniqueConstraints = {
        @UniqueConstraint(name = "uk_category_metadata_category_meta", columnNames = {"category_id", "meta_key"})
})
public class CategoryMetadataPO {

    @Id
    private String id;

    /** 类别 ID，关联 categories.id */
    @Column(name = "category_id")
    private String categoryId;

    /** 属性键 */
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

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
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
