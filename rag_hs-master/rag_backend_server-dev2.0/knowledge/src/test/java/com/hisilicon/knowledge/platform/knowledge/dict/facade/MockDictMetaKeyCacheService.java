package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link DictMetaKeyCacheService}
 * <p>捕获 evictCache 调用次数，避免真实 Redis 依赖。</p>
 */
public class MockDictMetaKeyCacheService extends DictMetaKeyCacheService {

    /** evictCache 调用次数 */
    private static volatile int evictCount;

    public MockDictMetaKeyCacheService() {
        super(null, null, null);
    }

    public static int getEvictCount() { return evictCount; }

    public static void reset() {
        evictCount = 0;
    }

    @Override
    public Uni<Void> evictCache() {
        evictCount++;
        return Uni.createFrom().voidItem();
    }
}
