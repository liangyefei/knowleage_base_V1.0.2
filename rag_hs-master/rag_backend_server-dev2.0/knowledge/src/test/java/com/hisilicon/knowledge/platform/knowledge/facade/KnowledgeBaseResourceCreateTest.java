package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
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
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @QuarkusTest} for the create knowledge base endpoint.
 *
 * <p>Uses {@link QuarkusMock#installMockForInstance} to replace the real
 * repository with {@link MockKnowledgeBaseRepository}, avoiding database
 * dependencies.</p>
 *
 * <p>Token note: the endpoint only parses the JWT payload at string level
 * (no signature verification), so tests hand-craft a 3-part token with the
 * required {@code sub} and {@code exp} claims.</p>
 *
 * <p>en-US <em>validation</em> message tests are excluded for the same
 * reason as in the system module (Hibernate Validator en-US locale init).</p>
 */
@QuarkusTest
class KnowledgeBaseResourceCreateTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @Inject
    FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(
                new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        MockKnowledgeBaseRepository.setExistingEntity(null);
        QuarkusMock.installMockForInstance(
                new MockFileStorageService(), fileStorageService);
        MockFileStorageService.reset();
        // 创建成功路径会写 KB_CREATED outbox 事件，须 mock（单元测试无 DB，真实 Panache 会 500）
        QuarkusMock.installMockForInstance(
                new MockDocumentOutboxRepository(), outboxRepository);
        MockDocumentOutboxRepository.reset();
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldCreateKbSuccess() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\",\"description\":\"产品文档合集\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data", equalTo(null));

        // 断言入库字段：默认状态、创建者、时间戳由领域工厂填充
        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getPersistedEntity();
        assertNotNull(entity, "persist 应被调用");
        assertEquals("产品知识库", entity.getName());
        assertEquals("产品文档合集", entity.getDescription());
        assertEquals(Short.valueOf((short) 1), entity.getStatus(), "默认状态应为 1（正常）");
        assertEquals(OPERATOR, entity.getCreatedBy(), "创建者应为操作员 ID");
        assertNotNull(entity.getId(), "ID 应由领域工厂生成");
        assertNotNull(entity.getCreatedAt(), "创建时间不应为空");
        assertNotNull(entity.getUpdatedAt(), "更新时间不应为空");
        // 断言：创建知识库时同步预建物理目录 kb/{清洗后KB名}（快照语义：改名不搬目录，
        // 见 KnowledgeBaseCommandService#create；清洗空段才回退 ID）
        assertEquals("kb/产品知识库", MockFileStorageService.getLastEnsureDirPath(),
                "创建知识库时应预建目录 kb/{KB名}");
    }

    @Test
    void shouldCreateKbSuccess_En() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"kbName\":\"Product KB\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("Operation successful"));
    }

    @Test
    void shouldCreateKbWithEmptyDescription() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\",\"description\":\"\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));
    }

    @Test
    void shouldCreateKbWithUniquifiedDirWhenDirOccupied() {
        // 旧 KB 改名后物理目录仍占旧名：创建同名 KB 时目录须自动唯一化（{名}_1），
        // 实际段持久化到 dirName，预建目录与之一致（快照语义保持，不迁移旧目录）
        MockFileStorageService.setExistingPaths(List.of("kb/产品知识库"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\",\"description\":\"产品文档合集\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getPersistedEntity();
        assertNotNull(entity, "persist 应被调用");
        assertEquals("产品知识库_1", entity.getDirName(), "目录被占用应跳号唯一化并持久化实际段");
        assertEquals("kb/产品知识库_1", MockFileStorageService.getLastEnsureDirPath(),
                "预建目录应使用唯一化后的段");
    }

    // ======================== 参数校验（中文） ========================

    @Test
    void shouldFailWhenKbNameBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"\",\"description\":\"\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("知识库名称不能为空"));
    }

    @Test
    void shouldFailWhenKbNameTooShort() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"ab\"}")
                .when().post("/kb/createKb")
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
                .body("{\"kbName\":\"" + "长".repeat(51) + "\"}")
                .when().post("/kb/createKb")
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
                .body("{\"kbName\":\"产品知识库\",\"description\":\"" + "长".repeat(201) + "\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("描述的长度必须在0到200之间"));
    }

    // ======================== 业务异常 ========================

    @Test
    void shouldFailWhenKbNameDuplicate() {
        MockKnowledgeBaseRepository.setExistingEntity(existingKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("知识库名称已存在，请更换名称"));
    }

    @Test
    void shouldFailWhenKbNameDuplicate_En() {
        MockKnowledgeBaseRepository.setExistingEntity(existingKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("Knowledge base name already exists"));
    }

    @Test
    void shouldCreateKbSuccessWhenSameNameDeleted() {
        // 已软删除（status=0）的同名知识库不占用名称，允许重建
        MockKnowledgeBaseRepository.setExistingEntity(deletedKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\",\"description\":\"产品文档合集\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));

        KnowledgeBaseEntity entity = MockKnowledgeBaseRepository.getPersistedEntity();
        assertNotNull(entity, "persist 应被调用");
        assertEquals("产品知识库", entity.getName());
    }

    // ======================== 令牌校验 ========================

    @Test
    void shouldFailWhenNoAuthorizationHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    @Test
    void shouldFailWhenAuthorizationWithoutBearer() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
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
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    @Test
    void shouldFailWhenTokenExpired_En() {
        String expiredToken = buildToken(OPERATOR, System.currentTimeMillis() / 1000 - 3600);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + expiredToken)
                .header("Accept-Language", "en-US")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("Unauthorized user or access token has expired"));
    }

    @Test
    void shouldFailWhenTokenIsGarbage() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer not-a-jwt")
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0220"))
                .body("message", containsString("非法访问令牌"));
    }

    @Test
    void shouldFailWhenTokenWithoutSub() {
        String noSubToken = buildTokenWithoutSub(System.currentTimeMillis() / 1000 + 3600);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + noSubToken)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbName\":\"产品知识库\"}")
                .when().post("/kb/createKb")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0220"))
                .body("message", containsString("非法访问令牌"));
    }

    // ======================== 辅助方法 ========================

    private static KnowledgeBaseEntity existingKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(UUID.randomUUID().toString()))
                .name("产品知识库")
                .status((short) 1)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static KnowledgeBaseEntity deletedKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(UUID.randomUUID().toString()))
                .name("产品知识库")
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

    private static String buildTokenWithoutSub(long expSeconds) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"exp\":" + expSeconds + "}");
        return header + "." + payload + ".sig";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
