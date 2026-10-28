package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KbCallbackProcessedRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动 Mock — 替换 {@link KbCallbackProcessedRepositoryImpl}。
 * <p>用静态 Set 记录已处理的 messageId，{@link #existsByMessageId} 据此判重。</p>
 */
public class MockKbCallbackProcessedRepository extends KbCallbackProcessedRepositoryImpl {

    private static final Set<String> PROCESSED = ConcurrentHashMap.newKeySet();

    private static volatile String lastRecordedMessageId;
    private static volatile String lastRecordedStatus;

    public MockKbCallbackProcessedRepository() {
        super(null);
    }

    public static void reset() {
        PROCESSED.clear();
        lastRecordedMessageId = null;
        lastRecordedStatus = null;
    }

    public static String getLastRecordedMessageId() {
        return lastRecordedMessageId;
    }

    public static String getLastRecordedStatus() {
        return lastRecordedStatus;
    }

    @Override
    public Uni<Boolean> existsByMessageId(String messageId) {
        return Uni.createFrom().item(PROCESSED.contains(messageId));
    }

    @Override
    public Uni<Void> record(String messageId, String documentId, String kbId,
                           String eventType, String status) {
        PROCESSED.add(messageId);
        lastRecordedMessageId = messageId;
        lastRecordedStatus = status;
        return Uni.createFrom().voidItem();
    }
}
