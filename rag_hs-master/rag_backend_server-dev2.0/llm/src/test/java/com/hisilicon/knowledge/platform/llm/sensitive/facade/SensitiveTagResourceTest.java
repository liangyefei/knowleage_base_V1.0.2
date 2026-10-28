package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache.SensitiveWordCacheService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveTagQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveWordQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagStatistics;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagWordCount;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 敏感词标签管理接口测试（create / edit / delete / changeStatus / search）
 */
@QuarkusTest
class SensitiveTagResourceTest {

    private static final String VALID_TOKEN = buildToken("admin", System.currentTimeMillis() / 1000 + 3600);

    @Inject
    SensitiveTagRepository tagRepository;

    @Inject
    SensitiveLexiconRepository lexiconRepository;

    @Inject
    SensitiveWordCacheService cacheService;

    @Inject
    SensitiveTagQueryService tagQueryService;

    @Inject
    SensitiveWordQueryService wordQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockSensitiveTagRepository(), tagRepository);
        QuarkusMock.installMockForInstance(new MockSensitiveLexiconRepository(), lexiconRepository);
        QuarkusMock.installMockForInstance(new MockSensitiveWordCacheService(), cacheService);
        QuarkusMock.installMockForInstance(new MockSensitiveTagQueryService(), tagQueryService);
        QuarkusMock.installMockForInstance(new MockSensitiveWordQueryService(), wordQueryService);
        MockSensitiveTagRepository.reset();
        MockSensitiveLexiconRepository.reset();
        MockSensitiveWordCacheService.reset();
        MockSensitiveTagQueryService.reset();
        MockSensitiveWordQueryService.reset();
    }

    /** 创建成功：持久化 + 缓存失效 1 次 */
    @Test
    void shouldCreateTagAndEvictCache() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"textLabel\":\"涉政\",\"labelIdentification\":\"POLITICAL\",\"remarks\":\"政治类\",\"status\":1}")
                .when().post("/system/sensitive/tags/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.textLabel", equalTo("涉政"))
                .body("data.status", equalTo(1));

        SensitiveTagEntity persisted = MockSensitiveTagRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals("涉政", persisted.getTextLabel());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount(), "创建后应失效缓存");
    }

    /** 文本标签重复（未删除记录内）→ A0429 */
    @Test
    void shouldRejectDuplicateTagLabel() {
        MockSensitiveTagRepository.setEntityByLabel(activeTag("t1", "涉政"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"textLabel\":\"涉政\",\"labelIdentification\":\"POLITICAL2\",\"status\":1}")
                .when().post("/system/sensitive/tags/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"));

        assertEquals(0, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 编辑：存在性 + 唯一性（排除自身）校验，更新后失效缓存 */
    @Test
    void shouldEditTagExcludingSelfOnUniqueCheck() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "涉政"));
        MockSensitiveTagRepository.setEntityByLabel(activeTag("t1", "涉政")); // 同一记录（自身）

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"textLabel\":\"涉政\",\"labelIdentification\":\"POLICY\",\"status\":2}")
                .when().post("/system/sensitive/tags/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertNotNull(MockSensitiveTagRepository.getUpdatedEntity());
        assertEquals("POLICY", MockSensitiveTagRepository.getUpdatedEntity().getLabelIdentification());
        assertEquals(SensitiveTagEntity.STATUS_DISABLED, MockSensitiveTagRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 缺 status → 400（bean validation 必填）；非法值 0（删除态）→ A0420 */
    @Test
    void shouldRejectCreateAndEditWithInvalidStatus() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"textLabel\":\"涉政\",\"labelIdentification\":\"POLITICAL\"}")
                .when().post("/system/sensitive/tags/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));

        MockSensitiveTagRepository.setEntityById(activeTag("t1", "涉政"));
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"textLabel\":\"涉政\",\"labelIdentification\":\"POLICY\",\"status\":0}")
                .when().post("/system/sensitive/tags/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));
    }

    /** 删除：软删标签 + 级联删词 + 缓存失效 */
    @Test
    void shouldSoftDeleteTagCascadeWords() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "涉政"));
        MockSensitiveLexiconRepository.setDeleteCount(3);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"ids\":[\"t1\"]}")
                .when().post("/system/sensitive/tags/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(SensitiveTagEntity.STATUS_DELETED, MockSensitiveTagRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 标签不存在 → A0430 */
    @Test
    void shouldFailWhenTagNotFound() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"ids\":[\"missing\"]}")
                .when().post("/system/sensitive/tags/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 切换状态：1↔2，成功后失效缓存 */
    @Test
    void shouldChangeStatusAndEvictCache() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "涉政"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":2}")
                .when().post("/system/sensitive/tags/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals((Short) (short) 2, MockSensitiveTagRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 分页查询 */
    @Test
    void shouldSearchTags() {
        MockSensitiveTagQueryService.setItems(java.util.List.of(
                new com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagItem(
                        "t1", "涉政", "POLITICAL", null, (short) 1, null, null)));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("keyword", "涉")
                .when().get("/system/sensitive/tags/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.records[0].textLabel", equalTo("涉政"));
    }

    /** 分类统计：全部分类词数 + 各分类词数（管理页侧边栏） */
    @Test
    void shouldReturnTagStatistics() {
        MockSensitiveTagQueryService.setStatistics(new SensitiveTagStatistics(21, java.util.List.of(
                new SensitiveTagWordCount("t1", "政治敏感", "POLITICAL", (short) 1, 5),
                new SensitiveTagWordCount("t2", "其他", "OTHER", (short) 2, 0))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/sensitive/tags/statistics")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.total", equalTo(21))
                .body("data.items[0].textLabel", equalTo("政治敏感"))
                .body("data.items[0].wordCount", equalTo(5))
                .body("data.items[1].status", equalTo(2))
                .body("data.items[1].wordCount", equalTo(0));
    }

    private static SensitiveTagEntity activeTag(String id, String label) {
        return SensitiveTagEntity.builder()
                .id(SensitiveTagId.of(id))
                .textLabel(label)
                .labelIdentification("POLITICAL")
                .status(SensitiveTagEntity.STATUS_ACTIVE)
                .build();
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
