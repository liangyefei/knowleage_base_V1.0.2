package com.hisilicon.knowledge.platform.llm.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository.ChipAgg;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository.TotalsAgg;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository.TrendAgg;
import com.hisilicon.knowledge.platform.llm.query.dto.ChipHitRateItem;
import com.hisilicon.knowledge.platform.llm.query.dto.HitRateSummaryItem;
import com.hisilicon.knowledge.platform.llm.query.dto.MissItem;
import com.hisilicon.knowledge.platform.llm.query.dto.TrendPointItem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 命中率仪表盘查询服务单元测试（手写 Fake/Stub 仓储，验证口径与映射，不依赖容器/Mockito）
 */
class HitRateQueryServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 12, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 8, 0, 0);

    /** Stub 仓储：按注入的 Supplier 返回聚合结果 */
    static class StubKbStatsRepository implements KbStatsRepository {
        Supplier<TotalsAgg> totals = () -> new TotalsAgg(0, 0, 0);
        Supplier<List<ChipAgg>> chips = List::of;
        Supplier<List<KbStatsEntity>> misses = List::of;
        long missCount;
        java.util.Map<String, String> kbNames = java.util.Map.of();

        @Override
        public Uni<KbStatsEntity> persist(KbStatsEntity entity) {
            return Uni.createFrom().item(entity);
        }

        @Override
        public Uni<KbStatsEntity> findById(String messageId) {
            return Uni.createFrom().item((KbStatsEntity) null);
        }

        @Override
        public Uni<TotalsAgg> countTotals(LocalDateTime from, LocalDateTime to, String source, String kbId) {
            return Uni.createFrom().item(totals.get());
        }

        @Override
        public Uni<List<TrendAgg>> aggregateTrend(String bucket, LocalDateTime from, LocalDateTime to,
                                                  String source, String kbId) {
            return Uni.createFrom().item(trend.get());
        }

        Supplier<List<TrendAgg>> trend = List::of;

        String lastByChipKbId;

        @Override
        public Uni<List<ChipAgg>> aggregateByChip(LocalDateTime from, LocalDateTime to, String source, String kbId) {
            this.lastByChipKbId = kbId;
            return Uni.createFrom().item(chips.get());
        }

        @Override
        public Uni<List<KbStatsEntity>> findMisses(String keyword, LocalDateTime from, LocalDateTime to,
                                                   String source, int page, int size) {
            return Uni.createFrom().item(misses.get());
        }

        @Override
        public Uni<Long> countMisses(String keyword, LocalDateTime from, LocalDateTime to, String source) {
            return Uni.createFrom().item(missCount);
        }

        @Override
        public Uni<java.util.Map<String, String>> resolveKbNames() {
            return Uni.createFrom().item(kbNames);
        }
    }

    private final StubKbStatsRepository repository = new StubKbStatsRepository();
    private final HitRateQueryService service = new HitRateQueryService(repository);

    @Test
    void rateShouldBeHitOverTotal() {
        assertEquals(0.5, HitRateQueryService.rate(5, 10));
        assertEquals(0.0, HitRateQueryService.rate(0, 0));
        assertEquals(0.0, HitRateQueryService.rate(3, 0));
    }

    @Test
    void summaryShouldComputeRateAndExtremums() {
        repository.totals = () -> new TotalsAgg(100, 80, 0);
        repository.kbNames = java.util.Map.of("diting", "谛听"); // cv610 无快照回退 id
        repository.chips = () -> List.of(
                new ChipAgg("diting", 50, 44),   // 88%
                new ChipAgg("ws63", 30, 24),     // 80%
                new ChipAgg("cv610", 20, 17));   // 85%

        HitRateSummaryItem summary = service.summary(FROM, TO, null, null).await().indefinitely();

        assertEquals(0.8, summary.overallRate());
        assertEquals(100, summary.totalQueries());
        assertEquals(80, summary.totalHits());
        assertEquals(20, summary.totalMisses());
        assertEquals(0, summary.totalChitchat());
        assertEquals("谛听", summary.max().label());
        assertEquals(0.88, summary.max().value(), 1e-9);
        assertEquals("ws63", summary.min().label());
        assertEquals(0.8, summary.min().value(), 1e-9);
    }

    /** 闲聊不进分母：17 行 = 14 命中 + 3 闲聊 → 命中率 100%，未命中 0（列表为空即正确） */
    @Test
    void summaryShouldExcludeChitchatFromRateDenominator() {
        repository.totals = () -> new TotalsAgg(17, 14, 3);

        HitRateSummaryItem summary = service.summary(FROM, TO, null, null).await().indefinitely();

        assertEquals(1.0, summary.overallRate(), 1e-9);
        assertEquals(17, summary.totalQueries());
        assertEquals(14, summary.totalHits());
        assertEquals(0, summary.totalMisses());
        assertEquals(3, summary.totalChitchat());
    }

    /** 环比同样按有效查询（排除闲聊）算分母 */
    @Test
    void prevRateShouldUseEffectiveDenominator() {
        List<TotalsAgg> sequence = List.of(new TotalsAgg(20, 6, 10), new TotalsAgg(10, 5, 5));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        repository.totals = () -> sequence.get(calls.getAndIncrement());

        HitRateSummaryItem summary = service.summary(FROM, TO, null, null).await().indefinitely();

        // 本期 6/(20-10) = 0.6；上期 5/(10-5) = 1.0
        assertEquals(0.6, summary.overallRate(), 1e-9);
        assertEquals(1.0, summary.prevRate(), 1e-9);
        assertEquals(4, summary.totalMisses());
        assertEquals(10, summary.totalChitchat());
    }

    /** 全闲聊窗口：分母为 0 → 命中率 0（不抛异常），未命中 0 */
    @Test
    void summaryShouldKeepIdentityWhenAllChitchat() {
        repository.totals = () -> new TotalsAgg(3, 0, 3);

        HitRateSummaryItem summary = service.summary(FROM, TO, null, null).await().indefinitely();

        assertEquals(0.0, summary.overallRate());
        assertEquals(3, summary.totalQueries());
        assertEquals(0, summary.totalHits());
        assertEquals(0, summary.totalMisses());
        assertEquals(3, summary.totalChitchat());
    }

    @Test
    void summaryShouldReturnNullExtremumsWhenNoChips() {
        HitRateSummaryItem summary = service.summary(FROM, TO, null, null).await().indefinitely();
        assertEquals(0.0, summary.overallRate());
        assertNull(summary.max());
        assertNull(summary.min());
    }

    @Test
    void missesShouldMapEntityToItem() {
        repository.misses = () -> List.of(KbStatsEntity.builder()
                .messageId("am-1")
                .kbHit(false)
                .queryText("CV610 NPU 如何配置 INT8 量化模式?")
                .account("zhangsan")
                .source(KbStatsEntity.SOURCE_CHAT_QA)
                .selectedKbs("diting,ws63")
                .kbNamesJson("{\"diting\":\"谛听\",\"ws63\":\"ws63\"}")
                .answerText("抱歉，该问题暂未命中知识库。")
                .createdAt(LocalDateTime.of(2026, 9, 27, 14, 23))
                .build());
        repository.missCount = 13L;

        PageResult<MissItem> result = service.misses(null, FROM, TO, null, 1, 10).await().indefinitely();

        assertEquals(13, result.totalRow());
        assertEquals(2, result.totalPage());
        assertEquals(1, result.records().size());
        MissItem item = result.records().get(0);
        assertEquals("am-1", item.messageId());
        assertEquals("zhangsan", item.account());
        assertEquals("谛听,ws63", item.kbName());
        assertEquals("抱歉，该问题暂未命中知识库。", item.answerText());
    }

    @Test
    void extractAnswerMarkdownShouldJoinAnswerPayloadsInOrder() {
        String content = "{\"type\":\"route_reasoning\",\"content\":\"思考\"}"
                + "{\"answer\":\"这是\",\"type\":\"answer\",\"message_id\":\"\"}"
                + "{\"answer\":\"一段**回答**。\",\"type\":\"answer\",\"message_id\":\"\"}";
        assertEquals("这是一段**回答**。", HitRateQueryService.extractAnswerMarkdown(content));
    }

    @Test
    void extractAnswerMarkdownShouldFallBackToRawWhenAnyPayloadInvalid() {
        String broken = "{\"answer\":\"ok\",\"type\":\"answer\"}{not-json";
        assertEquals(broken, HitRateQueryService.extractAnswerMarkdown(broken));
    }

    @Test
    void extractAnswerMarkdownShouldPassThroughPlainTextAndNull() {
        assertEquals("纯文本回答", HitRateQueryService.extractAnswerMarkdown("纯文本回答"));
        assertNull(HitRateQueryService.extractAnswerMarkdown(null));
        assertEquals("", HitRateQueryService.extractAnswerMarkdown(""));
    }

    @Test
    void missesShouldApplyAnswerMarkdownProcessing() {
        repository.misses = () -> List.of(KbStatsEntity.builder()
                .messageId("am-3")
                .kbHit(false)
                .queryText("q")
                .answerText("{\"type\":\"route_reasoning\",\"content\":\"..\"}"
                        + "{\"answer\":\"未命中\",\"type\":\"answer\",\"message_id\":\"\"}")
                .build());
        repository.missCount = 1L;

        PageResult<MissItem> result = service.misses(null, FROM, TO, null, 1, 10).await().indefinitely();

        assertEquals("未命中", result.records().get(0).answerText());
    }

    @Test
    void missesShouldFallBackToKbIdsWhenSnapshotMissing() {
        repository.misses = () -> List.of(KbStatsEntity.builder()
                .messageId("am-2")
                .kbHit(false)
                .source(KbStatsEntity.SOURCE_CHAT_QA)
                .selectedKbs("kb1, kb2")
                .kbNamesJson("not-json")
                .build());

        PageResult<MissItem> result = service.misses(null, FROM, TO, null, 1, 10).await().indefinitely();

        assertEquals("kb1,kb2", result.records().get(0).kbName());
    }

    @Test
    void resolveKbNameShouldHandleEmptyAndNull() {
        assertEquals("", HitRateQueryService.resolveKbName(null, null));
        assertEquals("", HitRateQueryService.resolveKbName("{\"a\":\"甲\"}", " "));
        assertEquals("甲", HitRateQueryService.resolveKbName("{\"a\":\"甲\"}", "a"));
        assertEquals("a,b", HitRateQueryService.resolveKbName(null, "a,b"));
    }

    @Test
    void trendShouldMapLabelBucketStartAndCounts() {
        repository.trend = () -> List.of(
                new TrendAgg("2026年49周", "2026-11-30", 10, 6, 2),
                new TrendAgg("2026年50周", "2026-12-07", 4, 1, 0));

        List<TrendPointItem> points = service.trend("week", FROM, TO, null, null).await().indefinitely();

        assertEquals(2, points.size());
        assertEquals("2026年49周", points.get(0).date());
        assertEquals("2026-11-30", points.get(0).bucketStart());
        // 6/(10-2) 命中率按有效查询；柱仍是全部查询次数
        assertEquals(0.75, points.get(0).hitRate(), 1e-9);
        assertEquals(10, points.get(0).queryCount());
        assertEquals(6, points.get(0).hitCount());
        assertEquals(2, points.get(0).chitchatCount());
        assertEquals("2026年50周", points.get(1).date());
        assertEquals(0.25, points.get(1).hitRate(), 1e-9);
        assertEquals(4, points.get(1).queryCount());
    }

    @Test
    void byChipShouldPassKbIdAndMapHitField() {
        repository.kbNames = java.util.Map.of("cv610", "CV610");
        repository.chips = () -> List.of(new ChipAgg("cv610", 12, 6));

        List<ChipHitRateItem> items = service.byChip(FROM, TO, null, "cv610").await().indefinitely();

        assertEquals("cv610", repository.lastByChipKbId);
        assertEquals(1, items.size());
        assertEquals("CV610", items.get(0).kbName());
        assertEquals(0.5, items.get(0).hitRate(), 1e-9);
        assertEquals(6, items.get(0).hit());
        assertEquals(12, items.get(0).total());
    }

    @Test
    void summaryShouldUseFilteredChipsForExtremums() {
        repository.totals = () -> new TotalsAgg(11, 6, 0);
        repository.chips = () -> List.of(new ChipAgg("cv610", 11, 6)); // kbId 过滤后仅剩该库

        HitRateSummaryItem summary = service.summary(FROM, TO, null, "cv610").await().indefinitely();

        assertEquals(0.5454545454545454, summary.overallRate());
        assertEquals("cv610", summary.max().label());
        assertEquals("cv610", summary.min().label());
        assertEquals(6, summary.totalHits());
    }
}
