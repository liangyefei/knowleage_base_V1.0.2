package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.exception.IllegalParameterException;
import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository;
import com.hisilicon.knowledge.platform.llm.query.HitRateQueryService;
import com.hisilicon.knowledge.platform.llm.query.dto.TrendPointItem;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 命中率仪表盘时间窗校验测试：
 * 所有接口（含 trend）统一 92 天（三个月）上限，超限提示国际化（中/英）。
 */
@QuarkusTest
class HitRateStatsResourceTest {

    private final StubKbStatsRepository repository = new StubKbStatsRepository();
    private final HitRateStatsResource resource =
            new HitRateStatsResource(new HitRateQueryService(repository));

    private void setLocale(String acceptLanguage) throws Exception {
        Field field = HitRateStatsResource.class.getDeclaredField("httpHeaders");
        field.setAccessible(true);
        HttpHeaders headers = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(),
                new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return acceptLanguage;
                    }
                    if ("getRequestHeaders".equals(method.getName())) {
                        return Map.of(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage == null ? "" : acceptLanguage);
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("toString")) {
                        return "StubHttpHeaders(" + acceptLanguage + ")";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        field.set(resource, headers);
    }

    /** from 起始日，days 为窗口跨度（to = from + days，按"含当日"口径窗口为 days+1 天） */
    private String[] window(int days) {
        LocalDate from = LocalDate.of(2026, 1, 1);
        return new String[]{from.toString(), from.plusDays(days).toString()};
    }

    @Test
    void trendOver92DaysShouldBeRejectedWithZhMessage() throws Exception {
        setLocale("zh-CN");
        String[] w = window(93);
        IllegalParameterException e = assertThrows(IllegalParameterException.class,
                () -> resource.trend("day", w[0], w[1], null, null));
        assertEquals("查询范围过大，最长支持三个月", e.getMessage());
    }

    @Test
    void trendOver92DaysShouldBeRejectedWithEnMessage() throws Exception {
        setLocale("en-US");
        String[] w = window(93);
        IllegalParameterException e = assertThrows(IllegalParameterException.class,
                () -> resource.trend("day", w[0], w[1], null, null));
        assertEquals("Date range too large; maximum is three months", e.getMessage());
    }

    @Test
    void trendLegacyLargeWindowsShouldAllBeRejected() throws Exception {
        setLocale("zh-CN");
        for (int days : new int[]{366, 1825, 3652}) {
            String[] w = window(days);
            IllegalParameterException e = assertThrows(IllegalParameterException.class,
                    () -> resource.trend("day", w[0], w[1], null, null),
                    "窗口 " + days + " 天应被拒绝");
            assertEquals("查询范围过大，最长支持三个月", e.getMessage());
            IllegalParameterException week = assertThrows(IllegalParameterException.class,
                    () -> resource.trend("week", w[0], w[1], null, null));
            assertEquals("查询范围过大，最长支持三个月", week.getMessage());
            IllegalParameterException month = assertThrows(IllegalParameterException.class,
                    () -> resource.trend("month", w[0], w[1], null, null));
            assertEquals("查询范围过大，最长支持三个月", month.getMessage());
        }
    }

    @Test
    void summaryAndMissesAndExportShouldShare92DayLimit() throws Exception {
        setLocale("zh-CN");
        String[] w = window(93);
        assertThrows(IllegalParameterException.class, () -> resource.summary(w[0], w[1], null, null));
        assertThrows(IllegalParameterException.class, () -> resource.byChip(w[0], w[1], null, null));
        assertThrows(IllegalParameterException.class,
                () -> resource.misses(null, w[0], w[1], null, 1, 20));
        assertThrows(IllegalParameterException.class,
                () -> resource.exportMisses(null, w[0], w[1], null));
    }

    @Test
    void trendAt92DayLimitShouldPass() throws Exception {
        setLocale("zh-CN");
        String[] w = window(92);
        ResponseResult<List<TrendPointItem>> result = resource.trend("day", w[0], w[1], null, null)
                .await().indefinitely();
        assertEquals(List.of(), result.getData());
    }

    /** 空实现仓储：仅供时间窗校验测试透传 */
    static class StubKbStatsRepository implements KbStatsRepository {
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
            return Uni.createFrom().item(new TotalsAgg(0, 0, 0));
        }

        @Override
        public Uni<List<TrendAgg>> aggregateTrend(String bucket, LocalDateTime from, LocalDateTime to,
                                                  String source, String kbId) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<ChipAgg>> aggregateByChip(LocalDateTime from, LocalDateTime to, String source, String kbId) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<KbStatsEntity>> findMisses(String keyword, LocalDateTime from, LocalDateTime to,
                                                   String source, int page, int size) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Long> countMisses(String keyword, LocalDateTime from, LocalDateTime to, String source) {
            return Uni.createFrom().item(0L);
        }

        @Override
        public Uni<Map<String, String>> resolveKbNames() {
            return Uni.createFrom().item(Map.of());
        }
    }
}
