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
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量提交审核端点测试（KB / CATEGORY 递归子分类 / DOCUMENT 三种范围 + 非 DRAFT 跳过 + 参数校验）。
 * <p>对齐 {@link DocumentResourceBatchReviewTest}，关键区别：
 * <ul>
 *   <li>成功后状态 REVIEWING（非 PUBLISHED/DRAFT）</li>
 *   <li><b>不发 outbox</b>（insert 次数 = 0，与 batch-review approve 写 outbox 区分）</li>
 *   <li>非 DRAFT 文档记 SKIPPED（非"非审核中"）</li>
 *   <li>请求体无 action / reason 字段</li>
 * </ul>
 */
@QuarkusTest
class DocumentResourceBatchSubmitReviewTest {

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
        MockDocumentRepository.setDraftByKbId(null);
        MockDocumentRepository.setDraftByCategoryIds(null);
        // lastDraftCategoryIds 不需重置（按用例断言）

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockKnowledgeBaseRepository.resetUpdatedEntity();

        MockCategoryRepository.setAllCategories(null);
        MockCategoryRepository.setEntityById(null);

        MockDocumentOutboxRepository.reset();
        MockReviewHistoryRepository.reset();
    }

    // ======================== KB 范围 ========================

    @Test
    void shouldBatchSubmitByKb() {
        MockDocumentRepository.setDraftByKbId(List.of(
                draftDoc("doc-1", "cat-a", "需求文档"),
                draftDoc("doc-2", null, "接口文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"KB\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(2))
                .body("data.summary.success", equalTo(2))
                .body("data.summary.skipped", equalTo(0));

        // 关键区别：批量提交审核不发 outbox（submitReview 不触发模型处理）
        assertEquals(0, MockDocumentOutboxRepository.getInsertCount(),
                "批量提交审核不应发 outbox 消息");
        DocumentEntity last = MockDocumentRepository.getUpdatedEntity();
        assertEquals(DocumentEntity.STATUS_REVIEWING, last.getStatus(), "最后一条应置为 REVIEWING");
        // reviewHistory 断言：2 个 DRAFT 文档各写一条 SUBMIT_REVIEW 历史
        assertEquals(2, MockReviewHistoryRepository.getSaveCount(),
                "两条 DRAFT 应各写一条 SUBMIT_REVIEW 历史");
        assertEquals("SUBMIT_REVIEW", MockReviewHistoryRepository.getLastSavedAction(),
                "动作应为 SUBMIT_REVIEW");
    }

    // ======================== CATEGORY 范围（递归子分类）========================

    @Test
    void shouldBatchSubmitByCategoryRecursingSubcategories() {
        // 树：root-1 → cat-a → cat-a-1
        MockCategoryRepository.setAllCategories(List.of(
                cat("root-1", null), cat("cat-a", "root-1"), cat("cat-a-1", "cat-a")));
        // 命中分类下的 DRAFT 文档（categoryId=cat-a-1，是选中 cat-a 的后代）
        MockDocumentRepository.setDraftByCategoryIds(
                List.of(draftDoc("doc-1", "cat-a-1", "子分类文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"CATEGORY\",\"kbId\":\"" + KB_ID + "\","
                        + "\"categoryIds\":[\"cat-a\"]}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.success", equalTo(1));

        // 递归断言：传给 findDraftByCategoryIds 的分类集合应包含后代 cat-a-1（不仅是选中的 cat-a）
        java.util.Collection<String> arg = MockDocumentRepository.getLastDraftCategoryIds();
        assertNotNull(arg, "应调用 findDraftByCategoryIds");
        assertTrue(arg.contains("cat-a"), "分类集合应包含选中的 cat-a");
        assertTrue(arg.contains("cat-a-1"), "分类集合应递归包含后代 cat-a-1");
        assertEquals(0, MockDocumentOutboxRepository.getInsertCount(), "批量提交审核不发 outbox 消息");
    }

    @Test
    void shouldFailBatchWhenCategoryNotInKb() {
        MockCategoryRepository.setAllCategories(List.of(cat("cat-a", "root-1")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"CATEGORY\",\"kbId\":\"" + KB_ID + "\","
                        + "\"categoryIds\":[\"cat-x\"]}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("类别不存在"));
    }

    // ======================== DOCUMENT 范围（混合状态 + 跨库 + 不存在）========================

    @Test
    void shouldBatchSubmitDocumentsWithMixedStates() {
        // doc-1 DRAFT 同库 → SUCCESS
        // doc-2 REVIEWING 同库 → SKIPPED（非草稿）
        // doc-3 DRAFT 跨库 → SKIPPED（不属于该 KB）
        // doc-4 不存在 → SKIPPED（文档不存在）
        MockDocumentRepository.setDocsByIds(List.of(
                draftDoc("doc-1", "cat-a", "草稿文档"),
                doc("doc-2", "cat-a", DocumentEntity.STATUS_REVIEWING, KB_ID),
                doc("doc-3", "cat-a", DocumentEntity.STATUS_DRAFT, "kb-other")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"kbId\":\"" + KB_ID + "\","
                        + "\"documentIds\":[\"doc-1\",\"doc-2\",\"doc-3\",\"doc-4\"]}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(4))
                .body("data.summary.success", equalTo(1))
                .body("data.summary.skipped", equalTo(3));

        assertEquals(0, MockDocumentOutboxRepository.getInsertCount(), "批量提交审核不发 outbox 消息");
        // reviewHistory 断言：仅 1 个 DRAFT 文档成功，只写 1 条 SUBMIT_REVIEW 历史
        assertEquals(1, MockReviewHistoryRepository.getSaveCount(),
                "仅同库 DRAFT 文档写 SUBMIT_REVIEW 历史");
    }

    @Test
    void shouldTruncateWhenDocumentExceedsLimit() {
        // DOCUMENT 1250 ids（全存在 DRAFT），超过 BATCH_REVIEW_MAX_PER_RUN(1000) 只审前 1000，remainingCount=250
        java.util.List<DocumentEntity> docs = new java.util.ArrayList<>();
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < 1250; i++) {
            docs.add(draftDoc("doc-" + i, null, "草稿" + i));
            if (i > 0) ids.append(',');
            ids.append("\"doc-").append(i).append('"');
        }
        ids.append(']');
        MockDocumentRepository.setDocsByIds(docs);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"kbId\":\"" + KB_ID + "\",\"documentIds\":" + ids + "}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.summary.total", equalTo(1000))
                .body("data.summary.success", equalTo(1000))
                .body("data.summary.remainingCount", equalTo(250));

        assertEquals(1000, MockReviewHistoryRepository.getSaveCount(),
                "1000 条提交各写一条 SUBMIT_REVIEW reviewHistory");
    }

    // ======================== 参数校验失败 ========================

    @Test
    void shouldFailBatchWhenKbIdMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"scope\":\"DOCUMENT\",\"documentIds\":[\"doc-1\"]}")
                .when().post("/kb/documents/batch-submit-review")
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
                .body("{\"scope\":\"DOCUMENT\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-submit-review")
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
                .body("{\"scope\":\"WHAT\",\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/documents/batch-submit-review")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("批量提交审核范围不合法，只能为KB、CATEGORY或DOCUMENT"));
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

    /** DRAFT 状态文档（同 KB_ID） */
    private static DocumentEntity draftDoc(String id, String categoryId, String title) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(id))
                .kbId(KB_ID)
                .categoryId(categoryId)
                .title(title)
                .status(DocumentEntity.STATUS_DRAFT)
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
