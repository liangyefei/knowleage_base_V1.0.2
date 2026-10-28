package com.hisilicon.knowledge.platform.system.dto;

/**
 * 令牌 DTO
 *
 * @param accessToken  访问令牌，有效期 1 小时
 * @param refreshToken 刷新令牌，有效期 2 小时
 */
public record TokenDTO(String accessToken, String refreshToken) {
}
