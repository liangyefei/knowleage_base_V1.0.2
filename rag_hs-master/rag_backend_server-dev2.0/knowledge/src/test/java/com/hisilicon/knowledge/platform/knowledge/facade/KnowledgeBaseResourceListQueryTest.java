package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.CategoryQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.CategoryListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.MetadataOptionGroup;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @QuarkusTest} for category/document list query endpoints.
 */
@QuarkusTest
class KnowledgeBaseResourceListQueryTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = "kb-001";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    CategoryQueryService categoryQueryService;

    @Inject
    DocumentQueryService documentQueryService;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockCategoryQueryService(), categoryQueryService);
        QuarkusMock.installMockForInstance(
                new MockDocumentQueryService(), documentQueryService);
        MockCategoryQueryService.setPresetList(null);
        MockDocumentQueryService.setPresetPage(null);
    }

    // ======================== 浏览子类别 ========================

    @Test
    void shouldListRootCategories() {
        MockCategoryQueryService.setPresetList(List.of(
                new CategoryListItem("cat-1", "惠享业务概设", false, 0, LocalDateTime.now()),
                new CategoryListItem("cat-2", "惠享业务接口规范", false, 1, LocalDateTime.now())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/categories?kbId=" + KB_ID)
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].name", equalTo("惠享业务概设"))
                .body("data[0].hasChildren", equalTo(false))
                .body("data[1].name", equalTo("惠享业务接口规范"));
    }

    @Test
    void shouldListSubCategories() {
        MockCategoryQueryService.setPresetList(List.of(
                new CategoryListItem("cat-3", "子类别A", true, 0, LocalDateTime.now())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-001")
                .when().get("/kb/categories?kbId=" + KB_ID)
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].name", equalTo("子类别A"))
                .body("data[0].hasChildren", equalTo(true));
    }

    @Test
    void shouldListEmptyCategories() {
        // 无子类别时返回空列表
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/categories?kbId=" + KB_ID)
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.size()", equalTo(0));
    }

    // ======================== 文档搜索（POST /kb/documents/search）====================

    @Test
    void shouldSearchDocumentsWithAllFilters() {
        PageResult<DocumentListItem> page = new PageResult<>(1, 20, 1, 1, List.of(
                new DocumentListItem("doc-6", "CV620规格书.pdf", "pdf", "DOCUMENT", 512L,
                        "PUBLISHED", "COMPLETED", "admin", LocalDateTime.now(), null, null, null,
                        null, true, "PUBLISHED", Map.of("chip_model", "CV620"), "OFFICIAL")));

        MockDocumentQueryService.setPresetPage(page);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"kb-1","categoryId":"cat-1","docName":"规格书",
                         "docTypes":["DOCUMENT"],"statuses":["PUBLISHED"],
                         "processStatuses":["COMPLETED"],
                         "metadata":{"chip_model":"CV620"},
                         "page":1,"pageSize":20}
                        """)
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.records[0].title", equalTo("CV620规格书.pdf"))
                .body("data.records[0].metadata.chip_model", equalTo("CV620"))
                .body("data.records[0].official", equalTo("OFFICIAL"));

        MockDocumentQueryService.Filters filters = MockDocumentQueryService.getLastFilters();
        assertEquals("cat-1", filters.categoryId(), "categoryId 应透传");
        assertEquals("规格书", filters.docName(), "docName 应透传");
        assertEquals(List.of("DOCUMENT"), filters.docTypes(), "docTypes 应透传");
        assertEquals(List.of("PUBLISHED"), filters.statuses(), "statuses 应透传");
        assertEquals(List.of("COMPLETED"), filters.processStatuses(), "processStatuses 应透传");
        assertEquals(Map.of("chip_model", "CV620"), filters.metadata(), "metadata 应透传");
    }

    // ======================== 官方标识过滤（officials → metadata.official 归一）====================

    @Test
    void shouldMergeOfficialsIntoMetadataFilter() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"officials\":[\"OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(Map.of("official", "OFFICIAL"),
                MockDocumentQueryService.getLastFilters().metadata(),
                "officials 应归一为 metadata.official（复用既有元数据过滤通道）");
    }

    @Test
    void shouldMergeOfficialsMultiValueAsCsv() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"officials\":[\" OFFICIAL \",\"\",\"NON_OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(Map.of("official", "OFFICIAL,NON_OFFICIAL"),
                MockDocumentQueryService.getLastFilters().metadata(),
                "多值应 trim/去空白后逗号 join（服务层按多值 OR 拆分）");
    }

    @Test
    void shouldLetOfficialsWinOverMetadataOfficial() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"officials\":[\"OFFICIAL\"],\"metadata\":{\"official\":\"NON_OFFICIAL\",\"chip_model\":\"CV620\"}}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(Map.of("official", "OFFICIAL", "chip_model", "CV620"),
                MockDocumentQueryService.getLastFilters().metadata(),
                "与 metadata.official 冲突时以 officials 为准，其它元数据 key 保留");
    }

    @Test
    void shouldKeepMetadataUntouchedWhenOfficialsBlankOrAbsent() {
        // 全空白 officials → 归一为「不传」：metadata 原样透传（null 保持 null）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"officials\":[\"\",\"  \"],\"metadata\":{\"chip_model\":\"CV620\"}}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(Map.of("chip_model", "CV620"),
                MockDocumentQueryService.getLastFilters().metadata(),
                "空白 officials 不应注入 official key");
    }

    @Test
    void shouldSearchDocumentsWithDefaults() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\"}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        MockDocumentQueryService.Filters filters = MockDocumentQueryService.getLastFilters();
        assertEquals(null, filters.categoryId(), "未传 categoryId 应为 null");
        assertEquals(null, filters.docName(), "未传 docName 应为 null");
        assertTrue(filters.docTypes().isEmpty(), "未传 docTypes 应为空");
        assertTrue(filters.statuses().isEmpty(), "未传 statuses 应为空");
        assertTrue(filters.processStatuses().isEmpty(), "未传 processStatuses 应为空");
        assertEquals(null, filters.metadata(), "未传 metadata 应为 null");
    }

    @Test
    void shouldFailSearchWhenPageSizeExceeded() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"pageSize\":2001}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("每页条数超出限制"));
    }

    @Test
    void shouldFailSearchWhenNoToken() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\"}")
                .when().post("/kb/documents/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 元数据下拉选项（GET /kb/documents/metadata-options）====================

    @Test
    void shouldGetMetadataOptions() {
        MockDocumentQueryService.setPresetOptions(List.of(
                new MetadataOptionGroup("chip_model", "芯片型号", List.of("CV610", "CV620")),
                new MetadataOptionGroup("version", "版本", List.of("v1.0", "v2.1"))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/kb/documents/metadata-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].metaKey", equalTo("chip_model"))
                .body("data[0].label", equalTo("芯片型号"))
                .body("data[0].values", hasItems("CV610", "CV620"))
                .body("data[1].metaKey", equalTo("version"));

        assertEquals("kb-1", MockDocumentQueryService.getLastOptionsKbId(), "kbId 应透传");
    }

    @Test
    void shouldGetMetadataOptionsEmptyWhenNoData() {
        MockDocumentQueryService.setPresetOptions(null);

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-empty")
                .when().get("/kb/documents/metadata-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));

        assertEquals("kb-empty", MockDocumentQueryService.getLastOptionsKbId(), "kbId 应透传");
    }

    @Test
    void shouldFailMetadataOptionsWhenNoToken() {
        given()
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/kb/documents/metadata-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 令牌校验 ========================

    @Test
    void shouldFailWhenNoTokenForCategories() {
        given()
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/categories?kbId=" + KB_ID)
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 辅助方法 ========================

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
