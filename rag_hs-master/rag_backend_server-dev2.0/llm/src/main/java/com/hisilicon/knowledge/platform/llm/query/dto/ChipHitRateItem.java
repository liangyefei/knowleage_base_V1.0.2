package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 芯片（=知识库）维度命中率（对应原型芯片维度横向条形图）
 *
 * @param kbName  知识库名称（即芯片展示名，如"谛听"；知识库已删除或不存在时回退为 id）
 * @param hitRate 命中率（0~1；分子=该 kb ∈ hit_kbs 且 kb_hit=true，分母=selected_kbs/hit_kbs 含该 kb
 *                且非闲聊的行数）
 * @param hit     命中行数（显式下发，避免前端以 total×hitRate 浮点反推）
 * @param total   分母行数（已排除闲聊行）
 */
public record ChipHitRateItem(String kbName, double hitRate, long hit, long total) {
}
