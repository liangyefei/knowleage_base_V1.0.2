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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * 集成测试：按方向搜索文件的树形下拉（{@code GET /kb/documents/file-options}，
 * 代码↔文档映射：选择源文件，树状展示）真实 PostgreSQL。
 * <p>验证：一次性整树返回（KB 根 / CATEGORY 根）、direction 锁定 docType、
 * keyword 命中 + 祖先链 + 空分支裁剪、PUBLISHED/知识库活跃/孤儿文档口径、
 * 超量 A0434、direction/nodeType 非法 A0420。</p>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentFileOptionsIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> persistAll(session,
                                kb("kb-1", "CV610", (short) 1),
                                kb("kb-2", "CV620", (short) 0),
                                cat(new CatSeed("cat-1", "kb-1", null, "文档目录", 0, (short) 1)),
                                cat(new CatSeed("cat-2", "kb-1", "cat-1", "子目录", 1, (short) 1)),
                                cat(new CatSeed("cat-del", "kb-1", null, "已删目录", 9, (short) 0))))
                        // doc-1..3 DOCUMENT（kb-1）；doc-4..5 CODE（kb-1，doc-5 在子目录）；
                        // doc-6 CODE 已删库；doc-7 DOCUMENT 草稿（根目录）；doc-8 CODE 孤儿（类别已删）
                        .chain(() -> persistAll(session,
                                doc(new DocSeed("doc-1", "kb-1", "cat-1", "CV610选型指南.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-2", "kb-1", "cat-1", "接口设计文档.docx", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-3", "kb-1", "cat-2", "通用规格书.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-4", "kb-1", null, "通用开发手册.c", "CODE", "PUBLISHED")),
                                doc(new DocSeed("doc-5", "kb-1", "cat-2", "驱动代码.c", "CODE", "PUBLISHED")),
                                doc(new DocSeed("doc-6", "kb-2", null, "已删库代码.c", "CODE", "PUBLISHED")),
                                doc(new DocSeed("doc-7", "kb-1", null, "草稿文档.pdf", "DOCUMENT", "DRAFT")),
                                doc(new DocSeed("doc-8", "kb-1", "cat-del", "孤儿代码.c", "CODE", "PUBLISHED")))))
                .await().indefinitely();
    }

    // ======================== doc2code：KB 整树 ========================

    @Test
    void shouldReturnKbTreeForDoc2Code() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 根 = KB 节点；根目录草稿 doc-7 排除 → children 仅 cat-1 分支
                .body("data.id", equalTo("kb-1"))
                .body("data.title", equalTo("CV610"))
                .body("data.nodeType", equalTo("KB"))
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("cat-1"))
                .body("data.children[0].nodeType", equalTo("CATEGORY"))
                // cat-1 children = cat-2 分支 + doc-1 + doc-2（CODE 叶子不出现）
                .body("data.children[0].children", hasSize(3))
                .body("data.children[0].children.id", hasItems("cat-2", "doc-1", "doc-2"))
                .body("data.children[0].children.find { it.id == 'cat-2' }.children", hasSize(1))
                .body("data.children[0].children.find { it.id == 'cat-2' }.children[0].id", equalTo("doc-3"))
                .body("data.children[0].children.find { it.id == 'doc-1' }.nodeType", equalTo("DOCUMENT"))
                .body("data.children[0].children.find { it.id == 'doc-1' }.docType", equalTo("DOCUMENT"))
                .body("data.children[0].children.find { it.id == 'doc-1' }.currentVersion", equalTo("1"))
                // 叶子携带存储文件名与小写扩展名；KB/CATEGORY 节点为 null
                .body("data.children[0].children.find { it.id == 'doc-1' }.fileName", equalTo("CV610选型指南.pdf"))
                .body("data.children[0].children.find { it.id == 'doc-1' }.fileType", equalTo("pdf"))
                .body("data.children[0].children.find { it.id == 'doc-2' }.fileName", equalTo("接口设计文档.docx"))
                .body("data.children[0].children.find { it.id == 'doc-2' }.fileType", equalTo("docx"))
                .body("data.children[0].fileName", nullValue())
                .body("data.children[0].fileType", nullValue());
    }

    // ======================== keyword：按文件名/扩展名搜索 ========================

    @Test
    void shouldFilterByFileExtensionKeyword() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                // 按「文件类型」搜索：keyword 命中存储文件名扩展名（标题均不含 .docx）
                .queryParam("keyword", ".docx")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 仅 doc-2 命中（接口设计文档.docx）：KB → cat-1 → doc-2
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("cat-1"))
                .body("data.children[0].children", hasSize(1))
                .body("data.children[0].children[0].id", equalTo("doc-2"))
                .body("data.children[0].children[0].fileName", equalTo("接口设计文档.docx"))
                .body("data.children[0].children[0].fileType", equalTo("docx"));
    }

    @Test
    void shouldFilterByFullFileNameKeyword() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "code2doc")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                // 完整文件名搜索（大小写不敏感）
                .queryParam("keyword", "驱动代码.C")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("cat-1"))
                .body("data.children[0].children[0].id", equalTo("cat-2"))
                .body("data.children[0].children[0].children[0].id", equalTo("doc-5"))
                .body("data.children[0].children[0].children[0].fileType", equalTo("c"));
    }

    // ======================== code2doc：KB 整树 + 口径排除 ========================

    @Test
    void shouldReturnKbTreeForCode2Doc() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "code2doc")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // CODE：doc-5（cat-2）+ doc-4（根目录）；doc-6 已删库、doc-8 孤儿排除
                .body("data.children", hasSize(2))
                .body("data.children.id", hasItems("cat-1", "doc-4"))
                .body("data.children.find { it.id == 'doc-4' }.docType", equalTo("CODE"))
                .body("data.children.find { it.id == 'cat-1' }.children.find { it.id == 'cat-2' }.children[0].id",
                        equalTo("doc-5"))
                .body("data.children.find { it.id == 'doc-5' }", nullValue());
    }

    // ======================== keyword：命中 + 祖先链 + 空分支裁剪 ========================

    @Test
    void shouldReturnFilteredTreeWithAncestorChain() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .queryParam("keyword", "cv610")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 仅 doc-1 命中（大小写不敏感）：KB → cat-1 → doc-1；cat-2 分支裁剪
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("cat-1"))
                .body("data.children[0].children", hasSize(1))
                .body("data.children[0].children[0].id", equalTo("doc-1"))
                .body("data.children[0].children[0].title", equalTo("CV610选型指南.pdf"));
    }

    @Test
    void shouldReturnEmptyTreeWhenKeywordNoHit() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .queryParam("keyword", "不存在")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 无命中：根节点保留但 children 为空（无任何文档分支）
                .body("data.children", hasSize(0));
    }

    // ======================== CATEGORY 根：以该类别为根的子树 ========================

    @Test
    void shouldReturnCategorySubtreeRoot() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "code2doc")
                .queryParam("nodeType", "CATEGORY")
                .queryParam("nodeId", "cat-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 根 = cat-1；子树内 CODE：cat-2 → doc-5
                .body("data.id", equalTo("cat-1"))
                .body("data.nodeType", equalTo("CATEGORY"))
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("cat-2"))
                .body("data.children[0].children", hasSize(1))
                .body("data.children[0].children[0].id", equalTo("doc-5"));
    }

    // ======================== 校验 ========================

    @Test
    void shouldFailWhenDirectionInvalid() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "bogus")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("映射方向不合法"));
    }

    @Test
    void shouldFailWhenNodeTypeInvalid() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "DOCUMENT")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("节点类型不合法"));
    }

    @Test
    void shouldFailWhenTooMany() {
        // 灌 51 条已发布 DOCUMENT → 超过 50 上限 → A0434
        sessionFactory.withTransaction(session -> {
            Uni<Void> uni = Uni.createFrom().voidItem();
            for (int i = 1; i <= 51; i++) {
                DocumentPO po = doc(new DocSeed("doc-bulk-" + i, "kb-1", null, "批量文档" + i + ".pdf",
                        "DOCUMENT", "PUBLISHED"));
                uni = uni.chain(() -> session.persist(po));
            }
            return uni;
        }).await().indefinitely();

        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then().statusCode(200)
                .body("code", equalTo("A0434"))
                .body("message", containsString("数据过多，请缩小查询范围"));
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

    private static KnowledgeBasePO kb(String id, String name, Short status) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setStatus(status);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 类目种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record CatSeed(String id, String kbId, String parentId, String name, int sortOrder, Short status) {
    }

    private static CategoryPO cat(CatSeed s) {
        CategoryPO po = new CategoryPO();
        po.setId(s.id());
        po.setKbId(s.kbId());
        po.setParentId(s.parentId());
        po.setName(s.name());
        po.setSortOrder(s.sortOrder());
        po.setInheritParent(true);
        po.setStatus(s.status());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 文档种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record DocSeed(String id, String kbId, String categoryId, String title,
                           String docType, String status) {
    }

    private static DocumentPO doc(DocSeed s) {
        DocumentPO po = new DocumentPO();
        po.setId(s.id());
        po.setKbId(s.kbId());
        po.setCategoryId(s.categoryId());
        po.setTitle(s.title());
        po.setStatus(s.status());
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion("1");
        po.setHasPublishedVersion(false);
        po.setFilePath(s.kbId() + "/" + s.title());
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType(s.docType());
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
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
