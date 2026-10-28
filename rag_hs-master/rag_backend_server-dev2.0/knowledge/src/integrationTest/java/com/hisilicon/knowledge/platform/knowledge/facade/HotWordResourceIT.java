package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.HotWordPO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * 集成测试：{@code /hotword/*} 热门词 CRUD + 启用选项（真实 PostgreSQL）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>create/list（分页 + keyword/kbId/status 过滤 + 排序）</li>
 *   <li>update（改内容 / 改所属知识库）/ changeStatus（启用、停用、幂等）</li>
 *   <li>delete（物理删除，再查不存在 → A0437）</li>
 *   <li>同知识库重复 content → A0438；跨知识库同 content 允许</li>
 *   <li>同知识库启用第 5 条 → A0439；停用腾出名额后可再启用</li>
 *   <li>多 kbIds listEnabled：总额度 max(4, 库数)，每库保底 1 条；库数 >4 时总数可超过 4</li>
 *   <li>stats：不传 kbIds = 全部；单选/多选/不存在的库；changeStatus 后数字联动</li>
 *   <li>空 kbIds / 非 0-1 status → A0420 参数校验</li>
 * </ul>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --tests "*HotWordResourceIT"}</p>
 */
@QuarkusTest
class HotWordResourceIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session ->
                        session.createQuery("delete from HotWordPO").executeUpdate()
                                .chain(v -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                                .chain(v -> session.persist(kb("kb-1", "IT-KB1")))
                                .chain(v -> session.persist(kb("kb-2", "IT-KB2"))))
                .await().indefinitely();
    }

    /** 知识库种子（status=1 正常，processStatus=COMPLETED） */
    private static KnowledgeBasePO kb(String id, String name) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setStatus((short) 1);
        po.setProcessStatus("COMPLETED");
        po.setCreatedBy("it");
        po.setCreatedAt(BASE);
        po.setUpdatedAt(BASE);
        return po;
    }

    // ======================== create + list ========================

    /** 创建成功后 list 可查到，字段回显正确 */
    @Test
    void shouldCreateAndList() {
        create("如何退货", "kb-1", 2, (short) 1)
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].content", equalTo("如何退货"))
                .body("data.records[0].kbId", equalTo("kb-1"))
                .body("data.records[0].kbName", equalTo("IT-KB1"))
                .body("data.records[0].sortOrder", equalTo(2))
                .body("data.records[0].status", equalTo(1));
    }

    /** create/update 传入不存在的知识库 id → A0430（应用层前置校验，不得落到 FK 报 500） */
    @Test
    void shouldRejectCreateAndUpdateWhenKbNotExists() {
        create("无效知识库", "kb-not-exist", 0, (short) 0)
                .then().statusCode(200).body("code", equalTo("A0430"));

        String id = createAndGetId("正常内容", "kb-1", 0, (short) 0);
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"正常内容\",\"kbId\":\"kb-not-exist\",\"sortOrder\":0}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("A0430"));
    }

    /** 同知识库重复 content → A0438；跨知识库同 content 允许 */
    @Test
    void shouldRejectDuplicateContentInSameKb() {
        create("如何退货", "kb-1", 0, (short) 0)
                .then().statusCode(200).body("code", equalTo("0"));

        // 同 kb 重复 → A0438
        create("如何退货", "kb-1", 0, (short) 0)
                .then().statusCode(200).body("code", equalTo("A0438"));

        // 不同 kb 同 content → 允许
        create("如何退货", "kb-2", 0, (short) 0)
                .then().statusCode(200).body("code", equalTo("0"));
    }

    /** 分页 + keyword/kbId/status 过滤 + sortOrder asc/updatedAt desc 排序 */
    @Test
    void shouldListWithFiltersOrderingAndPaging() {
        // kb-1：3 条启用（sortOrder 1/2/2），其中一条 updatedAt 更晚用于验证次级排序
        create("退款政策", "kb-1", 2, (short) 1)
                .then().statusCode(200).body("code", equalTo("0"));
        create("退货流程", "kb-1", 1, (short) 1)
                .then().statusCode(200).body("code", equalTo("0"));
        create("退款时效", "kb-1", 2, (short) 0)
                .then().statusCode(200).body("code", equalTo("0"));
        // kb-2 的干扰数据
        create("换货流程", "kb-2", 0, (short) 1)
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .queryParam("status", 1)
                .queryParam("keyword", "退")
                .queryParam("page", 1)
                .queryParam("pageSize", 10)
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                // sortOrder asc：1 在前；同 sortOrder=2 按 updatedAt desc
                .body("data.records.content", contains("退货流程", "退款政策"));
    }

    // ======================== update / changeStatus / delete ========================

    /** 更新内容与排序号生效 */
    @Test
    void shouldUpdateContentAndSortOrder() {
        String id = createAndGetId("原始内容", "kb-1", 0, (short) 0);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"更新内容\",\"kbId\":\"kb-1\",\"sortOrder\":3,\"status\":0}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("data.records[0].content", equalTo("更新内容"))
                .body("data.records[0].sortOrder", equalTo(3));
    }

    /** 更新直接切换状态生效；缺 status → 400（Bean Validation 必填） */
    @Test
    void shouldUpdateStatusAndRequireStatusField() {
        String id = createAndGetId("状态切换词", "kb-1", 0, (short) 0);

        // update 携带 status=1 → 启用生效
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"状态切换词\",\"kbId\":\"kb-1\",\"sortOrder\":0,\"status\":1}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .queryParam("status", 1)
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("data.records.content", contains("状态切换词"));

        // 缺 status → 400（必填）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"状态切换词\",\"kbId\":\"kb-1\",\"sortOrder\":0}")
                .when().post("/hotword/update")
                .then().statusCode(400).body("message", containsString("状态不能为空"));
    }

    /** 更新为同知识库已有内容 → A0438 */
    @Test
    void shouldRejectUpdateToDuplicateContent() {
        create("已有内容", "kb-1", 0, (short) 0);
        String id = createAndGetId("待更新内容", "kb-1", 0, (short) 0);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"已有内容\",\"kbId\":\"kb-1\",\"sortOrder\":0,\"status\":0}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("A0438"));
    }

    /** 更换所属知识库：换库生效；换到目标库已有同名内容 → A0438 */
    @Test
    void shouldMoveToAnotherKbAndRejectDuplicateOnMove() {
        // 目标库 kb-2 已有同名内容
        create("占用内容", "kb-2", 0, (short) 0);
        String id = createAndGetId("我的内容", "kb-1", 0, (short) 0);

        // 换到 kb-2 且内容与 kb-2 现有条目重复 → A0438
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"占用内容\",\"kbId\":\"kb-2\",\"sortOrder\":1,\"status\":0}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("A0438"));

        // 换到 kb-2（内容不重复）→ 成功，且原库不再有该行
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"content\":\"我的内容\",\"kbId\":\"kb-2\",\"sortOrder\":1,\"status\":0}")
                .when().post("/hotword/update")
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/hotword/list")
                .then().statusCode(200).body("data.totalRow", equalTo(0));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-2")
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("data.totalRow", equalTo(2))
                .body("data.records.kbId", everyItem(equalTo("kb-2")));
    }

    /** 启用/停用切换；目标状态与现值相同幂等；启用第 5 条 → A0439 */
    @Test
    void shouldChangeStatusAndEnforceEnabledLimit() {
        // 铺满 4 条启用
        for (int i = 1; i <= 4; i++) {
            create("启用词-" + i, "kb-1", i, (short) 1)
                    .then().statusCode(200).body("code", equalTo("0"));
        }
        // 第 5 条：直接以启用状态创建 → A0439
        create("第5条", "kb-1", 5, (short) 1)
                .then().statusCode(200).body("code", equalTo("A0439"));

        // 以停用状态创建成功，再启用 → A0439
        String fifth = createAndGetId("第5条", "kb-1", 5, (short) 0);
        changeStatus(fifth, 1)
                .then().statusCode(200).body("code", equalTo("A0439"));

        // 停用一条腾出名额 → 再启用成功
        String first = findByContent("启用词-1");
        changeStatus(first, 0)
                .then().statusCode(200).body("code", equalTo("0"));
        changeStatus(fifth, 1)
                .then().statusCode(200).body("code", equalTo("0"));

        // 幂等：目标状态与现值相同 → 直接返回成功
        changeStatus(fifth, 1)
                .then().statusCode(200).body("code", equalTo("0"));

        // status 非 0/1 → A0420
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + fifth + "\",\"status\":2}")
                .when().post("/hotword/changeStatus")
                .then().statusCode(200).body("code", equalTo("A0420"));
    }

    /** 物理删除：删除后 list 不再返回；再按 id 删除 → A0437 */
    @Test
    void shouldDeletePhysically() {
        String id = createAndGetId("待删除", "kb-1", 0, (short) 0);

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("id", id)
                .when().delete("/hotword/delete")
                .then().statusCode(200).body("code", equalTo("0"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbId", "kb-1")
                .when().get("/hotword/list")
                .then().statusCode(200)
                .body("data.totalRow", equalTo(0));

        // 不存在 → A0437
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("id", id)
                .when().delete("/hotword/delete")
                .then().statusCode(200).body("code", equalTo("A0437"));
    }

    // ======================== listEnabled ========================

    /** 多 kbIds：总额度 max(4, 库数)=4，名额尽量均衡（2 库各 2 条），按全局排序返回 */
    @Test
    void shouldListEnabledAcrossKbsWithGlobalLimit() {
        // kb-1：3 条启用（sortOrder 1/2/3）+ 1 条停用
        create("启用1", "kb-1", 1, (short) 1);
        create("启用2", "kb-1", 2, (short) 1);
        create("启用3", "kb-1", 3, (short) 1);
        create("停用1", "kb-1", 0, (short) 0);
        // kb-2：2 条启用（sortOrder 4/5）
        create("启用4", "kb-2", 4, (short) 1);
        create("启用5", "kb-2", 5, (short) 1);

        // 配额 max(4, 2)=4，轮询均衡：每库各 2 条
        io.restassured.response.Response resp = given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1")
                .queryParam("kbIds", "kb-2")
                .when().get("/hotword/listEnabled");
        resp.then().statusCode(200)
                .body("code", equalTo("0"))
                .body("data", hasSize(4))
                .body("data.content", contains("启用1", "启用2", "启用4", "启用5"));

        // 均衡校验：每库恰好 2 条
        java.util.Map<String, Long> perKb = resp.jsonPath().getList("data.kbId").stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        kbId -> String.valueOf(kbId), java.util.stream.Collectors.counting()));
        org.junit.jupiter.api.Assertions.assertEquals(Long.valueOf(2L), perKb.get("kb-1"));
        org.junit.jupiter.api.Assertions.assertEquals(Long.valueOf(2L), perKb.get("kb-2"));

        // 单 kbId：停用项不返回
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1")
                .when().get("/hotword/listEnabled")
                .then().statusCode(200)
                .body("data", hasSize(3));
    }

    /** 每库保底：某库仅 1 条启用也必须出现；库数 >4 时总数可超过 4 */
    @Test
    void shouldGuaranteeBaselinePerKbAndExceedFourWithMoreThanFourKbs() {
        // 追加 4 个知识库，共 6 个
        sessionFactory.withTransaction(session -> {
            List<KnowledgeBasePO> extras = new ArrayList<>();
            for (int i = 3; i <= 6; i++) {
                extras.add(kb("kb-" + i, "IT-KB" + i));
            }
            return session.persistAll(extras.toArray());
        }).await().indefinitely();

        // 每库各 1 条启用
        for (int i = 1; i <= 6; i++) {
            create("保底-" + i, "kb-" + i, i, (short) 1);
        }

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1")
                .queryParam("kbIds", "kb-2")
                .queryParam("kbIds", "kb-3")
                .queryParam("kbIds", "kb-4")
                .queryParam("kbIds", "kb-5")
                .queryParam("kbIds", "kb-6")
                .when().get("/hotword/listEnabled")
                .then().statusCode(200)
                .body("code", equalTo("0"))
                // 6 库 > 4，每库保底 1 条 → 总数 6，可超过 4
                .body("data", hasSize(6))
                .body("data.kbId", contains("kb-1", "kb-2", "kb-3", "kb-4", "kb-5", "kb-6"));
    }

    /** listEnabled 超出每库上限 4 条启用时截断为 4；空 kbIds → A0420 */
    @Test
    void shouldTruncateListEnabledToFourAndRejectEmptyKbIds() {
        for (int i = 1; i <= 3; i++) {
            create("截断-" + i, "kb-1", i, (short) 1)
                    .then().statusCode(200).body("code", equalTo("0"));
        }
        // 每库上限 4：第 5 条启用创建 → A0439
        create("截断-4", "kb-1", 4, (short) 1)
                .then().statusCode(200).body("code", equalTo("0"));
        create("截断-5", "kb-1", 5, (short) 1)
                .then().statusCode(200).body("code", equalTo("A0439"));

        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1")
                .when().get("/hotword/listEnabled")
                .then().statusCode(200)
                .body("data", hasSize(4));

        // 空 kbIds → A0420 参数校验
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/hotword/listEnabled")
                .then().statusCode(200).body("code", equalTo("A0420"));
    }

    // ======================== stats ========================

    /** stats：不传 kbIds = 全部；单选/多选 kbIds 只统计所选库；changeStatus 后数字联动 */
    @Test
    void shouldCountStatsWithOptionalKbIds() {
        // 种子：kb-1 启用2停用1，kb-2 启用1停用2（每库启用上限 4，未超限）
        create("统计-a1", "kb-1", 1, (short) 1).then().body("code", equalTo("0"));
        create("统计-a2", "kb-1", 2, (short) 1).then().body("code", equalTo("0"));
        create("统计-a3", "kb-1", 3, (short) 0).then().body("code", equalTo("0"));
        create("统计-b1", "kb-2", 1, (short) 1).then().body("code", equalTo("0"));
        create("统计-b2", "kb-2", 2, (short) 0).then().body("code", equalTo("0"));
        create("统计-b3", "kb-2", 3, (short) 0).then().body("code", equalTo("0"));

        // 不传 kbIds = 全部：6/3/3
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/hotword/stats")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.total", equalTo(6))
                .body("data.enabled", equalTo(3))
                .body("data.disabled", equalTo(3));

        // 单选 kb-1：3/2/1
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1")
                .when().get("/hotword/stats")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.total", equalTo(3))
                .body("data.enabled", equalTo(2))
                .body("data.disabled", equalTo(1));

        // 多选 kb-1 + kb-2：6/3/3（多值参数写法）
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-1", "kb-2")
                .when().get("/hotword/stats")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.total", equalTo(6))
                .body("data.enabled", equalTo(3))
                .body("data.disabled", equalTo(3));

        // 不存在的库：0/0/0
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("kbIds", "kb-x")
                .when().get("/hotword/stats")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.total", equalTo(0))
                .body("data.enabled", equalTo(0))
                .body("data.disabled", equalTo(0));

        // 停用一条后数字联动：全部口径 6/2/4
        String id = createAndGetId("统计-联动", "kb-1", 4, (short) 1);
        changeStatus(id, 0).then().statusCode(200).body("code", equalTo("0"));
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/hotword/stats")
                .then().statusCode(200).body("code", equalTo("0"))
                .body("data.total", equalTo(7))
                .body("data.enabled", equalTo(3))
                .body("data.disabled", equalTo(4));
    }

    // ======================== 参数校验 ========================

    /** create 缺 content → Bean 校验失败（400 + A0400，走 ConstraintViolationExceptionMapper，项目既有约定） */
    @Test
    void shouldRejectCreateWithoutContent() {
        given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"kb-1\"}")
                .when().post("/hotword/create")
                .then().statusCode(400).body("message", containsString("热门词内容不能为空"));
    }

    // ======================== 辅助：HTTP 种子 ========================

    private io.restassured.response.Response create(String content, String kbId, int sortOrder, Short status) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"content\":\"" + content + "\",\"kbId\":\"" + kbId
                        + "\",\"sortOrder\":" + sortOrder + ",\"status\":" + status + "}")
                .when().post("/hotword/create");
    }

    /** 创建并从库里取回生成的 id */
    private String createAndGetId(String content, String kbId, int sortOrder, Short status) {
        create(content, kbId, sortOrder, status).then().statusCode(200).body("code", equalTo("0"));
        return findByContent(content);
    }

    private String findByContent(String content) {
        return sessionFactory.withTransaction(session -> session
                        .createQuery("from HotWordPO where content = :content", HotWordPO.class)
                        .setParameter("content", content)
                        .getSingleResult()
                        .map(po -> po.getId()))
                .await().indefinitely();
    }

    private io.restassured.response.Response changeStatus(String id, int status) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("{\"id\":\"" + id + "\",\"status\":" + status + "}")
                .when().post("/hotword/changeStatus");
    }

    // ======================== token ========================

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
