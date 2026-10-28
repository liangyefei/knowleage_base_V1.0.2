package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 角色表
 * <p>
 * 预设角色（系统管理员、知识库管理员、审核员、编辑者、开发者）
 * 在系统初始化时创建且不可删除（is_preset=true）。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "roles")
public class RolePO {

    @Id
    private String id;

    /** 角色标识，唯一，如 sys_admin */
    private String roleKey;

    /** 角色名称 */
    private String roleName;

    /** 描述 */
    private String description;

    /** 是否预设角色：true=预设（不可删除）, false=自定义 */
    private Boolean isPreset;

    /** 状态：0=禁用, 1=正常 */
    private Short status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsPreset() {
        return isPreset;
    }

    public void setIsPreset(Boolean isPreset) {
        this.isPreset = isPreset;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
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
