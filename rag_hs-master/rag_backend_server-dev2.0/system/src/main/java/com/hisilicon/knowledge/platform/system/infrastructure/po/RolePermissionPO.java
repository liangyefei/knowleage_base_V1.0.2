package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 角色-权限关联表
 *
 * @author hisilicon
 */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionPO.RolePermissionPK.class)
public class RolePermissionPO {

    @Id
    @Column(name = "role_id")
    private String roleId;

    @Id
    @Column(name = "permission_id")
    private String permissionId;

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }

    public String getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(String permissionId) {
        this.permissionId = permissionId;
    }

    /**
     * 联合主键类
     */
    public static class RolePermissionPK implements Serializable {

        private static final long serialVersionUID = 1L;

        private String roleId;

        private String permissionId;

        public RolePermissionPK() {}

        public RolePermissionPK(String roleId, String permissionId) {
            this.roleId = roleId;
            this.permissionId = permissionId;
        }

        public String getRoleId() {
            return roleId;
        }

        public void setRoleId(String roleId) {
            this.roleId = roleId;
        }

        public String getPermissionId() {
            return permissionId;
        }

        public void setPermissionId(String permissionId) {
            this.permissionId = permissionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RolePermissionPK that)) return false;
            return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permissionId);
        }
    }
}
