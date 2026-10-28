package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockBatchDeleteDocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockDocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteResponse;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentCommandService#batchDelete} 单元测试（版本级批量删除）。
 *
 * <p>护栏语义：批量删除仅支持版本级（每条必须携带 version，缺失 → A0420 整批拒绝）；
 * 版本不存在 → SKIPPED(A0430)；执行异常 → FAILED(B0001) 不影响其余；汇总 total=目标条数。</p>
 */
@QuarkusTest
class DocumentCommandServiceBatchDeleteTest {

    private static final String LOCALE = "zh-CN";

    @Inject
    DocumentCommandService documentCommandService;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    ReviewHistoryRepository reviewHistoryRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockBatchDeleteDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockReviewHistoryRepository(), reviewHistoryRepository);
        MockBatchDeleteDocumentRepository.reset();
        MockDocumentOutboxRepository.reset();
    }

    private static BatchDeleteCommand.DeleteItem item(String docId, String version) {
        return new BatchDeleteCommand.DeleteItem(docId, version);
    }

    // ======================== 入参规整校验（fail-closed 护栏）====================

    @Test
    void shouldRejectNullItems() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentCommandService.batchDelete("admin", null, LOCALE));
        assertEquals(ErrorCodeEnum.USER_ERROR_A0420, ex.getErrorCodeEnum());
        assertTrue(MockBatchDeleteDocumentRepository.getDeletedVersionKeys().isEmpty());
    }

    @Test
    void shouldRejectItemWithoutVersion() {
        // 任一条目缺版本号 → 整批拒绝（防误操作，不静默部分执行）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentCommandService.batchDelete("admin",
                        List.of(item("doc-1", "v1.0"), item("doc-2", null)), LOCALE));
        assertEquals(ErrorCodeEnum.USER_ERROR_A0420, ex.getErrorCodeEnum());
        assertEquals("批量删除仅支持版本级操作，每个条目必须携带 version 参数", ex.getMessage());
        assertTrue(MockBatchDeleteDocumentRepository.getDeletedVersionKeys().isEmpty(), "整批拒绝，不应执行任何删除");
    }

    @Test
    void shouldRejectWhenExceeds200() {
        List<BatchDeleteCommand.DeleteItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            items.add(item("doc-" + i, "v1.0"));
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentCommandService.batchDelete("admin", items, LOCALE));
        assertEquals(ErrorCodeEnum.USER_ERROR_A0420, ex.getErrorCodeEnum());
    }

    // ======================== 版本级删除成功 ========================

    @Test
    void shouldDeleteVersionLevelSuccess() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        // filePath=null → 跳过文件回收（单元测试不触磁盘），仅验证 DB 行删除 + 汇总
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null)));

        BatchDeleteResponse resp = documentCommandService
                .batchDelete("admin", List.of(item("doc-x", "v1.0")), LOCALE)
                .await().indefinitely();

        assertNotNull(resp.getSummary());
        assertEquals(1, resp.getSummary().getTotal());
        assertEquals(1, resp.getSummary().getDeleted());
        assertEquals(0, resp.getSummary().getSkipped());
        assertEquals(0, resp.getSummary().getFailed());
        assertEquals(List.of("doc-x|v1.0"), MockBatchDeleteDocumentRepository.getDeletedVersionKeys());
        assertEquals("DELETED", resp.getResults().get(0).getStatus());
        assertEquals("v1.0", resp.getResults().get(0).getVersion());
    }

    // ======================== 逐条结果归一 ========================

    @Test
    void shouldSkipMissingVersionAndContinueOthers() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null)));

        BatchDeleteResponse resp = documentCommandService
                .batchDelete("admin",
                        List.of(item("doc-x", "v1.0"), item("doc-x", "v9.9")), LOCALE)
                .await().indefinitely();

        assertEquals(2, resp.getSummary().getTotal());
        assertEquals(1, resp.getSummary().getDeleted());
        assertEquals(1, resp.getSummary().getSkipped());
        assertEquals(0, resp.getSummary().getFailed());

        BatchDeleteItem deleted = resp.getResults().get(0);
        assertEquals("DELETED", deleted.getStatus());
        assertEquals("doc-x", deleted.getDocumentId());
        assertEquals("v1.0", deleted.getVersion());

        BatchDeleteItem missing = resp.getResults().get(1);
        assertEquals("SKIPPED", missing.getStatus());
        assertEquals("A0430", missing.getCode());
        assertEquals("v9.9", missing.getVersion());
    }

    @Test
    void shouldMarkFailedOnExecutionErrorWithoutBlockingOthers() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null),
                "doc-x|v2.0", version("doc-x", "v2.0", null)));
        MockBatchDeleteDocumentRepository.setFailDeleteVersionKeys(Set.of("doc-x|v1.0"));

        BatchDeleteResponse resp = documentCommandService
                .batchDelete("admin",
                        List.of(item("doc-x", "v1.0"), item("doc-x", "v2.0")), LOCALE)
                .await().indefinitely();

        assertEquals(2, resp.getSummary().getTotal());
        assertEquals(1, resp.getSummary().getDeleted());
        assertEquals(1, resp.getSummary().getFailed());
        assertEquals(0, resp.getSummary().getSkipped());
        assertEquals("FAILED", resp.getResults().get(0).getStatus());
        assertEquals("DELETED", resp.getResults().get(1).getStatus());
    }

    // ======================== 辅助 ========================

    private static DocumentEntity draftDoc(String id) {
        return DocumentEntity.builder()
                .id(com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId.of(id))
                .kbId("kb-1")
                .title("文档" + id)
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                // 当前版本与被删版本错开：批量删除为历史版本级路径（deleteVersion mock 可观测/可注入失败）
                .currentVersion("v0.9")
                .filePath("kb-1/" + id + ".pdf")
                .fileSize(100L)
                .mimeType("application/pdf")
                .docType("DOCUMENT")
                .createdBy("admin")
                .inheritParent(true)
                .build();
    }

    private static DocumentVersion version(String docId, String versionNumber, String filePath) {
        return new DocumentVersion(docId, versionNumber, filePath, 100L,
                "application/pdf", Boolean.TRUE,
                DocumentVersion.STATUS_PUBLISHED, "admin", LocalDateTime.now());
    }
}
