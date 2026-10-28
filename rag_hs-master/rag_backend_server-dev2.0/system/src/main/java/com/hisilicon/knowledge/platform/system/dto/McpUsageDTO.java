package com.hisilicon.knowledge.platform.system.dto;

/**
 * MCP 用量快照（管理员查询某账号某日消耗）。
 *
 * <p>{@code estimatedTokens} 是粗估口径：网关只统计返回字符数，按 4 字符 ≈ 1 token 折算，
 * 用于判断量级，不做精确计费。</p>
 *
 * @param username       账号
 * @param date           统计日期（Asia/Shanghai，yyyy-MM-dd）
 * @param calls          工具调用次数
 * @param chars          返回内容字符数
 * @param estimatedTokens 粗估 token 数（chars / 4）
 * @param limitCalls     当日调用次数上限（null 表示用网关默认值）
 * @param limitChars     当日字符数上限（null 表示用网关默认值）
 * @param overQuota      是否已达上限
 * @author hisilicon
 */
public record McpUsageDTO(String username,
                          String date,
                          long calls,
                          long chars,
                          long estimatedTokens,
                          Long limitCalls,
                          Long limitChars,
                          boolean overQuota) {
}