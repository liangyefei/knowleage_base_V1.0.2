package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import io.restassured.http.ContentType;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * 集成测试：{@code POST /kb/documents/search} 版本级结果行（真实 PostgreSQL）。
 *
 * <p>背景（版本覆盖问题 4 终版）：version 过滤曾只查 documents.current_version，后改
 * 「current_version OR document_versions」文档级命中——但命中行整行展示 currentVersion，
 * 选 v1.0 出来的是 currentVersion=v2.0 的文档行，无法辨识命中版本。终版语义为<b>版本级结果行</b>：
 * 未传 version = 当前版 + 全部历史版各一行；传 version = 仅保留命中版本的行。行内
 * {@code version} 即本行版本号，{@code isCurrentVersion} 标注当前/历史，前端按 (id, version) 下载。
 * 运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentSearchHistoricalVersionIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        // doc-overridden：发布 v1.0 后覆盖为 v2.0（current_version=v2.0，v1.0 归档）
                        // doc-current：v1.0 从未覆盖
                        .chain(() -> session.persist(kb("kb-hv")))
                        .chain(() -> session.persist(
                                doc("doc-overridden", "kb-hv", "覆盖后文档.pdf", "v2.0")))
                        .chain(() -> session.persist(
                                doc("doc-current", "kb-hv", "未覆盖文档.pdf", "v1.0")))
                        .chain(() -> session.persist(version("doc-overridden", "v1.0")))
                        .replaceWithVoid())
                .await().indefinitely();
    }

    /** 按历史版本号（v1.0）检索：版本级行——覆盖后文档仅返回其 v1.0 历史行，未覆盖文档返回当前行 */
    @Test
    void shouldMatchByHistoricalVersionAfterOverride() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                // doc-overridden 仅 v1.0 历史行（不出现 v2.0 当前行）；doc-current 为当前行
                .body("data.records", hasSize(2))
                .body("data.records.find { it.id == 'doc-overridden' }.version", equalTo("v1.0"))
                .body("data.records.find { it.id == 'doc-overridden' }.isCurrentVersion", equalTo(false))
                .body("data.records.find { it.id == 'doc-overridden' }.currentVersion", equalTo("v2.0"))
                .body("data.records.find { it.id == 'doc-overridden' }.metadata.version", equalTo("v1.0"))
                .body("data.records.find { it.id == 'doc-current' }.version", equalTo("v1.0"))
                .body("data.records.find { it.id == 'doc-current' }.isCurrentVersion", equalTo(true));
    }

    /** 按当前版本号检索：仅覆盖后文档的当前版行 */
    @Test
    void shouldMatchByCurrentVersion() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v2.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-overridden"))
                .body("data.records[0].version", equalTo("v2.0"))
                .body("data.records[0].isCurrentVersion", equalTo(true));
    }

    /** 未传 version 过滤：版本级全量行——覆盖后文档 2 行（当前 v2.0 + 历史 v1.0），未覆盖 1 行 */
    @Test
    void shouldReturnAllVersionRowsWithoutVersionFilter() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(3))
                // 行集合：v2.0 当前行 ×1 + v1.0 行 ×2（doc-overridden 历史行 + doc-current 当前行）
                .body("data.records.count { it.version == 'v1.0' }", equalTo(2))
                .body("data.records.count { it.version == 'v2.0' }", equalTo(1))
                .body("data.records.find { it.version == 'v2.0' }.isCurrentVersion", equalTo(true))
                .body("data.records.find { it.id == 'doc-overridden' && it.isCurrentVersion == false }.version",
                        equalTo("v1.0"))
                .body("data.records.find { it.id == 'doc-current' }.isCurrentVersion", equalTo(true));
    }

    /** 不存在的版本号 → 0 条 */
    @Test
    void shouldReturnEmptyWhenVersionNotMatchedAnywhere() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v9.9\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0))
                .body("data.records", hasSize(0));
    }

    /** 与 statuses 组合：历史版本命中 + status=PUBLISHED */
    @Test
    void shouldCombineHistoricalVersionWithStatuses() {
        sessionFactory.withTransaction(session -> {
            DocumentPO archived = doc("doc-archived", "kb-hv", "已归档文档.pdf", "v2.0");
            archived.setStatus("ARCHIVED");
            return session.persist(archived)
                    .chain(() -> session.persist(version("doc-archived", "v1.0")));
        }).await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"statuses\":[\"PUBLISHED\"]," +
                        "\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records.id", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("doc-archived")));
    }

    /** 历史版本过滤 + 分页：2 条命中，pageSize=1 → totalPage=2 */
    @Test
    void shouldPaginateWithHistoricalVersionFilter() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v1.0\"}," +
                        "\"page\":1,\"pageSize\":1}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records", hasSize(1))
                .body("data.totalPage", equalTo(2));
    }

    /** version 下拉选项聚合含历史版本号（否则用户无从选择旧版过滤） */
    @Test
    void shouldAggregateHistoricalVersionInOptions() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-hv")
                .when().get("/kb/documents/metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // kb-hv 文档无 document_metadata 行 → 仅 version 一组
                .body("data[0].metaKey", equalTo("version"))
                .body("data[0].values", org.hamcrest.Matchers.hasItems("v1.0", "v2.0"));
    }

    /** name-options 按历史版本号查询命中覆盖后文档 */
    @Test
    void shouldMatchNameOptionsByHistoricalVersion() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("nodeType", "KB")
                .queryParam("nodeId", "kb-hv")
                .queryParam("version", "v1.0")
                .when().get("/kb/documents/name-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.size()", equalTo(2))
                .body("data.id", org.hamcrest.Matchers.hasItems("doc-overridden", "doc-current"));
    }

    // ======================== 版本级归档/废弃 ========================

    /** 版本级归档：仅下线 v1.0 版本行，文档生命周期不变；search 按 statuses 决定可见性 */
    @Test
    void shouldArchiveSingleVersionAndRespectStatusesFilter() {
        // 1) 版本级归档 v1.0（不传 statuses → 行仍返回，versionStatus=ARCHIVED）
        archiveVersion("doc-overridden", "v1.0");

        String body = given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records", hasSize(2))
                .extract().body().asString();
        org.hamcrest.MatcherAssert.assertThat(body,
                org.hamcrest.Matchers.containsString("\"versionStatus\":\"ARCHIVED\""));

        // 2) statuses=[PUBLISHED] → 已归档版本行隐藏（仅 doc-current 当前行）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"statuses\":[\"PUBLISHED\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.findAll { it.id == 'doc-overridden' }.version",
                        org.hamcrest.Matchers.equalTo(java.util.List.of("v2.0")));

        // 3) statuses=[ARCHIVED] → 可查看已归档版本行
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"statuses\":[\"ARCHIVED\"]," +
                        "\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.find { it.id == 'doc-overridden' }.versionStatus", equalTo("ARCHIVED"));

        // 4) 文档本体不受影响（当前版行仍为 PUBLISHED/v2.0）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"metadata\":{\"version\":\"v2.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records[0].status", equalTo("PUBLISHED"))
                .body("data.records[0].versionStatus", equalTo("PUBLISHED"));
    }

    /** 下线版本 download/detail：A0508 + 后端消息「文档已归档」；重复归档幂等成功 */
    @Test
    void shouldRejectOfflineVersionDownloadAndDetailWithA0508() {
        archiveVersion("doc-overridden", "v1.0");

        // download → A0508 文档已归档
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档已归档"));

        // detail?version → A0508 文档已归档（后端直接返回消息）
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-overridden")
                .queryParam("version", "v1.0")
                .when().get("/kb/documents/detail")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档已归档"));

        // 当前版 detail 不受影响
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-overridden")
                .when().get("/kb/documents/detail")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.version", equalTo("v2.0"))
                .body("data.isCurrentVersion", equalTo(true))
                .body("data.versionStatus", equalTo("PUBLISHED"));

        // 重复版本级归档：幂等成功
        archiveVersion("doc-overridden", "v1.0");
    }

    /** 版本级废弃：版本行置 DEPRECATED，download/detail 提示「文档已废弃」 */
    @Test
    void shouldDeprecateSingleVersionWithDeprecatedMessage() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/deprecate")
                .then().statusCode(200).body("code", equalTo("0"));

        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", equalTo("A0508"))
                .body("message", equalTo("文档已废弃"));

        // 文档本体状态不变
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-overridden")
                .when().get("/kb/documents/detail")
                .then().statusCode(200)
                .body("data.status", equalTo("PUBLISHED"));
    }

    /** 版本不存在（或指向当前版）→ A0430 documentVersionNotFound */
    @Test
    void shouldReturnA0430WhenVersionLevelTargetMissing() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v9.9\"}")
                .when().post("/kb/documents/archive")
                .then().statusCode(200).body("code", equalTo("A0430"));

        // 当前版不在归档表 → 同样 A0430（当前版生命周期由文档级操作管理）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v2.0\"}")
                .when().post("/kb/documents/archive")
                .then().statusCode(200).body("code", equalTo("A0430"));
    }

    /** version 缺失 → 校验失败（400 文档版本不能为空）：仅版本级归档，不允许整文档归档 */
    @Test
    void shouldRejectArchiveWhenVersionAbsent() {
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\"}")
                .when().post("/kb/documents/archive")
                .then().statusCode(400)
                .body("message", containsString("文档版本不能为空"));
    }

    /** version 缺失 → 校验失败（400）：仅版本级废弃，不允许整文档废弃 */
    @Test
    void shouldRejectDeprecateWhenVersionAbsent() {
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\"}")
                .when().post("/kb/documents/deprecate")
                .then().statusCode(400)
                .body("message", containsString("文档版本不能为空"));
    }

    /** 防御性去重：脏数据（归档行 versionNumber == currentVersion）只出当前版一行 */
    @Test
    void shouldDeduplicateRowsWhenArchivedVersionEqualsCurrentVersion() {
        sessionFactory.withTransaction(session -> {
            // 构造脏数据：文档当前版 v1.0，且归档表里也有 v1.0（违反写侧不变量的历史脏数据）
            DocumentPO dirty = doc("doc-dirty", "kb-hv", "脏数据文档.pdf", "v1.0");
            DocumentVersionPO dirtyVersion = version("doc-dirty", "v1.0");
            return session.persist(dirty)
                    .chain(() -> session.persist(dirtyVersion));
        }).await().indefinitely();

        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true," +
                        "\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                // doc-dirty 只出当前版一行（归档 v1.0 行被防御守卫跳过）
                .body("data.records.count { it.id == 'doc-dirty' }", equalTo(1))
                .body("data.records.find { it.id == 'doc-dirty' }.isCurrentVersion", equalTo(true));
    }

    // ======================== 版本级删除 ========================

    /** 版本级删除：仅移除 v1.0 版本行，文档本体与其它版本不受影响 */
    @Test
    void shouldDeleteSingleVersionOnly() {
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        // v1.0 版本行已移除：search 不再返回该版本行（无 statuses 过滤全量）
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.count { it.id == 'doc-overridden' && it.version == 'v1.0' }", equalTo(0))
                // 当前版 v2.0 仍在
                .body("data.records.count { it.id == 'doc-overridden' && it.version == 'v2.0' }", equalTo(1));

        // 下载该版本 → A0430 documentVersionNotFound
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 版本级删除指向当前版 → A0430（当前版生命周期由文档级操作管理） */
    @Test
    void shouldRejectDeleteOfCurrentVersionViaVersionParam() {
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"doc-overridden\",\"version\":\"v2.0\"}")
                .when().post("/kb/documents/delete")
                .then().statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 批量删除 items：版本级 + 文档级 + 不存在的版本 SKIPPED，逐条独立 */
    @Test
    void shouldBatchDeleteWithVersionItems() {
        // doc-overridden(v1.0) 版本级删除成功；doc-overridden(v9.9) 版本不存在 SKIPPED
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"items\":[{\"documentId\":\"doc-overridden\",\"version\":\"v1.0\"}," +
                        "{\"documentId\":\"doc-overridden\",\"version\":\"v9.9\"}]}")
                .when().post("/kb/documents/batch-delete")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.summary.total", equalTo(2))
                .body("data.summary.deleted", equalTo(1))
                .body("data.summary.skipped", equalTo(1))
                .body("data.results[0].status", equalTo("DELETED"))
                .body("data.results[0].version", equalTo("v1.0"))
                .body("data.results[1].status", equalTo("SKIPPED"))
                .body("data.results[1].code", equalTo("A0430"));

        // 验证 v1.0 已删、v2.0 当前版仍在
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.count { it.id == 'doc-overridden' && it.version == 'v1.0' }", equalTo(0))
                .body("data.records.count { it.id == 'doc-overridden' && it.version == 'v2.0' }", equalTo(1));
    }

    /** 显示语义：覆盖后文档为 DRAFT 时，历史版本行仍显示 PUBLISHED（曾发布可检索），当前行显示 DRAFT */
    @Test
    void shouldShowHistoricalVersionAsPublishedAfterOverrideToDraft() {
        sessionFactory.withTransaction(session -> {
            DocumentPO draftDoc = doc("doc-draft-after-override", "kb-hv", "覆盖后文档.pdf", "v2.0");
            draftDoc.setStatus("DRAFT");
            draftDoc.setProcessStatus("PENDING");
            return session.persist(draftDoc)
                    .chain(() -> session.persist(version("doc-draft-after-override", "v1.0")));
        }).await().indefinitely();

        // 无 statuses 过滤：两行都在——当前行 DRAFT、历史行 PUBLISHED
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true," +
                        "\"metadata\":{\"version\":\"v1.0,v2.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.find { it.id == 'doc-draft-after-override' && it.version == 'v1.0' }.status",
                        equalTo("PUBLISHED"))
                .body("data.records.find { it.id == 'doc-draft-after-override' && it.version == 'v1.0' }.processStatus",
                        equalTo("COMPLETED"))
                .body("data.records.find { it.id == 'doc-draft-after-override' && it.version == 'v2.0' }.status",
                        equalTo("DRAFT"));

        // 筛选一致性：statuses=[PUBLISHED] 含历史 PUBLISHED 行；[DRAFT] 仅当前行
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"statuses\":[\"PUBLISHED\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.count { it.id == 'doc-draft-after-override' && it.version == 'v1.0' }",
                        equalTo(1))
                .body("data.records.count { it.id == 'doc-draft-after-override' && it.version == 'v2.0' }",
                        equalTo(0));

        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"statuses\":[\"DRAFT\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.count { it.id == 'doc-draft-after-override' && it.version == 'v2.0' }",
                        equalTo(1))
                .body("data.records.count { it.id == 'doc-draft-after-override' && it.version == 'v1.0' }",
                        equalTo(0));

        // detail?version= 历史版 → status=PUBLISHED
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-draft-after-override")
                .queryParam("version", "v1.0")
                .when().get("/kb/documents/detail")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.status", equalTo("PUBLISHED"))
                .body("data.isCurrentVersion", equalTo(false));
    }

    /** 初始上传未审核（DRAFT，document_versions 无行）：当前行 versionStatus 镜像文档状态，不再误显示发布中 */
    @Test
    void shouldMirrorDocumentStatusAsVersionStatusForFreshDraft() {
        sessionFactory.withTransaction(session -> {
            DocumentPO draftDoc = doc("doc-fresh-draft", "kb-hv", "初始草稿文档.pdf", "v1.0");
            draftDoc.setStatus("DRAFT");
            draftDoc.setProcessStatus("PENDING");
            return session.persist(draftDoc);
        }).await().indefinitely();

        // search 当前行：status 与 versionStatus 均为 DRAFT
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true," +
                        "\"metadata\":{\"version\":\"v1.0\"}}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.records.find { it.id == 'doc-fresh-draft' }.status", equalTo("DRAFT"))
                .body("data.records.find { it.id == 'doc-fresh-draft' }.versionStatus", equalTo("DRAFT"))
                .body("data.records.find { it.id == 'doc-fresh-draft' }.isCurrentVersion", equalTo(true));

        // detail 当前版：versionStatus 同样为 DRAFT
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("documentId", "doc-fresh-draft")
                .when().get("/kb/documents/detail")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.versionStatus", equalTo("DRAFT"));
    }

    /**
     * 官方标识是<b>文档级</b>属性：官方过滤命中后，覆盖后文档的历史版行与当前版行携带同一 official 值。
     * 本用例自行灌一条 official 元数据（{@code @BeforeEach} 每例重建，不影响同文件其它用例）。
     */
    @Test
    void shouldCarrySameOfficialOnCurrentAndHistoricalVersionRows() {
        sessionFactory.withTransaction(session -> session.persist(
                meta("hv-meta-official", "doc-overridden", "official", "OFFICIAL")))
                .await().indefinitely();

        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-hv\",\"includeDescendants\":true,\"officials\":[\"OFFICIAL\"]}")
                .when().post("/kb/documents/search")
                .then().statusCode(200).body("code", equalTo("0"))
                // 仅 doc-overridden 标注；当前版行 + 历史版行各一行
                .body("data.totalRow", equalTo(2))
                .body("data.records.count { it.id == 'doc-overridden' && it.isCurrentVersion == true && it.official == 'OFFICIAL' }",
                        equalTo(1))
                .body("data.records.count { it.id == 'doc-overridden' && it.isCurrentVersion == false && it.version == 'v1.0' && it.official == 'OFFICIAL' }",
                        equalTo(1));
    }

    private void archiveVersion(String documentId, String version) {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"" + documentId + "\",\"version\":\"" + version + "\"}")
                .when().post("/kb/documents/archive")
                .then().statusCode(200).body("code", equalTo("0"));
    }

    // ======================== 辅助 ========================

    private static KnowledgeBasePO kb(String id) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName("历史版本检索测试库");
        po.setStatus((short) 1);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static DocumentPO doc(String id, String kbId, String title, String currentVersion) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(null);
        po.setTitle(title);
        po.setStatus("PUBLISHED");
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion(currentVersion);
        po.setHasPublishedVersion(true);
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

    private static DocumentVersionPO version(String documentId, String versionNumber) {
        DocumentVersionPO po = new DocumentVersionPO();
        po.setDocumentId(documentId);
        po.setVersionNumber(versionNumber);
        po.setFilePath("kb-hv/" + documentId + "_" + versionNumber + ".pdf");
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setWasPublished(Boolean.TRUE);
        po.setStatus(com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_PUBLISHED);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        return po;
    }

    /** document_metadata 行（本文件仅官方标识版本行用例使用） */
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
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + expSeconds + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
