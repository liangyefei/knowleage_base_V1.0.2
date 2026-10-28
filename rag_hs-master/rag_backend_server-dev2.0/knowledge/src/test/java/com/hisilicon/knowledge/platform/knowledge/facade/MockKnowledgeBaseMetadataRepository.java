package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBaseMetadataRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 手动 Mock — 替换 {@link KnowledgeBaseMetadataRepositoryImpl}
 * <p>捕获最近一次 replaceAll 调用，用于断言全量替换语义。</p>
 */
public class MockKnowledgeBaseMetadataRepository extends KnowledgeBaseMetadataRepositoryImpl {

    /** 最近一次 replaceAll 的 kbId */
    private static volatile String lastKbId;

    /** 最近一次 replaceAll 的 metadata */
    private static volatile Map<String, String> lastMetadata;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockKnowledgeBaseMetadataRepository() {
        super(null);
    }

    @Override
    public Uni<Void> replaceAll(String kbId, Map<String, String> metadata) {
        lastKbId = kbId;
        lastMetadata = metadata;
        return Uni.createFrom().voidItem();
    }

    public static void reset() {
        lastKbId = null;
        lastMetadata = null;
    }

    public static String getLastKbId() {
        return lastKbId;
    }

    public static Map<String, String> getLastMetadata() {
        return lastMetadata;
    }
}
