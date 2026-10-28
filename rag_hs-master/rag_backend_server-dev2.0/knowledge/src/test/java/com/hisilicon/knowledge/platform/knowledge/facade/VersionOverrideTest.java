package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentMetadataRepositoryImpl;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本覆盖接口测试：POST /kb/documents/versions
 * <p>版本覆盖与上传对齐：须传 metadata（含 version）；version 由元数据提供并存入
 * documents.current_version；不得为已发布/已归档版本。</p>
 */
@QuarkusTest
class VersionOverrideTest {

    private static final String KB_ID = "kb-ver-1";
    private static final String DOC_ID = "doc-ver-1";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    DictValueValidator dictValueValidator;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        // 覆盖成功路径会作废旧内容周期 PENDING outbox 事件，须 mock（单元测试无 DB）
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockCategoryRepository.setEntityById(null);
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetArchivedVersion();
        // updatedEntity 为跨类共享静态：不重置则「拒绝后不应更新文档」断言受类执行顺序污染
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentMetadataRepository.reset();
        MockDictValueValidator.reset();
        MockDocumentOutboxRepository.reset();
    }

    /** 版本覆盖成功：新版本号=元数据 version，旧版归档，新 author/remark 入列 */
    @Test
    void shouldOverrideVersionSuccess() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        String metadataJson = "{\"version\":\"3\",\"author\":\"newAuthor\",\"remark\":\"newRemark\"}";

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", metadataJson)
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.documentId", equalTo(DOC_ID))
                .body("data.title", equalTo("new-version"))
                .body("data.newVersion", equalTo("3"))
                .body("data.status", equalTo("DRAFT"));

        // 文档更新断言
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "文档应被更新");
        assertEquals("3", updated.getCurrentVersion(), "currentVersion 应为元数据 version");
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "覆盖后进入 DRAFT");
        assertEquals(DocumentEntity.PROCESS_PENDING, updated.getProcessStatus(), "processStatus 重置 PENDING");
        assertTrue(updated.getHasPublishedVersion(), "旧版保持可检索（has_published_version=TRUE）");
        assertEquals("2", updated.getPublishedVersion(), "publishedVersion 保持旧版号");
        assertEquals("newAuthor", updated.getAuthor(), "新 author 入列");
        assertEquals("newRemark", updated.getRemark(), "新 remark 入列");

        // 归档断言
        var archived = MockDocumentRepository.getArchivedVersion();
        assertNotNull(archived, "旧版应归档");
        assertEquals(DOC_ID, archived.documentId());
        assertEquals("2", archived.versionNumber(), "归档版本号 = 旧 currentVersion");
        assertTrue(archived.wasPublished(), "归档版本标记 was_published=TRUE");

        // 出站作废断言：覆盖后旧内容周期的 PENDING outbox 事件应被作废（防陈旧回调污染草稿）
        assertEquals(DOC_ID, MockDocumentOutboxRepository.getCancelledDocumentId(),
                "版本覆盖应作废该文档未发送的 outbox 事件");
        assertEquals(1, MockDocumentOutboxRepository.getCancelCount());
    }

    /** 处理周期守卫：PUBLISHED 但 PROCESSING（向量化中）→ A0508 拒绝覆盖 */
    @Test
    void shouldRejectOverrideWhileProcessing() {
        DocumentEntity processing = publishedDoc("2");
        processing = DocumentEntity.builder()
                .id(processing.getId()).kbId(processing.getKbId()).categoryId(processing.getCategoryId())
                .title(processing.getTitle()).status(processing.getStatus())
                .processStatus(DocumentEntity.PROCESS_PROCESSING)
                .currentVersion(processing.getCurrentVersion())
                .publishedVersion(processing.getPublishedVersion())
                .hasPublishedVersion(processing.getHasPublishedVersion())
                .filePath(processing.getFilePath()).fileSize(processing.getFileSize())
                .mimeType(processing.getMimeType()).docType(processing.getDocType())
                .createdBy(processing.getCreatedBy()).inheritParent(true)
                .createdAt(processing.getCreatedAt()).updatedAt(processing.getUpdatedAt())
                .build();
        MockDocumentRepository.setEntityById(processing);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档正在处理中，请等待向量化完成后再覆盖版本"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "处理中不允许覆盖，不应更新文档");
        assertNull(MockDocumentRepository.getArchivedVersion(), "不应归档旧版");
    }

    /** 处理周期守卫：PUBLISHED 但 PENDING（已发布未处理）→ A0508 拒绝覆盖 */
    @Test
    void shouldRejectOverrideWhilePending() {
        DocumentEntity pending = publishedDoc("2");
        pending = DocumentEntity.builder()
                .id(pending.getId()).kbId(pending.getKbId()).categoryId(pending.getCategoryId())
                .title(pending.getTitle()).status(pending.getStatus())
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion(pending.getCurrentVersion())
                .publishedVersion(pending.getPublishedVersion())
                .hasPublishedVersion(pending.getHasPublishedVersion())
                .filePath(pending.getFilePath()).fileSize(pending.getFileSize())
                .mimeType(pending.getMimeType()).docType(pending.getDocType())
                .createdBy(pending.getCreatedBy()).inheritParent(true)
                .createdAt(pending.getCreatedAt()).updatedAt(pending.getUpdatedAt())
                .build();
        MockDocumentRepository.setEntityById(pending);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档正在处理中，请等待向量化完成后再覆盖版本"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "未处理不允许覆盖");
    }

    /** 处理周期守卫：PUBLISHED 但 FAILED（向量化失败）→ A0508 拒绝覆盖，提示先处理失败 */
    @Test
    void shouldRejectOverrideWhileFailed() {
        DocumentEntity failed = publishedDoc("2");
        failed = DocumentEntity.builder()
                .id(failed.getId()).kbId(failed.getKbId()).categoryId(failed.getCategoryId())
                .title(failed.getTitle()).status(failed.getStatus())
                .processStatus(DocumentEntity.PROCESS_FAILED)
                .currentVersion(failed.getCurrentVersion())
                .publishedVersion(failed.getPublishedVersion())
                .hasPublishedVersion(failed.getHasPublishedVersion())
                .filePath(failed.getFilePath()).fileSize(failed.getFileSize())
                .mimeType(failed.getMimeType()).docType(failed.getDocType())
                .createdBy(failed.getCreatedBy()).inheritParent(true)
                .createdAt(failed.getCreatedAt()).updatedAt(failed.getUpdatedAt())
                .build();
        MockDocumentRepository.setEntityById(failed);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档向量化失败，请先重试处理或等待打回草稿后再覆盖版本"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "处理失败不允许覆盖");
    }

    /** 版本覆盖携带 dict 受控元数据 → 全量替换 document_metadata（version 不入） */
    @Test
    void shouldOverrideWithDictMetadata() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        String metadataJson = "{\"version\":\"3\",\"product\":\"Mate 40\","
                + "\"chip_model\":\"CV620\",\"sensitivity\":\"INTERNAL\","
                + "\"official\":\"OFFICIAL\"}";

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", metadataJson)
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.newVersion", equalTo("3"));

        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals("3", updated.getCurrentVersion(), "currentVersion 应为元数据 version");

        Map<String, String> meta = MockDocumentMetadataRepository.getLastMetadata();
        assertNotNull(meta, "应全量替换 document_metadata");
        assertEquals("Mate 40", meta.get("product"));
        assertEquals("CV620", meta.get("chip_model"));
        assertEquals("INTERNAL", meta.get("sensitivity"));
        assertEquals("OFFICIAL", meta.get("official"));
        assertFalse(meta.containsKey("version"), "version 不入 document_metadata");
    }

    /** 未传 metadata（version 缺失）→ 版本号必填，拒绝 A0420 */
    @Test
    void shouldFailWhenVersionMissing() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("版本号不能为空"));
    }

    /** 新版本号 == 当前已发布版 → 拒绝 A0701 */
    @Test
    void shouldFailWhenVersionAlreadyPublished() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", equalTo("版本已发布过，不允许重复: 2"));
    }

    /** 新版本号 == 已归档历史版 → 拒绝 A0701 */
    @Test
    void shouldFailWhenVersionArchived() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));
        MockDocumentRepository.setVersionById(new com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion(
                DOC_ID, "3", KB_ID + "/old-version.pdf",
                1000L, "application/pdf", Boolean.TRUE,
                com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_PUBLISHED,
                "admin", LocalDateTime.now()));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", equalTo("版本已发布过，不允许重复: 3"));
    }

    @Test
    void shouldFailWhenNotPublished() {
        MockDocumentRepository.setEntityById(draftDoc());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"));
    }

    @Test
    void shouldFailWhenDocumentNotFound() {
        MockDocumentRepository.setEntityById(null);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("文档不存在"));
    }

    @Test
    void shouldFailWhenDocumentBelongsToAnotherKb() {
        MockDocumentRepository.setEntityById(publishedDocOtherKb());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("文档不存在"));
    }

    @Test
    void shouldFailWhenTypeNotSupported() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "evil.exe", "exe-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", equalTo("不支持的文件类型: exe"));
    }

    /**
     * 同类型强校验（行为变更）：新文件扩展名须与源文档一致（源=old-version.pdf），
     * 跨类型覆盖（pdf→txt）拒绝 A0701 fileTypeChanged。
     */
    @Test
    void shouldFailWhenFileTypeChanged() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "new-version.txt", "txt-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("documentId", DOC_ID)
                .multiPart("metadata", "{\"version\":\"3\"}")
                .when().post("/kb/documents/versions")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", equalTo("文件类型不能变：原pdf，现txt"));

        assertNull(MockDocumentRepository.getUpdatedEntity(), "拒绝后不应更新文档");
    }

    // ======================== 辅助方法 ========================

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("版本覆盖测试库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .build();
    }

    private static DocumentEntity publishedDoc(String version) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId(KB_ID)
                .categoryId(null)
                .title("old-version")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion(version)
                .publishedVersion(version)
                .hasPublishedVersion(false)
                .filePath(KB_ID + "/old-version.pdf")
                .fileSize(1000L)
                .mimeType("application/pdf")
                .reviewedBy("admin")
                .reviewedAt(now.minusDays(1))
                .createdBy("admin")
                .createdAt(now.minusDays(2))
                .updatedAt(now.minusDays(1))
                .build();
    }

    private static DocumentEntity draftDoc() {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId(KB_ID)
                .categoryId(null)
                .title("draft-doc")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .filePath(KB_ID + "/draft-doc.pdf")
                .fileSize(500L)
                .mimeType("application/pdf")
                .createdBy("admin")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static DocumentEntity publishedDocOtherKb() {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId("kb-other")
                .categoryId(null)
                .title("other-kb-doc")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .publishedVersion("1")
                .filePath("kb-other/other.pdf")
                .fileSize(1000L)
                .mimeType("application/pdf")
                .createdBy("admin")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String token() {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }

    // ======================== Mock ========================

    static class MockDocumentMetadataRepository extends DocumentMetadataRepositoryImpl {
        private static volatile String lastDocumentId;
        private static volatile Map<String, String> lastMetadata;

        MockDocumentMetadataRepository() {
            super(null);
        }

        static void reset() {
            lastDocumentId = null;
            lastMetadata = null;
        }

        static String getLastDocumentId() { return lastDocumentId; }
        static Map<String, String> getLastMetadata() { return lastMetadata; }

        @Override
        public io.smallrye.mutiny.Uni<Void> replaceAll(String documentId, Map<String, String> metadata) {
            lastDocumentId = documentId;
            lastMetadata = metadata;
            return io.smallrye.mutiny.Uni.createFrom().voidItem();
        }
    }
}
