package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档下载测试（POST /kb/documents/download）
 * <p>全部生命周期状态可下载；version 空 = 最新版，非空 = 历史版本；版本不存在 A0430。</p>
 */
@QuarkusTest
class DocumentResourceDownloadTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    private static final byte[] FILE_BYTES = "pdf-binary-content-测试".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(
                new MockFileStorageService(), fileStorageService);
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentRepository.resetDeletedDocumentId();
        MockDocumentRepository.setVersionById(null);
        MockFileStorageService.reset();
        MockFileStorageService.setReadContent(FILE_BYTES);
    }

    @Test
    void shouldDownloadLatestVersionForPublished() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));

        byte[] result = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", containsString("attachment; filename*=UTF-8''"))
                .extract().asByteArray();
        assertTrue(java.util.Arrays.equals(FILE_BYTES, result), "下载内容应与文件一致");

        // 最新版不走版本表
        assertEquals("kb-1/测试文档.pdf", MockFileStorageService.getLastReadPath());
    }

    @Test
    void shouldDownloadForDraft() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT));

        byte[] result = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(java.util.Arrays.equals(FILE_BYTES, result), "下载内容应与文件一致");
    }

    @Test
    void shouldDownloadForDeprecated() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DEPRECATED));

        byte[] result = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(java.util.Arrays.equals(FILE_BYTES, result), "下载内容应与文件一致");
    }

    @Test
    void shouldDownloadHistoricalVersion() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));
        MockDocumentRepository.setVersionById(new DocumentVersion(
                "doc-1", "1", "kb-1/历史版.txt", 50L, "text/plain", true,
                DocumentVersion.STATUS_PUBLISHED, "editor", LocalDateTime.now()));

        byte[] result = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType("text/plain")
                .header("Content-Disposition", containsString("attachment"))
                .extract().asByteArray();
        assertTrue(java.util.Arrays.equals(FILE_BYTES, result), "下载内容应与文件一致");

        // 历史版走版本表路径
        assertEquals("kb-1/历史版.txt", MockFileStorageService.getLastReadPath());
    }

    @Test
    void shouldReturnNotFoundForMissingVersion() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));
        MockDocumentRepository.setVersionById(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"9\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", org.hamcrest.Matchers.equalTo("A0430"))
                .body("message", containsString("版本不存在"));
    }

    @Test
    void shouldReturnNotFoundForMissingDocument() {
        MockDocumentRepository.setEntityById(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-missing\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", org.hamcrest.Matchers.equalTo("A0430"))
                .body("message", containsString("不存在"));
    }

    @Test
    void shouldReturnNotFoundForUnknownVersion() {
        // version 现为 varchar 自由文本：未知版本号查版本表未命中 → A0430（不再 @Min 数值校验）
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));
        MockDocumentRepository.setVersionById(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", org.hamcrest.Matchers.equalTo("A0430"))
                .body("message", containsString("版本不存在"));
    }

    @Test
    void shouldReturnReadErrorWhenFileMissing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));
        MockFileStorageService.setFailRead(true);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", org.hamcrest.Matchers.equalTo("A0706"));
    }

    // ======================== helpers ========================

    /** 构建指定状态的最小文档实体 */
    private static DocumentEntity document(String status) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .title("测试文档.pdf")
                .status(status)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("3")
                .publishedVersion(DocumentEntity.STATUS_PUBLISHED.equals(status) ? "3" : null)
                .hasPublishedVersion(false)
                .filePath("kb-1/测试文档.pdf")
                .fileSize((long) FILE_BYTES.length)
                .mimeType("application/pdf")
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
