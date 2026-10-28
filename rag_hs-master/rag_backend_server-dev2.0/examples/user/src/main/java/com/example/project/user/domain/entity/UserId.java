package com.example.project.user.domain.entity;

/**
 * 用户ID值对象
 * @param value 具体的值
 */
public record UserId(String value) {
    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户id不能为空");
        }

        if (value.length() < 5 || value.length() > 20) {
            throw new IllegalArgumentException("用户id长度为5-20");
        }
    }
}
