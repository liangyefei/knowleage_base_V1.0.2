package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentMetadataRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 手动 Mock — 替换 {@link DocumentMetadataRepositoryImpl}
 * <p>捕获最近一次 replaceAll 调用，用于断言全量替换语义。</p>
 */
public class MockDocumentMetadataRepository extends DocumentMetadataRepositoryImpl {

    /** 最近一次 replaceAll 的 documentId */
    private static volatile String lastDocumentId;

    /** 最近一次 replaceAll 的 metadata */
    private static volatile Map<String, String> lastMetadata;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDocumentMetadataRepository() {
        super(null);
    }

    @Override
    public Uni<Void> replaceAll(String documentId, Map<String, String> metadata) {
        lastDocumentId = documentId;
        lastMetadata = metadata;
        return Uni.createFrom().voidItem();
    }

    public static void reset() {
        lastDocumentId = null;
        lastMetadata = null;
    }

    public static String getLastDocumentId() {
        return lastDocumentId;
    }

    public static Map<String, String> getLastMetadata() {
        return lastMetadata;
    }
}
