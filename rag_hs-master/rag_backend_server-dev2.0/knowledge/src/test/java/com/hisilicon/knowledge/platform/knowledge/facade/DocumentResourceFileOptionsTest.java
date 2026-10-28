package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DocumentGlobalSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileOptionNode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
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
 * {@code @QuarkusTest} for the direction-based file-options tree endpoint
 * ({@code GET /kb/documents/file-options}，代码↔文档映射：选择源文件，树状展示).
 *
 * <p>Mocks {@link DocumentGlobalSearchQueryService} to avoid database access in tests
 * （与 {@link DocumentResourceNameOptionsTest} 同模式）；树形组装、搜索裁剪与查询口径
 * （PUBLISHED/活跃 KB/孤儿排除/docType 限定/50 护栏）由 {@code DocumentFileOptionsIT}
 * 真实数据库覆盖。</p>
 */
@QuarkusTest
class DocumentResourceFileOptionsTest {

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
    void shouldReturnTreeWithTrimmedParams() {
        MockDocumentGlobalSearchQueryService.setPresetFileTree(new FileOptionNode(
                "kb-1", "CV610", "KB", null, null, null, null,
                List.of(new FileOptionNode("doc-1", "CV610选型指南", "DOCUMENT", "DOCUMENT", "1",
                        "test.txt", "txt", List.of()))));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "  doc2code  ")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "  kb-1  ")
                .queryParam("keyword", " CV610 ")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.id", equalTo("kb-1"))
                .body("data.nodeType", equalTo("KB"))
                .body("data.children", hasSize(1))
                .body("data.children[0].id", equalTo("doc-1"))
                .body("data.children[0].nodeType", equalTo("DOCUMENT"))
                .body("data.children[0].docType", equalTo("DOCUMENT"))
                .body("data.children[0].currentVersion", equalTo("1"))
                .body("data.children[0].fileName", equalTo("test.txt"))
                .body("data.children[0].fileType", equalTo("txt"));
        // 接口层透传 direction/nodeType/nodeId/keyword 到查询服务
        org.junit.jupiter.api.Assertions.assertEquals("doc2code", MockDocumentGlobalSearchQueryService.getLastDirection());
        org.junit.jupiter.api.Assertions.assertEquals("KB", MockDocumentGlobalSearchQueryService.getLastNodeType());
        org.junit.jupiter.api.Assertions.assertEquals("kb-1", MockDocumentGlobalSearchQueryService.getLastNodeId());
        // GET 参数由 TrimStringParamConverterProvider 统一 trim，keyword 到达服务层时已去空白
        org.junit.jupiter.api.Assertions.assertEquals("CV610", MockDocumentGlobalSearchQueryService.getLastKeyword());
    }

    @Test
    void shouldWorkWithoutKeyword() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "code2doc")
                .queryParam("nodeType", "CATEGORY")
                .queryParam("nodeId", "cat-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", nullValue());
        org.junit.jupiter.api.Assertions.assertEquals("code2doc", MockDocumentGlobalSearchQueryService.getLastDirection());
        org.junit.jupiter.api.Assertions.assertNull(MockDocumentGlobalSearchQueryService.getLastKeyword());
    }

    // ======================== 参数校验场景 ========================

    @Test
    void shouldFailWhenDirectionInvalid() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "code2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("映射方向不合法，只能为 doc2code 或 code2doc"));
    }

    @Test
    void shouldFailWhenDirectionInvalidEn() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .queryParam("direction", "bogus")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("Invalid direction, only doc2code or code2doc is allowed"));
    }

    @Test
    void shouldFailWhenNodeTypeInvalid() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "DOCUMENT")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("节点类型不合法"));
    }

    @Test
    void shouldFailWhenDirectionMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("映射方向不能为空"));
    }

    // ======================== 超量提示场景 ========================

    @Test
    void shouldFailWhenTooMany() {
        MockDocumentGlobalSearchQueryService.setTooMany(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0434"))
                .body("message", containsString("数据过多，请缩小查询范围"))
                .body("data", nullValue());
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoAuthHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .queryParam("direction", "doc2code")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/file-options")
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
