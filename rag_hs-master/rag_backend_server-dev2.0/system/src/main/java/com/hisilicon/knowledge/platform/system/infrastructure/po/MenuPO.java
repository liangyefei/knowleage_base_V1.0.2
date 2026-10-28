package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 菜单表
 * <p>
 * 前端路由/菜单树配置。通过 permission_id 与权限表关联，
 * 实现菜单可见性与权限绑定。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "menus")
public class MenuPO {

    @Id
    private String id;

    /** 父菜单 ID */
    private String parentId;

    /** 菜单名称（支持国际化 key） */
    private String menuName;

    /** 前端路由路径 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 图标标识 */
    private String icon;

    /** 排序号 */
    private Integer sortOrder;

    /** 关联权限 ID，可为空（纯目录菜单可不绑定权限） */
    private String permissionId;

    /** 是否可见：true=显示, false=隐藏 */
    private Boolean visible;

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

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(String permissionId) {
        this.permissionId = permissionId;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
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
