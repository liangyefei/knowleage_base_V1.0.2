package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOptionGroup;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Locale;

/**
 * 手动 Mock — 替换 {@link DictOptionQueryService}
 * <p>通过静态字段控制返回结果；@BeforeEach 重置。</p>
 */
public class MockDictOptionQueryService extends DictOptionQueryService {

    /** getUploadOptions 返回的预设分组（null = 空列表） */
    private static volatile List<UploadDictOptionGroup> presetGroups;

    /** 最近一次请求的语言环境（null = 未调用） */
    private static volatile Locale lastLocale;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDictOptionQueryService() {
        super(null, null, null);
    }

    public static void setPresetGroups(List<UploadDictOptionGroup> groups) { presetGroups = groups; }
    public static Locale getLastLocale() { return lastLocale; }

    public static void reset() {
        presetGroups = null;
        lastLocale = null;
    }

    @Override
    public Uni<List<UploadDictOptionGroup>> getUploadOptions(Locale locale) {
        lastLocale = locale;
        List<UploadDictOptionGroup> groups = presetGroups;
        return Uni.createFrom().item(groups != null ? groups : List.of());
    }
}
