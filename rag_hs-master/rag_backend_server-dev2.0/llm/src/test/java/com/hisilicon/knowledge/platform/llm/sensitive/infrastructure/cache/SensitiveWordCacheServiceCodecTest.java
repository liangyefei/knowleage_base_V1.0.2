package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词 Redis 缓存编解码单测。
 *
 * <p>编解码为<b>显式 JsonNode 映射（零反射）</b>：native 镜像下 record 反射注册效果不可依赖
 * （测试环境曾出现写入整体退化为 {@code [{},{},…]} 空对象垃圾），故编解码不经过
 * {@code SensitiveWordMatch} 的反射元数据，并用"任一条目缺有效 word 即损坏"兜住历史垃圾载荷
 * ——损坏即回源 DB 重写，被污染的缓存 key 自愈，无需人工清理。</p>
 */
class SensitiveWordCacheServiceCodecTest {

    private SensitiveWordCacheService service;

    @BeforeEach
    void setUp() {
        // 仅测编解码：其余依赖不触达（构造器为纯赋值），ObjectMapper 用原生实例即可
        service = new SensitiveWordCacheService(null, null, null, null, new ObjectMapper());
    }

    @Test
    void serializeAndDeserializeShouldRoundTrip() {
        List<SensitiveWordMatch> matches = List.of(
                new SensitiveWordMatch("赌博", "涉赌", "GAMBLING"),
                new SensitiveWordMatch("机密", "涉密", "SECRET"));

        List<SensitiveWordMatch> parsed = service.deserialize(service.serialize(matches)).orElseThrow();

        assertEquals(matches, parsed);
    }

    /** 测试环境实发的损坏载荷：native 反射退化写出的空对象数组，必须判损坏（触发 DB 回源重写） */
    @Test
    void deserializeShouldTreatEmptyObjectArrayAsCorrupt() {
        assertTrue(service.deserialize("[{},{},{},{},{},{},{}]").isEmpty());
    }

    @Test
    void deserializeShouldTreatEntryWithoutWordAsCorrupt() {
        assertTrue(service.deserialize("[{\"textLabel\":\"涉政\",\"labelIdentification\":\"POLITICAL\"}]").isEmpty());
    }

    @Test
    void deserializeShouldTreatBlankWordAsCorrupt() {
        assertTrue(service.deserialize("[{\"word\":\"   \"}]").isEmpty());
    }

    @Test
    void deserializeShouldTreatNonArrayAndBrokenJsonAsCorrupt() {
        assertTrue(service.deserialize("{}").isEmpty(), "JSON 对象不是数组");
        assertTrue(service.deserialize("null").isEmpty(), "JSON null 不是数组");
        assertTrue(service.deserialize("<not-json>").isEmpty(), "非法 JSON");
        assertTrue(service.deserialize("").isEmpty(), "空串");
    }

    /** 空数组 = "词库确无启用词"的合法状态，必须正常解析为空列表（而非损坏） */
    @Test
    void deserializeShouldAcceptEmptyArrayAsValidEmptyWordList() {
        List<SensitiveWordMatch> parsed = service.deserialize("[]").orElseThrow();

        assertTrue(parsed.isEmpty());
    }

    /** 向前兼容：条目多出的字段忽略；textLabel/labelIdentification 缺失允许为 null */
    @Test
    void deserializeShouldIgnoreUnknownFieldsAndAllowNullLabels() {
        List<SensitiveWordMatch> parsed = service.deserialize(
                "[{\"word\":\"诈骗\",\"textLabel\":\"涉诈\",\"labelIdentification\":\"FRAUD\",\"extra\":1},"
                        + "{\"word\":\"违规\"}]").orElseThrow();

        assertEquals(2, parsed.size());
        assertEquals(new SensitiveWordMatch("诈骗", "涉诈", "FRAUD"), parsed.get(0));
        assertEquals(new SensitiveWordMatch("违规", null, null), parsed.get(1));
    }

    /**
     * 惰性续期窗口：刚加载不超期；超过 12h（TTL 24h 的一半）判定过期，
     * 触发下一次 HTTP 侧 match 回源覆写缓存，保证 key 在 TTL 到期前被续期。
     */
    @Test
    void staleShouldFlipAfterRefreshInterval() {
        service.lastLoadAt = System.currentTimeMillis();
        org.junit.jupiter.api.Assertions.assertFalse(service.stale(), "刚回源重建不应判过期");

        service.lastLoadAt = System.currentTimeMillis()
                - (SensitiveWordCacheService.REFRESH_INTERVAL_SECONDS + 60) * 1000;
        org.junit.jupiter.api.Assertions.assertTrue(service.stale(), "超过续期间隔应判过期");
    }
}
