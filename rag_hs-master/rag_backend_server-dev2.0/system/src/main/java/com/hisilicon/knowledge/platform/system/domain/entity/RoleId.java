package com.hisilicon.knowledge.platform.system.domain.entity;

/**
 * 角色 ID 值对象
 *
 * @param value 角色主键（{@code roles.id}，字符串 UUID）
 */
public record RoleId(String value) {

    /**
     * 由字符串构造角色 ID
     *
     * @param value 角色主键（非空、非空白）
     * @return 角色 ID
     * @throws IllegalArgumentException value 为空时抛出
     */
    public static RoleId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("角色 ID 不能为空");
        }
        return new RoleId(value);
    }
}
