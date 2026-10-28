package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @QuarkusTest} for {@code GET /kb/documents/chunks}（按 documentId 调模型 get_doc_by_filename）。
 *
 * <p>Mock {@link DocumentRepository}（ findById 返回静态实体或 null）+ {@link MockDocumentChunkClient}
 * （ @ApplicationScoped 测试侧唯一实现，控制切片返回/抛失败）。无 DB 访问。</p>
 *
 * <p>Token note：端点仅字符串级解析 JWT payload（不验签），故手造三段式 token。</p>
 */
@QuarkusTest
class DocumentChunkResourceTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    private static final String KB_ID = "kbfdb204ede8a344f481a922bfb7b2af7b";
    private static final String RELATIVE_PATH = "kbfdb204ede8a344f481a922bfb7b2af7b/Amlogic MBOX现场分析指南.pdf";

    @Inject
    DocumentRepository documentRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        MockDocumentRepository.setEntityById(null);
        MockDocumentChunkClient.reset();
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldReturnChunks() {
        MockDocumentRepository.setEntityById(document());
        MockDocumentChunkClient.setChunks(List.of(
                new DocChunk("Amlogic MBOX现场分析指南.pdf", "第一段切片内容", null),
                new DocChunk("Amlogic MBOX现场分析指南.pdf", "第二段切片内容", null)));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-1")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data", hasSize(2))
                .body("data[0].file_name", equalTo("Amlogic MBOX现场分析指南.pdf"))
                .body("data[0].text", equalTo("第一段切片内容"));

        // 验证透传到模型：collection_name=kbId，filename=/kb-files/+相对 filePath
        assertEquals(KB_ID, MockDocumentChunkClient.getLastCollectionName());
        assertEquals("/kb-files/" + RELATIVE_PATH, MockDocumentChunkClient.getLastFullFilename());
    }

    @Test
    void shouldStripLeadingSlashInFilePath() {
        // filePath 带首位 / → 拼接结果不应出现双斜杠
        MockDocumentRepository.setEntityById(DocumentEntity.builder()
                .id(DocumentId.of("doc-2")).kbId(KB_ID).title("t").filePath("/" + RELATIVE_PATH).build());
        MockDocumentChunkClient.setChunks(List.of());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-2")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("/kb-files/" + RELATIVE_PATH, MockDocumentChunkClient.getLastFullFilename());
    }

    // ======================== 文档不存在 ========================

    @Test
    void shouldFailWhenDocNotFound() {
        MockDocumentRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "missing")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("文档不存在"))
                .body("data", nullValue());
    }

    @Test
    void shouldFailWhenDocNotFoundEn() {
        MockDocumentRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .queryParam("documentId", "missing")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("Document not found"));
    }

    // ======================== 模型调用失败 ========================

    @Test
    void shouldFailWhenModelError() {
        MockDocumentRepository.setEntityById(document());
        MockDocumentChunkClient.setThrowOnGet();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-1")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"))
                .body("message", containsString("模型服务调用失败，请稍后重试"))
                .body("data", nullValue());
    }

    @Test
    void shouldFailWhenModelErrorEn() {
        MockDocumentRepository.setEntityById(document());
        MockDocumentChunkClient.setThrowOnGet();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .queryParam("documentId", "doc-1")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"))
                .body("message", containsString("Model service unavailable, please retry later"));
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoAuthHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-1")
                .when().get("/kb/documents/chunks")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 辅助方法 ========================

    private DocumentEntity document() {
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1")).kbId(KB_ID).title("Amlogic MBOX现场分析指南")
                .filePath(RELATIVE_PATH).build();
    }

    private static String buildToken(String sub, long expSeconds) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + sub + "\",\"exp\":" + expSeconds + "}");
        return header + "." + payload + ".sig";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
