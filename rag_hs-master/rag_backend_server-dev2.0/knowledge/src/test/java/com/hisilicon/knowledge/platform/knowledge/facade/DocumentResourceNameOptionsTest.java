package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DocumentGlobalSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentNameOption;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code @QuarkusTest} for the document name-options dropdown endpoint.
 *
 * <p>Mocks {@link DocumentGlobalSearchQueryService} to avoid database access in tests.
 * Token note: the endpoint only parses the JWT payload at string level
 * (no signature verification), so tests hand-craft a 3-part token.</p>
 */
@QuarkusTest
class DocumentResourceNameOptionsTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentGlobalSearchQueryService documentGlobalSearchQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentGlobalSearchQueryService(), documentGlobalSearchQueryService);
        MockDocumentGlobalSearchQueryService.reset();
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldReturnOptions() {
        MockDocumentGlobalSearchQueryService.setPresetOptions(List.of(
                new DocumentNameOption("doc-1", "需求规格说明书"),
                new DocumentNameOption("doc-2", "接口设计文档"),
                new DocumentNameOption("doc-3", "测试报告")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data", hasSize(3))
                .body("data[0].id", equalTo("doc-1"))
                .body("data[0].title", equalTo("需求规格说明书"));
        // 接口层透传 nodeType/nodeId 到查询服务
        org.junit.jupiter.api.Assertions.assertEquals("KB", MockDocumentGlobalSearchQueryService.getLastNodeType());
        org.junit.jupiter.api.Assertions.assertEquals("kb-1", MockDocumentGlobalSearchQueryService.getLastNodeId());
    }

    @Test
    void shouldTrimNodeIdAndVersionBeforePassingToService() {
        MockDocumentGlobalSearchQueryService.setPresetOptions(List.of());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "CATEGORY")
                .queryParam("nodeId", "  cat-1  ")
                .queryParam("version", "  v2.0  ")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        org.junit.jupiter.api.Assertions.assertEquals("CATEGORY", MockDocumentGlobalSearchQueryService.getLastNodeType());
        org.junit.jupiter.api.Assertions.assertEquals("cat-1", MockDocumentGlobalSearchQueryService.getLastNodeId());
        org.junit.jupiter.api.Assertions.assertEquals("v2.0", MockDocumentGlobalSearchQueryService.getLastVersion());
    }

    @Test
    void shouldReturnEmptyWhenNoData() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    @Test
    void shouldWorkWithoutVersionParam() {
        MockDocumentGlobalSearchQueryService.setPresetOptions(List.of(
                new DocumentNameOption("doc-1", "任意文档")));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1));
        org.junit.jupiter.api.Assertions.assertNull(MockDocumentGlobalSearchQueryService.getLastVersion());
    }

    // ======================== 超量提示场景 ========================

    @Test
    void shouldFailWhenTooMany() {
        MockDocumentGlobalSearchQueryService.setTooMany(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0434"))
                .body("message", containsString("数据过多，请缩小查询范围"))
                .body("data", nullValue());
    }

    @Test
    void shouldFailWhenTooManyEn() {
        MockDocumentGlobalSearchQueryService.setTooMany(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0434"))
                .body("message", containsString("Too many results, please narrow the search scope"))
                .body("data", nullValue());
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoAuthHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/documents/name-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    @Test
    void shouldFailWhenTokenExpired() {
        String expiredToken = buildToken(OPERATOR, System.currentTimeMillis() / 1000 - 3600);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + expiredToken)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/documents/name-options")
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
