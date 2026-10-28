package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentOutboxRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手动 Mock — 替换 {@link DocumentOutboxRepositoryImpl}
 * <p>通过静态字段捕获 insert/markSent 调用，控制 getDistinctKbIds/tryAcquireKbLock/scanPendingByKb 返回。</p>
 */
public class MockDocumentOutboxRepository extends DocumentOutboxRepositoryImpl {

    /** 最近一次 insert 的参数（捕获 KnowledgeEvent 字段） */
    private static volatile String insertedEventType;
    private static volatile String insertedKbId;
    private static volatile String insertedCategoryId;
    private static volatile String insertedDocumentId;
    private static volatile String insertedOperatorId;
    private static volatile Map<String, Object> insertedPayload;

    /** insert 调用次数（批量审核多条 approve 时用于断言 outbox 事件数量） */
    private static volatile int insertCount;

    /** getDistinctKbIds 返回的 kbId 列表（null = 空列表） */
    private static volatile List<String> distinctKbIds;

    /** 各 KB 的 PENDING 行（kbId → rows） */
    private static volatile Map<String, List<DocumentOutboxPO>> pendingByKb;

    /** tryAcquireKbLock 返回结果（true=获取成功） */
    private static volatile boolean lockAcquired = true;

    /** 被占用的 KB 集合（这些 KB 的 tryAcquireKbLock 返回 false） */
    private static volatile java.util.Set<String> lockedKbIds;

    /** 最近一次 markSent 的 ID */
    private static volatile String markedSentId;

    /** 最近一次 markRetry 的 ID */
    private static volatile String markedRetryId;

    /** 最近一次 markRetry 的下次重试时间（退避） */
    private static volatile LocalDateTime markedRetryNextRetryAt;

    /** 最近一次尝试加锁的 kbId */
    private static volatile String lastLockKbId;

    /** cancelPendingByDocument 最近一次调用的 documentId */
    private static volatile String cancelledDocumentId;

    /** cancelPendingByDocument 调用次数 */
    private static volatile int cancelCount;

    public MockDocumentOutboxRepository() {
        super(null);
    }

    public static void reset() {
        insertedEventType = null;
        insertedKbId = null;
        insertedCategoryId = null;
        insertedDocumentId = null;
        insertedOperatorId = null;
        insertedPayload = null;
        insertCount = 0;
        distinctKbIds = null;
        pendingByKb = null;
        lockAcquired = true;
        lockedKbIds = null;
        markedSentId = null;
        markedRetryId = null;
        markedRetryNextRetryAt = null;
        lastLockKbId = null;
        cancelledDocumentId = null;
        cancelCount = 0;
    }

    public static String getInsertedEventType() {
        return insertedEventType;
    }

    public static String getInsertedKbId() {
        return insertedKbId;
    }

    public static String getInsertedDocumentId() {
        return insertedDocumentId;
    }

    public static String getInsertedCategoryId() {
        return insertedCategoryId;
    }

    public static String getInsertedOperatorId() {
        return insertedOperatorId;
    }

    public static Map<String, Object> getInsertedPayload() {
        return insertedPayload;
    }

    public static int getInsertCount() {
        return insertCount;
    }

    public static void setDistinctKbIds(List<String> kbIds) {
        distinctKbIds = kbIds;
    }

    public static void setPendingRowsForKb(String kbId, List<DocumentOutboxPO> rows) {
        if (pendingByKb == null) {
            pendingByKb = new HashMap<>();
        }
        pendingByKb.put(kbId, rows);
    }

    public static void setLockAcquired(boolean acquired) {
        lockAcquired = acquired;
    }

    public static void setLockedKbIds(java.util.Set<String> kbIds) {
        lockedKbIds = kbIds;
    }

    public static String getMarkedSentId() {
        return markedSentId;
    }

    public static String getMarkedRetryId() {
        return markedRetryId;
    }

    public static LocalDateTime getMarkedRetryNextRetryAt() {
        return markedRetryNextRetryAt;
    }

    public static String getLastLockKbId() {
        return lastLockKbId;
    }

    public static String getCancelledDocumentId() {
        return cancelledDocumentId;
    }

    public static int getCancelCount() {
        return cancelCount;
    }

    @Override
    public Uni<Void> insert(KnowledgeEvent event) {
        insertedEventType = event.type().name();
        insertedKbId = event.kbId();
        insertedCategoryId = event.categoryId();
        insertedDocumentId = event.documentId();
        insertedOperatorId = event.operatorId();
        insertedPayload = event.payload();
        insertCount++;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<String>> getDistinctKbIds() {
        List<String> kbIds = distinctKbIds;
        if (kbIds == null) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.createFrom().item(kbIds);
    }

    @Override
    public Uni<Boolean> tryAcquireKbLock(String kbId) {
        lastLockKbId = kbId;
        java.util.Set<String> locked = lockedKbIds;
        if (locked != null && locked.contains(kbId)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(lockAcquired);
    }

    @Override
    public Uni<List<DocumentOutboxPO>> scanPendingByKb(String kbId, int limit) {
        Map<String, List<DocumentOutboxPO>> map = pendingByKb;
        if (map == null || !map.containsKey(kbId)) {
            return Uni.createFrom().item(List.of());
        }
        List<DocumentOutboxPO> rows = map.get(kbId);
        return Uni.createFrom().item(new ArrayList<>(rows));
    }

    @Override
    public Uni<Void> markSent(String id) {
        markedSentId = id;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> markRetry(String id, LocalDateTime nextRetryAt) {
        markedRetryId = id;
        markedRetryNextRetryAt = nextRetryAt;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> cancelPendingByDocument(String documentId) {
        cancelledDocumentId = documentId;
        cancelCount++;
        return Uni.createFrom().voidItem();
    }
}
