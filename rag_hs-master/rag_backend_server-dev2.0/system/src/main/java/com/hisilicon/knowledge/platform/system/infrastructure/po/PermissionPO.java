package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 权限表
 * <p>
 * 以权限码（permission_code）为主标识，支持树形结构（parent_id）。
 * 类型区分：menu（菜单权限）、button（按钮权限）、api（API 权限）。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "permissions")
public class PermissionPO {

    @Id
    private String id;

    /** 父权限 ID，支持树形结构 */
    private String parentId;

    /** 权限码，唯一，如 kb:doc:read、sys:user */
    private String permissionCode;

    /** 权限名称 */
    private String permissionName;

    /** 类型：menu / button / api */
    private String type;

    /** 排序号 */
    private Integer sortOrder;

    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
