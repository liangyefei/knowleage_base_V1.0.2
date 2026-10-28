package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseMetadataRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @QuarkusTest} for unified metadata edit: {@code POST /kb/metadata/set}.
 * <p>用真实 {@code MetadataCommandService}（mock 各 repository），验证：
 * 三种实体类型的全量替换、空 Map 清空、key/value 防攻击校验、
 * 实体状态约束（KB/CATEGORY 活跃、DOCUMENT 仅 DRAFT/PUBLISHED）。</p>
 */
@QuarkusTest
class MetadataResourceTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);
    private static final String KB_ID = "kb-1";
    private static final String CATEGORY_ID = "cat-1";
    private static final String DOC_ID = "doc-1";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    KnowledgeBaseMetadataRepository kbMetadataRepository;

    @Inject
    CategoryMetadataRepository categoryMetadataRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    DictMetaKeyCacheService dictMetaKeyCacheService;

    @Inject
    DictValueValidator dictValueValidator;

    /** 默认字典白名单：覆盖各成功/状态用例使用的 key（格式校验失败用例不查白名单） */
    private static final java.util.Set<String> DEFAULT_VALID_KEYS =
            java.util.Set.of("org", "default_sensitivity", "security_level",
                    "chip_model", "scenario", "sensitivity", "a", "ok");

    @Inject
    DictOptionQueryService dictOptionQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDictOptionQueryService(), dictOptionQueryService);
        MockDictOptionQueryService.reset();
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseMetadataRepository(), kbMetadataRepository);
        QuarkusMock.installMockForInstance(new MockCategoryMetadataRepository(), categoryMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDictMetaKeyCacheService(), dictMetaKeyCacheService);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        MockKnowledgeBaseRepository.setEntityById(null);
        MockCategoryRepository.setEntityById(null);
        MockDocumentRepository.setEntityById(null);
        MockKnowledgeBaseMetadataRepository.reset();
        MockCategoryMetadataRepository.reset();
        MockDocumentMetadataRepository.reset();
        MockDictMetaKeyCacheService.setPresetKeys(DEFAULT_VALID_KEYS);
        MockDictValueValidator.reset();
    }

    // ======================== 成功路径 ========================

    @Test
    void shouldSetKbMetadata() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"org\":\"海思\",\"default_sensitivity\":\"内部\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(KB_ID, MockKnowledgeBaseMetadataRepository.getLastKbId(), "kbId 应透传");
        assertNotNull(MockKnowledgeBaseMetadataRepository.getLastMetadata(), "metadata 应透传");
        assertEquals("海思", MockKnowledgeBaseMetadataRepository.getLastMetadata().get("org"));
        assertEquals("内部", MockKnowledgeBaseMetadataRepository.getLastMetadata().get("default_sensitivity"));
    }

    @Test
    void shouldSetCategoryMetadata() {
        MockCategoryRepository.setEntityById(activeCategory());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("CATEGORY", CATEGORY_ID, "{\"security_level\":\"L3\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(CATEGORY_ID, MockCategoryMetadataRepository.getLastCategoryId());
        assertEquals("L3", MockCategoryMetadataRepository.getLastMetadata().get("security_level"));
    }

    @Test
    void shouldSetDraftDocumentMetadata() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_DRAFT));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("DOCUMENT", DOC_ID, "{\"chip_model\":\"CV620\",\"scenario\":\"AI\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(DOC_ID, MockDocumentMetadataRepository.getLastDocumentId());
        assertEquals("CV620", MockDocumentMetadataRepository.getLastMetadata().get("chip_model"));
    }

    @Test
    void shouldSetPublishedDocumentMetadata() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_PUBLISHED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("DOCUMENT", DOC_ID, "{\"sensitivity\":\"公开\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("公开", MockDocumentMetadataRepository.getLastMetadata().get("sensitivity"));
    }

    @Test
    void shouldClearMetadataWithEmptyMap() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertNotNull(MockKnowledgeBaseMetadataRepository.getLastMetadata(), "空 Map 应透传（清空语义）");
        assertEquals(0, MockKnowledgeBaseMetadataRepository.getLastMetadata().size(), "空 Map = 清空");
    }

    // ======================== 参数校验（400） ========================

    @Test
    void shouldFailWhenEntityTypeMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"entityId\":\"" + KB_ID + "\",\"metadata\":{\"a\":\"1\"}}")
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("元数据实体类型不能为空"));
    }

    @Test
    void shouldFailWhenEntityIdMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"entityType\":\"KB\",\"metadata\":{\"a\":\"1\"}}")
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("元数据所属对象ID不能为空"));
    }

    @Test
    void shouldFailWhenMetadataNull() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"entityType\":\"KB\",\"entityId\":\"" + KB_ID + "\"}")
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("元数据不能为空"));
    }

    @Test
    void shouldFailWhenMetadataTooMany() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"key").append(i).append("\":\"v").append(i).append("\"");
        }
        sb.append("}");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, sb.toString()))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("元数据条目不能超过50个"));
    }

    // ======================== 业务校验（200 + A0420） ========================

    @Test
    void shouldFailWhenEntityTypeInvalid() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("XXX", KB_ID, "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据实体类型不合法"));
    }

    @Test
    void shouldFailWhenKeyStartsWithDigit() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"1abc\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据键不合法"));
    }

    @Test
    void shouldFailWhenKeyHasInvalidChar() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"abc-def\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据键不合法"));
    }

    @Test
    void shouldFailWhenKeyTooLong() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());
        String longKey = "k".repeat(51);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"" + longKey + "\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据键不合法"));
    }

    @Test
    void shouldFailWhenValueTooLong() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());
        String longValue = "v".repeat(201);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"ok\":\"" + longValue + "\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据值不合法"));
    }

    @Test
    void shouldFailWhenValueHasControlChar() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"ok\":\"line1\\nline2\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据值不合法"));
    }

    @Test
    void shouldFailWhenValueBlank() {
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"ok\":\"   \"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("元数据值不合法"));
    }

    // ======================== 实体不存在 / 状态约束 ========================

    @Test
    void shouldFailWhenKbNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", "kb-none", "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("知识库不存在"));
    }

    @Test
    void shouldFailWhenKbDeleted() {
        MockKnowledgeBaseRepository.setEntityById(deletedKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("知识库不存在"));
    }

    @Test
    void shouldFailWhenCategoryNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("CATEGORY", "cat-none", "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
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
                .body(body("CATEGORY", CATEGORY_ID, "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("类别不存在"));
    }

    @Test
    void shouldFailWhenDocumentNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("DOCUMENT", "doc-none", "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("文档不存在"));
    }

    @Test
    void shouldFailWhenDocumentReviewing() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_REVIEWING));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("DOCUMENT", DOC_ID, "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("文档状态不正确"));
    }

    @Test
    void shouldFailWhenDocumentArchived() {
        MockDocumentRepository.setEntityById(document(DocumentEntity.STATUS_ARCHIVED));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("DOCUMENT", DOC_ID, "{\"a\":\"1\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", containsString("文档状态不正确"));
    }

    @Test
    void shouldNotCallRepositoryWhenValidationFails() {
        // key 校验失败时不应触碰任何元数据仓储（防攻击语义：校验先于任何写操作）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"bad key\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));

        assertNull(MockKnowledgeBaseMetadataRepository.getLastKbId(), "key 非法时不应调用 replaceAll");
    }

    // ======================== 字典白名单校验（方案 A：全量白名单） ========================

    @Test
    void shouldFailWhenKeyNotInDict() {
        // 白名单校验失败短路：不查实体、不调用 replaceAll
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"unknown_meta\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("不在字典管控范围内"));

        assertNull(MockKnowledgeBaseMetadataRepository.getLastKbId(), "key 不在字典白名单时不应调用 replaceAll");
    }

    @Test
    void shouldFailWhenDictEmpty() {
        MockDictMetaKeyCacheService.setPresetKeys(java.util.Set.of());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"a\":\"v\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("不在字典管控范围内"));
    }

    @Test
    void shouldFailWhenOneKeyNotInDictAmongValidOnes() {
        // 混合 key：一个合法一个非法 → 整体拒绝（全量白名单）
        MockKnowledgeBaseRepository.setEntityById(activeKb());

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body(body("KB", KB_ID, "{\"org\":\"海思\",\"evil_key\":\"x\"}"))
                .when().post("/kb/metadata/set")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("不在字典管控范围内"));

        assertNull(MockKnowledgeBaseMetadataRepository.getLastKbId(), "任一 key 不在白名单时整体拒绝");
    }

    // ======================== 实体构造辅助 ========================

    private static String body(String entityType, String entityId, String metadataJson) {
        return "{\"entityType\":\"" + entityType + "\",\"entityId\":\"" + entityId
                + "\",\"metadata\":" + metadataJson + "}";
    }

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("产品知识库")
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
                .status((short) 0)
                .createdBy(OPERATOR)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static CategoryEntity activeCategory() {
        return CategoryEntity.builder()
                .id(CategoryId.of(CATEGORY_ID))
                .kbId(KB_ID)
                .parentId(null)
                .name("深度学习")
                .sortOrder(1)
                .inheritParent(true)
                .status((short) 1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static CategoryEntity deletedCategory() {
        return CategoryEntity.builder()
                .id(CategoryId.of(CATEGORY_ID))
                .kbId(KB_ID)
                .parentId(null)
                .name("深度学习")
                .sortOrder(1)
                .inheritParent(true)
                .status((short) 0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DocumentEntity document(String status) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId(KB_ID)
                .title("测试文档")
                .status(status)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .publishedVersion(DocumentEntity.STATUS_PUBLISHED.equals(status) ? "1" : null)
                .hasPublishedVersion(false)
                .filePath("kb-1/测试文档.txt")
                .fileSize(1024L)
                .mimeType("text/plain")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
