package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量审核端点测试（KB / CATEGORY 递归子分类 / DOCUMENT 三种范围 + 通过/驳回 + 跳过汇总）。
 * <p>Mock 基建对齐 {@link DocumentResourceLifecycleTest}：知识库 / 文档 / 分类 / outbox 均用手动 Mock 替换。</p>
 */
@QuarkusTest
class DocumentResourceBatchReviewTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = "kb-1";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    ReviewHistoryRepository reviewHistoryRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockReviewHistoryRepository(), reviewHistoryRepository);

        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentRepository.setReviewingByKbId(null);
        MockDocumentRepository.setReviewingByCategoryIds(null);
        MockDocumentRepository.setDocsByIds(null);
        // lastReviewingCategoryIds 不需重置（按用例断言）

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockKnowledgeBaseRepository.resetUpdatedEntity();

        MockCategoryRepository.setAllCategories(null);
        MockCategoryRepository.setEntityById(null);

        MockDocumentOutboxRepository.reset();
        MockReviewHistoryRepository.reset();
    }

    // ======================== KB 范围 ========================

    @Test
    void shouldBatchApproveByKb() {
        MockDocumentRepository.setReviewingByKbId(List.of(
                reviewingDoc("doc-1", "cat-a", "需求文档"),
                reviewingDoc("doc-2", null, "接口文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(2))
                .body("data.summary.success", equalTo(2))
                .body("data.summary.skipped", equalTo(0));

        assertEquals(2, MockDocumentOutboxRepository.getInsertCount(),
                "两条 approve 应各发一条 DOCUMENT_PUBLISHED outbox 消息");
        assertEquals("DOCUMENT_PUBLISHED", MockDocumentOutboxRepository.getInsertedEventType());
        DocumentEntity last = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_PUBLISHED, last.getStatus(), "最后一条应置为 PUBLISHED");
        assertEquals(OPERATOR, last.getReviewedBy(), "审核人应为操作员");
    }

    @Test
    void shouldTruncateWhenKbExceedsLimit() {
        // Mock 1250 条 REVIEWING 文档，超过 BATCH_REVIEW_MAX_PER_RUN(1000) 只审前 1000，remainingCount=250
        //（同时验证顺序处理 1000 条不栈溢出——曾因递归 flatMap 深度随条数增长而 StackOverflowError）
        List<DocumentEntity> docs = new ArrayList<>();
        for (int i = 0; i < 1250; i++) {
            docs.add(reviewingDoc("doc-" + i, null, "文档" + i));
        }
        MockDocumentRepository.setReviewingByKbId(docs);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(1000))
                .body("data.summary.success", equalTo(1000))
                .body("data.summary.remainingCount", equalTo(250));

        assertEquals(1000, MockDocumentOutboxRepository.getInsertCount(), "仅审 1000 条 → 1000 outbox");
    }

    @Test
    void shouldBatchRejectByKb() {
        MockDocumentRepository.setReviewingByKbId(List.of(reviewingDoc("doc-1", "cat-a", "需求文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"action\":\"REJECT\",\"kbId\":\"" + KB_ID + "\","
                        + "\"reason\":\"芯片型号标签缺失\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.success", equalTo(1));

        assertEquals(0, MockDocumentOutboxRepository.getInsertCount(), "驳回不发 outbox 消息");
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus(), "驳回应回 DRAFT");
        assertEquals("芯片型号标签缺失", updated.getReviewComment(), "应记录驳回原因");
    }

    // ======================== CATEGORY 范围（递归子分类）========================

    @Test
    void shouldBatchApproveByCategoryRecursingSubcategories() {
        // 树：root-1 → cat-a → cat-a-1
        MockCategoryRepository.setAllCategories(List.of(
                cat("root-1", null), cat("cat-a", "root-1"), cat("cat-a-1", "cat-a")));
        // 命中分类下的 REVIEWING 文档（categoryId=cat-a-1，是选中 cat-a 的后代）
        MockDocumentRepository.setReviewingByCategoryIds(
                List.of(reviewingDoc("doc-1", "cat-a-1", "子分类文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"CATEGORY\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\","
                        + "\"categoryIds\":[\"cat-a\"]}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.success", equalTo(1));

        // 递归断言：传给 findReviewingByCategoryIds 的分类集合应包含后代 cat-a-1（不仅是选中的 cat-a）
        java.util.Collection<String> arg = MockDocumentRepository.getLastReviewingCategoryIds();
        assertNotNull(arg, "应调用 findReviewingByCategoryIds");
        assertTrue(arg.contains("cat-a"), "分类集合应包含选中的 cat-a");
        assertTrue(arg.contains("cat-a-1"), "分类集合应递归包含后代 cat-a-1");
        assertEquals(1, MockDocumentOutboxRepository.getInsertCount(), "应发一条 outbox 消息");
    }

    @Test
    void shouldFailBatchWhenCategoryNotInKb() {
        MockCategoryRepository.setAllCategories(List.of(cat("cat-a", "root-1")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"CATEGORY\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\","
                        + "\"categoryIds\":[\"cat-x\"]}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("类别不存在"));
    }

    // ======================== DOCUMENT 范围（混合状态 + 跨库 + 不存在）========================

    @Test
    void shouldBatchReviewDocumentsWithMixedStates() {
        // doc-1 REVIEWING 同库 → SUCCESS
        // doc-2 PUBLISHED 同库 → SKIPPED（非审核中）
        // doc-3 REVIEWING 跨库 → SKIPPED（不属于该 KB）
        // doc-4 不存在 → SKIPPED（文档不存在）
        MockDocumentRepository.setDocsByIds(List.of(
                reviewingDoc("doc-1", "cat-a", "审核中文档"),
                doc("doc-2", "cat-a", DocumentEntity.STATUS_PUBLISHED, KB_ID),
                doc("doc-3", "cat-a", DocumentEntity.STATUS_REVIEWING, "kb-other")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\","
                        + "\"documentIds\":[\"doc-1\",\"doc-2\",\"doc-3\",\"doc-4\"]}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(4))
                .body("data.summary.success", equalTo(1))
                .body("data.summary.skipped", equalTo(3));

        assertEquals(1, MockDocumentOutboxRepository.getInsertCount(), "仅同库 REVIEWING 文档触发 outbox");
    }

    // ======================== 参数校验失败 ========================

    @Test
    void shouldFailBatchRejectWithoutReason() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"action\":\"REJECT\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("驳回原因不能为空"));
    }

    @Test
    void shouldFailBatchWhenKbIdMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"action\":\"APPROVE\",\"documentIds\":[\"doc-1\"]}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("知识库ID不能为空"));
    }

    @Test
    void shouldFailBatchWhenDocumentIdsMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("文档ID列表不能为空"));
    }

    @Test
    void shouldFailBatchWhenScopeInvalid() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"WHAT\",\"action\":\"APPROVE\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("批量审核范围不合法，只能为KB、CATEGORY或DOCUMENT"));
    }

    // ======================== 辅助方法 ========================

    /** 构建指定状态的最小文档实体 */
    private static DocumentEntity doc(String id, String categoryId, String status, String kbId) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(id))
                .kbId(kbId)
                .categoryId(categoryId)
                .title(id)
                .status(status)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath(kbId + "/" + id + ".txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** REVIEWING 状态文档（同 KB_ID） */
    private static DocumentEntity reviewingDoc(String id, String categoryId, String title) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(id))
                .kbId(KB_ID)
                .categoryId(categoryId)
                .title(title)
                .status(DocumentEntity.STATUS_REVIEWING)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath(KB_ID + "/" + id + ".txt")
                .fileSize(1024L)
                .mimeType("text/plain")
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

    /** 构建活跃分类 */
    private static CategoryEntity cat(String id, String parentId) {
        return CategoryEntity.builder()
                .id(CategoryId.of(id))
                .kbId(KB_ID)
                .parentId(parentId)
                .name(id)
                .sortOrder(0)
                .inheritParent(true)
                .status(CategoryEntity.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
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
