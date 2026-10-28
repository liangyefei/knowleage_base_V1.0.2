package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 洞察定时设置。
 * @param scheduleType DAILY / WEEKLY / MONTHLY
 * @param scheduleTime 执行时间点 HH:mm（如 "15:01"）
 * @param scheduleDay WEEKLY: 1=周一..7=周日；MONTHLY: 1~31日；DAILY: null
 * @param updatedAt 更新时间字符串
 */
public record InsightSettingsDTO(
        String scheduleType,
        String scheduleTime,
        Integer scheduleDay,
        String updatedAt) {
}
