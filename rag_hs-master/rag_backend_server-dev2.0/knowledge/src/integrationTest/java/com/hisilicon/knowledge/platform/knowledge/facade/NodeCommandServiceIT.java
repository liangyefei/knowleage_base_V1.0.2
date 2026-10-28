package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * 集成测试：统一树节点操作（{@code POST /kb/node/create|rename|delete}）跑真实 PostgreSQL。
 * <p>验证：KB 父节点创建根类别 / 类别父节点创建子类别 / 重命名仅改名称（description、sortOrder 保留）/
 * 删除级联 / 错误码（A0430 不存在、A0429 重名、A0420 类型非法）。</p>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class NodeCommandServiceIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        // 清库（外键顺序：versions/metadata/documents → categories → knowledge_bases）
                        .createQuery("delete from DocumentVersionPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentMetadataPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> persistAll(session,
                                kb("kb-1", "芯片知识库", "芯片知识库描述", (short) 1),
                                kb("kb-2", "FAQ知识库", null, (short) 1)))
                        .chain(() -> persistAll(session,
                                cat(new CatSeed("cat-1", "kb-1", null, "CV620系列", 0, (short) 1)),
                                cat(new CatSeed("cat-2", "kb-1", null, "CV720系列", 1, (short) 1)),
                                cat(new CatSeed("cat-11", "kb-1", "cat-1", "寄存器手册", 0, (short) 1)))))
                .await().indefinitely();
    }

    // ======================== 新建子文件夹 ========================

    @Test
    void shouldCreateRootCategoryUnderKb() {
        String nodeId = given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"新根类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.nodeId", notNullValue())
                .body("data.nodeType", equalTo("CATEGORY"))
                .body("data.name", equalTo("新根类别"))
                .extract().path("data.nodeId");

        // 树中可见（kb-1 根子类别 +1；新类别 sortOrder=0 与 cat-1 并列，位置不固定，按 nodeId 查找）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("data[0].children", hasSize(3))
                .body("data[0].children.findAll { it.nodeId == '" + nodeId + "' }", hasSize(1))
                .body("data[0].children.find { it.nodeId == '" + nodeId + "' }.name", equalTo("新根类别"));
    }

    @Test
    void shouldCreateSubCategoryUnderCategory() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"cat-1\",\"parentNodeType\":\"CATEGORY\",\"name\":\"新子类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.nodeType", equalTo("CATEGORY"))
                .body("data.name", equalTo("新子类别"));

        // 懒加载 cat-1 下一层可见
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("data[1].name", equalTo("新子类别"));
    }

    @Test
    void shouldFailCreateWhenParentNotFound() {
        // KB 父不存在 → A0430
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-999\",\"parentNodeType\":\"KB\",\"name\":\"新类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));

        // 类别父不存在 → A0430
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"cat-999\",\"parentNodeType\":\"CATEGORY\",\"name\":\"新类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    @Test
    void shouldFailCreateWhenNameDuplicate() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"CV620系列\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("已存在"));
    }

    @Test
    void shouldFailCreateWhenNodeTypeInvalid() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"FOLDER\",\"name\":\"新类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("节点类型不合法"));
    }

    // ======================== 重命名 ========================

    @Test
    void shouldRenameKbKeepingDescription() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\",\"name\":\"新芯片知识库\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // 名称已更新，description 保留原值（仅改名称语义）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbName", "新芯片")
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("data.records[0].kbName", equalTo("新芯片知识库"))
                .body("data.records[0].description", equalTo("芯片知识库描述"));
    }

    @Test
    void shouldRenameCategoryKeepingSortOrder() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-2\",\"nodeType\":\"CATEGORY\",\"name\":\"CV730系列\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // 名称更新，sortOrder=1 保留 → 仍在 cat-1 之后
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("data[0].children[0].name", equalTo("CV620系列"))
                .body("data[0].children[1].nodeId", equalTo("cat-2"))
                .body("data[0].children[1].name", equalTo("CV730系列"));
    }

    @Test
    void shouldFailRenameWhenDuplicate() {
        // KB 重名 → A0429
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\",\"name\":\"FAQ知识库\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"));

        // 类别同级重名 → A0429
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-2\",\"nodeType\":\"CATEGORY\",\"name\":\"CV620系列\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"));
    }

    @Test
    void shouldFailRenameWhenNodeNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-999\",\"nodeType\":\"KB\",\"name\":\"新名\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));

        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-999\",\"nodeType\":\"CATEGORY\",\"name\":\"新名\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    @Test
    void shouldFailRenameWhenNodeTypeInvalid() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"FOLDER\",\"name\":\"新名\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("节点类型不合法"));
    }

    // ======================== 删除 ========================

    @Test
    void shouldDeleteKbCascadingCategories() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // KB 及其全部类别从树中消失（软删除）
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].nodeId", equalTo("kb-2"));
    }

    @Test
    void shouldDeleteCategoryCascadingDescendants() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // cat-1 及其后代 cat-11 消失；cat-2 保留
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("data[0].children", hasSize(1))
                .body("data[0].children[0].nodeId", equalTo("cat-2"));
    }

    @Test
    void shouldFailDeleteWhenNodeNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-999\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));

        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-999\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    @Test
    void shouldFailDeleteWhenNodeTypeInvalid() {
        given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"FOLDER\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("节点类型不合法"));
    }

    // ======================== 创建 sortOrder = 同级最大 + 1 ========================

    @Test
    void shouldCreateNodesWithIncrementingSortOrder() {
        // 连续创建 3 个根类别 → sortOrder 依次为 2, 3, 4（cat-1=0, cat-2=1 之后）
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"新类别一\"}")
                .when().post("/kb/node/create").then().statusCode(200).body("code", equalTo("0"));
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"新类别二\"}")
                .when().post("/kb/node/create").then().statusCode(200).body("code", equalTo("0"));
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"新类别三\"}")
                .when().post("/kb/node/create").then().statusCode(200).body("code", equalTo("0"));

        // navTree 按 sortOrder 升序：新节点依次排在末尾
        given().header("Authorization", "Bearer " + VALID_TOKEN).header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then().statusCode(200)
                .body("data[0].children", hasSize(5))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[1].nodeId", equalTo("cat-2"))
                .body("data[0].children[2].name", equalTo("新类别一"))
                .body("data[0].children[3].name", equalTo("新类别二"))
                .body("data[0].children[4].name", equalTo("新类别三"));
    }

    @Test
    void shouldCreateRootCategoryInEmptyKbWithSortOrderZero() {
        // kb-2 无类别 → 首个根类别 sortOrder=0
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-2\",\"parentNodeType\":\"KB\",\"name\":\"FAQ首个类别\"}")
                .when().post("/kb/node/create")
                .then().statusCode(200).body("code", equalTo("0"));

        given().header("Authorization", "Bearer " + VALID_TOKEN).header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then().statusCode(200)
                .body("data[1].children", hasSize(1))
                .body("data[1].children[0].name", equalTo("FAQ首个类别"));
    }

    // ======================== 删除后同级前移 ========================

    @Test
    void shouldDeleteMiddleCategoryShiftingSiblings() {
        // 先创建 cat-3（sortOrder=2）→ 删除 cat-2（sortOrder=1）→ cat-3 前移到 sortOrder=1
        String cat3Id = given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"临时类别\"}")
                .when().post("/kb/node/create")
                .then().statusCode(200).body("code", equalTo("0"))
                .extract().path("data.nodeId");

        // 删除前：cat-1(0), cat-2(1), 临时(2)
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-2\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        // 删除后：cat-1(0), 临时前移到(1) → 顺序 cat-1, 临时
        given().header("Authorization", "Bearer " + VALID_TOKEN).header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then().statusCode(200)
                .body("data[0].children", hasSize(2))
                .body("data[0].children[0].nodeId", equalTo("cat-1"))
                .body("data[0].children[1].nodeId", equalTo(cat3Id))
                .body("data[0].children[1].name", equalTo("临时类别"));
    }

    @Test
    void shouldDeleteLastCategoryKeepingOthers() {
        // 删除末尾的 cat-2 → cat-1 不受影响
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-2\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        given().header("Authorization", "Bearer " + VALID_TOKEN).header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then().statusCode(200)
                .body("data[0].children", hasSize(1))
                .body("data[0].children[0].nodeId", equalTo("cat-1"));
    }

    // ======================== 删除级联文档（阻塞校验 + 物理清理） ========================

    @Test
    void shouldRejectDeleteCategoryWhenPublishedDocExists() {
        // cat-1 下有已发布文档 → 删除被拒（A0436），类别与文档原样保留
        seedDocs(doc("doc-pub", "kb-1", "cat-1", "已发布文档", "PUBLISHED"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0436"))
                .body("message", containsString("请先撤回或废弃"));

        Long activeCats = count("select count(c) from CategoryPO c where c.status = 1");
        Long docs = count("select count(d) from DocumentPO d");
        org.junit.jupiter.api.Assertions.assertEquals(3L, activeCats);
        org.junit.jupiter.api.Assertions.assertEquals(1L, docs);
    }

    @Test
    void shouldRejectDeleteCategoryWhenReviewingOrArchivedDocExists() {
        seedDocs(doc("doc-rev", "kb-1", "cat-2", "审核中文档", "REVIEWING"),
                doc("doc-arc", "kb-1", "cat-2", "已归档文档", "ARCHIVED"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-2\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0436"));
    }

    @Test
    void shouldDeleteCategoryCascadingDraftAndDeprecatedDocs() {
        // cat-1 下：DRAFT + DEPRECATED 级联物理删除（含 metadata/versions）；根目录直属文档不受影响
        seedDocs(doc("doc-draft", "kb-1", "cat-1", "草稿文档", "DRAFT"),
                doc("doc-dep", "kb-1", "cat-1", "废弃文档", "DEPRECATED"),
                doc("doc-root", "kb-1", null, "根目录文档", "DRAFT"));
        seedMetaAndVersions("doc-draft", "doc-dep");

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        Long remainingDocs = count("select count(d) from DocumentPO d");
        Long remainingMetas = count("select count(m) from DocumentMetadataPO m");
        Long remainingVersions = count("select count(v) from DocumentVersionPO v");
        Long cat1Status = count("select count(c) from CategoryPO c where c.id = 'cat-1' and c.status = 0");
        org.junit.jupiter.api.Assertions.assertEquals(1L, remainingDocs, "仅剩根目录直属文档 doc-root");
        org.junit.jupiter.api.Assertions.assertEquals(0L, remainingMetas, "子树文档 metadata 应级联删除");
        org.junit.jupiter.api.Assertions.assertEquals(0L, remainingVersions, "子树文档 versions 应级联删除");
        org.junit.jupiter.api.Assertions.assertEquals(1L, cat1Status, "cat-1 应软删除（status=0）");
    }

    @Test
    void shouldReturnEmptyWhenSearchDeletedCategory() {
        // 防御纵深：按已删 categoryId EXACT 查询返回空页（正常情况下文档已级联清空）
        seedDocs(doc("doc-stale", "kb-1", "cat-1", "历史文档", "DEPRECATED"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"includeDescendants\":false}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0));
    }

    @Test
    void shouldRejectDeleteKbWhenPublishedDocExists() {
        // KB 根目录直属（categoryId=null）已发布文档也阻塞 KB 删除
        seedDocs(doc("doc-root-pub", "kb-1", null, "根目录已发布文档", "PUBLISHED"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0436"))
                .body("message", containsString("请先撤回或废弃"));

        Long activeKb = count("select count(k) from KnowledgeBasePO k where k.id = 'kb-1' and k.status = 1");
        org.junit.jupiter.api.Assertions.assertEquals(1L, activeKb, "kb-1 应保持活跃");
    }

    @Test
    void shouldDeleteKbCascadingDraftAndDeprecatedDocs() {
        // KB 范围（含根目录）DRAFT/DEPRECATED 全部级联物理删除
        seedDocs(doc("doc-kb-draft", "kb-1", "cat-1", "KB草稿", "DRAFT"),
                doc("doc-kb-dep", "kb-1", null, "KB根目录废弃", "DEPRECATED"));

        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        Long docs = count("select count(d) from DocumentPO d where d.kbId = 'kb-1'");
        org.junit.jupiter.api.Assertions.assertEquals(0L, docs);
    }

    @Test
    void shouldRejectUploadToDeletedCategory() {
        // 删除 cat-1 后向其上传 → KB 行锁保证落库校验看到已删 → 类别不存在（A0430），无新孤儿
        given().contentType("application/json").header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(io.restassured.http.ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", "kb-1")
                .multiPart("categoryId", "cat-1")
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/upload")
                .then().statusCode(200)
                .body("code", equalTo("A0430"));

        Long docs = count("select count(d) from DocumentPO d where d.categoryId = 'cat-1'");
        org.junit.jupiter.api.Assertions.assertEquals(0L, docs, "不得向已删类别插入文档（孤儿防御）");
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

    private static KnowledgeBasePO kb(String id, String name, String description, Short status) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setDescription(description);
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

    /** 批量灌入文档（生命周期状态由 status 指定） */
    private void seedDocs(DocumentPO... docs) {
        sessionFactory.withTransaction(session -> persistAll(session, docs)).await().indefinitely();
    }

    /** 给指定文档各灌一条 metadata + 一条历史版本（验证级联物理删除覆盖子表） */
    private void seedMetaAndVersions(String... documentIds) {
        sessionFactory.withTransaction(session -> {
            Uni<Void> uni = Uni.createFrom().voidItem();
            int i = 0;
            for (String docId : documentIds) {
                final DocumentMetadataPO meta = new DocumentMetadataPO();
                meta.setId("m-it-" + (++i));
                meta.setDocumentId(docId);
                meta.setMetaKey("chip_model");
                meta.setMetaValue("CV620");
                meta.setCreatedAt(LocalDateTime.now());
                meta.setUpdatedAt(LocalDateTime.now());
                final DocumentVersionPO version = new DocumentVersionPO();
                version.setDocumentId(docId);
                version.setVersionNumber("0.9");
                version.setFilePath("kb/芯片知识库/" + docId + "-v0.9.pdf");
                version.setFileSize(1024L);
                version.setMimeType("application/pdf");
                version.setWasPublished(false);
                version.setCreatedBy("admin");
                version.setCreatedAt(LocalDateTime.now());
                uni = uni.chain(() -> persistAll(session, meta, version));
            }
            return uni;
        }).await().indefinitely();
    }

    /** 执行 HQL count 查询（须返回 Long） */
    private Long count(String hql) {
        return sessionFactory.withTransaction(session ->
                        session.createQuery(hql, Long.class).getSingleResult())
                .await().indefinitely();
    }

    private static DocumentPO doc(String id, String kbId, String categoryId, String title, String status) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(categoryId);
        po.setTitle(title);
        po.setStatus(status);
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion("1");
        po.setHasPublishedVersion(false);
        po.setFilePath("kb/芯片知识库/" + id + ".pdf");
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
