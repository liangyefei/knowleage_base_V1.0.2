package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache.SensitiveWordCacheService;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExcel;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveTagQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveWordQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveWordItem;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExportRow;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词词库接口测试（template / import / edit / changeStatus / export）
 * <p>导入 5 列同构格式：标签名称|标签标识|标签是否启用|敏感词|词是否启用。</p>
 */
@QuarkusTest
class SensitiveWordResourceTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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

    /** 模板下载：xlsx 二进制（PK 魔数）+ 正确 MIME，无编码乱码问题 */
    @Test
    void shouldDownloadTemplateAsXlsx() {
        byte[] body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/sensitive/words/template")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("spreadsheetml"))
                .extract().asByteArray();

        assertEquals((byte) 0x50, body[0]);
        assertEquals((byte) 0x4B, body[1]);
    }

    /** 多分类导入：新标签（默认启用）+ 已有标签（更新为停用），词按文件状态入库 */
    @Test
    void shouldImportXlsxWithMultipleTags() throws Exception {
        MockSensitiveTagRepository.setEntityByLabel(
                activeTag("t1", "已有标签", SensitiveTagEntity.STATUS_ACTIVE));

        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"新标签A", "", "启用", "词A1", "启用"},
                new String[]{"新标签A", "", "", "词A2", "停用"},
                new String[]{"已有标签", "", "停用", "已有词", "启用"}));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.xlsx", xlsx, XLSX_MIME)
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.total", equalTo(3))
                .body("data.imported", equalTo(3))
                .body("data.skipped", equalTo(0))
                .body("data.createdTags", equalTo(1))
                .body("data.statusUpdated", equalTo(0));

        // 新标签：标识空 → 默认取标签名称，状态=启用
        SensitiveTagEntity persistedTag = MockSensitiveTagRepository.getPersistedEntity();
        assertNotNull(persistedTag);
        assertEquals("新标签A", persistedTag.getTextLabel());
        assertEquals("新标签A", persistedTag.getLabelIdentification());
        assertEquals(SensitiveTagEntity.STATUS_ACTIVE, persistedTag.getStatus());

        // 已有标签：状态更新为停用
        assertEquals(SensitiveTagEntity.STATUS_DISABLED,
                MockSensitiveTagRepository.getUpdatedEntity().getStatus());

        // 3 个词按文件状态入库
        List<SensitiveLexiconEntity> persisted = MockSensitiveLexiconRepository.getPersistedEntities();
        assertEquals(3, persisted.size());
        assertEquals("词A1", persisted.get(0).getWord());
        assertEquals(SensitiveLexiconEntity.STATUS_ENABLED, persisted.get(0).getStatus());
        assertEquals("词A2", persisted.get(1).getWord());
        assertEquals(SensitiveLexiconEntity.STATUS_DISABLED, persisted.get(1).getStatus());
        assertEquals("已有词", persisted.get(2).getWord());
        // 导入词创建人 = 当前操作员（JWT sub）
        assertEquals("admin", persisted.get(0).getCreatedBy());

        assertEquals(1, MockSensitiveWordCacheService.getEvictCount(), "导入有变更应失效缓存 1 次");
    }

    /** 已存在词且状态不同 → 更新状态（statusUpdated），不重复入库 */
    @Test
    void shouldImportUpdateExistingWordStatus() throws Exception {
        MockSensitiveTagRepository.setEntityByLabel(
                activeTag("t1", "已有标签", SensitiveTagEntity.STATUS_ACTIVE));
        MockSensitiveLexiconRepository.setWordStatus("已有词", SensitiveLexiconEntity.STATUS_ENABLED);

        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"已有标签", "", "启用", "已有词", "停用"}));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.xlsx", xlsx, XLSX_MIME)
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.imported", equalTo(0))
                .body("data.skipped", equalTo(0))
                .body("data.statusUpdated", equalTo(1));

        assertEquals("已有词", MockSensitiveLexiconRepository.getUpdatedStatusWord());
        assertEquals(SensitiveLexiconEntity.STATUS_DISABLED,
                MockSensitiveLexiconRepository.getUpdatedStatusValue());
        assertTrue(MockSensitiveLexiconRepository.getPersistedEntities().isEmpty());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 已存在词且状态相同 → 跳过，无缓存失效 */
    @Test
    void shouldImportSkipWhenWordStatusSame() throws Exception {
        MockSensitiveTagRepository.setEntityByLabel(
                activeTag("t1", "已有标签", SensitiveTagEntity.STATUS_ACTIVE));
        MockSensitiveLexiconRepository.setWordStatus("已有词", SensitiveLexiconEntity.STATUS_ENABLED);

        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"已有标签", "", "启用", "已有词", "启用"}));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.xlsx", xlsx, XLSX_MIME)
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.skipped", equalTo(1));

        assertEquals(0, MockSensitiveWordCacheService.getEvictCount(), "无变更不应失效缓存");
    }

    /** 启用列取值非法 → 整体拒绝（A0400），不落任何数据 */
    @Test
    void shouldRejectInvalidEnableValue() throws Exception {
        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"标签A", "", "启用", "词A", "启用"},
                new String[]{"标签A", "", "启用", "词B", "禁用"}));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.xlsx", xlsx, XLSX_MIME)
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"));

        assertTrue(MockSensitiveLexiconRepository.getPersistedEntities().isEmpty(), "应整体拒绝，不部分导入");
        assertNull(MockSensitiveTagRepository.getPersistedEntity());
    }

    /** csv（5 列，UTF-8）同样可导入 */
    @Test
    void shouldImportCsvWithMultipleTags() {
        String csv = "标签名称,标签标识,标签是否启用,敏感词,词是否启用\n标签C,ID-C,停用,词C1,启用\n";
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/csv")
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.imported", equalTo(1))
                .body("data.createdTags", equalTo(1));

        // 新建标签状态=停用
        assertEquals(SensitiveTagEntity.STATUS_DISABLED,
                MockSensitiveTagRepository.getPersistedEntity().getStatus());
    }

    /** 文件格式不支持（.txt）→ A0400 */
    @Test
    void shouldRejectUnsupportedFileFormat() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "words.txt", "词A\n词B".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain")
                .when().post("/system/sensitive/words/import")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"));
    }

    /** 词批量状态切换：1↔2，成功后失效缓存 */
    @Test
    void shouldChangeWordStatusAndEvictCache() {
        MockSensitiveLexiconRepository.setUpdateStatusCount(2);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"ids\":[\"w1\",\"w2\"],\"status\":2}")
                .when().post("/system/sensitive/words/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(List.of("w1", "w2"), MockSensitiveLexiconRepository.getUpdateStatusIds());
        assertEquals(SensitiveLexiconEntity.STATUS_DISABLED,
                MockSensitiveLexiconRepository.getUpdateStatusTarget());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount());
    }

    /** 词状态参数不合法（0）→ A0420 */
    @Test
    void shouldRejectInvalidWordStatus() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"ids\":[\"w1\"],\"status\":0}")
                .when().post("/system/sensitive/words/changeStatus")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));
    }

    // ========== 敏感词编辑 ==========

    /** 编辑成功（改词文本）：捕获更新参数 + 缓存失效 1 次 */
    @Test
    void shouldEditWordAndEvictCache() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "政治敏感", SensitiveTagEntity.STATUS_ACTIVE));
        MockSensitiveLexiconRepository.putEntity(wordEntity("w1", "t1", "旧词"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"t1\",\"word\":\"新词\",\"status\":1,\"remarks\":\"新备注\"}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("w1", MockSensitiveLexiconRepository.getUpdatedWordId().value());
        assertEquals("t1", MockSensitiveLexiconRepository.getUpdatedWordTagId().value());
        assertEquals("新词", MockSensitiveLexiconRepository.getUpdatedWordText());
        assertEquals(SensitiveLexiconEntity.STATUS_ENABLED, MockSensitiveLexiconRepository.getUpdatedWordStatus());
        assertEquals("新备注", MockSensitiveLexiconRepository.getUpdatedWordRemarks());
        assertEquals(1, MockSensitiveWordCacheService.getEvictCount(), "编辑成功应失效缓存");
    }

    /** 编辑成功（移到另一分类，含停用分类——与新增同口径，仅拒绝已删除标签） */
    @Test
    void shouldEditWordMoveToAnotherTag() {
        MockSensitiveTagRepository.setEntityById(activeTag("t2", "其他", SensitiveTagEntity.STATUS_DISABLED));
        MockSensitiveLexiconRepository.putEntity(wordEntity("w1", "t1", "旧词"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"t2\",\"word\":\"旧词\",\"status\":2}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("t2", MockSensitiveLexiconRepository.getUpdatedWordTagId().value());
        assertEquals("旧词", MockSensitiveLexiconRepository.getExcludedWord());
    }

    /** 词不存在 → A0430 */
    @Test
    void shouldRejectEditWhenWordNotFound() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "政治敏感", SensitiveTagEntity.STATUS_ACTIVE));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"missing\",\"tagId\":\"t1\",\"word\":\"新词\",\"status\":1}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 目标标签不存在或已删除 → A0430 */
    @Test
    void shouldRejectEditWhenTargetTagMissing() {
        MockSensitiveLexiconRepository.putEntity(wordEntity("w1", "t1", "旧词"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"missing\",\"word\":\"新词\",\"status\":1}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 目标分类内词重复（去重排除自身，校验用新词）→ A0429，且不失效缓存 */
    @Test
    void shouldRejectEditWhenDuplicateInTargetTag() {
        MockSensitiveTagRepository.setEntityById(activeTag("t1", "政治敏感", SensitiveTagEntity.STATUS_ACTIVE));
        MockSensitiveLexiconRepository.putEntity(wordEntity("w1", "t1", "旧词"));
        MockSensitiveLexiconRepository.setExistsExcludingResult(true);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"t1\",\"word\":\"已有词\",\"status\":1}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"));

        assertEquals("已有词", MockSensitiveLexiconRepository.getExcludedWord());
        assertEquals(0, MockSensitiveWordCacheService.getEvictCount(), "编辑失败不应失效缓存");
    }

    /** 缺 status → 400（bean validation 必填） */
    @Test
    void shouldRejectEditWithMissingStatus() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"t1\",\"word\":\"新词\"}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"tagId\":\"t1\",\"word\":\"新词\"}")
                .when().post("/system/sensitive/words/add")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    /** 词为空串 → 400（bean validation → A0400） */
    @Test
    void shouldRejectEditWithBlankWord() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .body("{\"id\":\"w1\",\"tagId\":\"t1\",\"word\":\"  \",\"status\":1}")
                .when().post("/system/sensitive/words/edit")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    /** 词库分页查询：tagId 不传查全部（透传 null），传了按标签过滤（透传值） */
    @Test
    void shouldSearchWordsWithoutTagId() {
        MockSensitiveWordQueryService.setSearchItems(List.of(
                new SensitiveWordItem("w1", "t1", "政治敏感", "机密词汇", (short) 1,
                        java.time.LocalDateTime.now(), "admin", "备注A")));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/sensitive/words/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.records[0].textLabel", equalTo("政治敏感"))
                .body("data.records[0].word", equalTo("机密词汇"))
                .body("data.records[0].createdBy", equalTo("admin"))
                .body("data.records[0].remarks", equalTo("备注A"));
        assertNull(MockSensitiveWordQueryService.getLastSearchTagId(), "不传 tagId 应透传 null");

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("tagId", "t1")
                .when().get("/system/sensitive/words/search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        assertEquals("t1", MockSensitiveWordQueryService.getLastSearchTagId());
    }

    /** 导出 xlsx：与导入同构，产物可回读 */
    @Test
    void shouldExportXlsx() throws Exception {
        MockSensitiveWordQueryService.setExportRows(List.of(
                new SensitiveWordExportRow("涉政", "POLITICAL", (short) 1, "机密词汇", (short) 1),
                new SensitiveWordExportRow("涉密", "SECRET", (short) 2, "词B", (short) 2)));

        byte[] body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/sensitive/words/export")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("spreadsheetml"))
                .extract().asByteArray();

        assertEquals((byte) 0x50, body[0]);
        assertEquals((byte) 0x4B, body[1]);

        // 产物可被导入解析器读回（回导闭环）
        List<com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordImportRow> rows =
                SensitiveWordExcel.parseRows(body);
        assertEquals(2, rows.size());
        assertEquals("机密词汇", rows.get(0).word());
        assertEquals("启用", rows.get(0).wordEnableRaw());
    }

    /** 导出无命中数据：不产出空文档，走统一业务信封（A0430 + 提示文案） */
    @Test
    void shouldRejectExportWhenNoData() {
        MockSensitiveWordQueryService.setExportRows(List.of());

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/system/sensitive/words/export")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("code", equalTo("A0430"))
                .body("message", equalTo("没有可导出的敏感词数据"));
    }

    // ========== 测试辅助 ==========

    /** 构造导入 xlsx（表头 + 数据行） */
    private static byte[] buildXlsx(List<String[]> dataRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("敏感词导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < SensitiveWordExcel.HEADERS.length; i++) {
                header.createCell(i).setCellValue(SensitiveWordExcel.HEADERS[i]);
            }
            int r = 1;
            for (String[] cells : dataRows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < cells.length; c++) {
                    if (cells[c] != null && !cells[c].isEmpty()) {
                        row.createCell(c).setCellValue(cells[c]);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static SensitiveLexiconEntity wordEntity(String id, String tagId, String word) {
        return SensitiveLexiconEntity.builder()
                .id(SensitiveLexiconId.of(id))
                .tagId(SensitiveTagId.of(tagId))
                .word(word)
                .status(SensitiveLexiconEntity.STATUS_ENABLED)
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static SensitiveTagEntity activeTag(String id, String label, Short status) {
        return SensitiveTagEntity.builder()
                .id(SensitiveTagId.of(id))
                .textLabel(label)
                .labelIdentification("ID-" + label)
                .status(status)
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
