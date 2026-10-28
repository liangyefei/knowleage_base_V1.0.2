package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentContentPO;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * 集成测试：文档全局搜索（{@code POST /kb/documents/global-search} 与
 * {@code GET /kb/documents/global-metadata-options}）真实 PostgreSQL。
 * <p>芯片型号匹配口径 = <b>知识库名称</b>（KB 以芯片型号命名，大小写不敏感，多选 OR），
 * 不查 document_metadata。验证：跨知识库检索、KB 名称过滤（含大小写不敏感 / 已删库同名排除 /
 * 无已发布文档 KB 不命中）、docType/名称过滤、出处字段（kbId/kbName/categoryId/categoryName）、
 * 分页、pageSize 超限、content 字段留空、芯片下拉返回活跃且有已发布文档的 KB 名称。</p>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentGlobalSearchIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        // 清库（外键顺序：document_contents/metadata/documents → categories → knowledge_bases）
                        .createQuery("delete from DocumentContentPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentMetadataPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        // KB 以芯片型号命名：kb-3 与 kb-1 同名但已删（验证排除）；kb-4 活跃但无已发布文档
                        .chain(() -> persistAll(session,
                                kb("kb-1", "CV610", (short) 1),
                                kb("kb-2", "CV620", (short) 1),
                                kb("kb-3", "CV610", (short) 0),
                                kb("kb-4", "CV720", (short) 1)))
                        .chain(() -> persistAll(session,
                                cat(new CatSeed("cat-1", "kb-1", null, "CV610系列", 0, (short) 1)),
                                cat(new CatSeed("cat-2", "kb-1", null, "其他目录", 1, (short) 1))))
                        // 已发布文档：doc-1..5；doc-6 属已删库（同名 CV610，排除）；doc-7 草稿（排除）
                        .chain(() -> persistAll(session,
                                doc(new DocSeed("doc-1", "kb-1", "cat-1", "CV610选型指南.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-2", "kb-1", "cat-1", "CV610SDK开发指南.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-3", "kb-1", "cat-2", "通用规格书.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-4", "kb-1", null, "通用开发手册.c", "CODE", "PUBLISHED")),
                                doc(new DocSeed("doc-5", "kb-2", null, "CV620驱动代码.c", "CODE", "PUBLISHED")),
                                doc(new DocSeed("doc-6", "kb-3", null, "应被排除.pdf", "DOCUMENT", "PUBLISHED")),
                                doc(new DocSeed("doc-7", "kb-4", null, "草稿文档.pdf", "DOCUMENT", "DRAFT"))))
                        // 元数据与芯片过滤解耦：doc-1 chip_model 为历史脏值 "3" 仍应按 KB 名命中
                        .chain(() -> persistAll(session,
                                meta("m-1", "doc-1", "chip_model", "3"),
                                meta("m-2", "doc-2", "chip_model", "CV620,CV610"),
                                meta("m-3", "doc-3", "version", "v2.1"))))
                .await().indefinitely();
    }

    // ======================== 跨库检索 + 排除规则 ========================

    @Test
    void shouldReturnAllPublishedDocsAcrossActiveKbs() {
        // doc-1..5 命中；doc-6（已删库）、doc-7（草稿）被排除
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(5))
                .body("data.records", hasSize(5));
    }

    // ======================== 出处字段 ========================

    @Test
    void shouldReturnProvenanceKbAndCategory() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // 跨库文档带出 kbName；根目录文档 categoryName=null
                .body("data.records.find { it.id == 'doc-5' }.kbName", equalTo("CV620"))
                .body("data.records.find { it.id == 'doc-5' }.kbId", equalTo("kb-2"))
                .body("data.records.find { it.id == 'doc-4' }.categoryId", nullValue())
                .body("data.records.find { it.id == 'doc-4' }.categoryName", nullValue())
                .body("data.records.find { it.id == 'doc-1' }.categoryName", equalTo("CV610系列"));
    }

    // ======================== 芯片型号过滤（KB 名称口径） ========================

    @Test
    void shouldFilterByChipModelMatchingKbName() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV610\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // kb-1（CV610）下已发布 doc-1..4 共 4 条；doc-6 同名但已删库应排除
                .body("data.totalRow", equalTo(4))
                .body("data.records.id", hasSize(4))
                .body("data.records.id", hasItems("doc-1", "doc-2", "doc-3", "doc-4"));
    }

    @Test
    void shouldFilterByChipModelCaseInsensitive() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"cv610\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(4));
    }

    @Test
    void shouldFilterByChipModelDecoupledFromMetadata() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV610\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // doc-1 的 document_metadata.chip_model 为历史脏值 "3"（无 CV610 段），
                // 按 KB 名称口径仍应命中——证明过滤已与元数据解耦
                .body("data.records.find { it.id == 'doc-1' }.metadata.chip_model", equalTo("3"));
    }

    @Test
    void shouldFilterByChipModelMultiOr() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV610\",\"CV620\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // kb-1 + kb-2 全部已发布文档：doc-1..5
                .body("data.totalRow", equalTo(5));
    }

    @Test
    void shouldReturnEmptyWhenChipModelKbHasNoPublishedDocs() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV720\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // kb-4（CV720）活跃但仅有草稿 doc-7 → 0 条
                .body("data.totalRow", equalTo(0))
                .body("data.records", hasSize(0));
    }

    @Test
    void shouldCombineChipModelWithDocTypeAndDocName() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV610\",\"CV620\"],\"docTypes\":[\"CODE\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // CV610/CV620 库 ∩ CODE：doc-4、doc-5
                .body("data.totalRow", equalTo(2))
                .body("data.records.id", hasItems("doc-4", "doc-5"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"chipModels\":[\"CV610\"],\"docName\":\"cv610\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // CV610 库 ∩ 标题含 cv610：doc-1、doc-2（doc-3 标题不含）
                .body("data.totalRow", equalTo(2))
                .body("data.records.id", hasItems("doc-1", "doc-2"));
    }

    // ======================== 其他过滤 ========================

    @Test
    void shouldFilterByDocType() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docTypes\":[\"CODE\"]}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // doc-4、doc-5 为 CODE
                .body("data.totalRow", equalTo(2))
                .body("data.records.docType", hasItems("CODE", "CODE"));
    }

    @Test
    void shouldFilterByDocNameCaseInsensitive() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"cv610\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // 标题含 CV610：doc-1、doc-2
                .body("data.totalRow", equalTo(2));
    }

    @Test
    void shouldExcludeDraftDoc() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"草稿\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                // doc-7 为草稿，非 PUBLISHED，应被排除
                .body("data.totalRow", equalTo(0));
    }

    // ======================== 分页 + 校验 + content ========================

    @Test
    void shouldPaginate() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"page\":1,\"pageSize\":2}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(5))
                .body("data.records", hasSize(2))
                .body("data.totalPage", equalTo(3));
    }

    @Test
    void shouldFailWhenPageSizeExceeded() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"pageSize\":2001}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("每页条数超出限制"));
    }

    @Test
    void shouldReturnNullContentField() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"CV610选型\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-1"))
                // content 本期对非文本类型留空
                .body("data.records[0].content", nullValue());
    }

    // ======================== 按内容检索（searchField） ========================

    @Test
    void shouldMatchByContentAndReturnSnippet() {
        // doc-3 标题不含"寄存器配置"，但抽取文本包含 → 默认 all 模式内容命中，返回片段
        sessionFactory.withTransaction(session -> persistAll(session,
                content("doc-3", "kb-1/通用规格书.pdf", "本章描述 UART 寄存器配置细节与时钟树。")))
                .await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"寄存器配置\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].id", equalTo("doc-3"))
                .body("data.records[0].content", containsString("寄存器配置"));
    }

    @Test
    void shouldLimitContentModeToContentMatchesOnly() {
        sessionFactory.withTransaction(session -> persistAll(session,
                content("doc-3", "kb-1/通用规格书.pdf", "本章描述 UART 寄存器配置细节。")))
                .await().indefinitely();

        // searchField=content：标题含 CV610 但内容不含的 doc-1/doc-2 不命中
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"cv610\",\"searchField\":\"content\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0));

        // searchField=title：仅标题命中 doc-1/doc-2（内容命中的 doc-3 不出现）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"cv610\",\"searchField\":\"title\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records.id", hasItems("doc-1", "doc-2"))
                .body("data.records.find { it.id == 'doc-1' }.content", nullValue());
    }

    @Test
    void shouldExcludeNonReadyOrStalePathContent() {
        // doc-1：SKIPPED；doc-2：READY 但 filePath 为旧版本路径（与 documents.file_path 不等值）→ 均不命中
        sessionFactory.withTransaction(session -> persistAll(session,
                content("doc-1", "kb-1/CV610选型指南.pdf", "正文包含魔术语义参考", "SKIPPED"),
                content("doc-2", "kb-1/旧版本路径.pdf", "正文包含魔术语义参考")))
                .await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"魔术\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0));
    }

    @Test
    void shouldExcludePublishedButNotVectorizedDocs() {
        // 已发布但 processStatus=PENDING（未向量化完成）→ 整体口径排除（含 title 命中）
        sessionFactory.withTransaction(session -> {
            DocumentPO po = doc(new DocSeed("doc-pending", "kb-1", null, "未向量化文档.pdf", "DOCUMENT", "PUBLISHED"));
            po.setProcessStatus("PENDING");
            return persistAll(session, po);
        }).await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"未向量化\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0));
    }

    @Test
    void shouldFailWhenSearchFieldInvalid() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"searchField\":\"body\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("搜索范围不合法"));
    }

    // ======================== 排序（sortField）====================

    @Test
    void shouldSortByUpdatedAtDescWhenDefault() {
        // 不传 sortField → 默认 time：更新时间倒序（d-new → d-mid → d-old）
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        reseedForTimeSort(base);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(3))
                .body("data.records[0].id", equalTo("d-new"))
                .body("data.records[1].id", equalTo("d-mid"))
                .body("data.records[2].id", equalTo("d-old"));
    }

    @Test
    void shouldSortByUpdatedAtDescWhenSortFieldTime() {
        // sortField=time 显式：与默认同序
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        reseedForTimeSort(base);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"sortField\":\"time\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(3))
                .body("data.records[0].id", equalTo("d-new"))
                .body("data.records[1].id", equalTo("d-mid"))
                .body("data.records[2].id", equalTo("d-old"));
    }

    @Test
    void shouldSortByTitleAscCaseInsensitive() {
        // sortField=TITLE（大写，证入参大小写不敏感）→ 标题升序、不区分大小写；同标题按 updatedAt 倒序。
        // 'Z'(90)<'a'(97)：大小写敏感时 Zebra 排在 apple 前；lower 后 'apple'<'zebra' → apple 在前，证不敏感。
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> persistAll(session,
                                doc(new DocSeed("d-zebra", "kb-1", "cat-1", "Zebra.pdf", "DOCUMENT", "PUBLISHED"), base),
                                doc(new DocSeed("d-apple", "kb-1", "cat-1", "apple.pdf", "DOCUMENT", "PUBLISHED"), base),
                                doc(new DocSeed("d-same-new", "kb-1", "cat-1", "same.pdf", "DOCUMENT", "PUBLISHED"), base.plusHours(2)),
                                doc(new DocSeed("d-same-old", "kb-1", "cat-1", "same.pdf", "DOCUMENT", "PUBLISHED"), base))))
                .await().indefinitely();

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"sortField\":\"TITLE\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(4))
                // lower 序：apple, same(new), same(old), Zebra
                .body("data.records[0].id", equalTo("d-apple"))
                .body("data.records[1].id", equalTo("d-same-new"))
                .body("data.records[2].id", equalTo("d-same-old"))
                .body("data.records[3].id", equalTo("d-zebra"));
    }

    @Test
    void shouldFallbackRelevanceToTimeOrder() {
        // relevance 本期回退为 time 语义：200 正常返回，序与 time 一致
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        reseedForTimeSort(base);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"sortField\":\"relevance\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(3))
                .body("data.records[0].id", equalTo("d-new"))
                .body("data.records[1].id", equalTo("d-mid"))
                .body("data.records[2].id", equalTo("d-old"));
    }

    @Test
    void shouldRejectInvalidSortField() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"sortField\":\"bogus\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("排序字段不合法"));
    }

    // ======================== 全局芯片型号下拉（KB 名称口径） ========================

    @Test
    void shouldReturnGlobalMetadataOptionsFromKbNames() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/documents/global-metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                // 单组 chip_model；kb-4（CV720）活跃但无已发布文档不出现
                .body("data", hasSize(1))
                .body("data[0].metaKey", equalTo("chip_model"))
                .body("data[0].label", equalTo("芯片型号"))
                // hasSize(2) 同时证明 kb-3（已删库同名 CV610）去重后仅一个选项
                .body("data[0].values", hasSize(2))
                .body("data[0].values", hasItems("CV610", "CV620"))
                .body("data[0].values", not(hasItem("CV720")));
    }

    // ======================== 孤儿文档排除（与 navTree 口径一致）====================
    // 历史存量孤儿（2026-08 前删除知识库/类别不级联处理文档遗留）：categoryId 指向
    // 不存在类别 / 已软删类别（status=0）/ 跨库类别。全局搜索与芯片下拉均不可见。

    @Test
    void shouldExcludeOrphanDocumentsFromGlobalSearch() {
        // seed 三类孤儿（全部 PUBLISHED + COMPLETED，仅类别归属非法）
        sessionFactory.withTransaction(session -> persistAll(session,
                cat(new CatSeed("cat-del", "kb-1", null, "已删除类别", 9, (short) 0)),
                cat(new CatSeed("cat-x-kb2", "kb-2", null, "kb2的类别", 9, (short) 1)),
                doc(new DocSeed("doc-orphan-missing", "kb-1", "cat-gone", "孤儿-类别不存在.pdf", "DOCUMENT", "PUBLISHED")),
                doc(new DocSeed("doc-orphan-deleted", "kb-1", "cat-del", "孤儿-类别已删.pdf", "DOCUMENT", "PUBLISHED")),
                doc(new DocSeed("doc-orphan-crosskb", "kb-1", "cat-x-kb2", "孤儿-跨库类别.pdf", "DOCUMENT", "PUBLISHED"))))
                .await().indefinitely();

        // 跨库检索：仅 @BeforeEach 的 doc-1..5 可见，3 条孤儿全部排除
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(5))
                .body("data.records.id", hasSize(5))
                .body("data.records.id", not(hasItems("doc-orphan-missing", "doc-orphan-deleted", "doc-orphan-crosskb")));

        // 按名称搜索孤儿 → 0 命中
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"docName\":\"孤儿\"}")
                .when().post("/kb/documents/global-search")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.totalRow", equalTo(0))
                .body("data.records", hasSize(0));
    }

    @Test
    void shouldExcludeKbWithOnlyOrphanPublishedDocsFromGlobalMetadataOptions() {
        // seed：kb-5（CV999）仅有孤儿已发布文档（类别已软删）→ 不应出现在芯片下拉
        sessionFactory.withTransaction(session -> persistAll(session,
                kb("kb-5", "CV999", (short) 1),
                cat(new CatSeed("cat-del5", "kb-5", null, "已删除类别", 9, (short) 0)),
                doc(new DocSeed("doc-orphan-5", "kb-5", "cat-del5", "孤儿-唯一文档.pdf", "DOCUMENT", "PUBLISHED"))))
                .await().indefinitely();

        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kb/documents/global-metadata-options")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data[0].metaKey", equalTo("chip_model"))
                // 仅 CV610（kb-1）、CV620（kb-2）有可见已发布文档；CV999 仅孤儿、CV720 无已发布 → 排除
                .body("data[0].values", hasItems("CV610", "CV620"))
                .body("data[0].values", not(hasItem("CV999")))
                .body("data[0].values", not(hasItem("CV720")));
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

    /** 文档种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record DocSeed(String id, String kbId, String categoryId, String title,
                           String docType, String status) {
    }

    private static DocumentPO doc(DocSeed s) {
        DocumentPO po = new DocumentPO();
        po.setId(s.id());
        po.setKbId(s.kbId());
        po.setCategoryId(s.categoryId());
        po.setTitle(s.title());
        po.setStatus(s.status());
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion("1");
        po.setHasPublishedVersion(false);
        po.setFilePath(s.kbId() + "/" + s.title());
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType(s.docType());
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 排序测试用：指定 updatedAt（createdAt 同步置为该值，隔离时间排序断言）。 */
    private static DocumentPO doc(DocSeed s, LocalDateTime updatedAt) {
        DocumentPO po = doc(s);
        po.setCreatedAt(updatedAt);
        po.setUpdatedAt(updatedAt);
        return po;
    }

    /** 排序测试共用：清文档后重灌 3 条差异化 updatedAt 的已发布文档（KB/类别沿用 @BeforeEach 预置）。 */
    private void reseedForTimeSort(LocalDateTime base) {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentContentPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentMetadataPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> persistAll(session,
                                doc(new DocSeed("d-old", "kb-1", "cat-1", "old.pdf", "DOCUMENT", "PUBLISHED"), base),
                                doc(new DocSeed("d-mid", "kb-1", "cat-1", "mid.pdf", "DOCUMENT", "PUBLISHED"), base.plusHours(2)),
                                doc(new DocSeed("d-new", "kb-1", "cat-1", "new.pdf", "DOCUMENT", "PUBLISHED"), base.plusHours(4)))))
                .await().indefinitely();
    }

    /** document_contents 种子行（默认 READY） */
    private static DocumentContentPO content(String documentId, String filePath, String text) {
        return content(documentId, filePath, text, "READY");
    }

    private static DocumentContentPO content(String documentId, String filePath, String text, String status) {
        DocumentContentPO po = new DocumentContentPO();
        po.setDocumentId(documentId);
        po.setFilePath(filePath);
        po.setContentText(text);
        po.setContentStatus(status);
        po.setExtractedAt(LocalDateTime.now());
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
