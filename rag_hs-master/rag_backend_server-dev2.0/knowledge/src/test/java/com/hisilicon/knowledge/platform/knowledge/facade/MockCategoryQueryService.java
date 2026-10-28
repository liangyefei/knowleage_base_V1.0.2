package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.CategoryQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.CategoryListItem;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * Mock — 替换 {@link CategoryQueryService}，返回预设类别列表
 */
public class MockCategoryQueryService extends CategoryQueryService {

    private static volatile List<CategoryListItem> presetList;

    public MockCategoryQueryService() {
        super(null);
    }

    public static void setPresetList(List<CategoryListItem> list) {
        presetList = list;
    }

    @Override
    public Uni<List<CategoryListItem>> listCategories(String kbId, String parentId) {
        List<CategoryListItem> list = presetList;
        if (list == null) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.createFrom().item(list);
    }
}
