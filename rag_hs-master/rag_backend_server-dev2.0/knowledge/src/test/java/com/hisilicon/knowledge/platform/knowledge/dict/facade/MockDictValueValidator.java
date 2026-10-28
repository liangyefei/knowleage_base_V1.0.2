package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link DictValueValidator}
 * <p>捕获 evictCache 调用次数，避免真实 Redis 依赖。
 * 字典条目增/改/删后应触发值缓存（{@code dict:active-meta-key-values}）失效。</p>
 */
public class MockDictValueValidator extends DictValueValidator {

    /** evictCache 调用次数 */
    private static volatile int evictCount;

    public MockDictValueValidator() {
        super(null, null, null, null);
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
