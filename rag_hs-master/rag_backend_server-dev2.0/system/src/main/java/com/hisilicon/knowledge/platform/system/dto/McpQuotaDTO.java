package com.hisilicon.knowledge.platform.system.dto;

/**
 * MCP 日限额覆盖（管理员视角）。
 *
 * <p>字段为 {@code null} 表示该维度没有账号级覆盖，网关回落到全局默认
 * （{@code mcp-gateway.quota.daily-calls} / {@code daily-chars}）。</p>
 *
 * @param username   账号
 * @param dailyCalls 每日调用次数覆盖（null = 用默认）
 * @param dailyChars 每日字符数覆盖（null = 用默认）
 * @author hisilicon
 */
public record McpQuotaDTO(String username, Long dailyCalls, Long dailyChars) {
}