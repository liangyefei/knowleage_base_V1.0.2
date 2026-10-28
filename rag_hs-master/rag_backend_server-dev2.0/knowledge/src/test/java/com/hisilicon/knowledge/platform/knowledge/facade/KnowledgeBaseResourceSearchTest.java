package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.KbSearchQueryService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code @QuarkusTest} for the knowledge base search endpoint.
 *
 * <p>Mocks {@link KbSearchQueryService} to avoid database access in tests.
 * Token note: the endpoint only parses the JWT payload at string level
 * (no signature verification), so tests hand-craft a 3-part token.</p>
 */
@QuarkusTest
class KnowledgeBaseResourceSearchTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    KbSearchQueryService searchQueryService;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockKbSearchQueryService(), searchQueryService);
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldSearchSuccess() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbName", "产品")
                .queryParam("page", 1)
                .queryParam("pageSize", 20)
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data.records", hasSize(0))
                .body("data.totalRow", equalTo(0));
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoAuthHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/search")
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
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 参数校验 ========================

    @Test
    void shouldFailWhenStatusInvalid() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("status", 2)
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("状态参数不合法，只能为0或1"));
    }

    @Test
    void shouldFailWhenPageSizeTooLarge() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("pageSize", 1001)
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("每页条数超出限制，最大为1000"));
    }

    @Test
    void shouldFailWhenPageSizeTooLarge_En() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .queryParam("pageSize", 1001)
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("Page size exceeds the limit, maximum is 1000"));
    }

    @Test
    void shouldSearchSuccessWithMaxPageSize() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("pageSize", 1000)
                .when().get("/kb/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
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
