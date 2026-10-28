package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @QuarkusTest} for the move category endpoint.
 * <p>树结构（KB_ID 下）：root-1 → cat-a → cat-a-1；root-1 → target-x</p>
 */
@QuarkusTest
class KnowledgeBaseResourceMoveCategoryTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = "kb-001";
    private static final String ROOT = "root-1";
    private static final String CAT_A = "cat-a";
    private static final String CAT_A1 = "cat-a-1";
    private static final String TARGET = "target-x";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(
                new MockCategoryRepository(), categoryRepository);
        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockCategoryRepository.setEntityById(null);
        MockCategoryRepository.setEntityMap(null);
        MockCategoryRepository.setSiblingEntity(null);
        MockCategoryRepository.setAllCategories(null);
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldMoveToRoot() {
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(standardTree());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertNotNull(updated, "update 应被调用");
        assertNull(updated.getParentId(), "未传 targetParentId 应移到根");
        assertEquals(KB_ID, updated.getKbId(), "kbId 不应变化");
    }

    @Test
    void shouldMoveToOtherParent() {
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(standardTree());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"" + TARGET + "\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(TARGET, updated.getParentId());
    }

    // ======================== 类别校验 ========================

    @Test
    void shouldFailWhenCategoryNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"no-such-cat\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("类别不存在"));
    }

    @Test
    void shouldFailWhenCategoryDeleted() {
        MockCategoryRepository.setEntityById(deletedCat());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("类别不存在"));
    }

    // ======================== 目标父类别校验 ========================

    @Test
    void shouldFailWhenTargetParentNotFound() {
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(standardTree());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"no-such-parent\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("父类别不存在"));
    }

    @Test
    void shouldFailWhenMoveToSelf() {
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(standardTree());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"" + CAT_A + "\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("不能移动到自身或子类别下"));
    }

    @Test
    void shouldFailWhenMoveToDescendant() {
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(standardTree());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"" + CAT_A1 + "\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("不能移动到自身或子类别下"));
    }

    // ======================== 深度限制（MAX_CATEGORY_DEPTH = 20） ========================

    @Test
    void shouldFailWhenDepthExceeded() {
        // d1(根) → ... → d19（深度 19），cat-a 子树高度 2 → 19 + 2 = 21 > 20 → 拒绝
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(deepTree(19));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"d19\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("类别深度不能超过20层"));
    }

    @Test
    void shouldMoveWhenDepthExactlyAtLimit() {
        // d1(根) → ... → d18（深度 18），cat-a 子树高度 2 → 18 + 2 = 20 = 上限，允许
        MockCategoryRepository.setEntityById(cat(CAT_A, ROOT));
        MockCategoryRepository.setAllCategories(deepTree(18));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_A + "\",\"targetParentId\":\"d18\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
    }

    // ======================== 参数校验 ========================

    @Test
    void shouldFailWhenCategoryIdBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"\"}")
                .when().post("/kb/moveCategory")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("类别ID不能为空"));
    }

    // ======================== 辅助方法 ========================

    /** 标准树：root-1 → cat-a → cat-a-1；root-1 → target-x */
    private static List<CategoryEntity> standardTree() {
        List<CategoryEntity> list = new ArrayList<>();
        list.add(cat(ROOT, null));
        list.add(cat(CAT_A, ROOT));
        list.add(cat(CAT_A1, CAT_A));
        list.add(cat(TARGET, ROOT));
        return list;
    }

    /** d1(根) → d2 → ... → d{levels}（深度 levels）+ cat-a 子树（cat-a → cat-a-1，高度 2） */
    private static List<CategoryEntity> deepTree(int levels) {
        List<CategoryEntity> list = new ArrayList<>();
        String prev = null;
        for (int i = 1; i <= levels; i++) {
            String id = "d" + i;
            list.add(cat(id, prev));
            prev = id;
        }
        list.add(cat(CAT_A, ROOT));
        list.add(cat(CAT_A1, CAT_A));
        return list;
    }

    private static CategoryEntity cat(String id, String parentId) {
        return CategoryEntity.builder()
                .id(CategoryId.of(id))
                .kbId(KB_ID)
                .parentId(parentId)
                .name(id)
                .sortOrder(0)
                .inheritParent(true)
                .status((short) 1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static CategoryEntity deletedCat() {
        return CategoryEntity.builder()
                .id(CategoryId.of(CAT_A))
                .kbId(KB_ID)
                .parentId(ROOT)
                .name("cat-a")
                .sortOrder(0)
                .inheritParent(true)
                .status((short) 0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(UUID.randomUUID().toString()))
                .name("产品知识库")
                .status((short) 1)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
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
