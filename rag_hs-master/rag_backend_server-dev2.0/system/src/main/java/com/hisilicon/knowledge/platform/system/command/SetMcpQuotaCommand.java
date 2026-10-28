package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.Min;

/**
 * 设置账号级 MCP 日限额覆盖（管理员）。
 *
 * <p>语义是 <b>整体替换</b>：为 {@code null} 的维度表示不覆盖、回落网关全局默认值；
 * 两个维度都为 {@code null} 等于清除该账号的覆盖。{@code 0} 是合法值，表示当日全部拒绝
 * （与网关 {@code mcp:quota:{username}} 的读法一致：字段存在即为准）。</p>
 *
 * @param dailyCalls 每日调用次数上限（null = 用默认）
 * @param dailyChars 每日返回字符数上限（null = 用默认）
 * @author hisilicon
 */
public record SetMcpQuotaCommand(@Min(0) Long dailyCalls, @Min(0) Long dailyChars) {
}