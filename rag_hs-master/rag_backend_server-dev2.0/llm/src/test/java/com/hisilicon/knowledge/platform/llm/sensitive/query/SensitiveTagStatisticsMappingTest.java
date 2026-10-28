package com.hisilicon.knowledge.platform.llm.sensitive.query;

import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagStatistics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分类统计映射单测（纯 JUnit）：守护两个不变量——无词分类补 0、total = 各分类之和，
 * 以及标签顺序即返回顺序。聚合查询本身（group by + 孤儿词排除）需真实数据库，走集成验证。
 */
class SensitiveTagStatisticsMappingTest {

    @Test
    void shouldZeroFillMissingCountAndSumTotal() {
        SensitiveTagPO t1 = tag("t1", "政治敏感", (short) 1);
        SensitiveTagPO t2 = tag("t2", "其他", (short) 2);

        SensitiveTagStatistics stats = SensitiveTagQueryService.toStatistics(List.of(t1, t2), Map.of("t1", 5L));

        assertEquals(5, stats.total(), "total 应为各分类之和");
        assertEquals(2, stats.items().size());
        assertEquals("t1", stats.items().get(0).tagId());
        assertEquals(5, stats.items().get(0).wordCount());
        assertEquals(0, stats.items().get(1).wordCount(), "无词分类保留 0");
        assertEquals((short) 2, stats.items().get(1).status());
    }

    @Test
    void shouldSumMultipleCategories() {
        SensitiveTagStatistics stats = SensitiveTagQueryService.toStatistics(
                List.of(tag("t1", "政治敏感", (short) 1), tag("t2", "色情低俗", (short) 1),
                        tag("t3", "其他", (short) 2)),
                Map.of("t1", 5L, "t2", 4L, "t3", 1L));

        assertEquals(10, stats.total());
    }

    @Test
    void shouldReturnZeroTotalWhenNoTags() {
        SensitiveTagStatistics stats = SensitiveTagQueryService.toStatistics(List.of(), Map.of());

        assertEquals(0, stats.total());
        assertEquals(0, stats.items().size());
    }

    @Test
    void shouldIgnoreCountOfUnknownTag() {
        // 已删标签的计数（孤儿词）不应进入结果：映射只按传入标签取数
        SensitiveTagStatistics stats = SensitiveTagQueryService.toStatistics(
                List.of(tag("t1", "政治敏感", (short) 1)), Map.of("t1", 5L, "deleted-tag", 99L));

        assertEquals(5, stats.total());
        assertEquals(1, stats.items().size());
    }

    private static SensitiveTagPO tag(String id, String label, short status) {
        SensitiveTagPO po = new SensitiveTagPO();
        po.setId(id);
        po.setTextLabel(label);
        po.setLabelIdentification(label);
        po.setStatus(status);
        return po;
    }
}
