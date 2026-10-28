package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未命中列表谓词守卫（纯 JUnit，无容器）。
 *
 * <p>背景：命中率分母排除闲聊（{@code no_hit_reason='chitchat'}），未命中列表必须用同一谓词，
 * 否则会出现"命中率不是 100%、未命中列表却为空"（2026-10-21 线上现象）。HQL 无法在单测里执行
 * （模块无 DB 集成测试源集），这里用字符串守卫防止谓词被改回旧形态：</p>
 * <ul>
 *   <li>{@code s.kbHit = false} 匹配不到 NULL —— 模型未下发 kb_hit 的行会既不算命中又不进列表；</li>
 *   <li>闲聊判定必须大小写/空白归一，否则 {@code Chitchat} 之类变体会混进补知识工作流。</li>
 * </ul>
 */
class KbStatsRepositoryImplTest {

    @Test
    void missesPredicateShouldCoverNullKbHit() {
        assertTrue(KbStatsRepositoryImpl.MISSES_WHERE.contains("s.kbHit = false OR s.kbHit IS NULL"),
                "kb_hit 为 NULL 的行必须被当作未命中（模型未下发该字段时不能静默丢行）");
    }

    @Test
    void missesPredicateShouldNormalizeChitchat() {
        assertTrue(KbStatsRepositoryImpl.MISSES_WHERE.contains("LOWER(TRIM(COALESCE(s.noHitReason, '')))"),
                "闲聊判定必须与命中率分母同源（大小写/空白归一，NULL 归空串）");
        assertTrue(KbStatsRepositoryImpl.MISSES_WHERE.contains("<> 'chitchat'"),
                "未命中列表必须继续排除闲聊行");
    }
}
