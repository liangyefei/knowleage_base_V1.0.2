package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录命令
 * <p>username 和 password 为前端用 RSA 公钥加密后的 Base64 字符串。</p>
 *
 * @param username 加密后的用户名
 * @param password 加密后的密码
 */
public record LoginCommand(@NotBlank(message = "{app.login.username.notblank}") String username,
                           @NotBlank(message = "{app.login.password.notblank}") String password) {
}
