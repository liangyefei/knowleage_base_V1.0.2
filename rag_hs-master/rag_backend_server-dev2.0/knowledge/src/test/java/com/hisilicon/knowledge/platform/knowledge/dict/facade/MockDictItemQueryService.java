package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.knowledge.dict.query.DictItemQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictItemItem;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link DictItemQueryService}
 * <p>预设条目列表，避免真实 Panache 查询依赖。</p>
 */
public class MockDictItemQueryService extends DictItemQueryService {

    /** 预设的条目列表 */
    private static volatile List<DictItemItem> presetList;

    /** 最后一次查询的 typeCode */
    private static volatile String lastTypeCode;

    public MockDictItemQueryService() {
        super(null);
    }

    public static void setPresetList(List<DictItemItem> list) { presetList = list; }
    public static String getLastTypeCode() { return lastTypeCode; }

    public static void reset() {
        presetList = null;
        lastTypeCode = null;
    }

    @Override
    public Uni<List<DictItemItem>> list(String typeCode, Short status) {
        lastTypeCode = typeCode;
        return Uni.createFrom().item(presetList != null ? presetList : List.of());
    }
}
