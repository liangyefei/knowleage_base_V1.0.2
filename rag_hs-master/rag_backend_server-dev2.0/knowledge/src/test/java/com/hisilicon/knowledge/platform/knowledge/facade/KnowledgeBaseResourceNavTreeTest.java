package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.NavTreeQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @QuarkusTest} for the navigation tree endpoint {@code GET /kb/navTree}.
 */
@QuarkusTest
class KnowledgeBaseResourceNavTreeTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    NavTreeQueryService navTreeQueryService;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockNavTreeQueryService(), navTreeQueryService);
        MockNavTreeQueryService.reset();
    }

    // ======================== 根模式：全部 KB + 根子类别 ========================

    @Test
    void shouldReturnEmptyTree() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    @Test
    void shouldReturnKbWithoutRootCategories() {
        MockNavTreeQueryService.setRootTree(List.of(
                new NavTreeItem("kb-1", NavTreeItem.TYPE_KB, "FAQ知识库", "kb-1",
                        86, 0, false, List.of())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].nodeId", equalTo("kb-1"))
                .body("data[0].nodeType", equalTo("KB"))
                .body("data[0].name", equalTo("FAQ知识库"))
                .body("data[0].kbId", equalTo("kb-1"))
                .body("data[0].documentCount", equalTo(86))
                .body("data[0].categoryCount", equalTo(0))
                .body("data[0].hasChildren", equalTo(false))
                .body("data[0].children", hasSize(0));
    }

    @Test
    void shouldReturnKbWithRootCategoriesLazy() {
        MockNavTreeQueryService.setRootTree(List.of(
                new NavTreeItem("kb-1", NavTreeItem.TYPE_KB, "芯片知识库", "kb-1",
                        128, 3, true, List.of(
                                new NavTreeItem("cat-1", NavTreeItem.TYPE_CATEGORY, "CV620系列", "kb-1",
                                        56, 7, true, List.of()),
                                new NavTreeItem("cat-2", NavTreeItem.TYPE_CATEGORY, "CV720系列", "kb-1",
                                        18, 2, false, List.of())))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(1))
                // KB 节点
                .body("data[0].name", equalTo("芯片知识库"))
                .body("data[0].documentCount", equalTo(128))
                .body("data[0].categoryCount", equalTo(3))
                .body("data[0].hasChildren", equalTo(true))
                // 根子类别（懒加载：children 为空）
                .body("data[0].children", hasSize(2))
                .body("data[0].children[0].nodeType", equalTo("CATEGORY"))
                .body("data[0].children[0].name", equalTo("CV620系列"))
                .body("data[0].children[0].kbId", equalTo("kb-1"))
                .body("data[0].children[0].documentCount", equalTo(56))
                .body("data[0].children[0].categoryCount", equalTo(7))
                .body("data[0].children[0].hasChildren", equalTo(true))
                .body("data[0].children[0].children", hasSize(0))
                .body("data[0].children[1].name", equalTo("CV720系列"))
                .body("data[0].children[1].hasChildren", equalTo(false));
    }

    @Test
    void shouldReturnMultipleKbsInOrder() {
        MockNavTreeQueryService.setRootTree(List.of(
                new NavTreeItem("kb-1", NavTreeItem.TYPE_KB, "芯片知识库", "kb-1",
                        128, 3, true, List.of()),
                new NavTreeItem("kb-2", NavTreeItem.TYPE_KB, "FAQ知识库", "kb-2",
                        86, 0, false, List.of()),
                new NavTreeItem("kb-3", NavTreeItem.TYPE_KB, "开发教程", "kb-3",
                        24, 0, false, List.of())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(3))
                .body("data[0].name", equalTo("芯片知识库"))
                .body("data[1].name", equalTo("FAQ知识库"))
                .body("data[2].name", equalTo("开发教程"));
    }

    // ======================== 懒加载模式：指定类别的下一层 ========================

    @Test
    void shouldLoadChildrenByParentId() {
        MockNavTreeQueryService.setSubTree("cat-1", List.of(
                new NavTreeItem("cat-11", NavTreeItem.TYPE_CATEGORY, "寄存器手册", "kb-1",
                        24, 3, true, List.of()),
                new NavTreeItem("cat-12", NavTreeItem.TYPE_CATEGORY, "SDK开发指南", "kb-1",
                        20, 0, false, List.of())));

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
                .body("data[0].documentCount", equalTo(24))
                .body("data[0].categoryCount", equalTo(3))
                .body("data[0].hasChildren", equalTo(true))
                .body("data[0].children", hasSize(0))
                .body("data[1].name", equalTo("SDK开发指南"))
                .body("data[1].hasChildren", equalTo(false));
    }

    @Test
    void shouldReturnEmptyWhenNoChildren() {
        // 无子类或 parentId 不存在 → 空列表
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-none")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    // ======================== 搜索：keyword 透传 ========================

    @Test
    void shouldPassKeywordToService() {
        MockNavTreeQueryService.setRootTree(List.of(
                new NavTreeItem("kb-1", NavTreeItem.TYPE_KB, "芯片知识库", "kb-1",
                        128, 3, true, List.of(
                                new NavTreeItem("cat-1", NavTreeItem.TYPE_CATEGORY, "寄存器手册", "kb-1",
                                        24, 2, true, List.of())))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "芯片")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].name", equalTo("芯片知识库"))
                .body("data[0].children[0].name", equalTo("寄存器手册"));

        assertEquals("芯片", MockNavTreeQueryService.getLastKeyword(), "keyword 应透传给查询服务");
        assertEquals(null, MockNavTreeQueryService.getLastParentId(), "未传 parentId 应为 null");
    }

    @Test
    void shouldPassKeywordWithParentId() {
        MockNavTreeQueryService.setSubTree("cat-1", List.of(
                new NavTreeItem("cat-11", NavTreeItem.TYPE_CATEGORY, "寄存器手册", "kb-1",
                        24, 2, true, List.of())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("parentId", "cat-1")
                .queryParam("keyword", "寄存器")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].name", equalTo("寄存器手册"));

        assertEquals("cat-1", MockNavTreeQueryService.getLastParentId(), "parentId 应透传");
        assertEquals("寄存器", MockNavTreeQueryService.getLastKeyword(), "keyword 应透传");
    }

    @Test
    void shouldPassBlankKeywordAsEmpty() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("keyword", "   ")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // RESTEasy 将空白 queryParam 归一化为空串，服务层按空白处理（不过滤）
        assertEquals("", MockNavTreeQueryService.getLastKeyword(), "空白 keyword 归一化为空串透传");
    }

    @Test
    void shouldPassNullKeywordWhenNotProvided() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(null, MockNavTreeQueryService.getLastKeyword(), "未传 keyword 应为 null");
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoToken() {
        given()
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    @Test
    void shouldFailWhenTokenExpired() {
        String expiredToken = buildToken(OPERATOR, System.currentTimeMillis() / 1000 - 3600);

        given()
                .header("Authorization", "Bearer " + expiredToken)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
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
