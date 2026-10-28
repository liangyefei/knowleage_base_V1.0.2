package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量物理删除端点测试（POST /kb/documents/batch-delete，仅版本级）
 * <p>护栏语义：
 * <ul>
 *   <li>items 为空 → A0420；任一条目缺 version → A0420 整批拒绝（不静默部分执行）</li>
 *   <li>上限 200 条超限抛 A0420，HTTP 层始终 200</li>
 *   <li>版本不存在/指向当前版 → SKIPPED(A0430)；单条执行异常 → FAILED(B0001) 不影响其余</li>
 *   <li>整体恒为 code=0（部分失败也成功返回）；结果带 documentId+version</li>
 * </ul>
 */
@QuarkusTest
class DocumentResourceBatchDeleteTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

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

    // ======================== 参数校验（fail-closed 护栏） ========================

    @Test
    void shouldRejectEmptyItems() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("文档ID列表不能为空"));

        assertTrue(MockBatchDeleteDocumentRepository.getDeletedVersionKeys().isEmpty(), "不应执行任何删除");
    }

    @Test
    void shouldRejectItemWithoutVersion() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[{\"documentId\":\"doc-1\",\"version\":\"v1.0\"}," +
                        "{\"documentId\":\"doc-2\"}]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("批量删除仅支持版本级操作，每个条目必须携带 version 参数"));

        assertTrue(MockBatchDeleteDocumentRepository.getDeletedVersionKeys().isEmpty(),
                "缺 version 整批拒绝，不应执行任何删除");
    }

    @Test
    void shouldRejectWhenExceeds200() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":" + itemsArray(201) + "}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("批量删除数量超过限制，上限200"));

        assertTrue(MockBatchDeleteDocumentRepository.getDeletedVersionKeys().isEmpty(),
                "超限整批拒绝，不应执行任何删除");
    }

    // ======================== 版本级删除成功 ========================

    @Test
    void shouldDeleteVersionLevelSuccess() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null)));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[{\"documentId\":\"doc-x\",\"version\":\"v1.0\"}]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(1))
                .body("data.summary.deleted", equalTo(1))
                .body("data.summary.skipped", equalTo(0))
                .body("data.summary.failed", equalTo(0))
                .body("data.results[0].documentId", equalTo("doc-x"))
                .body("data.results[0].version", equalTo("v1.0"))
                .body("data.results[0].status", equalTo("DELETED"));

        assertEquals(List.of("doc-x|v1.0"), MockBatchDeleteDocumentRepository.getDeletedVersionKeys());
    }

    // ======================== 混合结果（SKIPPED 不影响其余） ========================

    @Test
    void shouldSkipMissingVersionAndContinueOthers() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null)));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[{\"documentId\":\"doc-x\",\"version\":\"v1.0\"}," +
                        "{\"documentId\":\"doc-x\",\"version\":\"v9.9\"}]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(2))
                .body("data.summary.deleted", equalTo(1))
                .body("data.summary.skipped", equalTo(1))
                .body("data.results[0].status", equalTo("DELETED"))
                .body("data.results[1].status", equalTo("SKIPPED"))
                .body("data.results[1].code", equalTo("A0430"))
                .body("data.results[1].version", equalTo("v9.9"));
    }

    // ======================== FAILED 不中断整批 ========================

    @Test
    void shouldMarkFailedWithoutBlockingOthers() {
        MockBatchDeleteDocumentRepository.setSingleEntity(draftDoc("doc-x"));
        MockBatchDeleteDocumentRepository.setVersionsByKey(Map.of(
                "doc-x|v1.0", version("doc-x", "v1.0", null),
                "doc-x|v2.0", version("doc-x", "v2.0", null)));
        MockBatchDeleteDocumentRepository.setFailDeleteVersionKeys(java.util.Set.of("doc-x|v1.0"));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[{\"documentId\":\"doc-x\",\"version\":\"v1.0\"}," +
                        "{\"documentId\":\"doc-x\",\"version\":\"v2.0\"}]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(2))
                .body("data.summary.deleted", equalTo(1))
                .body("data.summary.skipped", equalTo(0))
                .body("data.summary.failed", equalTo(1))
                .body("data.results[0].status", equalTo("FAILED"))
                .body("data.results[0].code", equalTo("B0001"))
                .body("data.results[1].status", equalTo("DELETED"));
    }

    // ======================== 辅助方法 ========================

    /** 生成 [{"documentId":"doc-0","version":"v1.0"},...] 共 count 条的 JSON 数组字符串 */
    private static String itemsArray(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"documentId\":\"doc-").append(i).append("\",\"version\":\"v1.0\"}");
        }
        return sb.append(']').toString();
    }

    /** DRAFT 状态文档 */
    private static DocumentEntity draftDoc(String id) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(id))
                .kbId("kb-1")
                .title(id)
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                // 当前版本与被删版本错开：批量删除为历史版本级路径（deleteVersion mock 可观测/可注入失败）
                .currentVersion("v0.9")
                .hasPublishedVersion(false)
                .filePath("kb-1/" + id + ".txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static DocumentVersion version(String docId, String versionNumber, String filePath) {
        return new DocumentVersion(docId, versionNumber, filePath, 100L,
                "application/pdf", Boolean.TRUE,
                DocumentVersion.STATUS_PUBLISHED, "admin", LocalDateTime.now());
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
