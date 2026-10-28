package com.hisilicon.knowledge.platform.system.domain.entity;

/**
 * 用户 ID 值对象
 *
 * @param value UUID 字符串
 */
public record UserId(String value) {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }
}
