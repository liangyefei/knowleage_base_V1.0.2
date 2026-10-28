package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
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
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @QuarkusTest} for the edit category endpoint.
 */
@QuarkusTest
class KnowledgeBaseResourceEditCategoryTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = "kb-001";
    private static final String CAT_ID = "cat-001";
    private static final String PARENT_ID = null; // 根类别
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
        MockCategoryRepository.setEntityById(null);
        MockCategoryRepository.setSiblingEntity(null);
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldEditCategorySuccess() {
        MockCategoryRepository.setEntityById(activeCategory(5, true));
        MockCategoryRepository.setSiblingEntity(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"计算机视觉\",\"sortOrder\":10,\"inheritParent\":false}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data", equalTo(null));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertNotNull(updated, "update 应被调用");
        assertEquals("计算机视觉", updated.getName());
        assertEquals(Integer.valueOf(10), updated.getSortOrder());
        assertEquals(Boolean.FALSE, updated.getInheritParent());
    }

    @Test
    void shouldEditCategoryNameUnchanged() {
        // 名称未变时跳过唯一性校验，仅更新 sortOrder/inheritParent
        MockCategoryRepository.setEntityById(activeCategory(0, true));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"深度学习\",\"sortOrder\":20,\"inheritParent\":false}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertNotNull(updated, "名称未变时也应执行 update");
        assertEquals("深度学习", updated.getName());
        assertEquals(Integer.valueOf(20), updated.getSortOrder());
        assertEquals(Boolean.FALSE, updated.getInheritParent());
    }

    // ======================== 空值重置行为 ========================

    @Test
    void shouldResetSortOrderToZeroWhenNull() {
        MockCategoryRepository.setEntityById(activeCategory(5, true));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"深度学习\",\"inheritParent\":true}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertEquals(Integer.valueOf(0), updated.getSortOrder(),
                "sortOrder 为 null 时应重置为 0");
    }

    @Test
    void shouldResetInheritParentToTrueWhenNull() {
        MockCategoryRepository.setEntityById(activeCategory(0, false));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"深度学习\",\"sortOrder\":5}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        CategoryEntity updated = MockCategoryRepository.getUpdatedEntity();
        assertEquals(Boolean.TRUE, updated.getInheritParent(),
                "inheritParent 为 null 时应重置为 true");
    }

    // ======================== 类别不存在 / 已删除 ========================

    @Test
    void shouldFailWhenCategoryNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"no-such-cat\",\"categoryName\":\"计算机视觉\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("类别不存在"));
    }

    @Test
    void shouldFailWhenCategoryDeleted() {
        MockCategoryRepository.setEntityById(deletedCategory());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"计算机视觉\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("类别不存在"));
    }

    @Test
    void shouldFailWhenCategoryNotFound_En() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"categoryId\":\"no-such-cat\",\"categoryName\":\"CV\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("Category not found"));
    }

    // ======================== 同级名称唯一性 ========================

    @Test
    void shouldFailWhenNameDuplicate() {
        MockCategoryRepository.setEntityById(activeCategory(0, true));
        MockCategoryRepository.setSiblingEntity(activeCategory(0, true));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"计算机视觉\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("同级类别名称已存在，请更换名称"));
    }

    // ======================== 参数校验 ========================

    @Test
    void shouldFailWhenCategoryIdBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"\",\"categoryName\":\"计算机视觉\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("类别ID不能为空"));
    }

    @Test
    void shouldFailWhenCategoryNameBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("类别名称不能为空"));
    }

    // ======================== 参数类型不匹配 ========================

    @Test
    void shouldFailWhenSortOrderIsNotInteger() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"计算机视觉\",\"sortOrder\":\"sss\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0410"))
                .body("message", containsString("参数sortOrder格式错误"))
                .body("message", containsString("应为整数"))
                .body("message", containsString("实际收到sss"));
    }

    @Test
    void shouldFailWhenInheritParentIsNotBoolean() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"categoryId\":\"" + CAT_ID + "\",\"categoryName\":\"计算机视觉\",\"inheritParent\":\"hhhhh\"}")
                .when().post("/kb/editCategory")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0410"))
                .body("message", containsString("参数inheritParent格式错误"))
                .body("message", containsString("应为布尔值"))
                .body("message", containsString("实际收到hhhhh"));
    }

    // ======================== 辅助方法 ========================

    private static CategoryEntity activeCategory(int sortOrder, boolean inheritParent) {
        return CategoryEntity.builder()
                .id(CategoryId.of(CAT_ID))
                .kbId(KB_ID)
                .parentId(PARENT_ID)
                .name("深度学习")
                .sortOrder(sortOrder)
                .inheritParent(inheritParent)
                .status((short) 1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static CategoryEntity deletedCategory() {
        return CategoryEntity.builder()
                .id(CategoryId.of(CAT_ID))
                .kbId(KB_ID)
                .parentId(PARENT_ID)
                .name("深度学习")
                .sortOrder(0)
                .inheritParent(true)
                .status((short) 0)
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
