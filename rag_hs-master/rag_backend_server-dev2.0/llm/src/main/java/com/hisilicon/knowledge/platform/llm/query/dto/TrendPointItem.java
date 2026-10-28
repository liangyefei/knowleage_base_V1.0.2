package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 命中率时间趋势点（对应原型时间趋势图：折线=命中率，柱=查询次数）
 *
 * @param date          展示标签（day: 2026-12-01；week: 2026年49周，ISO 周历；month: 2026年12月）
 * @param bucketStart   时间桶起始日（yyyy-MM-dd，week/month 时为桶首日；供前端排序/tooltip）
 * @param hitRate       命中率（0~1；分母=桶内有效查询数 = 查询次数 - 闲聊数）
 * @param queryCount    查询次数（桶内全部行数，含闲聊；柱状口径）
 * @param hitCount      命中次数（kb_hit=true 且非闲聊）
 * @param chitchatCount 闲聊次数（不进命中率分母、也不进未命中列表；tooltip 可显式标注）
 * @apiNote queryCount = hitCount + missCount + chitchatCount
 */
public record TrendPointItem(String date, String bucketStart, double hitRate, long queryCount,
                             long hitCount, long chitchatCount) {
}
