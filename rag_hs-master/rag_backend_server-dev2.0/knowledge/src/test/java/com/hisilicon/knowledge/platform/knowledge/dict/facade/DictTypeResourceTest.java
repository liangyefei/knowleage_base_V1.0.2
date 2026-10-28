package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictItemRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictTypeRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.query.DictTypeQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictTypeItem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 字典类型管理接口测试（create / edit / delete / search）
 */
@QuarkusTest
class DictTypeResourceTest {

    private static final String VALID_TOKEN = buildToken("admin", System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DictTypeRepository dictTypeRepository;

    @Inject
    DictItemRepository dictItemRepository;

    @Inject
    DictMetaKeyCacheService dictMetaKeyCacheService;

    @Inject
    DictReferenceChecker dictReferenceChecker;

    @Inject
    DictTypeQueryService dictTypeQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDictTypeRepository(), dictTypeRepository);
        QuarkusMock.installMockForInstance(new MockDictItemRepository(), dictItemRepository);
        QuarkusMock.installMockForInstance(new MockDictMetaKeyCacheService(), dictMetaKeyCacheService);
        QuarkusMock.installMockForInstance(new MockDictTypeQueryService(), dictTypeQueryService);
        MockDictTypeRepository.reset();
        MockDictItemRepository.reset();
        MockDictMetaKeyCacheService.reset();
        MockDictTypeQueryService.reset();
        // DictReferenceChecker 的真实实现 KnowledgeDictReferenceCheckerImpl 与本桩同处 knowledge，
        // 由 @Mock 桩压制真实 bean 后直接注入，仅重置静态状态
        MockDictReferenceChecker.reset();
    }

    // ======================== create ========================

    @Test
    void shouldCreateType() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"typeName\":\"产品\",\"controlledMetaKeys\":\"product,chip_model\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.typeCode", equalTo("product"))
                .body("data.typeName", equalTo("产品"))
                .body("data.controlledMetaKeys", equalTo("product,chip_model"))
                .body("data.id", notNullValue());
        assertNotNull(MockDictTypeRepository.getPersistedEntity());
        assertEquals((short) 1, MockDictTypeRepository.getPersistedEntity().getStatus());
        // 类型创建后白名单缓存失效
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRejectDuplicateTypeCode() {
        MockDictTypeRepository.setEntityByCode(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", equalTo("字典类型编码已存在"));
        assertNull(MockDictTypeRepository.getPersistedEntity());
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRejectInvalidTypeCodeFormat() {
        // 大写字母开头 / 包含横线均不合法
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"Product\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("字典类型编码不合法（仅支持小写字母开头的字母/数字/下划线，长度不超过50）"));
        assertNull(MockDictTypeRepository.getPersistedEntity());
    }

    @Test
    void shouldRejectBlankTypeCode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldRejectBlankTypeName() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"typeName\":\"\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    // ======================== edit ========================

    @Test
    void shouldEditTypeName() {
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"typeName\":\"产品目录\",\"controlledMetaKeys\":\"product\"}")
                .when().post("/system/dict/types/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("产品目录", MockDictTypeRepository.getUpdatedEntity().getTypeName());
        // typeCode 不可修改：编辑后保持原值
        assertEquals("product", MockDictTypeRepository.getUpdatedEntity().getTypeCode());
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRejectEditNotFound() {
        MockDictTypeRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    @Test
    void shouldRejectEditDeletedType() {
        MockDictTypeRepository.setEntityById(deletedType("t1", "product"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    // ======================== delete ========================

    @Test
    void shouldRejectDeletePresetType() {
        MockDictTypeRepository.setEntityById(presetType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("预置字典类型不允许删除"));
        // 未触发任何软删（条目级联 + 类型更新均未调用）
        assertNull(MockDictItemRepository.getLastSoftDeletedTypeCode());
        assertNull(MockDictTypeRepository.getUpdatedEntity());
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldDeleteTypeWithCascadeItems() {
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        // 级联软删条目 + 软删类型 + 白名单缓存失效
        assertEquals("product", MockDictItemRepository.getLastSoftDeletedTypeCode());
        assertEquals((short) 0, MockDictTypeRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
        // 删除前引用检查已咨询（mock 默认 false → 放行级联删除）
        assertEquals("product", MockDictReferenceChecker.getLastTypeTypeCode());
    }

    @Test
    void shouldRejectDeleteTypeWhenReferenced() {
        // 类型下条目值被业务数据引用 → 拒绝删除（A0420），不级联软删
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));
        MockDictReferenceChecker.setTypeReferenced(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("字典类型\"product\"被业务数据引用，不允许删除"));
        // 拒绝删除：未级联软删条目、未软删类型、未清缓存
        assertNull(MockDictItemRepository.getLastSoftDeletedTypeCode());
        assertNull(MockDictTypeRepository.getUpdatedEntity());
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
        assertEquals("product", MockDictReferenceChecker.getLastTypeTypeCode());
    }

    @Test
    void shouldRejectDeleteNotFound() {
        MockDictTypeRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    @Test
    void shouldRejectDeleteDeletedType() {
        MockDictTypeRepository.setEntityById(deletedType("t1", "product"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    @Test
    void shouldDeleteDisabledType() {
        // 守卫放宽：禁用态(2)仍可删除（三态连贯性）
        MockDictTypeRepository.setEntityById(disabledType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("product", MockDictItemRepository.getLastSoftDeletedTypeCode());
        assertEquals((short) 0, MockDictTypeRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldEditDisabledType() {
        // 守卫放宽：禁用态(2)仍可编辑（仅拒绝已删除）
        MockDictTypeRepository.setEntityById(disabledType("t1", "product", "旧名"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"typeName\":\"新名\"}")
                .when().post("/system/dict/types/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("新名", MockDictTypeRepository.getUpdatedEntity().getTypeName());
        // 禁用态编辑不改变状态
        assertEquals((short) 2, MockDictTypeRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    // ======================== changeStatus ========================

    @Test
    void shouldDisableActiveType() {
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":2}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 2, MockDictTypeRepository.getUpdatedEntity().getStatus());
        // 类型状态变更影响 key 白名单 → 清 key 白名单 + 值缓存
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldEnableDisabledType() {
        MockDictTypeRepository.setEntityById(disabledType("t1", "product", "产品"));
        // 再启用预检：无同名活跃类型冲突（findByCode 返回 null）
        MockDictTypeRepository.setEntityByCode(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":1}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 1, MockDictTypeRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRejectEnableWhenCodeConflict() {
        // 禁用期间另建了同名活跃类型 → 再启用预检命中
        MockDictTypeRepository.setEntityById(disabledType("t1", "product", "产品"));
        MockDictTypeRepository.setEntityByCode(activeType("t2", "product", "产品占用"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":1}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", equalTo("字典类型编码已存在"));
        assertNull(MockDictTypeRepository.getUpdatedEntity());
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldIdempotentWhenAlreadyInTargetStatus() {
        // 已是目标状态 → 幂等成功，不写库、不清缓存
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":1}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertNull(MockDictTypeRepository.getUpdatedEntity());
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRejectChangeStatusNotFound() {
        MockDictTypeRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\",\"status\":2}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    @Test
    void shouldRejectChangeStatusDeleted() {
        MockDictTypeRepository.setEntityById(deletedType("t1", "product"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":1}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
    }

    @Test
    void shouldRejectChangeStatusInvalidValue() {
        MockDictTypeRepository.setEntityById(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\",\"status\":3}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("字典状态不合法，只能为1(启用)或2(禁用)"));
        assertNull(MockDictTypeRepository.getUpdatedEntity());
    }

    @Test
    void shouldRejectChangeStatusMissingStatus() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"t1\"}")
                .when().post("/system/dict/types/changeStatus")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    // ======================== search ========================

    @Test
    void shouldSearchTypes() {
        MockDictTypeQueryService.setPresetPage(new PageResult<>(1, 10, 1, 1, List.of(
                new DictTypeItem("t1", "product", "产品", "product,chip_model",
                        (short) 1, false, LocalDateTime.now(), LocalDateTime.now()))));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("page", 1)
                .queryParam("pageSize", 10)
                .queryParam("keyword", "产")
                .when().get("/system/dict/types/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].typeCode", equalTo("product"))
                .body("data.records[0].typeName", equalTo("产品"));
    }

    @Test
    void shouldRequireAuth() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"typeCode\":\"product\",\"typeName\":\"产品\"}")
                .when().post("/system/dict/types/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"));
    }

    // ======================== helpers ========================

    private static DictTypeEntity activeType(String id, String typeCode, String typeName) {
        return DictTypeEntity.builder()
                .id(DictTypeId.of(id))
                .typeCode(typeCode)
                .typeName(typeName)
                .controlledMetaKeys("product,chip_model")
                .status(DictTypeEntity.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DictTypeEntity presetType(String id, String typeCode, String typeName) {
        return DictTypeEntity.builder()
                .id(DictTypeId.of(id))
                .typeCode(typeCode)
                .typeName(typeName)
                .controlledMetaKeys("product,chip_model")
                .status(DictTypeEntity.STATUS_ACTIVE)
                .isPreset(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DictTypeEntity deletedType(String id, String typeCode) {
        return DictTypeEntity.builder()
                .id(DictTypeId.of(id))
                .typeCode(typeCode)
                .typeName("产品")
                .controlledMetaKeys(null)
                .status(DictTypeEntity.STATUS_DELETED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DictTypeEntity disabledType(String id, String typeCode, String typeName) {
        return DictTypeEntity.builder()
                .id(DictTypeId.of(id))
                .typeCode(typeCode)
                .typeName(typeName)
                .controlledMetaKeys("product,chip_model")
                .status(DictTypeEntity.STATUS_DISABLED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes());
        return header + "." + payload + ".dummy-signature";
    }
}
