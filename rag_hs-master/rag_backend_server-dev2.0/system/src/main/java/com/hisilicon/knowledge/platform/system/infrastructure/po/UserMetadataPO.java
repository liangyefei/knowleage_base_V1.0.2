package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 用户元数据表
 * <p>
 * 存储用户维度的 ABAC 属性（如部门、地域、雇佣类型、安全级别）。
 * key-value 扩展模式，新增属性无需改表。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "user_metadata", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_metadata_user_meta", columnNames = {"user_id", "meta_key"})
})
public class UserMetadataPO {

    @Id
    private String id;

    /** 用户 ID，关联 users.id */
    @Column(name = "user_id")
    private String userId;

    /** 属性键，如 department, region, employment_type, clearance */
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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
