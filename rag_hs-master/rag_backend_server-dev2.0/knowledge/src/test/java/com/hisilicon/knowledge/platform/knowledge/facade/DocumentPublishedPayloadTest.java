package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DOCUMENT_PUBLISHED payload 字段回归测试（file_version_label / tags）。
 * <p>覆盖三条 approve 路径：单条 approve、批量 approve、编辑覆盖（版本覆盖后重新发布）approve。
 * 断言点：</p>
 * <ul>
 *   <li>{@code file_version_label} 恒等于实体的 current_version；</li>
 *   <li>{@code tags} 为逗号串拆出的去空白列表，空项剔除；未打标签时<b>省略</b>该字段；</li>
 *   <li>覆盖路径附带 {@code isVersionUpdate=true} 与 {@code previousVersion}（沿用既有语义）。</li>
 * </ul>
 * <p>Mock 基建对齐 {@link DocumentResourceLifecycleTest}。</p>
 */
@QuarkusTest
class DocumentPublishedPayloadTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = "kb-1";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    ReviewHistoryRepository reviewHistoryRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockReviewHistoryRepository(), reviewHistoryRepository);

        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentRepository.setReviewingByKbId(null);
        MockDocumentRepository.setDocsByIds(null);
        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockKnowledgeBaseRepository.resetUpdatedEntity();
        MockDocumentOutboxRepository.reset();
        MockReviewHistoryRepository.reset();
    }

    // ======================== 单条 approve ========================

    @Test
    void singleApprovePayloadShouldContainFileVersionLabelAndTags() {
        // tags 含空项与空白：应 trim 后剔除空项；首次发布（无历史发布版）
        MockDocumentRepository.setEntityById(
                reviewingDoc("1", false, "GPIO, UART , ,Kirin"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        Map<String, Object> payload = MockDocumentOutboxRepository.getInsertedPayload();
        assertEquals("DOCUMENT_PUBLISHED", MockDocumentOutboxRepository.getInsertedEventType());
        assertEquals("1", payload.get("file_version_label"),
                "file_version_label 应取 current_version");
        assertEquals(List.of("GPIO", "UART", "Kirin"), payload.get("tags"),
                "tags 应为逗号串拆出的去空白列表");
        assertFalse(payload.containsKey("previousVersion"), "首次发布不应有 previousVersion");
        assertEquals(Boolean.FALSE, payload.get("isVersionUpdate"));
    }

    @Test
    void singleApproveWithoutTagsShouldOmitTagsField() {
        MockDocumentRepository.setEntityById(reviewingDoc("1", false, null));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        Map<String, Object> payload = MockDocumentOutboxRepository.getInsertedPayload();
        assertEquals("1", payload.get("file_version_label"));
        assertFalse(payload.containsKey("tags"), "未打标签时应省略 tags 字段");
    }

    // ======================== 批量 approve ========================

    @Test
    void batchApprovePayloadShouldContainFileVersionLabelAndTags() {
        MockDocumentRepository.setReviewingByKbId(List.of(
                reviewingDoc("1", true, "GPIO,UART")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.success", equalTo(1));

        assertEquals(1, MockDocumentOutboxRepository.getInsertCount());
        Map<String, Object> payload = MockDocumentOutboxRepository.getInsertedPayload();
        assertEquals("1", payload.get("file_version_label"));
        assertEquals(List.of("GPIO", "UART"), payload.get("tags"));
    }

    // ======================== 编辑覆盖（版本覆盖后重新发布）approve ========================

    @Test
    void overwriteApprovePayloadShouldCarryFileVersionLabelTagsAndPreviousVersion() {
        // hasPublishedVersion=TRUE + publishedVersion=1 → approve 后 payload 应标记覆盖语义
        MockDocumentRepository.setEntityById(reviewingDoc("2", true, " GPIO , "));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-1\"}")
                .when().post("/kb/documents/approve")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        Map<String, Object> payload = MockDocumentOutboxRepository.getInsertedPayload();
        assertEquals("2", payload.get("file_version_label"),
                "覆盖发布时 file_version_label 应为新版本号");
        assertEquals("1", payload.get("previousVersion"),
                "覆盖发布应携带被覆盖的上一版版本号");
        assertEquals(Boolean.TRUE, payload.get("isVersionUpdate"));
        assertEquals(List.of("GPIO"), payload.get("tags"),
                "标签串中空白与空项应剔除，仅保留有效标签");
    }

    // ======================== 辅助方法 ========================

    /**
     * REVIEWING 状态文档。
     *
     * @param version            current_version
     * @param hasPublishedVersion 是否存在历史发布版（TRUE = 覆盖发布路径）
     * @param tags               逗号分隔标签串（可为 null）
     */
    private static DocumentEntity reviewingDoc(String version, boolean hasPublishedVersion, String tags) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId(KB_ID)
                .title("测试文档")
                .status(DocumentEntity.STATUS_REVIEWING)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion(version)
                .publishedVersion(hasPublishedVersion ? "1" : null)
                .hasPublishedVersion(hasPublishedVersion)
                .filePath(KB_ID + "/doc-1.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .tags(tags)
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 构建最小知识库实体（活跃） */
    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("测试知识库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
