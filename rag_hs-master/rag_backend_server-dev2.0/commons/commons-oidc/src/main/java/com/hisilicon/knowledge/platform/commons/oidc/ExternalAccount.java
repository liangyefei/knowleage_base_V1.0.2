package com.hisilicon.knowledge.platform.commons.oidc;

import java.util.Map;

/**
 * 外部身份提供方（OneAccess / 华为账号）校验通过后的账号身份信息（不可变值对象）。
 *
 * <p>字段名与 {@link OidcClaims} 常量一一对应；除 {@code externalId} 外均可选
 * （OneAccess 门户映射配置可不放出该字段）。</p>
 *
 * @param externalId  外部唯一标识（OIDC {@code sub}），与平台用户绑定的唯一键
 * @param username    登录名/工号（{@code preferred_username}），可选
 * @param displayName 显示名（{@code name}），可选
 * @param email       邮箱，可选
 * @param phone       手机号，可选
 * @param picture     头像 URL，可选
 * @param rawClaims   用户信息接口返回的原始 claim 集合（防御性拷贝，调用方不得期待可变）
 */
public record ExternalAccount(
        String externalId,
        String username,
        String displayName,
        String email,
        String phone,
        String picture,
        Map<String, Object> rawClaims) {

    public ExternalAccount {
        if (rawClaims == null) {
            rawClaims = Map.of();
        } else {
            rawClaims = Map.copyOf(rawClaims);
        }
    }
}
