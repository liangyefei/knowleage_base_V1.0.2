package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryEntry;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ReviewHistoryRepositoryImpl;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link ReviewHistoryRepositoryImpl}
 * <p>测试环境 {@code quarkus.hibernate-orm.enabled=false}，Panache persist 会因实体未注册而抛异常。
 * 此 Mock 绕过 DB，通过静态字段捕获 save 调用供断言（调用次数、动作、文档 ID）。</p>
 */
public class MockReviewHistoryRepository extends ReviewHistoryRepositoryImpl {

    /** save 调用次数（断言批量操作写入的审核历史条数） */
    private static volatile int saveCount;

    /** 最近一次 save 的文档 ID */
    private static volatile String lastSavedDocumentId;

    /** 最近一次 save 的动作（SUBMIT_REVIEW / APPROVE / REJECT 等） */
    private static volatile String lastSavedAction;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockReviewHistoryRepository() {
        super(null);
    }

    public static void reset() {
        saveCount = 0;
        lastSavedDocumentId = null;
        lastSavedAction = null;
    }

    public static int getSaveCount() {
        return saveCount;
    }

    public static String getLastSavedDocumentId() {
        return lastSavedDocumentId;
    }

    public static String getLastSavedAction() {
        return lastSavedAction;
    }

    @Override
    public Uni<Void> save(ReviewHistoryEntry entry) {
        saveCount++;
        lastSavedDocumentId = entry.documentId();
        lastSavedAction = entry.action();
        return Uni.createFrom().voidItem();
    }
}
