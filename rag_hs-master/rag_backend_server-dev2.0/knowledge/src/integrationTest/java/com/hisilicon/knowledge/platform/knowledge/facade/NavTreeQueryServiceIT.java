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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * 集成测试：{@code GET /kb/navTree} 跑真实 PostgreSQL（Podman postgres:18.4 / kb_test 库）
 * <p>验证真实 SQL 下的树构建、递归计数、软删除过滤，以及串行查询不会触发
 * Hibernate Reactive 的 LoadContexts 并发问题（Illegal pop() 报错）。</p>
 * <p>种子数据用原生 {@link Mutiny.SessionFactory}（Hibernate Reactive 原生 API，
 * 不依赖 Vertx context）；断言走 HTTP（请求在 event loop 处理，天然带 Vertx context，
 * 与生产路径一致）。</p>
 * <p>build-time 配置（datasource / hibernate-orm.enabled）由 Gradle
 * {@code integrationTest} 任务以系统属性注入；运行：
 * {@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class NavTreeQueryServiceIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        // 清库（外键顺序：documents → categories → knowledge_bases）
                        .createQuery("delete from DocumentPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        // 知识库：kb-1 带完整树，kb-2 空库，kb-3 已删除（不应出现）
                        .chain(() -> persistAll(session,
                                kb("kb-1", "芯片知识库", (short) 1),
                                kb("kb-2", "FAQ知识库", (short) 1),
                                kb("kb-3", "已删除知识库", (short) 0)))
                        // 类别：cat-1 下有 cat-11 → cat-111 两级，cat-9 已删除（不应出现）
                        .chain(() -> persistAll(session,
                                cat(new CatSeed("cat-1", "kb-1", null, "CV620系列", 0, (short) 1)),
                                cat(new CatSeed("cat-2", "kb-1", null, "CV720系列", 1, (short) 1)),
                                cat(new CatSeed("cat-11", "kb-1", "cat-1", "寄存器手册", 0, (short) 1)),
                                cat(new CatSeed("cat-12", "kb-1", "cat-1", "SDK开发指南", 1, (short) 1)),
                                cat(new CatSeed("cat-111", "kb-1", "cat-11", "核心寄存器", 0, (short) 1)),
                                cat(new CatSeed("cat-9", "kb-1", null, "已删除类别", 2, (short) 0))))
                        // 文档：根目录 1 篇，cat-1 下 1 篇，cat-11 下 1 篇，cat-111 下 1 篇，cat-2 下 1 篇
                        .chain(() -> persistAll(session,
                                doc("doc-1", "kb-1", null, "产品总览.pdf"),
                                doc("doc-2", "kb-1", "cat-1", "CV620选型指南.pdf"),
                                doc("doc-3", "kb-1", "cat-11", "寄存器手册.pdf"),
                                doc("doc-4", "kb-1", "cat-111", "NPU寄存器.pdf"),
                                doc("doc-5", "kb-1", "cat-2", "CV720规格书.pdf"))))
                .await().indefinitely();
    }

    // ======================== 根模式：全部 KB + 根子类别 ========================

    @Test
    void shouldBuildRootTreeWithRecursiveCounts() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                // 软删除知识库不出现，KB 按 createdAt 排序
                .body("data", hasSize(2))
                .body("data[0].nodeId", equalTo("kb-1"))
                .body("data[1].nodeId", equalTo("kb-2"))
                // KB 节点：递归计数（文档含根目录 + 全部后代；类别 = 全部活跃）
                .body("data[0].nodeType", equalTo("KB"))
                .body("data[0].name", equalTo("芯片知识库"))
                .body("data[0].kbId", equalTo("kb-1"))
                .body("data[0].documentCount", equalTo(5))
                .body("data[0].categoryCount", equalTo(5))
                .body("data[0].hasChildren", equalTo(true))
                // 根子类别：只返回一层，sortOrder 升序
                .body("data[0].children", hasSize(2))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[0].nodeType", equalTo("CATEGORY"))
                .body("data[0].children[0].name", equalTo("CV620系列"))
                .body("data[0].children[0].kbId", equalTo("kb-1"))
                .body("data[0].children[0].documentCount", equalTo(3))
                .body("data[0].children[0].categoryCount", equalTo(4))
                .body("data[0].children[0].hasChildren", equalTo(true))
                .body("data[0].children[0].children", hasSize(0))
                .body("data[0].children[1].nodeId", equalTo("cat-2"))
                .body("data[0].children[1].documentCount", equalTo(1))
                .body("data[0].children[1].hasChildren", equalTo(false))
                // 空知识库
                .body("data[1].documentCount", equalTo(0))
                .body("data[1].categoryCount", equalTo(0))
                .body("data[1].hasChildren", equalTo(false));
    }

    // ======================== 懒加载模式：指定类别的下一层 ========================

    @Test
    void shouldLoadNextLevelByParentId() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(2))
                .body("data[0].nodeId", equalTo("cat-11"))
                .body("data[0].nodeType", equalTo("CATEGORY"))
                .body("data[0].name", equalTo("寄存器手册"))
                .body("data[0].kbId", equalTo("kb-1"))
                .body("data[0].documentCount", equalTo(2))
                .body("data[0].categoryCount", equalTo(2))
                .body("data[0].hasChildren", equalTo(true))
                .body("data[0].children", hasSize(0))
                .body("data[1].nodeId", equalTo("cat-12"))
                .body("data[1].name", equalTo("SDK开发指南"))
                .body("data[1].documentCount", equalTo(0))
                .body("data[1].categoryCount", equalTo(1))
                .body("data[1].hasChildren", equalTo(false));
    }

    @Test
    void shouldReturnEmptyForDeletedOrMissingParent() {
        // 已删除类别 → 空
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-9")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));

        // 不存在的类别 → 空
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-999")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    // ======================== 搜索模式：keyword 过滤完整树 ========================

    @Test
    void shouldSearchKbNameReturnsFullSubtree() {
        // KB 名称命中 → 整个子树全量保留（多级 children）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "芯片")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-1"))
                .body("data[0].documentCount", equalTo(5))
                .body("data[0].categoryCount", equalTo(5))
                // 完整子树：cat-1 → cat-11 → cat-111 多级可见
                .body("data[0].children", hasSize(2))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[0].children", hasSize(2))
                .body("data[0].children[0].children[0].nodeId", equalTo("cat-11"))
                .body("data[0].children[0].children[0].children", hasSize(1))
                .body("data[0].children[0].children[0].children[0].name", equalTo("核心寄存器"))
                .body("data[0].children[1].nodeId", equalTo("cat-2"));
    }

    @Test
    void shouldSearchCategoryWithAncestorChain() {
        // 命中"寄存器手册"（第 2 层）：保留祖先链 + 命中节点后代，兄弟裁剪，计数按可见子树重算
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "寄存器")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-1"))
                // kb-1 可见内容：根文档 1 + cat-1 分支 3 = 4；类别：cat-1 分支 3
                .body("data[0].documentCount", equalTo(4))
                .body("data[0].categoryCount", equalTo(3))
                .body("data[0].children", hasSize(1))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[0].documentCount", equalTo(3))
                .body("data[0].children[0].categoryCount", equalTo(3))
                .body("data[0].children[0].children", hasSize(1))
                .body("data[0].children[0].children[0].nodeId", equalTo("cat-11"))
                .body("data[0].children[0].children[0].name", equalTo("寄存器手册"))
                .body("data[0].children[0].children[0].documentCount", equalTo(2))
                .body("data[0].children[0].children[0].categoryCount", equalTo(2))
                // 命中节点后代全量保留（cat-111）
                .body("data[0].children[0].children[0].children", hasSize(1))
                .body("data[0].children[0].children[0].children[0].name", equalTo("核心寄存器"));
    }

    @Test
    void shouldSearchDeepCategory() {
        // 命中第 3 层"核心寄存器"：祖先链 cat-1 → cat-11 完整保留
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "核心")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-1"))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[0].children[0].nodeId", equalTo("cat-11"))
                .body("data[0].children[0].children[0].children[0].nodeId", equalTo("cat-111"))
                .body("data[0].children[0].children[0].children[0].name", equalTo("核心寄存器"));
    }

    @Test
    void shouldSearchSiblingCategory() {
        // 命中"CV720系列"：cat-1 分支裁剪，cat-2 命中全量保留
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "CV720")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-1"))
                // kb-1 可见 = 根文档 1 + cat-2 1
                .body("data[0].documentCount", equalTo(2))
                .body("data[0].categoryCount", equalTo(1))
                .body("data[0].children", hasSize(1))
                .body("data[0].children[0].nodeId", equalTo("cat-2"))
                .body("data[0].children[0].documentCount", equalTo(1));
    }

    @Test
    void shouldSearchKbWithoutMatchingChildren() {
        // 命中"FAQ知识库"名称：kb-2 全量返回，kb-1 无命中不返回
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "FAQ")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-2"))
                .body("data[0].documentCount", equalTo(0))
                .body("data[0].children", hasSize(0));
    }

    @Test
    void shouldReturnEmptyWhenKeywordNoMatch() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "不存在的关键字")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    // ======================== 搜索 + 懒加载组合：直接子级别过滤 ========================

    @Test
    void shouldFilterDirectChildrenWithKeyword() {
        // parentId=cat-1 的直接子级 [cat-11, cat-12]，keyword 命中 cat-11
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .queryParam("keyword", "寄存器")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("cat-11"))
                .body("data[0].name", equalTo("寄存器手册"));

        // keyword 命中 cat-12
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .queryParam("keyword", "SDK")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("cat-12"));

        // 无命中 → 空
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .queryParam("keyword", "不存在")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    // ======================== 辅助：种子数据构造 ========================

    /** 链式 persist 多个实体（Mutiny.Session.persist 无变参） */
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
        po.setDescription("描述-" + name);
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

    private static DocumentPO doc(String id, String kbId, String categoryId, String title) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(categoryId);
        po.setTitle(title);
        po.setStatus("PUBLISHED");
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion("1");
        po.setHasPublishedVersion(false);
        po.setFilePath(kbId + "/" + title);
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType("DOCUMENT");
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
