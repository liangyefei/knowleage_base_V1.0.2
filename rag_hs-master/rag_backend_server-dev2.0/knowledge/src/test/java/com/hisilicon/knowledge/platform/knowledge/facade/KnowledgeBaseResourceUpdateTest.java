package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @QuarkusTest} for the update knowledge base endpoint.
 *
 * <p>Mocks {@link KnowledgeBaseRepository} to avoid database access in tests.
 * Token note: the endpoint only parses the JWT payload at string level
 * (no signature verification), so tests hand-craft a 3-part token.</p>
 */
@QuarkusTest
class KnowledgeBaseResourceUpdateTest {

    private static final String OPERATOR = "admin";
    private static final String KB_ID = UUID.randomUUID().toString();
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        MockKnowledgeBaseRepository.setExistingEntity(null);
        MockKnowledgeBaseRepository.setEntityById(existingKb());
        // 更新成功路径会写 KB_UPDATED outbox 事件，须 mock（单元测试无 DB，真实 Panache 会 500）
        QuarkusMock.installMockForInstance(
                new MockDocumentOutboxRepository(), outboxRepository);
        MockDocumentOutboxRepository.reset();
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldUpdateKbNameAndDescriptionSuccess() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\",\"description\":\"新描述\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data", equalTo(null));

        // 断言更新后的实体：字段更新，创建信息保留
        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(entity, "update 应被调用");
        assertEquals("新名称", entity.getName());
        assertEquals("新描述", entity.getDescription());
        assertEquals(KB_ID, entity.getId().value(), "ID 不应变化");
        assertEquals(OPERATOR, entity.getCreatedBy(), "创建者不应变化");
        assertNotNull(entity.getUpdatedAt(), "更新时间应被刷新");
    }

    @Test
    void shouldUpdateKbNameOnlySuccess() {
        // 全量替换：未传 description → 描述清空
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(entity, "update 应被调用");
        assertEquals("新名称", entity.getName());
        assertEquals(null, entity.getDescription(), "未传 description 时应清空描述");
    }

    @Test
    void shouldUpdateKbDescriptionOnlySuccess() {
        // kbName 必填：只改描述时需带上名称（原值或新值均可）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"产品知识库\",\"description\":\"新描述\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(entity, "update 应被调用");
        assertEquals("产品知识库", entity.getName());
        assertEquals("新描述", entity.getDescription());
    }

    @Test
    void shouldUpdateKbWithSameNameSuccess() {
        // 名称与原名称相同 → 跳过唯一性校验（即使 existingEntity 非空也不报重复）
        MockKnowledgeBaseRepository.setExistingEntity(
                KnowledgeBaseEntity.builder()
                        .id(KnowledgeBaseId.of(UUID.randomUUID().toString()))
                        .name("产品知识库")
                        .status((short) 1)
                        .createdBy(OPERATOR)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"产品知识库\",\"description\":\"新描述\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(entity, "update 应被调用");
        assertEquals("产品知识库", entity.getName());
        assertEquals("新描述", entity.getDescription());
    }

    @Test
    void shouldUpdateKbClearDescriptionSuccess() {
        // kbName 提供值 + description 空串 → 允许，清空描述
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\",\"description\":\"\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getUpdatedEntity();
        assertNotNull(entity, "update 应被调用");
        assertEquals("新名称", entity.getName());
        assertEquals("", entity.getDescription(), "空串应清空描述");
    }

    // ======================== 参数校验 ========================

    @Test
    void shouldFailWhenKbIdBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库ID不能为空"));
    }

    @Test
    void shouldFailWhenKbNameTooShort() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"ab\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库名称的长度必须在3到50之间"));
    }

    @Test
    void shouldFailWhenKbNameTooLong() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"" + "长".repeat(51) + "\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库名称的长度必须在3到50之间"));
    }

    @Test
    void shouldFailWhenDescriptionTooLong() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"description\":\"" + "长".repeat(201) + "\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("描述的长度必须在0到200之间"));
    }

    @Test
    void shouldFailWhenNothingToUpdate() {
        // kbName 必填：缺省 → 400 名称不能为空（全量替换语义下无需"至少一个字段"校验）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库名称不能为空"));
    }

    @Test
    void shouldFailWhenAllFieldsBlank() {
        // kbName 空串命中 @NotBlank → 400；"不修改"应不传字段
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"\",\"description\":\"\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库名称不能为空"));
    }

    @Test
    void shouldFailWhenNothingToUpdate_En() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"kbId\":\"" + KB_ID + "\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("Knowledge base name cannot be blank"));
    }

    // ======================== 业务异常 ========================

    @Test
    void shouldFailWhenKbNotFound() {
        MockKnowledgeBaseRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("知识库不存在"));
    }

    @Test
    void shouldFailWhenKbNotFound_En() {
        MockKnowledgeBaseRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("Knowledge base not found"));
    }

    @Test
    void shouldFailWhenKbAlreadyDeleted() {
        // 已软删除（status=0）的知识库不可编辑
        MockKnowledgeBaseRepository.setEntityById(deletedKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("知识库不存在"));
    }

    @Test
    void shouldFailWhenKbNameDuplicate() {
        // 已存在另一个同名知识库（名称与原 KB 不同 → 触发唯一性校验）
        MockKnowledgeBaseRepository.setExistingEntity(otherKb("研发知识库"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"研发知识库\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("知识库名称已存在，请更换名称"));
    }

    @Test
    void shouldFailWhenKbNameDuplicate_En() {
        MockKnowledgeBaseRepository.setExistingEntity(otherKb("研发知识库"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"研发知识库\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("Knowledge base name already exists"));
    }

    // ======================== 令牌校验 ========================

    @Test
    void shouldFailWhenNoAuthorizationHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
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
                .body("{\"kbId\":\"" + KB_ID + "\",\"kbName\":\"新名称\"}")
                .when().post("/kb/updateKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 辅助方法 ========================

    private static KnowledgeBaseEntity existingKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("产品知识库")
                .description("原描述")
                .status((short) 1)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static KnowledgeBaseEntity otherKb(String name) {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(UUID.randomUUID().toString()))
                .name(name)
                .status((short) 1)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static KnowledgeBaseEntity deletedKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("产品知识库")
                .description("原描述")
                .status((short) 0)
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
