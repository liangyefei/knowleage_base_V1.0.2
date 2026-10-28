package com.example.project.user.dto;

/**
 * 令牌信息返回前端数据
 * @param accessToken 访问令牌
 * @param refreshToken 刷新令牌
 */
public record TokenDTO(String accessToken, String refreshToken) {
}
