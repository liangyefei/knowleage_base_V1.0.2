package com.example.project.user.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录命令
 * @param username 用户名
 * @param password 密码
 */
public record LoginCommand(@NotBlank(message = "用户名不能为空") String username,
                           @NotBlank(message = "密码不能为空") String password) {
}
