package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * 集成测试：文档搜索（{@code POST /kb/documents/search}）真实 PostgreSQL。
 * <p>验证：元数据筛选（单 key 精确 / 逗号分隔多值片段匹配 / 多 key AND）、
 * 完整 metadata Map 响应、与既有过滤条件组合、分页。</p>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentSearchIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        // 清库（外键顺序：metadata/documents → categories → knowledge_bases）
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> persistAll(session,
                                kb("kb-1", "芯片知识库", (short) 1),
                                kb("kb-2", "空知识库", (short) 1)))
                        .chain(() -> persistAll(session,
                                cat(new CatSeed("cat-1", "kb-1", null, "CV620系列", 0, (short) 1)),
                                cat(new CatSeed("cat-2", "kb-1", null, "CV720系列", 1, (short) 1))))
                        // 文档：doc-1 根目录(v1.0)，doc-2/3/4/5 在 cat-1 下（版本差异化以便 version 过滤有区分度）
                        .chain(() -> persistAll(session,
                                doc("doc-1", "kb-1", null, "产品总览.pdf", "v1.0"),
                                doc("doc-2", "kb-1", "cat-1", "CV620选型指南.pdf", "v2.1"),
                                doc("doc-3", "kb-1", "cat-1", "CV620SDK开发指南.pdf", "v1.0"),
                                doc("doc-4", "kb-1", "cat-1", "CV720规格书.pdf", "v3.0"),
                                doc("doc-5", "kb-1", "cat-1", "通用开发手册.pdf", "v1.0")))
                        // 元数据：doc-2 chip_model=CV620；doc-3 多芯片逗号分隔；doc-4 chip_model=CV720
                        // version 不入 document_metadata（由 documents.current_version 承载），故不在此 seed
                        // 官方标识：doc-2=OFFICIAL、doc-4=NON_OFFICIAL；doc-1/3/5 未标注（无该行 → 顶层 official=null）
                        .chain(() -> persistAll(session,
                                meta("m-1", "doc-2", "chip_model", "CV620"),
                                meta("m-2", "doc-2", "product", "Hi3516CV610"),
                                meta("m-3", "doc-3", "chip_model", "CV620,CV610"),
                                meta("m-4", "doc-4", "chip_model", "CV720"),
                                meta("m-5", "doc-2", "official", "OFFICIAL"),
                                meta("m-6", "doc-4", "official", "NON_OFFICIAL"))))
                .await().indefinitely();
    }

    // ======================== 元数据筛选 ========================

    @Test
    void shouldFilterByMetadataExactValue() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"chip_model\":\"CV620\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                // doc-2（精确）与 doc-3（逗号分隔包含 CV620）命中，doc-4 不命中
                .body("data.totalRow", equalTo(2))
                .body("data.records", hasSize(2));
    }

    @Test
    void shouldFilterByMetadataMultiKeyAnd() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"chip_model\":\"CV620\",\"version\":\"v2.1\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                // 只有 doc-2 同时满足两个 key
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-2"));
    }

    @Test
    void shouldFilterByMetadataMultiValueOrWithinKey() {
        // chip_model="CV620,CV610" → 拆分为 CV620 / CV610，OR 匹配：
        // doc-2（chip_model=CV620，精确命中 CV620）+ doc-3（chip_model=CV620,CV610，首段命中 CV620 / 末段命中 CV610）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"chip_model\":\"CV620,CV610\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records", hasSize(2));
    }

    @Test
    void shouldFilterByMetadataMultiValueAndMultiKey() {
        // chip_model="CV620,CV610"（OR）与 version="v1.0"（AND）→ 仅 doc-3 同时满足
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"chip_model\":\"CV620,CV610\",\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-3"));
    }

    // ======================== version 特判（走 documents.current_version）====================

    @Test
    void shouldFilterByVersionViaMetadata() {
        // version 走 documents.current_version（不入 document_metadata）：v2.1 仅 doc-2
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"version\":\"v2.1\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-2"));
    }

    @Test
    void shouldFilterByVersionMultiValueOr() {
        // version 多值逗号分隔 OR：v2.1(doc-2) + v3.0(doc-4)
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"version\":\"v2.1,v3.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records", hasSize(2));
    }

    @Test
    void shouldReturnEmptyWhenVersionNotMatched() {
        // 不存在的版本号 → 0 条
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"version\":\"v9.9\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0))
                .body("data.records", hasSize(0));
    }

    @Test
    void shouldFilterByVersionMultiKeyAnd() {
        // version=v3.0 + chip_model=CV720 → 仅 doc-4
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"version\":\"v3.0\",\"chip_model\":\"CV720\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-4"));
    }

    @Test
    void shouldFilterByMetadataMultiValueWithWhitespaceAndEmpty() {
        // " CV620 , , CV610 " → 清洗为 CV620 / CV610，与多值 OR 等价
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"chip_model\":\" CV620 , , CV610 \"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records", hasSize(2));
    }

    @Test
    void shouldReturnFullMetadataMap() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"docName\":\"CV620选型\"}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].metadata.chip_model", equalTo("CV620"))
                .body("data.records[0].metadata.version", equalTo("v2.1"))
                .body("data.records[0].metadata.product", equalTo("Hi3516CV610"))
                // 顶层 official 与 metadata.official 并存（新字段上线后旧字段保持兼容）
                .body("data.records[0].official", equalTo("OFFICIAL"))
                .body("data.records[0].metadata.official", equalTo("OFFICIAL"));
    }

    @Test
    void shouldReturnEmptyMetadataForDocsWithoutMetadata() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"docName\":\"通用\"}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-5"))
                .body("data.records[0].metadata.size()", equalTo(5));
    }

    // ======================== 官方标识过滤（officials 一等入参）====================
    // seed：doc-2 official=OFFICIAL、doc-4 official=NON_OFFICIAL、doc-1/3/5 未标注（无该元数据行）

    @Test
    void shouldFilterByOfficialSingleValue() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"officials\":[\"OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-2"))
                .body("data.records[0].official", equalTo("OFFICIAL"));
    }

    @Test
    void shouldFilterByOfficialMultiValueOr() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"officials\":[\"OFFICIAL\",\"NON_OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                // 多值 OR：doc-2 + doc-4
                .body("data.totalRow", equalTo(2))
                .body("data.records.id", hasItems("doc-2", "doc-4"));
    }

    @Test
    void shouldNotMatchUnmarkedDocumentsByOfficialFilter() {
        // 未标注 official 的文档（doc-1/3/5）不被 NON_OFFICIAL 命中：非官方 = 显式标注 NON_OFFICIAL
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"officials\":[\"NON_OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-4"))
                .body("data.records.id", not(hasItems("doc-1", "doc-3", "doc-5")));
    }

    @Test
    void shouldCombineOfficialWithMetadataFilter() {
        // officials（meta_key=official）与 metadata（chip_model）多 key AND → 仅 doc-2
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"officials\":[\"OFFICIAL\"],\"metadata\":{\"chip_model\":\"CV620\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-2"));
    }

    @Test
    void shouldKeepLegacyMetadataOfficialFilterWorking() {
        // 兼容回归：不传 officials，仅用 metadata.official 旧路径
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"metadata\":{\"official\":\"NON_OFFICIAL\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-4"));
    }

    @Test
    void shouldLetOfficialsWinOverMetadataOfficial() {
        // 两个来源冲突：以 officials 为准（不静默 AND 出空页），facade 记 WARN
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"officials\":[\"OFFICIAL\"],\"metadata\":{\"official\":\"NON_OFFICIAL\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-2"));
    }

    @Test
    void shouldReturnNullOfficialForUnmarkedDocuments() {
        // 未标注 → 顶层字段存在且为 null（区别于字段缺失），metadata 形状不被污染（doc-5 仍 5 个 key）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\"}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(4))
                .body("data.records.find { it.id == 'doc-2' }.official", equalTo("OFFICIAL"))
                .body("data.records.find { it.id == 'doc-4' }.official", equalTo("NON_OFFICIAL"))
                .body("data.records.find { it.id == 'doc-5' }", hasKey("official"))
                .body("data.records.find { it.id == 'doc-5' }.official", nullValue())
                .body("data.records.find { it.id == 'doc-5' }.metadata.size()", equalTo(5));
    }

    // ======================== 组合过滤 ========================

    @Test
    void shouldCombineMetadataWithCategoryAndStatus() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"statuses\":[\"PUBLISHED\"],\"metadata\":{\"chip_model\":\"CV720\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-4"));
    }

    @Test
    void shouldPaginateWithMetadataFilter() {
        // cat-1 下 4 个文档（doc-2..5），pageSize=2 → totalPage=2
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"categoryId\":\"cat-1\",\"page\":1,\"pageSize\":2}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(4))
                .body("data.records", hasSize(2))
                .body("data.totalPage", equalTo(2));
    }

    @Test
    void shouldFailWhenPageSizeExceeded() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"pageSize\":2001}")
                .when().post("/kb/documents/search")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("每页条数超出限制"));
    }

    // ======================== 排序（状态优先级 + updated_at 倒序）====================

    @Test
    void shouldSortByStatusPriorityThenUpdatedAtDesc() {
        // 排序规则：一级 DRAFT→REVIEWING→PUBLISHED→ARCHIVED→DEPRECATED，
        // 二级同状态内 updated_at 倒序（最新在前）。
        // 用固定基准时间 + 偏移保证可断言（不依赖 LocalDateTime.now() 的瞬时不确定性）。
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        sessionFactory.withTransaction(session -> session
                        // 清空 @BeforeEach 预置的文档（全 PUBLISHED），换为多状态 + 差异化 updatedAt
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> persistAll(session,
                                // ARCHIVED 两条：验证二级 updatedAt 倒序（new 应排在 old 之前）
                                doc(new StatusSeed("archived-old", "kb-1", null, "归档旧.pdf", "ARCHIVED", base)),
                                doc(new StatusSeed("archived-new", "kb-1", null, "归档新.pdf", "ARCHIVED", base.plusHours(2))),
                                doc(new StatusSeed("published-1", "kb-1", null, "已发布.pdf", "PUBLISHED", base.plusHours(1))),
                                doc(new StatusSeed("reviewing-1", "kb-1", null, "审核中.pdf", "REVIEWING", base)),
                                doc(new StatusSeed("draft-1", "kb-1", null, "草稿.pdf", "DRAFT", base)),
                                doc(new StatusSeed("deprecated-1", "kb-1", null, "已废弃.pdf", "DEPRECATED", base)))))
                .await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"page\":1,\"pageSize\":20}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(6))
                // 一级：DRAFT → REVIEWING → PUBLISHED → ARCHIVED → DEPRECATED
                .body("data.records[0].id", equalTo("draft-1"))
                .body("data.records[1].id", equalTo("reviewing-1"))
                .body("data.records[2].id", equalTo("published-1"))
                // 二级：ARCHIVED 内 updated_at 倒序（new 在 old 前）
                .body("data.records[3].id", equalTo("archived-new"))
                .body("data.records[4].id", equalTo("archived-old"))
                .body("data.records[5].id", equalTo("deprecated-1"));
    }

    // ======================== 元数据下拉选项 ========================

    @Test
    void shouldReturnMetadataOptionsGroupedAndDeduped() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/kb/documents/metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 白名单 3 个 key；doc-3 的 "CV620,CV610" 拆分；values 字典序去重
                .body("data", hasSize(3))
                .body("data[0].metaKey", equalTo("chip_model"))
                .body("data[0].label", equalTo("芯片型号"))
                .body("data[0].values", hasItems("CV610", "CV620", "CV720"))
                .body("data[1].metaKey", equalTo("product"))
                .body("data[1].values", hasItems("Hi3516CV610"))
                .body("data[2].metaKey", equalTo("version"))
                // version 从 documents.current_version 聚合（不入 document_metadata）：doc-1/3/5=v1.0，doc-2=v2.1，doc-4=v3.0
                .body("data[2].values", hasItems("v1.0", "v2.1", "v3.0"));
    }

    @Test
    void shouldReturnEmptyOptionsForKbWithoutMetadata() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-2")
                .when().get("/kb/documents/metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data", hasSize(0));
    }

    // ======================== 孤儿文档排除（与 navTree documentCount 口径一致）====================
    // 历史存量孤儿（2026-08 前删除知识库/类别不级联处理文档遗留，见 docs/sql/cleanup_orphan_documents.sql）：
    // categoryId 指向不存在类别 / 已软删类别（status=0）/ 跨库类别。整库搜索（includeDescendants=true
    // 且无 categoryId）必须与 navTree 一致——仅根目录 + 活跃类别子树下的文档可见。

    @Test
    void shouldExcludeOrphanDocumentsFromWholeKbSearch() {
        // seed 三类孤儿：cat-gone（不存在）、cat-del（已软删 status=0）、cat-x-kb2（属于 kb-2 的活跃类别）
        sessionFactory.withTransaction(session -> persistAll(session,
                cat(new CatSeed("cat-del", "kb-1", null, "已删除类别", 9, (short) 0)),
                cat(new CatSeed("cat-x-kb2", "kb-2", null, "kb2的类别", 9, (short) 1)),
                doc("doc-orphan-missing", "kb-1", "cat-gone", "孤儿-类别不存在.pdf", "v1.0"),
                doc("doc-orphan-deleted", "kb-1", "cat-del", "孤儿-类别已删.pdf", "v1.0"),
                doc("doc-orphan-crosskb", "kb-1", "cat-x-kb2", "孤儿-跨库类别.pdf", "v1.0")))
                .await().indefinitely();

        // 整库搜索（全量枚举过滤）：仅 @BeforeEach 的 doc-1..5 可见，3 条孤儿全部排除
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"includeDescendants\":true," +
                        "\"docTypes\":[\"DOCUMENT\",\"CODE\",\"CONFIG\",\"OTHER\"]," +
                        "\"statuses\":[\"DRAFT\",\"REVIEWING\",\"PUBLISHED\",\"ARCHIVED\",\"DEPRECATED\"]," +
                        "\"processStatuses\":[\"PENDING\",\"PROCESSING\",\"COMPLETED\",\"FAILED\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(5))
                .body("data.records", hasSize(5))
                .body("data.records.id", not(hasItems("doc-orphan-missing", "doc-orphan-deleted", "doc-orphan-crosskb")));

        // 按孤儿名称模糊搜索：一条都不应命中
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"includeDescendants\":true,\"docName\":\"孤儿\"}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0))
                .body("data.records", hasSize(0));
    }

    @Test
    void navTreeDocumentCountMatchesWholeKbSearchTotal() {
        // 与上一个测试同 seed：三类孤儿 + 常规文档
        sessionFactory.withTransaction(session -> persistAll(session,
                cat(new CatSeed("cat-del", "kb-1", null, "已删除类别", 9, (short) 0)),
                cat(new CatSeed("cat-x-kb2", "kb-2", null, "kb2的类别", 9, (short) 1)),
                doc("doc-orphan-missing", "kb-1", "cat-gone", "孤儿-类别不存在.pdf", "v1.0"),
                doc("doc-orphan-deleted", "kb-1", "cat-del", "孤儿-类别已删.pdf", "v1.0"),
                doc("doc-orphan-crosskb", "kb-1", "cat-x-kb2", "孤儿-跨库类别.pdf", "v1.0")))
                .await().indefinitely();

        // navTree：kb-1 节点 documentCount 只计根目录 + 活跃类别子树（doc-1..5），不含孤儿
        long navTreeCount = given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/navTree")
                .then().statusCode(200).body("code", equalTo("0"))
                .extract().jsonPath().getLong("data.find { it.nodeId == 'kb-1' }.documentCount");

        // 整库搜索 totalRow：与 navTree 一致
        long searchTotal = given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\",\"includeDescendants\":true," +
                        "\"docTypes\":[\"DOCUMENT\",\"CODE\",\"CONFIG\",\"OTHER\"]," +
                        "\"statuses\":[\"DRAFT\",\"REVIEWING\",\"PUBLISHED\",\"ARCHIVED\",\"DEPRECATED\"]," +
                        "\"processStatuses\":[\"PENDING\",\"PROCESSING\",\"COMPLETED\",\"FAILED\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .extract().jsonPath().getLong("data.totalRow");

        org.junit.jupiter.api.Assertions.assertEquals(navTreeCount, searchTotal,
                "navTree documentCount 应与整库搜索 totalRow 一致（孤儿文档双端均不可见）");

        // /kb/search 的 fileCount 聚合口径同样与 navTree 一致（KbSearchQueryService 排除孤儿）
        long kbSearchFileCount = given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbName", "芯片知识库")
                .queryParam("page", "1")
                .queryParam("pageSize", "20")
                .when().get("/kb/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .extract().jsonPath().getLong("data.records.find { it.kbId == 'kb-1' }.fileCount");
        org.junit.jupiter.api.Assertions.assertEquals(navTreeCount, kbSearchFileCount,
                "/kb/search fileCount 应与 navTree documentCount 一致（孤儿不计入）");
    }

    @Test
    void shouldHideOrphansFromMetadataOptionsNameOptionsAndByIdAccess() {
        // seed 孤儿（已软删类别下的已发布文档）+ 其专有元数据/版本（验证各聚合面不泄漏）
        sessionFactory.withTransaction(session -> persistAll(session,
                cat(new CatSeed("cat-del", "kb-1", null, "已删除类别", 9, (short) 0)),
                doc("doc-orphan-deleted", "kb-1", "cat-del", "孤儿-类别已删.pdf", "v9.9"),
                meta("m-orphan", "doc-orphan-deleted", "chip_model", "ZZZ999")))
                .await().indefinitely();

        // 1) metadata-options 不聚合孤儿：chip_model 不含 ZZZ999、version 不含 v9.9
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/kb/documents/metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data[0].metaKey", equalTo("chip_model"))
                .body("data[0].values", not(hasItem("ZZZ999")))
                .body("data[2].metaKey", equalTo("version"))
                .body("data[2].values", not(hasItem("v9.9")));

        // 2) name-options（KB 范围）不含孤儿：仅 doc-1..5
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-1")
                .when().get("/kb/documents/name-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.id", hasSize(5))
                .body("data.id", not(hasItem("doc-orphan-deleted")));

        // 3) 按 id 直取孤儿：detail / metadata / download 均视同不存在（A0430）
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-orphan-deleted")
                .when().get("/kb/documents/detail")
                .then().statusCode(200).body("code", equalTo("A0430"));
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-orphan-deleted")
                .when().get("/kb/documents/metadata")
                .then().statusCode(200).body("code", equalTo("A0430"));
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-orphan-deleted\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200).body("code", equalTo("A0430"));
    }

    // ======================== 辅助：种子数据构造 ========================

    @SafeVarargs
    private static <T> Uni<Void> persistAll(Mutiny.Session session, T... entities) {
        Uni<Void> uni = Uni.createFrom().voidItem();
        for (T entity : entities) {
            uni = uni.chain(() -> session.persist(entity));
        }
        return uni;
    }

    private static KnowledgeBasePO kb(String id, String name, Short status) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setStatus(status);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 类目种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record CatSeed(String id, String kbId, String parentId, String name, int sortOrder, Short status) {
    }

    private static CategoryPO cat(CatSeed s) {
        CategoryPO po = new CategoryPO();
        po.setId(s.id());
        po.setKbId(s.kbId());
        po.setParentId(s.parentId());
        po.setName(s.name());
        po.setSortOrder(s.sortOrder());
        po.setInheritParent(true);
        po.setStatus(s.status());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static DocumentPO doc(String id, String kbId, String categoryId, String title, String currentVersion) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(categoryId);
        po.setTitle(title);
        po.setStatus("PUBLISHED");
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion(currentVersion);
        po.setHasPublishedVersion(false);
        po.setFilePath(kbId + "/" + title);
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType("DOCUMENT");
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 排序/状态种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record StatusSeed(String id, String kbId, String categoryId, String title,
                              String status, java.time.LocalDateTime updatedAt) {
    }

    /** 排序测试用：指定 status 与 updatedAt 的文档构造（currentVersion 置空，不影响排序断言）。 */
    private static DocumentPO doc(StatusSeed s) {
        DocumentPO po = new DocumentPO();
        po.setId(s.id());
        po.setKbId(s.kbId());
        po.setCategoryId(s.categoryId());
        po.setTitle(s.title());
        po.setStatus(s.status());
        po.setProcessStatus("COMPLETED");
        po.setHasPublishedVersion(false);
        po.setFilePath(s.kbId() + "/" + s.title());
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType("DOCUMENT");
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(s.updatedAt());
        po.setUpdatedAt(s.updatedAt());
        return po;
    }

    private static DocumentMetadataPO meta(String id, String documentId, String key, String value) {
        DocumentMetadataPO po = new DocumentMetadataPO();
        po.setId(id);
        po.setDocumentId(documentId);
        po.setMetaKey(key);
        po.setMetaValue(value);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
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
