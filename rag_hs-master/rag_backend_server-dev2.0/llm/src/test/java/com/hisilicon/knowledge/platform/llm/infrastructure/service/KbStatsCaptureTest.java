package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KbStatsCapture 单测（纯 JUnit，无容器）：
 * 命中/闲聊/未命中三种 kb_stats 样例解析、无 kb_stats 不采集、docs 载荷透传不受影响。
 */
class KbStatsCaptureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private KbStatsCapture newCapture() {
        return new KbStatsCapture(mapper);
    }

    /** 命中样例：docs + kb_stats 同帧收尾事件，字段完整解析且原始 JSON 留档 */
    @Test
    void shouldParseHitSample() {
        KbStatsCapture capture = newCapture();
        String chunk = "{\"type\":\"docs\",\"message_id\":\"\","
                + "\"docs\":[{\"file\":\"/kb-files/kb123/guide.pdf\",\"page\":\"1\"}],"
                + "\"kb_stats\":{\"rag_triggered\":true,\"kb_hit\":true,"
                + "\"retrieved_count\":8,\"used_count\":3,"
                + "\"hit_kbs\":[\"kb001\",\"kb002\"],"
                + "\"rewritten_query\":\"芯片 功耗 规格 teachings\","
                + "\"top_score\":0.87,\"route_reason\":\"retrieval\",\"degraded\":false}}";
        capture.feed(chunk);
        KbStatsCapture.KbStatsData stats = capture.captured();
        assertNotNull(stats);
        assertEquals(Boolean.TRUE, stats.ragTriggered());
        assertEquals(Boolean.TRUE, stats.kbHit());
        assertNull(stats.noHitReason());
        assertEquals(8, stats.retrievedCount());
        assertEquals(3, stats.usedCount());
        assertEquals("kb001,kb002", stats.hitKbs());
        assertEquals("芯片 功耗 规格 teachings", stats.rewrittenQuery());
        assertEquals(new BigDecimal("0.87"), stats.topScore());
        assertEquals("retrieval", stats.routeReason());
        assertEquals(Boolean.FALSE, stats.degraded());
        assertNotNull(stats.kbStatsJson());
        // 原始 JSON 留档包含关键字段
        assertTrue(stats.kbStatsJson().contains("\"kb_hit\":true"));

        // 落库实体映射（messageId 由调用方传平台侧 assistantMessageId）
        KbStatsEntity entity = KbStatsEntity.builder()
                .messageId("assistant-msg-1")
                .ragTriggered(stats.ragTriggered())
                .kbHit(stats.kbHit())
                .noHitReason(stats.noHitReason())
                .retrievedCount(stats.retrievedCount())
                .usedCount(stats.usedCount())
                .hitKbs(stats.hitKbs())
                .rewrittenQuery(stats.rewrittenQuery())
                .topScore(stats.topScore())
                .routeReason(stats.routeReason())
                .degraded(stats.degraded())
                .kbStatsJson(stats.kbStatsJson())
                .build();
        assertEquals("assistant-msg-1", entity.getMessageId());
        assertEquals(Boolean.TRUE, entity.getKbHit());
        assertEquals(8, entity.getRetrievedCount());
        assertEquals("kb001,kb002", entity.getHitKbs());
    }

    /** 闲聊样例：rag 未触发、无检索字段，字段解析为 null 且不抛异常 */
    @Test
    void shouldParseChitchatSample() {
        KbStatsCapture capture = newCapture();
        capture.feed("{\"type\":\"answer\",\"answer\":\"你好\",\"message_id\":\"\","
                + "\"kb_stats\":{\"rag_triggered\":false,\"kb_hit\":false,"
                + "\"no_hit_reason\":\"chitchat\"}}");
        KbStatsCapture.KbStatsData stats = capture.captured();
        assertNotNull(stats);
        assertEquals(Boolean.FALSE, stats.ragTriggered());
        assertEquals(Boolean.FALSE, stats.kbHit());
        assertEquals("chitchat", stats.noHitReason());
        assertNull(stats.retrievedCount());
        assertNull(stats.usedCount());
        assertNull(stats.hitKbs());
        assertNull(stats.topScore());
        assertNull(stats.degraded());
    }

    /** 未命中样例：触发检索但无结果，no_hit_reason + hit_kbs 字符串形态 */
    @Test
    void shouldParseNoHitSample() {
        KbStatsCapture capture = newCapture();
        capture.feed("{\"kb_stats\":{\"rag_triggered\":true,\"kb_hit\":false,"
                + "\"no_hit_reason\":\"no_result\",\"retrieved_count\":0,\"used_count\":0,"
                + "\"hit_kbs\":\"kb001\",\"degraded\":true}}");
        KbStatsCapture.KbStatsData stats = capture.captured();
        assertNotNull(stats);
        assertEquals(Boolean.TRUE, stats.ragTriggered());
        assertEquals(Boolean.FALSE, stats.kbHit());
        assertEquals("no_result", stats.noHitReason());
        assertEquals(0, stats.retrievedCount());
        assertEquals(0, stats.usedCount());
        assertEquals("kb001", stats.hitKbs());
        assertEquals(Boolean.TRUE, stats.degraded());
    }

    /** 无 kb_stats：answer/纯文本分片不采集，captured 保持 null */
    @Test
    void shouldNotCaptureWhenNoKbStats() {
        KbStatsCapture capture = newCapture();
        capture.feed("{\"type\":\"answer\",\"answer\":\"正常回答\",\"message_id\":\"\"}");
        capture.feed("纯文本分片");
        capture.feed(null);
        capture.feed("");
        assertNull(capture.captured());
    }

    /** docs 载荷透传不受影响：feed 为纯观察，不产出/不改写分片；含 docs 的分片本身不抛异常 */
    @Test
    void shouldNotTouchDocsPayload() {
        KbStatsCapture capture = newCapture();
        String docsChunk = "{\"type\":\"docs\",\"message_id\":\"\","
                + "\"docs\":[{\"file\":\"/kb-files/kb123/a.pdf\",\"page\":\"\"}]}";
        capture.feed(docsChunk);
        assertNull(capture.captured());
        // 后续 answer 分片不受影响
        capture.feed("{\"type\":\"answer\",\"answer\":\"继续回答\"}");
        assertNull(capture.captured());
        // 非法 JSON 静默忽略，不抛异常
        capture.feed("not-json{{");
        assertNull(capture.captured());
    }

    /** 收尾事件只在流内出现一次：已捕获后忽略后续分片（保留首个） */
    @Test
    void shouldKeepFirstCapturedStats() {
        KbStatsCapture capture = newCapture();
        capture.feed("{\"kb_stats\":{\"kb_hit\":true,\"retrieved_count\":5,\"used_count\":2}}");
        capture.feed("{\"kb_stats\":{\"kb_hit\":false,\"retrieved_count\":9,\"used_count\":9}}");
        assertEquals(5, capture.captured().retrievedCount());
    }
}
