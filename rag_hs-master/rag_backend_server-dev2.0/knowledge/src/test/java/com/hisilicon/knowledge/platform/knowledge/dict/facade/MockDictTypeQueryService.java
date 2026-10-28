package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.dict.query.DictTypeQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictTypeItem;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link DictTypeQueryService}
 * <p>预设分页结果，避免真实 Panache 查询依赖。</p>
 */
public class MockDictTypeQueryService extends DictTypeQueryService {

    /** 预设的分页结果 */
    private static volatile PageResult<DictTypeItem> presetPage;

    /** 最后一次查询的 typeCode */
    private static volatile Integer lastPage;

    public MockDictTypeQueryService() {
        super(null);
    }

    public static void setPresetPage(PageResult<DictTypeItem> page) { presetPage = page; }
    public static Integer getLastPage() { return lastPage; }

    public static void reset() {
        presetPage = null;
        lastPage = null;
    }

    @Override
    public Uni<PageResult<DictTypeItem>> search(Integer page, Integer pageSize, String keyword, Short status) {
        lastPage = page;
        return Uni.createFrom().item(presetPage != null ? presetPage : emptyPage(page, pageSize));
    }

    private PageResult<DictTypeItem> emptyPage(Integer page, Integer pageSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : pageSize;
        return new PageResult<>(p, ps, 0, 0, List.of());
    }
}
