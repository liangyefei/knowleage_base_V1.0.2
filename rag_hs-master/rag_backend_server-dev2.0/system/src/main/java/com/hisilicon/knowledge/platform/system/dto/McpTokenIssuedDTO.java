package com.hisilicon.knowledge.platform.system.dto;

import java.time.LocalDateTime;

/**
 * MCP 令牌签发结果 —— <b>令牌原文只在此出现一次</b>，之后任何接口都不再返回。
 *
 * <p>{@code mcpServersJson} 是可直接粘进 Trae / Cursor 等客户端 {@code mcpServers} 配置的片段，
 * 省掉用户手拼 header 的步骤；客户端不支持 streamable-http 时可只取 {@code token} 与 {@code gatewayUrl}。</p>
 *
 * <p><b>每账号同时只有一枚有效令牌</b>：签发即轮换，旧令牌在同一事务里被吊销。
 * {@code replacedJti} 非空表示这次签发顶掉了旧令牌 —— 前端必须提示用户
 * "旧令牌已失效，请更新客户端配置"，否则用户会拿着旧 token 排查半天。</p>
 *
 * @param jti            令牌唯一标识（注销时用）
 * @param label          用户自定义备注
 * @param token          完整 JWT 明文（仅本次返回）
 * @param expiresAt      过期时间
 * @param gatewayUrl     MCP 网关端点（streamable-http）
 * @param mcpServersJson 客户端配置片段
 * @param replacedJti    被本次签发顶掉的旧令牌 jti；无旧令牌时为 null
 * @author hisilicon
 */
public record McpTokenIssuedDTO(String jti,
                                String label,
                                String token,
                                LocalDateTime expiresAt,
                                String gatewayUrl,
                                String mcpServersJson,
                                String replacedJti) {
}