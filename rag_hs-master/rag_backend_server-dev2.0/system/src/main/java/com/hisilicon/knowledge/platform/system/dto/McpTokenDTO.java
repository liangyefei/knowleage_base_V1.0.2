package com.hisilicon.knowledge.platform.system.dto;

import java.time.LocalDateTime;

/**
 * MCP 令牌列表项（不含令牌原文）。
 *
 * <p>列表只暴露元数据：jti 用于注销，token 明文仅在签发响应里出现一次。</p>
 *
 * @param jti        令牌唯一标识
 * @param label      用户自定义备注
 * @param createdAt  签发时间
 * @param expiresAt  过期时间
 * @param revokedAt  注销时间（null 表示未注销）
 * @param usable     当前是否可用（未注销且未过期）
 * @param lastUsedAt 最近一次被网关使用的时间（来自 Redis，可能为 null）
 * @author hisilicon
 */
public record McpTokenDTO(String jti,
                          String label,
                          LocalDateTime createdAt,
                          LocalDateTime expiresAt,
                          LocalDateTime revokedAt,
                          boolean usable,
                          LocalDateTime lastUsedAt) {
}