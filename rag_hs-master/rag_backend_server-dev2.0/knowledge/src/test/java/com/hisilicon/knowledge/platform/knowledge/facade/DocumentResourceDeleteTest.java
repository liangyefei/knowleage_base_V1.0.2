package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文档物理删除测试（POST /kb/documents/delete，<b>仅版本级</b>且<b>仅 DRAFT 可删</b>）
 * <p>生命周期守卫：仅 DRAFT 状态的文档可删除（当前版与历史版本行同等约束）；
 * REVIEWING/PUBLISHED/ARCHIVED/DEPRECATED 一律 A0508「仅草稿状态的文档可删除」。
 * version 必填（blank → 400 参数校验）。删除不通知模型——向量清理由
 * deprecate / 版本级下线时的 DOCUMENT_DELETED 事件负责。</p>
 */
@QuarkusTest
class DocumentResourceDeleteTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    ReviewHistoryRepository reviewHistoryRepository;

    @Inject
    FileStorageService fileStorageService;

    @Inject
    DocumentCommandService documentCommandService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(
                new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockReviewHistoryRepository(), reviewHistoryRepository);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentRepository.resetDeletedDocumentId();
        MockDocumentOutboxRepository.reset();
        MockReviewHistoryRepository.reset();
        MockFileStorageService.reset();
    }

    @Test
    void shouldDeleteDraftCurrentVersionWithoutNotify() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT, DocumentEntity.PROCESS_PENDING));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("0"));

        // 删除当前版发 DOCUMENT_DELETED（清当前版向量）；历史版路径不发（向量已随版本下线清理）
        assertNotNull(MockDocumentOutboxRepository.getInsertedDocumentId());
    }

    @Test
    void shouldRejectReviewingDelete() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING, DocumentEntity.PROCESS_PENDING));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("仅草稿"));

        // 未执行删除、未通知
        assertNull(MockDocumentRepository.getDeletedDocumentId());
        assertNull(MockDocumentOutboxRepository.getInsertedDocumentId());
    }

    @Test
    void shouldRejectPublishedDelete() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("仅草稿"));

        assertNull(MockDocumentRepository.getDeletedDocumentId());
        assertNull(MockDocumentOutboxRepository.getInsertedDocumentId());
    }

    @Test
    void shouldRejectArchivedDelete() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_ARCHIVED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("仅草稿"));

        assertNull(MockDocumentRepository.getDeletedDocumentId());
        assertNull(MockDocumentOutboxRepository.getInsertedDocumentId());
    }

    @Test
    void shouldRejectDeprecatedDelete() {
        // 生命周期收紧：DEPRECATED 亦不可物理删除（仅 DRAFT），下线请走版本级 deprecate
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DEPRECATED, DocumentEntity.PROCESS_COMPLETED));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("仅草稿"));

        assertNull(MockDocumentRepository.getDeletedDocumentId());
        assertNull(MockDocumentOutboxRepository.getInsertedDocumentId());
    }

    @Test
    void shouldReturnNotFoundForMissingDocument() {
        MockDocumentRepository.setEntityById(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-missing\",\"version\":\"1\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("不存在"));

        assertNull(MockDocumentRepository.getDeletedDocumentId());
    }

    @Test
    void shouldRejectBlankVersion() {
        // 仅版本级删除：version 必填（@NotBlank → 400）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\" \"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(400);
    }

    @Test
    void shouldRejectMissingBody() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .when().post("/kb/documents/delete")
                .then().statusCode(400);
    }

    // ======================== helpers ========================

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

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
