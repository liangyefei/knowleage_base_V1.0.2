package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 用户-角色关联表
 *
 * @author hisilicon
 */
@Entity
@Table(name = "user_roles")
@IdClass(UserRolePO.UserRolePK.class)
public class UserRolePO {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Id
    @Column(name = "role_id")
    private String roleId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }

    /**
     * 联合主键类
     */
    public static class UserRolePK implements Serializable {

        private static final long serialVersionUID = 1L;

        private String userId;

        private String roleId;

        public UserRolePK() {}

        public UserRolePK(String userId, String roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getRoleId() {
            return roleId;
        }

        public void setRoleId(String roleId) {
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserRolePK that)) return false;
            return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
