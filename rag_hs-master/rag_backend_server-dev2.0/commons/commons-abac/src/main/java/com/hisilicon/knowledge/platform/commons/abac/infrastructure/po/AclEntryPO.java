package com.hisilicon.knowledge.platform.commons.abac.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * ACL 条目表
 * <p>
 * 提供资源级别的细粒度权限控制。与 RBAC 的全局角色权限和 ABAC 的条件策略不同，
 * ACL 直接绑定在具体资源上，精确控制"谁对这个资源能做什么"。
 * 支持对知识库（KB）、类别（CATEGORY）、文档（DOCUMENT）三级资源分别设置权限，
 * 并通过继承机制实现上级权限向下传递。
 * </p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "acl_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_acl_entries_resource_subject_permission",
                columnNames = {"resource_type", "resource_id", "subject_type", "subject_id", "permission"})
})
public class AclEntryPO {

    @Id
    private String id;

    /** 资源类型：KB / CATEGORY / DOCUMENT */
    private String resourceType;

    /** 资源 ID */
    private String resourceId;

    /** 主体类型：USER / ROLE */
    private String subjectType;

    /** 主体 ID（用户 ID 或角色 ID） */
    private String subjectId;

    /** 权限码，如 doc:read、doc:edit、doc:delete、doc:review、kb:config */
    private String permission;

    /** 效果：ALLOW / DENY */
    private String effect;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
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
