package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExportRow;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveWordQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveWordItem;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link SensitiveWordQueryService}
 */
public class MockSensitiveWordQueryService extends SensitiveWordQueryService {

    private static volatile PageResult<SensitiveWordItem> searchResult =
            new PageResult<>(1, 20, 0, 0, List.of());
    private static volatile List<SensitiveWordExportRow> exportRows = List.of();
    private static volatile String lastSearchTagId;

    public MockSensitiveWordQueryService() {
        super(null, null);
    }

    public static void setSearchItems(List<SensitiveWordItem> items) {
        searchResult = new PageResult<>(1, 20, items.size(), items.size(), items);
    }

    public static void setExportRows(List<SensitiveWordExportRow> rows) { exportRows = rows; }

    public static String getLastSearchTagId() { return lastSearchTagId; }

    public static void reset() {
        searchResult = new PageResult<>(1, 20, 0, 0, List.of());
        exportRows = List.of();
        lastSearchTagId = null;
    }

    @Override
    public Uni<PageResult<SensitiveWordItem>> search(String tagId, String word, Short status,
                                                     Integer page, Integer pageSize) {
        lastSearchTagId = tagId;
        return Uni.createFrom().item(searchResult);
    }

    @Override
    public Uni<List<SensitiveWordExportRow>> listForExport(String tagId, String word) {
        return Uni.createFrom().item(exportRows);
    }
}
