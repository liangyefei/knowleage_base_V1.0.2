package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 命中率仪表盘概览（对应原型四张卡片）
 *
 * @param overallRate   整体命中率（0~1；分母=有效查询数 = 全部行 - 闲聊行）
 * @param prevRate      上一等长周期命中率（环比基准；同一口径；范围前无数据时为 0）
 * @param totalQueries  总查询数（范围内全部行数，含闲聊）
 * @param totalHits     命中行数（kb_hit=true 且非闲聊）
 * @param totalMisses   未命中行数（非命中且非闲聊；恒等于未命中列表同筛选下的 totalRow）
 * @param totalChitchat 闲聊行数（no_hit_reason='chitchat'；不进命中率分母，也不进未命中列表）
 * @param max           芯片（知识库）维度命中率最高项
 * @param min           芯片（知识库）维度命中率最低项（total=0 的维度不参与极值）
 * @apiNote 恒等式：totalQueries = totalHits + totalMisses + totalChitchat，前端可用它自检并解释
 *          "命中率不到 100% 而未命中列表为空"（差额来自闲聊）。
 */
public record HitRateSummaryItem(double overallRate, double prevRate,
                                 long totalQueries, long totalHits, long totalMisses, long totalChitchat,
                                 Extremum max, Extremum min) {

    /**
     * 极值项
     *
     * @param value 命中率（0~1）
     * @param label 芯片展示名（=知识库名称，如"谛听"；知识库已删除或不存在时回退为 id）
     */
    public record Extremum(double value, String label) {
    }
}
