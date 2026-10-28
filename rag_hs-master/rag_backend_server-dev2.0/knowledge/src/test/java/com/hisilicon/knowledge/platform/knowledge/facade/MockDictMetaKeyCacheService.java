package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import io.smallrye.mutiny.Uni;

import java.util.Set;

/**
 * 手动 Mock — 替换 {@link DictMetaKeyCacheService}
 * <p>预设 getActiveMetaKeys 返回的白名单集合（null = 空集），避免测试依赖 Redis/DB。</p>
 */
public class MockDictMetaKeyCacheService extends DictMetaKeyCacheService {

    /** 预设白名单（null = 空集） */
    private static volatile Set<String> presetKeys;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDictMetaKeyCacheService() {
        super(null, null, null);
    }

    @Override
    public Uni<Set<String>> getActiveMetaKeys() {
        Set<String> keys = presetKeys;
        if (keys == null) {
            return Uni.createFrom().item(Set.of());
        }
        return Uni.createFrom().item(keys);
    }

    public static void setPresetKeys(Set<String> keys) {
        presetKeys = keys;
    }

    public static void reset() {
        presetKeys = null;
    }
}
