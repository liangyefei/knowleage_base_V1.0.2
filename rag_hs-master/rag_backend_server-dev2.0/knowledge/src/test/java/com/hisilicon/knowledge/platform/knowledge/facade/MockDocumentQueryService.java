package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.ListDocumentsQuery;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.MetadataOptionGroup;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mock — 替换 {@link DocumentQueryService}，返回预设分页文档列表
 */
public class MockDocumentQueryService extends DocumentQueryService {

    private static volatile PageResult<DocumentListItem> presetPage;
    private static volatile List<MetadataOptionGroup> presetOptions;

    public MockDocumentQueryService() {
        super(null, null, null, null, null, null);
    }

    public static void setPresetPage(PageResult<DocumentListItem> page) {
        presetPage = page;
    }

    public static void setPresetOptions(List<MetadataOptionGroup> options) {
        presetOptions = options;
    }

    @Override
    public Uni<PageResult<DocumentListItem>> listDocuments(ListDocumentsQuery query) {
        // 记录过滤条件供测试断言（POST body 未传的 List 反序列化为 null，归一化为空列表）
        lastFilters = new Filters(query.categoryId(), query.docName(),
                query.docTypes() != null ? query.docTypes() : List.of(),
                query.statuses() != null ? query.statuses() : List.of(),
                query.processStatuses() != null ? query.processStatuses() : List.of(),
                query.metadata());
        PageResult<DocumentListItem> result = presetPage;
        if (result == null) {
            return Uni.createFrom().item(
                    new PageResult<>(query.page(), query.pageSize(), 0, 0, List.of()));
        }
        return Uni.createFrom().item(result);
    }

    /** 最近一次调用的过滤条件 */
    private static volatile Filters lastFilters;

    @Override
    public Uni<List<MetadataOptionGroup>> getMetadataOptions(String kbId, Locale locale) {
        lastOptionsKbId = kbId;
        return Uni.createFrom().item(presetOptions != null ? presetOptions : List.of());
    }

    /** 最近一次调用元数据选项的 kbId */
    private static volatile String lastOptionsKbId;

    /** 读取最近一次调用元数据选项的 kbId */
    public static String getLastOptionsKbId() {
        return lastOptionsKbId;
    }

    /** 读取最近一次调用的过滤条件（null = 尚未调用） */
    public static Filters getLastFilters() {
        return lastFilters;
    }

    /** 过滤条件记录 */
    public record Filters(String categoryId, String docName, List<String> docTypes,
                          List<String> statuses, List<String> processStatuses,
                          Map<String, String> metadata) {
    }
}
