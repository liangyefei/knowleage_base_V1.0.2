package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOptionGroup;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code @QuarkusTest} for {@code GET /kb/metadata/dict-options}.
 * <p>用 MockDictOptionQueryService 预设返回，验证：4 组固定顺序、
 * 分组结构（metaKey/label/multiSelect/options）、中文标签与鉴权。</p>
 */
@QuarkusTest
class DictOptionResourceTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
    }

    @Test
    void shouldReturnFourGroupsWithStructure() {
        MockDictOptionQueryService.setPresetGroups(List.of(
                new UploadDictOptionGroup("product", "产品", true, false,
                        List.of(new UploadDictOption("看看宝Pro S3", "看看宝Pro S3"))),
                new UploadDictOptionGroup("chip_model", "芯片型号", true, false,
                        List.of(new UploadDictOption("CV610", "CV610"),
                                new UploadDictOption("CV620", "CV620"))),
                new UploadDictOptionGroup("version", "版本", false, false,
                        List.of(new UploadDictOption("v1.1.0", "v1.1.0"))),
                new UploadDictOptionGroup("sensitivity", "安全等级", false, false,
                        List.of(new UploadDictOption("公开", "PUBLIC"),
                                new UploadDictOption("绝密", "TOP_SECRET")))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .accept(ContentType.JSON)
                .when().get("/kb/metadata/dict-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(4))
                .body("data[0].metaKey", equalTo("product"))
                .body("data[0].label", equalTo("产品"))
                .body("data[0].multiSelect", equalTo(true))
                .body("data[0].options[0].label", equalTo("看看宝Pro S3"))
                .body("data[0].options[0].value", equalTo("看看宝Pro S3"))
                .body("data[1].metaKey", equalTo("chip_model"))
                .body("data[1].options", hasSize(2))
                .body("data[3].metaKey", equalTo("sensitivity"))
                .body("data[3].label", equalTo("安全等级"))
                .body("data[3].multiSelect", equalTo(false))
                .body("data[3].options[1].value", equalTo("TOP_SECRET"));
    }

    @Test
    void shouldReturnEmptyGroupsWhenNoPreset() {
        MockDictOptionQueryService.setPresetGroups(List.of());

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .accept(ContentType.JSON)
                .when().get("/kb/metadata/dict-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    @Test
    void shouldRequireAuth() {
        given()
                .accept(ContentType.JSON)
                .when().get("/kb/metadata/dict-options")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"));
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
