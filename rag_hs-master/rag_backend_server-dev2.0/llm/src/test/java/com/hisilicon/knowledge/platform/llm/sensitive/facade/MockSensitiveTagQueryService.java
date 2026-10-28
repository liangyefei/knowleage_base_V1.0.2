package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveTagQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagItem;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagStatistics;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link SensitiveTagQueryService}
 */
public class MockSensitiveTagQueryService extends SensitiveTagQueryService {

    private static volatile List<SensitiveTagItem> items = List.of();
    private static volatile SensitiveTagStatistics statistics = new SensitiveTagStatistics(0, List.of());

    public MockSensitiveTagQueryService() {
        super(null, null);
    }

    public static void setItems(List<SensitiveTagItem> list) { items = list; }

    public static void setStatistics(SensitiveTagStatistics value) { statistics = value; }

    public static void reset() {
        items = List.of();
        statistics = new SensitiveTagStatistics(0, List.of());
    }

    @Override
    public Uni<PageResult<SensitiveTagItem>> search(Integer page, Integer pageSize, String keyword, Short status) {
        return Uni.createFrom().item(new PageResult<>(1, 20, items.size(), items.size(), items));
    }

    @Override
    public Uni<List<SensitiveTagItem>> listActive() {
        return Uni.createFrom().item(items);
    }

    @Override
    public Uni<SensitiveTagStatistics> statistics() {
        return Uni.createFrom().item(statistics);
    }
}
