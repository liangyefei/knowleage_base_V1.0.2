package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 热门词统计（管理页顶部卡片）
 *
 * @param total    总数
 * @param enabled  启用中数量（status = 1）
 * @param disabled 已停用数量（total - enabled；越界脏数据宽松归入此值）
 */
public record HotWordStats(long total, long enabled, long disabled) {
}
