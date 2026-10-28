package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.KnowledgeBaseCommandService;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KbCallbackProcessedRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging.DmsProcessCallbackConsumer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档生命周期状态流转测试（submit-review / cancel-review / approve / reject / archive / deprecate / 模型处理结果消费）
 */
@QuarkusTest
class DocumentResourceLifecycleTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentCommandService documentCommandService;

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    KnowledgeBaseCommandService knowledgeBaseCommandService;

    @Inject
    KbCallbackProcessedRepository callbackProcessedRepository;

    @Inject
    DictValueValidator dictValueValidator;

    @Inject
    FileStorageService fileStorageService;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    ReviewHistoryRepository reviewHistoryRepository;

    /**
     * DmsProcessCallbackConsumer 仅在 kb.messaging.kafka.enabled=true 时注册为 CDI bean
     * （Kafka 接线 bean，测试环境不启用 Kafka 通道），测试直接手动构造调用 consume() 逻辑。
     * 重试参数显式传入：最多 3 次尝试、退避 1ms（单测不等待真实退避）。
     */
    private DmsProcessCallbackConsumer callbackConsumer() {
        return new DmsProcessCallbackConsumer(documentCommandService, knowledgeBaseCommandService, 3, 1);
    }

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(
                new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(
                new MockKbCallbackProcessedRepository(), callbackProcessedRepository);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockReviewHistoryRepository(), reviewHistoryRepository);
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentRepository.resetFailureInjection();
        MockDocumentRepository.resetVersionStatusUpdate();
        MockKnowledgeBaseRepository.setEntityById(null);
        MockKnowledgeBaseRepository.setExistingEntity(null);
        MockKnowledgeBaseRepository.resetUpdatedEntity();
        MockKbCallbackProcessedRepository.reset();
        MockDictValueValidator.reset();
        MockFileStorageService.reset();
        MockDocumentOutboxRepository.reset();
        MockReviewHistoryRepository.reset();
    }

    // ======================== submit-review ========================

    @Test
    void shouldSubmitReviewFromDraft() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(DocumentEntity.STATUS_REVIEWING, MockDocumentRepository.getUpdatedEntity().getStatus(),
                "提交审核后状态应为 REVIEWING");
    }

    @Test
    void shouldFailSubmitReviewWhenDocumentNotFound() {
        MockDocumentRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"no-such-doc\"}")
                .when().post("/kb/documents/submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("文档不存在"));
    }

    @Test
    void shouldFailSubmitReviewWhenNotDraft() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档状态不正确，无法执行该操作"));
    }

    @Test
    void shouldFailSubmitReviewWhenDocumentIdMissing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{}")
                .when().post("/kb/documents/submit-review")
                .then()
                .statusCode(400);
    }

    // ======================== cancel-review ========================

    @Test
    void shouldCancelReviewFromReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/cancel-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "撤回后状态应为 DRAFT");
        assertNull(updated.getReviewedBy(), "撤回应清空审核人");
    }

    @Test
    void shouldFailCancelReviewWhenNotReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/cancel-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"));
    }

    // ======================== approve ========================

    @Test
    void shouldApproveFromReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_PUBLISHED, updated.getStatus(), "审核通过后状态应为 PUBLISHED");
        assertEquals(OPERATOR, updated.getReviewedBy(), "审核人应为操作员");
        assertNotNull(updated.getReviewedAt(), "审核时间不应为空");
        assertEquals("1", updated.getPublishedVersion(), "已发布版本号应为当前版本号");
        assertFalse(updated.getHasPublishedVersion(), "审核通过后清除历史发布标记");
    }

    @Test
    void shouldFailApproveWhenNotReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档状态不正确，无法执行该操作"));
    }

    @Test
    void shouldClearStaleReviewCommentOnApprove() {
        // 上轮 reject 残留的审核意见不应带入已发布文档：approve 清空 reviewComment 并写入本次审核人
        MockDocumentRepository.setEntityById(reviewedDocument(DocumentEntity.STATUS_REVIEWING,
                "上一轮审核人", "芯片型号标签缺失"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNull(updated.getReviewComment(), "approve 应清空历史驳回意见");
        assertEquals(OPERATOR, updated.getReviewedBy(), "审核人应为本次操作员");
    }

    // ======================== reject ========================

    @Test
    void shouldRejectFromReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"reason\":\"芯片型号标签缺失\"}")
                .when().post("/kb/documents/reject")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "驳回后状态应为 DRAFT");
        assertEquals("芯片型号标签缺失", updated.getReviewComment(), "驳回原因应记录");
        assertEquals(OPERATOR, updated.getReviewedBy(), "驳回人应为操作员");
    }

    @Test
    void shouldFailRejectWhenReasonBlank() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING, DocumentEntity.PROCESS_PENDING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"reason\":\"\"}")
                .when().post("/kb/documents/reject")
                .then()
                .statusCode(400);
    }

    @Test
    void shouldClearStaleReviewCommentOnResubmit() {
        // reject 残留的审核意见在重新提交审核时清空，不再随状态流转残留在后续环节
        MockDocumentRepository.setEntityById(reviewedDocument(DocumentEntity.STATUS_DRAFT,
                "上一轮审核人", "芯片型号标签缺失"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_REVIEWING, updated.getStatus(), "提交审核后应为 REVIEWING");
        assertNull(updated.getReviewComment(), "重新提交应清空历史驳回意见");
        assertNull(updated.getReviewedBy(), "重新提交应清空历史审核人");
        assertNull(updated.getReviewedAt(), "重新提交应清空历史审核时间");
    }

    // ======================== edit ========================

    @Test
    void shouldEditDocumentTitle() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("metadata", "{\"title\":\"new-title\"}")
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("new-title", MockDocumentRepository.getUpdatedEntity().getTitle(), "改名后标题应更新");
    }

    @Test
    void shouldReplaceDraftFileWhenSameExtension() {
        MockDocumentRepository.setEntityById(draftWithFile("kb-1/spec.pdf", "application/pdf"));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("metadata", "{\"title\":\"new-title\"}")
                .multiPart("file", "new.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "应更新文档实体");
        assertEquals("new-title", updated.getTitle(), "标题应更新");
        assertTrue(updated.getFilePath().endsWith("new.pdf"), "文件路径应更新为新文件");
        assertEquals(MockFileStorageService.getLastStorePath(), updated.getFilePath(),
                "实体 filePath 应为存储返回路径");
        assertEquals(DocumentEntity.PROCESS_PENDING, updated.getProcessStatus(),
                "换文件后 processStatus 应重置为 PENDING");
        assertEquals("1", updated.getCurrentVersion(), "DRAFT 换文件不应升版本");
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "状态应保持 DRAFT");
    }

    @Test
    void shouldRejectDraftFileReplaceWhenExtensionChanged() {
        MockDocumentRepository.setEntityById(draftWithFile("kb-1/spec.pdf", "application/pdf"));

        // 现有扩展名 pdf，上传 txt → 类型变更被拒（A0701 + fileTypeChanged）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("file", "new.txt", "test-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", containsString("文件类型不能变"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "类型变更被拒不应更新文档");
        assertNull(MockFileStorageService.getLastStorePath(), "类型变更被拒不应存储文件");
    }

    @Test
    void shouldRejectFileReplaceWhenPublished() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED,
                DocumentEntity.PROCESS_COMPLETED));

        // PUBLISHED + 文件 → 拒绝（A0508 + documentPublishedUseVersion），须走版本覆盖端点
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("file", "new.txt", "test-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("版本覆盖接口"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "已发布文档不应原地换文件");
    }

    @Test
    void shouldReplaceDraftFileWithoutMetadata() {
        MockDocumentRepository.setEntityById(draftWithFile("kb-1/spec.pdf", "application/pdf"));

        // 仅传文件、不传 metadata —— isAllNull 守卫应放行（file!=null 即有效编辑）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("file", "new.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "应更新文档实体");
        assertEquals("测试文档", updated.getTitle(), "未传 title 应沿用原标题");
        assertTrue(updated.getFilePath().endsWith("new.pdf"), "文件路径应更新为新文件");
        assertEquals(DocumentEntity.PROCESS_PENDING, updated.getProcessStatus(),
                "换文件后 processStatus 应重置为 PENDING");
    }

    // ======================== edit version ========================

    @Test
    void shouldUpdateVersionWhenEdit() {
        // DRAFT currentVersion="2"，无 publishedVersion/归档版 → 编辑改版本号 "3" 成功
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity draft = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("2")
                .hasPublishedVersion(false)
                .filePath("kb-1/测试文档.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .createdAt(now)
                .updatedAt(now)
                .build();
        MockDocumentRepository.setEntityById(draft);
        MockDocumentRepository.setVersionById(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "应更新文档实体");
        assertEquals("3", updated.getCurrentVersion(), "编辑应将 currentVersion 更新为新版本号");
    }

    @Test
    void shouldRejectEditWhenVersionAlreadyPublished() {
        // DRAFT 二次修订：currentVersion="2"，旧版 publishedVersion="1" 仍可检索
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity draft = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("2")
                .publishedVersion("1")
                .hasPublishedVersion(true)
                .filePath("kb-1/测试文档.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .createdAt(now)
                .updatedAt(now)
                .build();
        MockDocumentRepository.setEntityById(draft);
        MockDocumentRepository.setVersionById(null);

        // 编辑改版本号为已发布版 "1" → 拒绝 A0701
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", containsString("版本已发布过"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "版本查重失败不应更新文档");
    }

    @Test
    void shouldRejectEditVersionWhenPublished() {
        // PUBLISHED 禁改版本号：属性编辑携带 version → A0508「已发布文档不允许修改版本号」
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .multiPart("documentId", "doc-1")
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("不允许修改版本号"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "已发布文档改版本号不应更新文档");
    }

    // ======================== archive（仅版本级，version 必填） ========================

    @Test
    void shouldArchiveVersionLevel() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));
        MockDocumentRepository.setVersionById(new com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion(
                "doc-1", "v1.0", "kb-1/old-v1.txt", 100L, "text/plain", true,
                com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_PUBLISHED,
                OPERATOR, LocalDateTime.now()));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/archive")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // 仅版本行置 ARCHIVED，文档本体不变
        assertEquals("doc-1|v1.0|ARCHIVED", MockDocumentRepository.getLastVersionStatusUpdate(),
                "应仅对指定版本行置 ARCHIVED");
        assertNull(MockDocumentRepository.getUpdatedEntity(), "文档本体状态不应变化");
        // 事件 payload 携带 version/filePath/scope=VERSION
        assertEquals("DOCUMENT_ARCHIVED", MockDocumentOutboxRepository.getInsertedEventType(),
                "版本级归档应写入 DOCUMENT_ARCHIVED outbox 事件");
        Map<String, Object> expectedPayload = new java.util.HashMap<>();
        expectedPayload.put("reason", "ARCHIVED");
        expectedPayload.put("filePath", "kb-1/old-v1.txt");
        expectedPayload.put("version", "v1.0");
        expectedPayload.put("scope", "VERSION");
        assertEquals(expectedPayload, MockDocumentOutboxRepository.getInsertedPayload(),
                "payload 应为 {reason, filePath, version, scope=VERSION}");
        assertEquals("ARCHIVE", MockReviewHistoryRepository.getLastSavedAction(),
                "应写一条 ARCHIVE 审核历史");
    }

    @Test
    void shouldFailArchiveWhenVersionMissing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/archive")
                .then()
                .statusCode(400)
                .body("message", containsString("文档版本不能为空"));

        assertNull(MockDocumentRepository.getLastVersionStatusUpdate(), "缺 version 不应执行任何版本级操作");
    }

    @Test
    void shouldFailArchiveWhenVersionBlank() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"   \"}")
                .when().post("/kb/documents/archive")
                .then()
                .statusCode(400)
                .body("message", containsString("文档版本不能为空"));

        assertNull(MockDocumentRepository.getLastVersionStatusUpdate(), "version 空白不应执行任何版本级操作");
    }

    @Test
    void shouldFailArchiveWhenVersionNotFound() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));
        MockDocumentRepository.setVersionById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"v9.9\"}")
                .when().post("/kb/documents/archive")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    // ======================== deprecate（仅版本级，version 必填） ========================

    @Test
    void shouldDeprecateVersionLevel() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));
        MockDocumentRepository.setVersionById(new com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion(
                "doc-1", "v1.0", "kb-1/old-v1.txt", 100L, "text/plain", true,
                com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_PUBLISHED,
                OPERATOR, LocalDateTime.now()));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/deprecate")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // 仅版本行置 DEPRECATED，文档本体不变
        assertEquals("doc-1|v1.0|DEPRECATED", MockDocumentRepository.getLastVersionStatusUpdate(),
                "应仅对指定版本行置 DEPRECATED");
        assertNull(MockDocumentRepository.getUpdatedEntity(), "文档本体状态不应变化");
        // 事件 payload 携带 version/filePath/scope=VERSION
        assertEquals("DOCUMENT_DELETED", MockDocumentOutboxRepository.getInsertedEventType(),
                "版本级废弃应写入 DOCUMENT_DELETED outbox 事件");
        Map<String, Object> expectedPayload = new java.util.HashMap<>();
        expectedPayload.put("reason", "DEPRECATED");
        expectedPayload.put("filePath", "kb-1/old-v1.txt");
        expectedPayload.put("version", "v1.0");
        expectedPayload.put("scope", "VERSION");
        assertEquals(expectedPayload, MockDocumentOutboxRepository.getInsertedPayload(),
                "payload 应为 {reason, filePath, version, scope=VERSION}");
        assertEquals("DEPRECATE", MockReviewHistoryRepository.getLastSavedAction(),
                "应写一条 DEPRECATE 审核历史");
    }

    @Test
    void shouldIdempotentlySkipDeprecateWhenVersionAlreadyOffline() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));
        MockDocumentRepository.setVersionById(new com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion(
                "doc-1", "v1.0", "kb-1/old-v1.txt", 100L, "text/plain", true,
                com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_DEPRECATED,
                OPERATOR, LocalDateTime.now()));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/deprecate")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertNull(MockDocumentRepository.getLastVersionStatusUpdate(), "已下线版本重复操作应幂等跳过");
        assertNull(MockDocumentOutboxRepository.getInsertedEventType(), "幂等跳过不应写 outbox 事件");
    }

    @Test
    void shouldFailDeprecateWhenVersionMissing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/deprecate")
                .then()
                .statusCode(400)
                .body("message", containsString("文档版本不能为空"));

        assertNull(MockDocumentRepository.getLastVersionStatusUpdate(), "缺 version 不应执行任何版本级操作");
    }

    @Test
    void shouldFailDeprecateWhenVersionNotFound() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));
        MockDocumentRepository.setVersionById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\",\"version\":\"v9.9\"}")
                .when().post("/kb/documents/deprecate")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    // ======================== 模型处理结果消费（DMS Kafka 入站） ========================

    @Test
    void shouldUpdateProcessStatusCompleted() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m1\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"timestamp\":\"2026-08-05T10:35:00Z\"," +
                "\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":45}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "处理后状态应为 COMPLETED");
        assertEquals(DocumentEntity.STATUS_PUBLISHED, updated.getStatus(), "不改生命周期状态");
        assertEquals(45, updated.getChunkCount(), "应记录切片数");
        assertNull(updated.getProcessError(), "成功应清空失败原因");
        assertNull(updated.getProcessErrorCode(), "成功应清空错误码");
        assertNotNull(updated.getProcessedAt(), "应记录处理完成时间");
        assertEquals("m1", MockKbCallbackProcessedRepository.getLastRecordedMessageId(), "应记录 messageId 做幂等");
    }

    @Test
    void shouldUpdateProcessStatusFailedWithError() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m2\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"timestamp\":\"2026-08-05T10:32:00Z\"," +
                "\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"errorCode\":\"DOWNLOAD_TIMEOUT\",\"error\":\"文件下载超时\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus(), "处理后状态应为 FAILED");
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "FAILED(向量化失败)应将 PUBLISHED 打回 DRAFT");
        assertEquals("文件下载超时", updated.getProcessError(), "失败原因应记录");
        assertEquals("DOWNLOAD_TIMEOUT", updated.getProcessErrorCode(), "错误码应记录");
        assertNull(updated.getChunkCount(), "失败应清空切片数");
        assertNotNull(updated.getProcessedAt(), "应记录失败时间");
    }

    @Test
    void shouldParseCallbackWithNewPassThroughFields() {
        // 新增透传字段 totalDuration / callbackedAt：原样字符串解析，不因新字段存在而失败
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-passthrough-ok\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"timestamp\":\"2026-08-05T10:35:00Z\"," +
                "\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":45," +
                "\"totalDuration\":\"12345ms\",\"callbackedAt\":\"2026-08-05T10:35:01Z\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "带透传字段的成功回调应正常应用");
        assertEquals(45, updated.getChunkCount());
        assertEquals("m-passthrough-ok", MockKbCallbackProcessedRepository.getLastRecordedMessageId(), "应记录 messageId 做幂等");
    }

    @Test
    void shouldParseFailedCallbackWithNewPassThroughFields() {
        // FAILED 回调携带 totalDuration / callbackedAt / error / errorCode：解析不失败，日志应输出 error 与 errorCode
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-passthrough-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"timestamp\":\"2026-08-05T10:32:00Z\"," +
                "\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"errorCode\":\"DOWNLOAD_TIMEOUT\"," +
                "\"error\":\"文件下载超时\",\"totalDuration\":\"6789ms\",\"callbackedAt\":\"2026-08-05T10:32:01Z\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus(), "带透传字段的失败回调应正常应用");
        assertEquals("文件下载超时", updated.getProcessError());
        assertEquals("DOWNLOAD_TIMEOUT", updated.getProcessErrorCode());
        assertEquals("m-passthrough-fail", MockKbCallbackProcessedRepository.getLastRecordedMessageId(), "应记录 messageId 做幂等");
    }

    @Test
    void shouldParseCallbackWhenNewPassThroughFieldsMissing() {
        // 旧消息不含 totalDuration / callbackedAt：asText(null) 兜底，解析不因新字段缺失而失败
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-passthrough-absent\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"timestamp\":\"2026-08-05T10:35:00Z\"," +
                "\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":10}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "缺失透传字段不应影响回调应用");
        assertEquals("m-passthrough-absent", MockKbCallbackProcessedRepository.getLastRecordedMessageId(), "应记录 messageId 做幂等");
    }

    @Test
    void shouldKeepDraftStatusWhenFailedOnDraftDocument() {
        // 打回后人工重试再失败：来源已是 DRAFT → 幂等保持 DRAFT
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-draft-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"error\":\"再次失败\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus());
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "DRAFT 来源应保持 DRAFT 不变");
    }

    @Test
    void shouldNotDemoteArchivedDocumentOnFailed() {
        // 处理期间被归档的竞态：FAILED 回调只落 processStatus，人工终态不被异步回调打回
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_ARCHIVED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-arch-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"error\":\"失败\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus(), "处理状态应照常落 FAILED");
        assertEquals(DocumentEntity.STATUS_ARCHIVED, updated.getStatus(), "ARCHIVED 是人工终态，不应被打回 DRAFT");
    }

    @Test
    void shouldNotDemoteDeprecatedDocumentOnFailed() {
        // 处理期间被废弃的竞态：软删除终态不允许被异步回调复活为 DRAFT
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DEPRECATED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-dep-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"error\":\"失败\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus(), "处理状态应照常落 FAILED");
        assertEquals(DocumentEntity.STATUS_DEPRECATED, updated.getStatus(), "DEPRECATED 是软删除终态，不应被打回 DRAFT");
    }

    @Test
    void shouldStayDraftWhenLaterCompletedOverridesFailedDemotion() {
        // 打回草稿后人工重试成功：更晚的 COMPLETED 覆盖 processStatus，但生命周期停留 DRAFT（不自动恢复发布）
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .processError("旧失败")
                .processedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-retry-ok\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:40:00Z\",\"chunkCount\":50}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "重试新回调(processedAt 更晚)应覆盖终态，不应跳过");
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "应覆盖为 COMPLETED");
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "COMPLETED 不自动恢复发布，应停留 DRAFT 待重审");
    }

    @Test
    void shouldSkipStaleFailedCallbackWhenAlreadyFailed() {
        // 已 FAILED 的过期 FAILED 回调（processedAt 不晚于当前终态）→ 守卫跳过，不打回
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .processError("旧失败")
                .processedAt(LocalDateTime.of(2026, 8, 5, 10, 32))
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-stale-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:30:00Z\",\"error\":\"过期失败\"}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "过期 FAILED 应视为重复/过期，不 update 不打回");
    }

    @Test
    void shouldFillDefaultErrorWhenFailedPayloadMissingError() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        // FAILED 但 payload 无 error 字段 → 记录占位描述，不报错
        callbackConsumer().consume(
                "{\"messageId\":\"m2b\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\"}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_FAILED, updated.getProcessStatus());
        assertTrue(updated.getProcessError() != null && !updated.getProcessError().isBlank(),
                "缺失 error 应填占位描述，实际: " + updated.getProcessError());
    }

    @Test
    void shouldSkipDuplicateCallbackByIdempotency() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));
        String json = "{\"messageId\":\"m-dup\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":1}}";

        // 第一次：PROCESSING → COMPLETED，记录 messageId
        callbackConsumer().consume(json).await().indefinitely();
        DocumentEntity firstUpdated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.PROCESS_COMPLETED, firstUpdated.getProcessStatus());

        // 第二次：同 messageId 重投 → 幂等跳过，不应再次 update
        MockDocumentRepository.resetUpdatedEntity();
        callbackConsumer().consume(json).await().indefinitely();
        assertNull(MockDocumentRepository.getUpdatedEntity(), "重复 messageId 应幂等跳过，不再 update");
    }

    @Test
    void shouldSkipStaleCallbackWhenAlreadyTerminal() {
        // 文档已 COMPLETED，一条新 messageId 的旧回调到达 → 状态机守卫跳过，不回退
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        callbackConsumer().consume(
                "{\"messageId\":\"m-stale\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":99}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "已终态应视为重复/过期，ACK 跳过不 update");
        assertEquals("m-stale", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "跳过仍应记录 messageId 防后续重放");
    }

    @Test
    void shouldOverrideTerminalWhenLaterCallback() {
        // 重试场景：文档已 FAILED(processedAt 早)，模型重试成功发 COMPLETED(processedAt 晚) → 覆盖为 COMPLETED
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .processError("旧失败")
                .processedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-retry\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:40:00Z\",\"chunkCount\":50}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "重试新回调(processedAt 更晚)应覆盖终态，不应跳过");
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "应覆盖为 COMPLETED");
        assertEquals(50, updated.getChunkCount(), "应记录新切片数");
        assertNull(updated.getProcessError(), "覆盖应清空旧失败原因");
    }

    @Test
    void shouldOverrideFailedTerminalWithCompletedWhenProcessedAtMissing() {
        // 生产事故场景：重新发布后模型秒回 COMPLETED，但 payload 未带 processedAt（契约缺失），
        // 无法按"最新回调胜"比较 → 兜底规则 COMPLETED 覆盖 FAILED，否则文档冻结在"已发布+向量化失败"
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .processError("上一周期失败")
                .processedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-no-time-ok\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"chunkCount\":3}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "无 processedAt 的 COMPLETED 应按兜底规则覆盖 FAILED，不应跳过");
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "应覆盖为 COMPLETED");
        assertEquals(DocumentEntity.STATUS_PUBLISHED, updated.getStatus(), "COMPLETED 不改生命周期状态");
        assertNull(updated.getProcessError(), "覆盖应清空旧失败原因");
        assertEquals("m-no-time-ok", MockKbCallbackProcessedRepository.getLastRecordedMessageId(), "应记录 messageId 做幂等");
    }

    @Test
    void shouldOverrideFailedTerminalWithCompletedWhenBothProcessedAtMissing() {
        // 双侧都无法比较（实体 processedAt 也为空）时，兜底规则同样生效
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .processError("旧失败")
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-no-time-both\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"chunkCount\":0}}")
                .await().indefinitely();

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "双侧 processedAt 缺失时 COMPLETED 仍应覆盖 FAILED");
        assertEquals(DocumentEntity.PROCESS_COMPLETED, updated.getProcessStatus());
    }

    @Test
    void shouldSkipStaleCompletedWhenProcessedAtMissing() {
        // 已 COMPLETED，无 processedAt 的新 COMPLETED 回调 → 兜底不适用（非成功覆盖失败），保守跳过
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .processedAt(LocalDateTime.of(2026, 8, 5, 10, 35))
                .chunkCount(45)
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-no-time-dup\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"chunkCount\":99}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "无法比较的重复 COMPLETED 应保守跳过，不覆盖切片数");
        assertEquals("m-no-time-dup", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "跳过仍应记录 messageId 防后续重放");
    }

    @Test
    void shouldSkipFailedCallbackWithoutTimeOnCompletedDoc() {
        // 已 COMPLETED，无 failedAt 的 FAILED 回调 → 不允许无凭据的失败冲掉成功，且不触发打回
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .processedAt(LocalDateTime.of(2026, 8, 5, 10, 35))
                .chunkCount(45)
                .currentVersion("1")
                .build();
        MockDocumentRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"m-no-time-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"error\":\"迟到的失败\"}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "无法比较的 FAILED 不应覆盖 COMPLETED，更不应打回 DRAFT");
        assertEquals("m-no-time-fail", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "跳过仍应记录 messageId 防后续重放");
    }

    @Test
    void shouldAckWhenDocumentNotFound() {
        MockDocumentRepository.setEntityById(null);

        // 文档不存在（可能已物理删除）→ ACK 跳过，不再重投
        callbackConsumer().consume(
                "{\"messageId\":\"m-nf\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"no-such-doc\",\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\"}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "文档不存在不应 update");
        assertEquals("m-nf", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "文档不存在也应记录 messageId 防重放");
    }

    @Test
    void shouldSkipStaleCompletedCallbackWhenDocDemotedToDraftPending() {
        // 版本覆盖/草稿重传后（DRAFT+PENDING = 新内容周期），旧内容周期的 COMPLETED 回调不落库
        //（否则草稿被盖章"已向量化"，即测试环境 200+ 条 DRAFT+COMPLETED 的成因）
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-stale-draft\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":99}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "DRAFT+PENDING 窗口的陈旧 COMPLETED 回调不应 update");
        assertEquals("m-stale-draft", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "跳过仍应记录 messageId 防后续重放");
    }

    @Test
    void shouldSkipStaleFailedCallbackWhenDocDemotedToDraftPending() {
        // FAILED 变体：同样不落库（否则打开人工重试入口，重试的是已被替换的旧内容周期）
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        callbackConsumer().consume(
                "{\"messageId\":\"m-stale-draft-fail\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\",\"error\":\"旧周期失败\"}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "DRAFT+PENDING 窗口的陈旧 FAILED 回调不应 update");
        assertEquals("m-stale-draft-fail", MockKbCallbackProcessedRepository.getLastRecordedMessageId(),
                "跳过仍应记录 messageId 防后续重放");
    }

    @Test
    void shouldAckAndDropCallbackWithUnknownEventType() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        // 未知 eventType（不再 startsWith 兜底）→ ERROR 日志 + ACK（不进死信：
        // 死信写入失败会让 nack 失败并终止整条消费通道）
        callbackConsumer().consume(
                "{\"messageId\":\"m3\",\"eventType\":\"VECTORIZED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "非法 eventType 不应落库");
        assertEquals(0, MockDocumentRepository.getFindByIdCallCount(),
                "非法信封在解析阶段即丢弃，不应触达业务逻辑");
    }

    @Test
    void shouldAckAndDropCallbackWhenMessageIdMissing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));

        callbackConsumer().consume(
                "{\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{}}")
                .await().indefinitely();

        assertNull(MockDocumentRepository.getUpdatedEntity(), "缺 messageId 无法幂等去重，不应落库");
        assertEquals(0, MockDocumentRepository.getFindByIdCallCount(), "非法信封不触达业务逻辑");
    }

    @Test
    void shouldAckAndDropKbLevelCallbackWhenKbIdMissing() {
        // KB 级回调（documentId 缺失）但 kbId 也缺失 → 非法 → ACK 丢弃
        callbackConsumer().consume(
                "{\"messageId\":\"m-kb\",\"eventType\":\"PROCESSING_COMPLETED\"," +
                "\"documentId\":null,\"payload\":{\"chunkCount\":0}}")
                .await().indefinitely();

        assertNull(MockKnowledgeBaseRepository.getUpdatedEntity(), "缺 kbId 的 KB 级回调不应落库");
    }

    @Test
    void shouldRetryTransientFailureThenApplyCallback() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));
        MockDocumentRepository.failNextFindByIdCalls(2);   // 前两次抛瞬时故障，第三次成功

        callbackConsumer().consume(
                "{\"messageId\":\"m-retry-ok\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:30:00Z\",\"chunkCount\":5}}")
                .await().indefinitely();

        assertNotNull(MockDocumentRepository.getUpdatedEntity(), "瞬时故障重试后应成功落库");
        assertEquals(3, MockDocumentRepository.getFindByIdCallCount(),
                "有界重试应为 3 次尝试（2 次失败 + 1 次成功）");
    }

    @Test
    void shouldFailAfterRetryExhausted() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_PROCESSING));
        MockDocumentRepository.failNextFindByIdCalls(3);   // 3 次尝试全部失败

        // 重试耗尽 → 失败（nack → 死信：消息保留供人工对账重放），绝不静默 ACK
        assertThrows(RuntimeException.class, () -> callbackConsumer().consume(
                "{\"messageId\":\"m-retry-fail\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":\"doc-1\",\"payload\":{\"processedAt\":\"2026-08-05T10:30:00Z\",\"chunkCount\":5}}")
                .await().indefinitely());
        assertEquals(3, MockDocumentRepository.getFindByIdCallCount(),
                "最多 3 次尝试（含首次），不应无限重试");
    }

    // ---------- KB 级回调（KB_CREATED / KB_DELETED） ----------

    @Test
    void shouldUpdateKbProcessStatusCompleted() {
        MockKnowledgeBaseRepository.setEntityById(kb(KnowledgeBaseEntity.PROCESS_PENDING));

        callbackConsumer().consume(
                "{\"messageId\":\"kb-1\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":null,\"timestamp\":\"2026-08-05T10:35:00Z\"," +
                "\"payload\":{\"processedAt\":\"2026-08-05T10:35:00Z\",\"chunkCount\":0}}")
                .await().indefinitely();

        KnowledgeBaseEntity updated = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertEquals(KnowledgeBaseEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "KB 处理状态应为 COMPLETED");
        assertEquals("kb-1", MockKbCallbackProcessedRepository.getLastRecordedMessageId());
    }

    @Test
    void shouldUpdateKbProcessStatusFailed() {
        MockKnowledgeBaseRepository.setEntityById(kb(KnowledgeBaseEntity.PROCESS_PENDING));

        callbackConsumer().consume(
                "{\"messageId\":\"kb-2\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":null,\"payload\":{\"failedAt\":\"2026-08-05T10:32:00Z\"," +
                "\"errorCode\":\"500\",\"error\":\"向量库初始化失败\"}}")
                .await().indefinitely();

        KnowledgeBaseEntity updated = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertEquals(KnowledgeBaseEntity.PROCESS_FAILED, updated.getProcessStatus(), "KB 处理状态应为 FAILED");
        assertEquals("向量库初始化失败", updated.getProcessError());
        assertEquals("500", updated.getProcessErrorCode());
    }

    @Test
    void shouldAckWhenKbNotFound() {
        MockKnowledgeBaseRepository.setEntityById(null);

        // KB 不存在 → ACK 跳过，记录 messageId
        callbackConsumer().consume(
                "{\"messageId\":\"kb-nf\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"no-such-kb\"," +
                "\"documentId\":null,\"payload\":{\"chunkCount\":0}}")
                .await().indefinitely();

        assertNull(MockKnowledgeBaseRepository.getUpdatedEntity(), "KB 不存在不应 update");
        assertEquals("kb-nf", MockKbCallbackProcessedRepository.getLastRecordedMessageId());
    }

    @Test
    void shouldSkipKbStaleCallbackWhenAlreadyTerminal() {
        MockKnowledgeBaseRepository.setEntityById(kb(KnowledgeBaseEntity.PROCESS_COMPLETED));

        callbackConsumer().consume(
                "{\"messageId\":\"kb-stale\",\"eventType\":\"PROCESSING_FAILED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":null,\"payload\":{\"error\":\"旧失败\"}}")
                .await().indefinitely();

        assertNull(MockKnowledgeBaseRepository.getUpdatedEntity(), "KB 已终态应跳过不回退");
        assertEquals("kb-stale", MockKbCallbackProcessedRepository.getLastRecordedMessageId());
    }

    @Test
    void shouldOverrideKbTerminalWhenLaterCallback() {
        // KB 重试场景：已 FAILED(processedAt 早)，重试成功发 COMPLETED(processedAt 晚) → 覆盖为 COMPLETED
        KnowledgeBaseEntity existing = KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of("kb-1"))
                .name("测试知识库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .processStatus(KnowledgeBaseEntity.PROCESS_FAILED)
                .processError("旧失败")
                .processedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .createdBy("admin")
                .build();
        MockKnowledgeBaseRepository.setEntityById(existing);

        callbackConsumer().consume(
                "{\"messageId\":\"kb-retry\",\"eventType\":\"PROCESSING_COMPLETED\",\"kbId\":\"kb-1\"," +
                "\"documentId\":null,\"payload\":{\"processedAt\":\"2026-08-05T10:40:00Z\",\"chunkCount\":0}}")
                .await().indefinitely();

        KnowledgeBaseEntity updated = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(updated, "KB 重试新回调应覆盖终态，不应跳过");
        assertEquals(KnowledgeBaseEntity.PROCESS_COMPLETED, updated.getProcessStatus(), "应覆盖为 COMPLETED");
    }

    // ======================== 辅助方法 ========================

    /** 构建指定状态的最小文档实体 */
    private static DocumentEntity document(String status, String processStatus) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(status)
                .processStatus(processStatus)
                .currentVersion("1")
                .publishedVersion(DocumentEntity.STATUS_PUBLISHED.equals(status) ? "1" : null)
                .hasPublishedVersion(false)
                .filePath("kb-1/测试文档.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 构建带历史审核信息的文档实体（reviewedBy/reviewComment/reviewedAt 已残留） */
    private static DocumentEntity reviewedDocument(String status, String reviewedBy, String reviewComment) {
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(status)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath("kb-1/测试文档.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .reviewedBy(reviewedBy)
                .reviewComment(reviewComment)
                .reviewedAt(LocalDateTime.now().minusDays(1))
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** DRAFT 文档（已处理过，processStatus=COMPLETED），filePath/mimeType 指定，用于文件替换测试 */
    private static DocumentEntity draftWithFile(String filePath, String mimeType) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath(filePath)
                .fileSize(100L)
                .mimeType(mimeType)
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 构建指定处理状态的最小知识库实体 */
    private static KnowledgeBaseEntity kb(String processStatus) {
        LocalDateTime now = LocalDateTime.now();
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of("kb-1"))
                .name("测试知识库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .processStatus(processStatus)
                .createdBy("admin")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
