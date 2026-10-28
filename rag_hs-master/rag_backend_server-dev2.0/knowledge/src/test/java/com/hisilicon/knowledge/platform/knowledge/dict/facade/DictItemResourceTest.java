package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictItemRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictTypeRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.query.DictItemQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictItemItem;
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
 * 字典条目管理接口测试（create / edit / delete / search）
 */
@QuarkusTest
class DictItemResourceTest {

    private static final String VALID_TOKEN = buildToken("admin", System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DictTypeRepository dictTypeRepository;

    @Inject
    DictItemRepository dictItemRepository;

    @Inject
    DictMetaKeyCacheService dictMetaKeyCacheService;

    @Inject
    DictValueValidator dictValueValidator;

    @Inject
    DictReferenceChecker dictReferenceChecker;

    @Inject
    DictItemQueryService dictItemQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDictTypeRepository(), dictTypeRepository);
        QuarkusMock.installMockForInstance(new MockDictItemRepository(), dictItemRepository);
        QuarkusMock.installMockForInstance(new MockDictMetaKeyCacheService(), dictMetaKeyCacheService);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        QuarkusMock.installMockForInstance(new MockDictItemQueryService(), dictItemQueryService);
        MockDictTypeRepository.reset();
        MockDictItemRepository.reset();
        MockDictMetaKeyCacheService.reset();
        MockDictValueValidator.reset();
        MockDictItemQueryService.reset();
        // DictReferenceChecker 的真实实现 KnowledgeDictReferenceCheckerImpl 与本桩同处 knowledge，
        // 由 @Mock 桩压制真实 bean 后直接注入，仅重置静态状态
        MockDictReferenceChecker.reset();
    }

    // ======================== create ========================

    @Test
    void shouldCreateItemWithAutoSortOrder() {
        // 类型存在；已有最大 sortOrder = 3 → 新条目 sortOrder = 4
        MockDictTypeRepository.setEntityByCode(activeType("t1", "product", "产品"));
        MockDictItemRepository.setPresetMaxSortOrder(3);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"label\":\"Hi3516CV610\",\"value\":\"Hi3516CV610\"}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.typeCode", equalTo("product"))
                .body("data.value", equalTo("Hi3516CV610"))
                .body("data.sortOrder", equalTo(4))
                .body("data.id", notNullValue());
        assertNotNull(MockDictItemRepository.getPersistedEntity());
        // 条目操作不触发白名单缓存失效，但触发值缓存失效
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldCreateItemWithExplicitSortOrder() {
        MockDictTypeRepository.setEntityByCode(activeType("t1", "product", "产品"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"label\":\"Ascend910\",\"value\":\"Ascend910\",\"sortOrder\":5}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.sortOrder", equalTo(5));
    }

    @Test
    void shouldCreateItemFirstWithSortOrderOne() {
        // 类型存在；无已有条目 → sortOrder = 1
        MockDictTypeRepository.setEntityByCode(activeType("t1", "product", "产品"));
        MockDictItemRepository.setPresetMaxSortOrder(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"label\":\"X\",\"value\":\"X\"}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.sortOrder", equalTo(1));
    }

    @Test
    void shouldRejectCreateItemWhenTypeNotFound() {
        MockDictTypeRepository.setEntityByCode(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"missing\",\"label\":\"X\",\"value\":\"X\"}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典类型不存在或已删除"));
        assertNull(MockDictItemRepository.getPersistedEntity());
        // 失败路径不触发值缓存失效
        assertEquals(0, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldRejectCreateItemWhenValueDuplicate() {
        MockDictTypeRepository.setEntityByCode(activeType("t1", "product", "产品"));
        MockDictItemRepository.setEntityByTypeValue(activeItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"label\":\"Hi3516CV610\",\"value\":\"Hi3516CV610\"}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", equalTo("字典条目值已存在"));
        assertNull(MockDictItemRepository.getPersistedEntity());
        // 失败路径不触发值缓存失效
        assertEquals(0, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldRejectCreateItemBlankValue() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"typeCode\":\"product\",\"label\":\"X\",\"value\":\"\"}")
                .when().post("/system/dict/items/create")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    // ======================== edit ========================

    @Test
    void shouldEditItemLabelAndValue() {
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "旧值"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"label\":\"新名称\",\"value\":\"新值\",\"sortOrder\":9}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("新名称", MockDictItemRepository.getUpdatedEntity().getLabel());
        assertEquals("新值", MockDictItemRepository.getUpdatedEntity().getValue());
        assertEquals(9, MockDictItemRepository.getUpdatedEntity().getSortOrder());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldEditItemKeepSortOrderWhenNull() {
        // sortOrder 不传 = 保持原值（用户确认的例外）
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "旧值").update("旧名称", "旧值", 7));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"label\":\"新名称\",\"value\":\"新值\"}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals(7, MockDictItemRepository.getUpdatedEntity().getSortOrder());
    }

    @Test
    void shouldRejectEditItemNotFound() {
        MockDictItemRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\",\"label\":\"X\",\"value\":\"X\"}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典条目不存在或已删除"));
    }

    @Test
    void shouldRejectEditItemDeleted() {
        MockDictItemRepository.setEntityById(deletedItem("i1", "product"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"label\":\"X\",\"value\":\"X\"}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典条目不存在或已删除"));
    }

    @Test
    void shouldRejectEditItemValueDuplicate() {
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "旧值"));
        MockDictItemRepository.setEntityByTypeValue(activeItem("i2", "product", "占用值"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"label\":\"X\",\"value\":\"占用值\"}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", equalTo("字典条目值已存在"));
        assertNull(MockDictItemRepository.getUpdatedEntity());
        // 失败路径不触发值缓存失效
        assertEquals(0, MockDictValueValidator.getEvictCount());
    }

    // ======================== delete ========================

    @Test
    void shouldDeleteItem() {
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\"}")
                .when().post("/system/dict/items/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 0, MockDictItemRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictValueValidator.getEvictCount());
        // 删除前引用检查已咨询（mock 默认 false → 放行删除）
        assertEquals("product", MockDictReferenceChecker.getLastItemTypeCode());
        assertEquals("Hi3516CV610", MockDictReferenceChecker.getLastItemValue());
    }

    @Test
    void shouldRejectDeleteItemWhenReferenced() {
        // 被业务数据引用 → 拒绝删除（A0420）
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "Hi3516CV610"));
        MockDictReferenceChecker.setItemReferenced(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\"}")
                .when().post("/system/dict/items/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("字典条目值\"Hi3516CV610\"被业务数据引用，不允许删除"));
        // 拒绝删除：未软删条目、未清值缓存
        assertNull(MockDictItemRepository.getUpdatedEntity());
        assertEquals(0, MockDictValueValidator.getEvictCount());
        // 引用检查传入了正确的 typeCode/value
        assertEquals("product", MockDictReferenceChecker.getLastItemTypeCode());
        assertEquals("Hi3516CV610", MockDictReferenceChecker.getLastItemValue());
    }

    @Test
    void shouldRejectDeleteItemNotFound() {
        MockDictItemRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\"}")
                .when().post("/system/dict/items/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典条目不存在或已删除"));
    }

    @Test
    void shouldDeleteDisabledItem() {
        // 守卫放宽：禁用态(2)仍可删除（三态连贯性）
        MockDictItemRepository.setEntityById(disabledItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\"}")
                .when().post("/system/dict/items/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 0, MockDictItemRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldEditDisabledItem() {
        // 守卫放宽：禁用态(2)仍可编辑（仅拒绝已删除）
        MockDictItemRepository.setEntityById(disabledItem("i1", "product", "旧值"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"label\":\"新名称\",\"value\":\"新值\"}")
                .when().post("/system/dict/items/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("新名称", MockDictItemRepository.getUpdatedEntity().getLabel());
        assertEquals("新值", MockDictItemRepository.getUpdatedEntity().getValue());
        // 禁用态编辑不改变状态
        assertEquals((short) 2, MockDictItemRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    // ======================== changeStatus ========================

    @Test
    void shouldDisableActiveItem() {
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":2}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 2, MockDictItemRepository.getUpdatedEntity().getStatus());
        // 条目状态变更不影响 key 白名单 → 仅清值缓存
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldEnableDisabledItem() {
        MockDictItemRepository.setEntityById(disabledItem("i1", "product", "Hi3516CV610"));
        // 再启用预检：无同值活跃条目冲突（findByTypeAndValue 返回 null）
        MockDictItemRepository.setEntityByTypeValue(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":1}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals((short) 1, MockDictItemRepository.getUpdatedEntity().getStatus());
        assertEquals(1, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldRejectEnableWhenValueConflict() {
        // 禁用期间另建了同值活跃条目 → 再启用预检命中
        MockDictItemRepository.setEntityById(disabledItem("i1", "product", "占用值"));
        MockDictItemRepository.setEntityByTypeValue(activeItem("i2", "product", "占用值"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":1}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", equalTo("字典条目值已存在"));
        assertNull(MockDictItemRepository.getUpdatedEntity());
        assertEquals(0, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldIdempotentWhenItemAlreadyInTargetStatus() {
        // 已是目标状态 → 幂等成功，不写库、不清缓存
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":1}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertNull(MockDictItemRepository.getUpdatedEntity());
        assertEquals(0, MockDictValueValidator.getEvictCount());
    }

    @Test
    void shouldRejectChangeStatusItemNotFound() {
        MockDictItemRepository.setEntityById(null);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\",\"status\":2}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典条目不存在或已删除"));
    }

    @Test
    void shouldRejectChangeStatusItemDeleted() {
        MockDictItemRepository.setEntityById(deletedItem("i1", "product"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":1}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", equalTo("字典条目不存在或已删除"));
    }

    @Test
    void shouldRejectChangeStatusItemInvalidValue() {
        MockDictItemRepository.setEntityById(activeItem("i1", "product", "Hi3516CV610"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\",\"status\":3}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("字典状态不合法，只能为1(启用)或2(禁用)"));
        assertNull(MockDictItemRepository.getUpdatedEntity());
    }

    @Test
    void shouldRejectChangeStatusItemMissingStatus() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"i1\"}")
                .when().post("/system/dict/items/changeStatus")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    // ======================== search ========================

    @Test
    void shouldSearchItems() {
        MockDictItemQueryService.setPresetList(List.of(
                new DictItemItem("i1", "product", "Hi3516CV610", "Hi3516CV610", 1,
                        (short) 1, LocalDateTime.now(), LocalDateTime.now())));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("typeCode", "product")
                .queryParam("status", 1)
                .when().get("/system/dict/items/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data[0].value", equalTo("Hi3516CV610"))
                .body("data[0].sortOrder", equalTo(1));
        assertEquals("product", MockDictItemQueryService.getLastTypeCode());
    }

    @Test
    void shouldRejectSearchWithoutTypeCode() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/dict/items/search")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldRequireAuth() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"typeCode\":\"product\",\"label\":\"X\",\"value\":\"X\"}")
                .when().post("/system/dict/items/create")
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

    private static DictItemEntity activeItem(String id, String typeCode, String value) {
        return DictItemEntity.builder()
                .id(DictItemId.of(id))
                .typeCode(typeCode)
                .label(value)
                .value(value)
                .sortOrder(1)
                .status(DictItemEntity.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DictItemEntity deletedItem(String id, String typeCode) {
        return DictItemEntity.builder()
                .id(DictItemId.of(id))
                .typeCode(typeCode)
                .label("X")
                .value("X")
                .sortOrder(1)
                .status(DictItemEntity.STATUS_DELETED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DictItemEntity disabledItem(String id, String typeCode, String value) {
        return DictItemEntity.builder()
                .id(DictItemId.of(id))
                .typeCode(typeCode)
                .label(value)
                .value(value)
                .sortOrder(1)
                .status(DictItemEntity.STATUS_DISABLED)
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
