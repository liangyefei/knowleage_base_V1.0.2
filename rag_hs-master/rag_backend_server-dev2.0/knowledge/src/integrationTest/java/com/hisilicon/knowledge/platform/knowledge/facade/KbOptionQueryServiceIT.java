package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * 集成测试：{@code GET /kb/options} 可检索知识库下拉选项（真实 PostgreSQL）。
 *
 * <p>验证约束组合：仅返回 {@code kb.status=1} 且 {@code kb.processStatus='COMPLETED'}，
 * 且其下至少存在一篇 {@code status='PUBLISHED'} 且 {@code processStatus='COMPLETED'}
 * 文档的知识库；按 {@code created_at} 升序。</p>
 * <p>种子（created_at 升序）：</p>
 * <ul>
 *   <li>kb-1 COMPLETED + 已发布已向量化文档 → <b>命中</b></li>
 *   <li>kb-2 COMPLETED + 已发布已向量化文档 → <b>命中</b></li>
 *   <li>kb-3 COMPLETED + 仅 DRAFT 文档（未发布）→ 排除</li>
 *   <li>kb-4 COMPLETED + 仅 PUBLISHED 但 PROCESSING 文档（向量化未完成）→ 排除</li>
 *   <li>kb-5 COMPLETED + 无文档 → 排除</li>
 *   <li>kb-6 PENDING + 已发布已向量化文档（KB 未就绪）→ 排除</li>
 *   <li>kb-7 COMPLETED status=0（已删）+ 已发布已向量化文档 → 排除</li>
 * </ul>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class KbOptionQueryServiceIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        // 清库（外键顺序：metadata/documents → categories → knowledge_bases）
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> persistAll(session,
                                kb("kb-1", "官方CV620库", (short) 1, "COMPLETED", 1),
                                kb("kb-2", "代码知识库", (short) 1, "COMPLETED", 2),
                                kb("kb-3", "仅草稿库", (short) 1, "COMPLETED", 3),
                                kb("kb-4", "向量化未完库", (short) 1, "COMPLETED", 4),
                                kb("kb-5", "空库", (short) 1, "COMPLETED", 5),
                                kb("kb-6", "未就绪库", (short) 1, "PENDING", 6),
                                kb("kb-7", "已删库", (short) 0, "COMPLETED", 7)))
                        .chain(() -> persistAll(session,
                                doc("doc-1", "kb-1", "官方手册", "PUBLISHED", "COMPLETED"),
                                doc("doc-2", "kb-2", "驱动代码", "PUBLISHED", "COMPLETED"),
                                doc("doc-3", "kb-3", "草稿文档", "DRAFT", "COMPLETED"),
                                doc("doc-4", "kb-4", "处理中文档", "PUBLISHED", "PROCESSING"),
                                doc("doc-5", "kb-6", "未就绪库文档", "PUBLISHED", "COMPLETED"),
                                doc("doc-6", "kb-7", "已删库文档", "PUBLISHED", "COMPLETED"))))
                .await().indefinitely();
    }

    // ======================== 主验证：精确结果集 + 排序 ========================

    /** 仅 kb-1、kb-2 命中，按 created_at 升序（同时覆盖所有排除规则） */
    @Test
    void shouldReturnOnlyRetrievableKbsOrderedByCreatedAt() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/options")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(2))
                .body("data.kbId", contains("kb-1", "kb-2"))
                .body("data.kbName", contains("官方CV620库", "代码知识库"));
    }

    // ======================== 针对性排除规则 ========================

    /** KB 自身 processStatus=PENDING（未就绪）即使其下有可检索文档也不返回 */
    @Test
    void shouldExcludePendingKb() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/options")
                .then().statusCode(200)
                .body("data.kbId", not(org.hamcrest.Matchers.hasItems("kb-6")));
    }

    /** 已删知识库（status=0）不返回 */
    @Test
    void shouldExcludeDeletedKb() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/options")
                .then().statusCode(200)
                .body("data.kbId", not(org.hamcrest.Matchers.hasItems("kb-7")));
    }

    /** 文档向量化未完成（processStatus=PROCESSING）的 KB 不返回 */
    @Test
    void shouldExcludeKbWithOnlyUnvectorizedDoc() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/options")
                .then().statusCode(200)
                .body("data.kbId", not(org.hamcrest.Matchers.hasItems("kb-4")));
    }

    // ======================== 孤儿文档排除（与 navTree 口径一致）====================

    /** 仅剩孤儿已发布文档（categoryId 指向已软删类别）的 KB 不视为可检索 */
    @Test
    void shouldExcludeKbWithOnlyOrphanPublishedDocs() {
        // seed：kb-8 COMPLETED，唯一已发布文档是孤儿（类别已软删 status=0）
        CategoryPO cat = new CategoryPO();
        cat.setId("cat-del8");
        cat.setKbId("kb-8");
        cat.setParentId(null);
        cat.setName("已删除类别");
        cat.setSortOrder(9);
        cat.setInheritParent(true);
        cat.setStatus((short) 0);
        cat.setCreatedAt(LocalDateTime.now());
        cat.setUpdatedAt(LocalDateTime.now());
        DocumentPO orphan = doc("doc-orphan-8", "kb-8", "孤儿文档", "PUBLISHED", "COMPLETED");
        orphan.setCategoryId("cat-del8");

        sessionFactory.withTransaction(session ->
                        persistAll(session, kb("kb-8", "仅孤儿库", (short) 1, "COMPLETED", 8), cat, orphan))
                .await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/options")
                .then().statusCode(200)
                // 仅孤儿已发布文档 → kb-8 排除；kb-1/kb-2（可见已发布文档）保持命中
                .body("data.kbId", contains("kb-1", "kb-2"))
                .body("data.kbId", not(org.hamcrest.Matchers.hasItems("kb-8")));
    }

    // ======================== 辅助：种子数据构造 ========================

    @SafeVarargs
    private static <T> Uni<Void> persistAll(Mutiny.Session session, T... entities) {
        Uni<Void> uni = Uni.createFrom().voidItem();
        for (T entity : entities) {
            uni = uni.chain(() -> session.persist(entity));
        }
        return uni;
    }

    private static KnowledgeBasePO kb(String id, String name, Short status, String processStatus, int seq) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setStatus(status);
        po.setProcessStatus(processStatus);
        po.setCreatedBy("admin");
        po.setCreatedAt(BASE.plusSeconds(seq));
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static DocumentPO doc(String id, String kbId, String title, String status, String processStatus) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(null);
        po.setTitle(title);
        po.setStatus(status);
        po.setProcessStatus(processStatus);
        po.setCurrentVersion("1");
        po.setHasPublishedVersion(false);
        po.setFilePath(kbId + "/" + title);
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType("DOCUMENT");
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(BASE);
        po.setUpdatedAt(BASE);
        return po;
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
