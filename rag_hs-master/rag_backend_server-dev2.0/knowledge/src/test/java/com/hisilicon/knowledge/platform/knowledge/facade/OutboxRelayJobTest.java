package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.job.OutboxRelayJob;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging.NoopStreamPublisher;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging.StreamPublisher;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务发件箱 relay 测试（同 KB 保序）
 * <p>验证：KB 分组扫描→锁获取→KB 内顺序发送→markSent；发送失败→markRetry；
 * 锁竞争（其他实例在处理）→跳过；无 PENDING→空转。</p>
 */
@QuarkusTest
class OutboxRelayJobTest {

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    StreamPublisher streamPublisher;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    OutboxRelayJob outboxRelayJob;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentOutboxRepository(), outboxRepository);
        MockDocumentOutboxRepository.reset();
        QuarkusMock.installMockForInstance(
                new MockStreamPublisher(), streamPublisher);
        MockStreamPublisher.reset();
        QuarkusMock.installMockForInstance(
                new MockDocumentRepository(), documentRepository);
        MockDocumentRepository.resetMarkProcessing();
        QuarkusMock.installMockForInstance(
                new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        MockKnowledgeBaseRepository.resetMarkProcessing();
    }

    @Test
    void shouldSendPendingAndMarkSent() {
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_DELETED", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedSentId());
        assertNull(MockDocumentOutboxRepository.getMarkedRetryId());
        assertEquals(1, MockStreamPublisher.getPublishedCount());
    }

    @Test
    void shouldMarkRetryWhenPublishFails() {
        MockStreamPublisher.setFailPublish(true);
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_DELETED", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        assertNull(MockDocumentOutboxRepository.getMarkedSentId());
        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedRetryId());
    }

    @Test
    void shouldSkipWhenNoPending() {
        MockDocumentOutboxRepository.setDistinctKbIds(null);

        outboxRelayJob.relay().await().indefinitely();

        assertNull(MockDocumentOutboxRepository.getMarkedSentId());
        assertNull(MockDocumentOutboxRepository.getMarkedRetryId());
        assertEquals(0, MockStreamPublisher.getPublishedCount());
    }

    @Test
    void shouldDropUnknownEventType() {
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "UNKNOWN_TYPE", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        // 未知类型直接标记已发送（避免死循环），不发布
        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedSentId());
        assertEquals(0, MockStreamPublisher.getPublishedCount());
    }

    @Test
    void shouldSkipKbWhenLockContended() {
        // kb-1 锁被其他实例占用 → 跳过不处理；kb-2 锁可获取 → 正常处理
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1", "kb-2"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_DELETED", "kb-1", "doc-1")));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-2",
                List.of(row("o-2", "DOCUMENT_DELETED", "kb-2", "doc-2")));
        MockDocumentOutboxRepository.setLockedKbIds(java.util.Set.of("kb-1"));

        outboxRelayJob.relay().await().indefinitely();

        // kb-1 被跳过（锁竞争），kb-2 正常处理并 markSent
        assertEquals("o-2", MockDocumentOutboxRepository.getMarkedSentId());
        assertNull(MockDocumentOutboxRepository.getMarkedRetryId());
        assertEquals(1, MockStreamPublisher.getPublishedCount());
        assertEquals("kb-2", MockDocumentOutboxRepository.getLastLockKbId());
    }

    @Test
    void shouldPreserveEventFieldsOnReplay() {
        // 验证 relay 重放透传 categoryId/operatorId/payload（修复历史 sendOne 丢字段的回归）
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row(new RowSeed("o-1", "DOCUMENT_PUBLISHED", "kb-1", "cat-1", "doc-1", "admin",
                        "{\"filePath\":\"/kb/doc.xlsx\",\"version\":\"1\"}"))));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedSentId());
        assertNull(MockDocumentOutboxRepository.getMarkedRetryId());
        KnowledgeEvent published = MockStreamPublisher.getLastPublished();
        assertNotNull(published);
        assertEquals(KnowledgeEvent.EventType.DOCUMENT_PUBLISHED, published.type());
        assertEquals("kb-1", published.kbId());
        assertEquals("cat-1", published.categoryId());
        assertEquals("doc-1", published.documentId());
        assertEquals("admin", published.operatorId());
        assertNotNull(published.payload());
        assertEquals("/kb/doc.xlsx", published.payload().get("filePath"));
        assertEquals("1", published.payload().get("version"));
    }

    @Test
    void shouldBackoffRetryOnFailure() {
        // retryCount=2 失败 → delay = min(10 << 2, 300) = 40s（base 默认 10s，封顶 300s）
        MockStreamPublisher.setFailPublish(true);
        DocumentOutboxPO r = row("o-1", "DOCUMENT_DELETED", "kb-1", "doc-1");
        r.setRetryCount(2);
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1", List.of(r));

        LocalDateTime before = LocalDateTime.now();
        outboxRelayJob.relay().await().indefinitely();

        LocalDateTime nextRetryAt = MockDocumentOutboxRepository.getMarkedRetryNextRetryAt();
        assertNotNull(nextRetryAt);
        assertTrue(nextRetryAt.isAfter(before.plusSeconds(35)), "退避应 > 35s");
        assertTrue(nextRetryAt.isBefore(before.plusSeconds(46)), "退避应 < 46s");
    }

    @Test
    void shouldMarkDocumentProcessingOnPublished() {
        // DOCUMENT_PUBLISHED 发送成功 → 文档 markProcessing 被调；KB 不调
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_PUBLISHED", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("doc-1", MockDocumentRepository.getMarkedProcessingDocumentId(),
                "DOCUMENT_PUBLISHED 发送成功后应置文档 processStatus=PROCESSING");
        assertEquals(1, MockDocumentRepository.getMarkProcessingCallCount());
        assertEquals(0, MockKnowledgeBaseRepository.getMarkProcessingCallCount(),
                "文档事件不应触发 KB markProcessing");
    }

    @Test
    void shouldMarkKbProcessingOnKbCreated() {
        // KB_CREATED 发送成功 → KB markProcessing；文档不调
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "KB_CREATED", "kb-1", null)));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("kb-1", MockKnowledgeBaseRepository.getMarkedProcessingKbId(),
                "KB_CREATED 发送成功后应置 KB processStatus=PROCESSING");
        assertEquals(1, MockKnowledgeBaseRepository.getMarkProcessingCallCount());
        assertEquals(0, MockDocumentRepository.getMarkProcessingCallCount(),
                "KB 事件不应触发文档 markProcessing");
    }

    @Test
    void shouldNotMarkProcessingOnArchived() {
        // DOCUMENT_ARCHIVED（移除活跃索引，非向量化中）→ 不动 processStatus
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_ARCHIVED", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedSentId());
        assertEquals(0, MockDocumentRepository.getMarkProcessingCallCount(),
                "ARCHIVED 不应触发 markProcessing");
        assertEquals(0, MockKnowledgeBaseRepository.getMarkProcessingCallCount());
    }

    @Test
    void shouldNotMarkProcessingOnSendFailure() {
        // 发送失败 → markRetry；消息未真正发出，不应 markProcessing
        MockStreamPublisher.setFailPublish(true);
        MockDocumentOutboxRepository.setDistinctKbIds(List.of("kb-1"));
        MockDocumentOutboxRepository.setPendingRowsForKb("kb-1",
                List.of(row("o-1", "DOCUMENT_PUBLISHED", "kb-1", "doc-1")));

        outboxRelayJob.relay().await().indefinitely();

        assertEquals("o-1", MockDocumentOutboxRepository.getMarkedRetryId());
        assertEquals(0, MockDocumentRepository.getMarkProcessingCallCount(),
                "发送失败不应 markProcessing");
    }

    // ======================== helpers ========================

    private static DocumentOutboxPO row(String id, String eventType, String kbId, String documentId) {
        return row(new RowSeed(id, eventType, kbId, null, documentId, null, null));
    }

    /** 全字段行种子（参数对象：完整字段重放用例 → row 工厂） */
    private record RowSeed(String id, String eventType, String kbId, String categoryId,
                           String documentId, String operatorId, String payloadJson) {
    }

    private static DocumentOutboxPO row(RowSeed seed) {
        DocumentOutboxPO po = new DocumentOutboxPO();
        po.setId(seed.id());
        po.setEventType(seed.eventType());
        po.setKbId(seed.kbId());
        po.setCategoryId(seed.categoryId());
        po.setDocumentId(seed.documentId());
        po.setOperatorId(seed.operatorId());
        po.setPayload(seed.payloadJson());
        po.setStatus(DocumentOutboxPO.STATUS_PENDING);
        po.setRetryCount(0);
        po.setCreatedAt(LocalDateTime.now());
        return po;
    }

    /** 可控制失败的 StreamPublisher mock（须继承实际实现类，QuarkusMock 要求） */
    static class MockStreamPublisher extends NoopStreamPublisher {

        private static volatile boolean failPublish;
        private static volatile int publishedCount;
        private static volatile KnowledgeEvent lastPublished;

        public static void reset() {
            failPublish = false;
            publishedCount = 0;
            lastPublished = null;
        }

        public static void setFailPublish(boolean fail) {
            failPublish = fail;
        }

        public static int getPublishedCount() {
            return publishedCount;
        }

        public static KnowledgeEvent getLastPublished() {
            return lastPublished;
        }

        @Override
        public Uni<Void> publish(KnowledgeEvent event) {
            publishedCount++;
            lastPublished = event;
            if (failPublish) {
                return Uni.createFrom().failure(new RuntimeException("DMS 发送失败"));
            }
            return Uni.createFrom().voidItem();
        }
    }
}
