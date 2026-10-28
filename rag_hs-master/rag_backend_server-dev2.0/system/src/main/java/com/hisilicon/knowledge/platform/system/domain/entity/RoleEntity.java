package com.hisilicon.knowledge.platform.system.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层角色实体（纯 POJO，无任何 JPA 注解）。
 *
 * <p>角色是 RBAC 的授权载体：{@code role_key} 是稳定的外部标识（如 {@code sys_admin}），
 * 一经创建不允许修改；{@code is_preset=true} 的内置角色不允许改状态、不允许删除
 * （该业务规则由应用服务层判定，见 {@code RoleCommandService}）。</p>
 */
public class RoleEntity {

    /** 状态：禁用 */
    public static final Short STATUS_DISABLED = 0;

    /** 状态：正常 */
    public static final Short STATUS_ENABLED = 1;

    /** 接口层兼容的禁用值：部分调用方沿用 1=正常 / 2=禁用，落库前统一归一为 {@link #STATUS_DISABLED} */
    public static final Short API_STATUS_DISABLED_COMPAT = 2;

    /** 内置社区用户角色标识：OIDC 新用户默认角色，任何用户授权都必须保留 */
    public static final String COMMUNITY_ROLE_KEY = "community";

    /** 内置系统管理员角色标识：角色管理/用户授权的最小管理员判定依据 */
    public static final String ADMIN_ROLE_KEY = "sys_admin";

    /** role_key 最大长度（列 role_key varchar2(30)） */
    public static final int ROLE_KEY_MAX_LENGTH = 30;

    /** role_name 最大长度（列 role_name varchar2(50)） */
    public static final int ROLE_NAME_MAX_LENGTH = 50;

    /** description 最大长度（列 description varchar2(200)） */
    public static final int DESCRIPTION_MAX_LENGTH = 200;

    private RoleId id;
    private String roleKey;
    private String roleName;
    private String description;
    private Boolean isPreset;
    private Short status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 领域工厂：新建自定义角色（非预设、状态正常）。
     *
     * @param roleKey     角色标识（非空，≤30）
     * @param roleName    角色名称（非空，≤50）
     * @param description 描述（可空，落库为空串，≤200）
     * @return 新建的角色实体
     */
    public static RoleEntity create(String roleKey, String roleName, String description) {
        LocalDateTime now = LocalDateTime.now();
        RoleEntity entity = new RoleEntity();
        entity.id = RoleId.of(UUID.randomUUID().toString());
        entity.roleKey = requireText(roleKey, "角色标识", ROLE_KEY_MAX_LENGTH);
        entity.roleName = requireText(roleName, "角色名称", ROLE_NAME_MAX_LENGTH);
        entity.description = normalizeDescription(description);
        entity.isPreset = Boolean.FALSE;
        entity.status = STATUS_ENABLED;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * 是否内置角色（{@code is_preset=true}）
     *
     * @return 内置返回 true
     */
    public boolean isBuiltin() {
        return Boolean.TRUE.equals(isPreset);
    }

    /**
     * 领域行为：修改名称与描述（role_key 不可变，不在本方法范围内）。
     *
     * @param roleName    新名称（非空，≤50）
     * @param description 新描述（可空，≤200）
     */
    public void rename(String roleName, String description) {
        this.roleName = requireText(roleName, "角色名称", ROLE_NAME_MAX_LENGTH);
        this.description = normalizeDescription(description);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 领域行为：变更状态（仅允许 0/1；内置角色的拦截由应用服务层完成）。
     *
     * @param status 目标状态：0=禁用 / 1=正常
     */
    public void changeStatus(Short status) {
        if (status == null
                || (status.intValue() != STATUS_DISABLED.intValue()
                && status.intValue() != STATUS_ENABLED.intValue())) {
            throw new IllegalArgumentException("角色状态非法，仅允许 0（禁用）或 1（正常）");
        }
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private static String normalizeDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("角色描述长度不能超过 " + DESCRIPTION_MAX_LENGTH);
        }
        return value;
    }

    // ========== getters / setters（MapStruct 转换与持久化映射用） ==========

    public RoleId getId() {
        return id;
    }

    public void setId(RoleId id) {
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
