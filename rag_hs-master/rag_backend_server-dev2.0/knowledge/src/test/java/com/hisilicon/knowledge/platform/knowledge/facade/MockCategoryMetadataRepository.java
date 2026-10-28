package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryMetadataRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 手动 Mock — 替换 {@link CategoryMetadataRepositoryImpl}
 * <p>捕获最近一次 replaceAll 调用，用于断言全量替换语义。</p>
 */
public class MockCategoryMetadataRepository extends CategoryMetadataRepositoryImpl {

    /** 最近一次 replaceAll 的 categoryId */
    private static volatile String lastCategoryId;

    /** 最近一次 replaceAll 的 metadata */
    private static volatile Map<String, String> lastMetadata;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockCategoryMetadataRepository() {
        super(null);
    }

    @Override
    public Uni<Void> replaceAll(String categoryId, Map<String, String> metadata) {
        lastCategoryId = categoryId;
        lastMetadata = metadata;
        return Uni.createFrom().voidItem();
    }

    public static void reset() {
        lastCategoryId = null;
        lastMetadata = null;
    }

    public static String getLastCategoryId() {
        return lastCategoryId;
    }

    public static Map<String, String> getLastMetadata() {
        return lastMetadata;
    }
}
