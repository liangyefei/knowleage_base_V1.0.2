package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.command.NodeCommandService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NodeCreatedResult;
import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @QuarkusTest} for unified node operations: {@code POST /kb/node/create|rename|delete}.
 */
@QuarkusTest
class KnowledgeBaseResourceNodeTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    NodeCommandService nodeCommandService;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockNodeCommandService(), nodeCommandService);
        MockNodeCommandService.reset();
    }

    // ======================== 新建子文件夹 ========================

    @Test
    void shouldCreateNodeUnderKb() {
        MockNodeCommandService.setPresetResult(new NodeCreatedResult("cat-new", "CATEGORY", "新根类别"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"新根类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.nodeId", equalTo("cat-new"))
                .body("data.nodeType", equalTo("CATEGORY"))
                .body("data.name", equalTo("新根类别"));

        assertEquals("kb-1", MockNodeCommandService.getLastCreate().parentId(), "parentId 应透传");
        assertEquals("KB", MockNodeCommandService.getLastCreate().parentNodeType(), "parentNodeType 应透传");
        assertEquals("新根类别", MockNodeCommandService.getLastCreate().name(), "name 应透传");
    }

    @Test
    void shouldCreateNodeUnderCategory() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"cat-1\",\"parentNodeType\":\"CATEGORY\",\"name\":\"新子类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("cat-1", MockNodeCommandService.getLastCreate().parentId());
        assertEquals("CATEGORY", MockNodeCommandService.getLastCreate().parentNodeType());
    }

    @Test
    void shouldFailCreateWhenNameMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("节点名称不能为空"));
    }

    @Test
    void shouldFailCreateWhenParentNodeTypeMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"name\":\"新类别\"}")
                .when().post("/kb/node/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("父节点类型不能为空"));
    }

    // ======================== 重命名 ========================

    @Test
    void shouldRenameKbNode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\",\"name\":\"新知识库名\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("kb-1", MockNodeCommandService.getLastRename().nodeId(), "nodeId 应透传");
        assertEquals("KB", MockNodeCommandService.getLastRename().nodeType(), "nodeType 应透传");
        assertEquals("新知识库名", MockNodeCommandService.getLastRename().name(), "name 应透传");
    }

    @Test
    void shouldRenameCategoryNode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\",\"name\":\"新类别名\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("cat-1", MockNodeCommandService.getLastRename().nodeId());
        assertEquals("CATEGORY", MockNodeCommandService.getLastRename().nodeType());
    }

    @Test
    void shouldFailRenameWhenNameMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/rename")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("节点名称不能为空"));
    }

    // ======================== 删除 ========================

    @Test
    void shouldDeleteKbNode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\",\"nodeType\":\"KB\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("kb-1", MockNodeCommandService.getLastDelete().nodeId(), "nodeId 应透传");
        assertEquals("KB", MockNodeCommandService.getLastDelete().nodeType(), "nodeType 应透传");
    }

    @Test
    void shouldDeleteCategoryNode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"cat-1\",\"nodeType\":\"CATEGORY\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("cat-1", MockNodeCommandService.getLastDelete().nodeId());
        assertEquals("CATEGORY", MockNodeCommandService.getLastDelete().nodeType());
    }

    @Test
    void shouldFailDeleteWhenNodeTypeMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"nodeId\":\"kb-1\"}")
                .when().post("/kb/node/delete")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("节点类型不能为空"));
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoToken() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"parentId\":\"kb-1\",\"parentNodeType\":\"KB\",\"name\":\"x\"}")
                .when().post("/kb/node/create")
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
