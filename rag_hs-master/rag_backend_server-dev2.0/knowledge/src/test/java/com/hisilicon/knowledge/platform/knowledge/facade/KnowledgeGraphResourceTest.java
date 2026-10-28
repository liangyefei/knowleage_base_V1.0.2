package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeFileListResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @QuarkusTest} for {@code /kg/*}（对齐《API-GRAPH.md》端点集：projects/graph-search/graph-node/
 * graph-kg/doc-view）。
 *
 * <p>Mock {@link MockKnowledgeGraphClient}（端口测试侧唯一实现），无 DB 访问（kbId 由请求直传为
 * dual-mcp 的 project）。</p>
 *
 * <p>Token note：端点仅字符串级解析 JWT payload（不验签），故手造三段式 token。</p>
 */
@QuarkusTest
class KnowledgeGraphResourceTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    private static final String KB_ID = "kb1234abcd";
    private static final String Q = "osal_dump_stack";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @BeforeEach
    void installMocks() {
        MockKnowledgeGraphClient.reset();
        // doc_view 路径裁剪默认无 KB 数据（未注册 → 透传）；裁剪用例内单独安装 KB mock
        MockKnowledgeBaseRepository.setEntityById(null);
    }

    /** 安装 KB 仓储 mock（返回 dirName=CV610ALL 的在库知识库），用于 doc_view 裁剪用例 */
    private void installKbMock() {
        KnowledgeBaseEntity kb = KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("CV610ALL")
                .dirName("CV610ALL")
                .status((short) 1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        MockKnowledgeBaseRepository.setEntityById(kb);
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
    }

    // ======================== GET /kg/projects ========================

    @Test
    void shouldReturnProjects() {
        MockKnowledgeGraphClient.setProjectsResult(new GraphProjectsResult(List.of(
                new GraphProjectsResult.GraphProject("projects-" + KB_ID, 81507, 163616,
                        "/projects/" + KB_ID, "CV610ALL"))));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kg/projects")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data.projects", hasSize(1))
                .body("data.projects[0].name", equalTo("projects-" + KB_ID))
                .body("data.projects[0].nodes", equalTo(81507))
                .body("data.projects[0].kb_name", equalTo("CV610ALL"));
    }

    @Test
    void shouldFailProjectsWhenModelFails() {
        MockKnowledgeGraphClient.setThrowOnProjects();

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .when().get("/kg/projects")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"));
    }

    // ======================== POST /kg/graph-search ========================

    @Test
    void shouldReturnSearchResult() {
        MockKnowledgeGraphClient.setSearchResult(new GraphSearchResult(
                Q, "projects-" + KB_ID,
                new GraphSearchResult.GraphNode(40561, "Function", Q, "osal_debug.osal_dump_stack",
                        "sdk/osal/src/linux/kernel/osal_debug.c", 32, 35, true, 8, 30, 0),
                List.of(), List.of(), List.of(), null, 43.4));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"%s","limit":8,"candidates":0}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-search")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.query", equalTo(Q))
                .body("data.center.name", equalTo(Q))
                .body("data.center.file_path", equalTo("sdk/osal/src/linux/kernel/osal_debug.c"))
                .body("data.center.deg_local", equalTo(8));

        // 验证端口收到 project=kbId + q + 可选参数透传
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastSearchProject());
        assertEquals(Q, MockKnowledgeGraphClient.getLastSearchQ());
        assertEquals(8, MockKnowledgeGraphClient.getLastSearchLimit());
        assertEquals(0, MockKnowledgeGraphClient.getLastSearchCandidates());
    }

    @Test
    void shouldFailSearchWhenNoHit() {
        // 默认桩：center=null、candidates 空 → A0430

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"%s"}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("未找到相关信息"));
    }

    @Test
    void shouldFailSearchWhenModelFails() {
        MockKnowledgeGraphClient.setThrowOnSearch();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "en-US")
                .body("""
                        {"kbId":"%s","q":"%s"}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-search")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"));
    }

    @Test
    void shouldRejectBlankQ() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-search")
                .then()
                .statusCode(400);
    }

    // ======================== POST /kg/graph-node ========================

    @Test
    void shouldReturnNodeDetail() {
        MockKnowledgeGraphClient.setNodeResult(new GraphNodeResult(
                new GraphSearchResult.GraphNode(40561, "Function", Q, null,
                        "sdk/osal/src/linux/kernel/osal_debug.c", 32, 35, null, null, 30, 0),
                "void osal_dump_stack(void) { }", null, Map.of("CALLS", 29, "DEFINES", 1), Map.of(),
                Map.of("complexity", 0, "lines", 2, "is_exported", true),
                Map.of("signature", "void osal_dump_stack(void)"), 12.9));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","name":"%s","withSource":false}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-node")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.node.name", equalTo(Q))
                .body("data.node.in_degree", equalTo(30))
                .body("data.in_types.CALLS", equalTo(29))
                .body("data.source", equalTo("void osal_dump_stack(void) { }"))
                .body("data.props.is_exported", equalTo(true))
                .body("data.extra.signature", equalTo("void osal_dump_stack(void)"));

        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastNodeProject());
        assertEquals(Q, MockKnowledgeGraphClient.getLastNodeName());
        assertEquals(Boolean.FALSE, MockKnowledgeGraphClient.getLastNodeWithSource());
    }

    @Test
    void shouldPassNodeNameThroughUnchanged() {
        // 模型 /graph_node 的 name = 节点精确名：Module 节点是整条相对路径，File/Document 带扩展名。
        // 后端不得截路径/去扩展名（旧实现会把 Module 路径截成 File 文件名、"README.md" 截成 "README"）
        MockKnowledgeGraphClient.setNodeResult(new GraphNodeResult(
                new GraphSearchResult.GraphNode(1, "Document", "README.md", null,
                        null, null, null, null, null, null, 0),
                null, null, Map.of(), Map.of(), null, null, null));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","name":" hi_aiot_solution/README.md ","withSource":false}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-node")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("hi_aiot_solution/README.md", MockKnowledgeGraphClient.getLastNodeName(),
                "name 只做 trim，保持模型要求的节点精确名（含路径/扩展名）");
    }

    @Test
    void shouldAllowIdOnlyNodeQuery() {
        // 模型允许仅按 id 查询（name 由 llm 侧补空串）；服务层不应把 id-only 判为参数非法
        MockKnowledgeGraphClient.setNodeResult(new GraphNodeResult(
                new GraphSearchResult.GraphNode(40561, "Function", Q, null,
                        null, null, null, null, null, null, 0),
                null, null, Map.of(), Map.of(), null, null, null));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","id":40561}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-node")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(40561, MockKnowledgeGraphClient.getLastNodeId());
        assertNull(MockKnowledgeGraphClient.getLastNodeName(), "knowledge 层不臆造 name，补空串由 llm 请求体负责");
    }

    @Test
    void shouldFailNodeWhenNodeMissing() {
        // 默认桩 node=null → A0430

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","name":"%s"}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-node")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    @Test
    void shouldFailNodeWhenNameAndIdBothMissing() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-node")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));
    }

    // ======================== POST /kg/graph-kg ========================

    @Test
    void shouldReturnKgResult() {
        MockKnowledgeGraphClient.setKgResult(new GraphKgResult(
                Q, KB_ID, "mempalace-kg",
                new GraphKgResult.KgNode(Q, Q, 3, "实体", true),
                35,
                List.of(new GraphKgResult.KgNode(Q, Q, 3, "实体", true),
                        new GraphKgResult.KgNode("花果山", "花果山", 3, "实体", false)),
                List.of(new GraphKgResult.KgEdge(Q, "花果山", "born_at", "born_at",
                        1.0f, null, null, true)),
                null, 113.0));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"%s"}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-kg")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.query", equalTo(Q))
                .body("data.palace", equalTo(KB_ID))
                .body("data.count", equalTo(35))
                .body("data.nodes", hasSize(2))
                .body("data.nodes[0].type", equalTo("实体"))
                .body("data.edges[0].confidence", equalTo(1.0f))
                .body("data.edges[0].current", equalTo(true));

        assertEquals(Q, MockKnowledgeGraphClient.getLastKgQ());
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastKgPalace());
    }

    @Test
    void shouldKgWithoutKbIdQueryAllGraph() {
        // kbId 可选：缺省=全库模式，palace 下传为 null（向后兼容）
        MockKnowledgeGraphClient.setKgResult(new GraphKgResult(
                Q, null, "mempalace-kg",
                new GraphKgResult.KgNode(Q, Q, 3, "实体", true),
                48, List.of(), List.of(), null, 113.0));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"q":"%s"}
                        """.formatted(Q))
                .when().post("/kg/graph-kg")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.count", equalTo(48));

        assertEquals(Q, MockKnowledgeGraphClient.getLastKgQ());
        assertNull(MockKnowledgeGraphClient.getLastKgPalace(), "kbId 缺省时 palace 应为 null（全库模式）");
    }

    @Test
    void shouldFailKgWhenCountZero() {
        // 默认桩 count=0（实体不在图，模型 200 正常响应）→ A0430

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"q":"%s"}
                        """.formatted(Q))
                .when().post("/kg/graph-kg")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("未找到相关信息"));
    }

    // ======================== POST /kg/graph-fragment ========================

    @Test
    void shouldReturnFragmentResult() {
        MockKnowledgeGraphClient.setFragmentResult(new GraphFragmentResult(
                "LED", "born_at", "花果山", 2,
                List.of(new GraphFragmentResult.Fragment("石猴出生于花果山", "doc1.md", "room-1", "3",
                                "/kg/doc-view?doc=room-1&kb=" + KB_ID),
                        new GraphFragmentResult.Fragment("猴王称霸水帘洞", "doc2.md", "room-1", "7", null)),
                45.2, null));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","subject":"LED","predicate":"born_at","object":"花果山"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-fragment")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.subject", equalTo("LED"))
                .body("data.count", equalTo(2))
                .body("data.fragments", hasSize(2))
                .body("data.fragments[0].text", equalTo("石猴出生于花果山"))
                .body("data.fragments[0].source_file", equalTo("doc1.md"))
                .body("data.fragments[0].chunk_index", equalTo("3"))
                .body("data.fragments[0].doc_view_url", equalTo("/kg/doc-view?doc=room-1&kb=" + KB_ID));

        assertEquals("LED", MockKnowledgeGraphClient.getLastFragmentSubject());
        assertEquals("born_at", MockKnowledgeGraphClient.getLastFragmentPredicate());
        assertEquals("花果山", MockKnowledgeGraphClient.getLastFragmentObject());
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastFragmentPalace());
    }

    @Test
    void shouldPassThroughFragmentCountZero() {
        // 默认桩 count=0 → 与 /graph_kg 不同：这是数据真值，正常返回空列表而非 A0430
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","subject":"不存在的实体"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-fragment")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.count", equalTo(0))
                .body("data.fragments", hasSize(0));

        assertNull(MockKnowledgeGraphClient.getLastFragmentPredicate(), "predicate 缺省应透传 null");
        assertNull(MockKnowledgeGraphClient.getLastFragmentObject(), "object 缺省应透传 null");
    }

    @Test
    void shouldFailFragmentWhenModelErrors() {
        MockKnowledgeGraphClient.setThrowOnFragment();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","subject":"LED"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-fragment")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"));
    }

    @Test
    void shouldRejectFragmentWithoutSubject() {
        // 缺 subject 走 Bean Validation（@NotBlank）→ 与 shouldRejectBlankQ 同为 400 路径
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-fragment")
                .then()
                .statusCode(400);
    }

    // ======================== POST /kg/graph-suggest ========================

    @Test
    void shouldReturnSuggestResult() {
        MockKnowledgeGraphClient.setSuggestResult(new GraphSuggestResult("LED", "projects-" + KB_ID, List.of(
                new GraphSuggestResult.Suggestion("LED", "实体", "kg", "", 231),
                new GraphSuggestResult.Suggestion("osal_dump_stack", "Function", "code",
                        "osal_debug.c", 30)), 68.1));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"LED","limit":10}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-suggest")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.query", equalTo("LED"))
                .body("data.suggestions", hasSize(2))
                .body("data.suggestions[0].name", equalTo("LED"))
                .body("data.suggestions[0].source", equalTo("kg"))
                .body("data.suggestions[1].source", equalTo("code"));

        // 验证端口收到 project=kbId + palace=kbId + q + limit 透传
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastSuggestProject());
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastSuggestPalace(), "kbId 应显式作为 palace 下传（KG 源按库过滤）");
        assertEquals("LED", MockKnowledgeGraphClient.getLastSuggestQ());
        assertEquals(10, MockKnowledgeGraphClient.getLastSuggestLimit());
    }

    @Test
    void shouldReturnEmptySuggestionsAsNormalState() {
        // 联想无结果是正常态：透传空列表，不转 A0430（默认桩即空）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"nohit"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-suggest")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.suggestions", hasSize(0));
    }

    @Test
    void shouldFailSuggestWhenModelFails() {
        MockKnowledgeGraphClient.setThrowOnSuggest();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"LED"}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-suggest")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"));
    }

    @Test
    void shouldRejectBlankSuggestQ() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":" "}
                        """.formatted(KB_ID))
                .when().post("/kg/graph-suggest")
                .then()
                .statusCode(400);
    }

    // ======================== POST /kg/doc-code-files ========================

    @Test
    void shouldReturnDocCodeFiles() {
        MockKnowledgeGraphClient.setDocCodeFilesResult(new DocCodeFileListResult(
                "projects-" + KB_ID, "code", 2, List.of(
                new DocCodeFileListResult.DocCodeFileItem(
                        "osal_debug.c", "osal_debug.c", 3, "/kg/doc-view?project=" + KB_ID + "&file=osal_debug.c"),
                new DocCodeFileListResult.DocCodeFileItem(
                        "hi_aiot_solution/LED.md", "LED.md", 1,
                        "/kg/doc-view?project=" + KB_ID + "&file=hi_aiot_solution%2FLED.md")), 12.5));

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","type":"code"}
                        """.formatted(KB_ID))
                .when().post("/kg/doc-code-files")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.project", equalTo("projects-" + KB_ID))
                .body("data.type", equalTo("code"))
                .body("data.count", equalTo(2))
                .body("data.files", hasSize(2))
                .body("data.files[0].file", equalTo("osal_debug.c"))
                .body("data.files[0].refs", equalTo(3));

        // 验证端口收到 project=kbId + type 透传
        assertEquals(KB_ID, MockKnowledgeGraphClient.getLastDocCodeFilesProject());
        assertEquals("code", MockKnowledgeGraphClient.getLastDocCodeFilesType());
    }

    @Test
    void shouldReturnEmptyDocCodeFilesAsNormalState() {
        // count=0（该库尚无映射）为数据真值：透传，不转 A0430（默认桩即空清单）
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s"}
                        """.formatted(KB_ID))
                .when().post("/kg/doc-code-files")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.count", equalTo(0))
                .body("data.files", hasSize(0));
    }

    @Test
    void shouldFailDocCodeFilesWhenModelFails() {
        MockKnowledgeGraphClient.setThrowOnDocCodeFiles();

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s"}
                        """.formatted(KB_ID))
                .when().post("/kg/doc-code-files")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"));
    }

    @Test
    void shouldRejectInvalidDocCodeFilesType() {
        // type 非法：平台前置拦截 A0420，不透传给模型
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","type":"docx"}
                        """.formatted(KB_ID))
                .when().post("/kg/doc-code-files")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));
    }

    @Test
    void shouldRejectBlankDocCodeFilesKbId() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":" ","type":"code"}
                        """)
                .when().post("/kg/doc-code-files")
                .then()
                .statusCode(400);
    }

    // ======================== GET /kg/doc-view ========================

    @Test
    void shouldRedirectDocViewCodeEntry() {
        // redirects().follow(false)：只断言 302 本身，不真实跳转模型服务
        // KB 未注册（mock 返回 null）→ 裁剪降级原样透传，避免测试触达真实仓储
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "sdk/osal/src/linux/kernel/osal_debug.c")
                .queryParam("line", 32)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .header("Location", containsString("/doc_view?project=" + KB_ID));
    }

    @Test
    void shouldRedirectDocViewKgEntry() {
        String location = given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("doc", "BSP FAQ.md")
                .queryParam("kb", KB_ID)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .extract().header("Location");
        assertTrue(location.startsWith("http://139.9.104.64:9789/doc_view?"),
                "Location 应指向模型侧原文查看页: " + location);
        assertTrue(location.contains("doc=") && location.contains("kb=" + KB_ID),
                "Location 应携带 doc/kb 参数: " + location);
    }

    @Test
    void shouldTrimStoredPathPrefixInDocViewCodeEntry() {
        // 存储相对路径 kb/CV610ALL/... → 裁剪为项目内相对路径再拼给模型
        installKbMock();
        String location = given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file",
                        "kb/CV610ALL/hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c")
                .queryParam("line", 32)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .extract().header("Location");
        assertTrue(location.contains("file=hi_aiot_solution%2Fsdk"),
                "Location 的 file 应已裁剪存储前缀: " + location);
        assertTrue(!location.contains("kb%2FCV610ALL"),
                "Location 不应再含 kb/CV610ALL 前缀: " + location);
    }

    @Test
    void shouldPassThroughRelativePathInDocViewCodeEntry() {
        // 已是项目内相对路径（图谱返回的 file_path）：原样透传
        installKbMock();
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c")
                .queryParam("line", 32)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .header("Location", containsString("file=hi_aiot_solution"));
    }

    @Test
    void shouldPassThroughWhenKbNotRegistered() {
        // kbId 未注册（KB mock 返回 null）：降级原样透传，不报错
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "kb/CV610ALL/sdk/osal_debug.c")
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .header("Location", containsString("file=kb%2FCV610ALL%2Fsdk"));
    }

    @Test
    void shouldRouteDocExtensionToDocEntry() {
        // 文档类扩展名（.md）→ 模型 KG 文档入口：doc=文件名末段 + kb=project，不带 file/line
        String location = given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "docs/07FAQ/Hi3516CV610 FAQ.md")
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .extract().header("Location");
        assertTrue(location.contains("doc=Hi3516CV610+FAQ.md"),
                "Location 的 doc 应为文件名末段: " + location);
        assertTrue(location.contains("kb=" + KB_ID), "Location 应携带 kb: " + location);
        assertTrue(!location.contains("file=") && !location.contains("line="),
                "文档入口不应携带 file/line: " + location);
    }

    @Test
    void shouldRouteDocExtensionWithStoredPathToBaseName() {
        // 文档类扩展名 + 存储路径 → doc 取末段文件名（模型按文件名定位，全路径会失败）
        String location = given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "kb/CV610ALL/docs/03参考案例/BSP FAQ.md")
                .queryParam("line", 8)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .extract().header("Location");
        assertTrue(location.contains("doc=BSP+FAQ.md"),
                "Location 的 doc 应为末段文件名: " + location);
        assertTrue(location.contains("kb=" + KB_ID), "Location 应携带 kb: " + location);
        assertTrue(!location.contains("line="), "文档入口不应携带 line: " + location);
    }

    @Test
    void shouldFallBackToCodeEntryForUnknownExtension() {
        // 无扩展名/未知扩展（.ini）→ 兜底代码入口（现状行为）
        installKbMock();
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "hi_aiot_solution/sdk/config.ini")
                .queryParam("line", 3)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(303)
                .header("Location", containsString("file=hi_aiot_solution"));
    }

    @Test
    void shouldReturnUrlJsonInUrlMode() {
        // mode=url：前端 XHR 拿 JSON 里的 url 再 window.open（导航无 CORS），不返回 303
        installKbMock();
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "kb/CV610ALL/hi_aiot_solution/sdk/osal/osal_debug.c")
                .queryParam("line", 32)
                .queryParam("mode", "url")
                .when().get("/kg/doc-view")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.url", containsString("/doc_view?project=" + KB_ID))
                .body("data.url", containsString("file=hi_aiot_solution"))
                .body("data.url", containsString("line=32"));
    }

    @Test
    void shouldReturnDocEntryUrlJsonInUrlMode() {
        // mode=url + 文档扩展：data.url 为 doc=末段文件名 + kb 的模型地址
        given()
                .redirects().follow(false)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("project", KB_ID)
                .queryParam("file", "docs/07FAQ/Hi3516CV610 FAQ.md")
                .queryParam("mode", "url")
                .when().get("/kg/doc-view")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.url", containsString("doc=Hi3516CV610+FAQ.md"))
                .body("data.url", containsString("kb=" + KB_ID));
    }

    @Test
    void shouldRejectIllegalDocViewCombo() {
        // 两种入参混给 → A0420
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .header("Accept-Language", "zh-CN")
                .queryParam("project", KB_ID)
                .queryParam("file", "a.c")
                .queryParam("doc", "BSP FAQ.md")
                .queryParam("kb", KB_ID)
                .when().get("/kg/doc-view")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"));
    }

    // ======================== 鉴权 ========================

    @Test
    void shouldFailWhenNoAuthHeader() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("""
                        {"kbId":"%s","q":"%s"}
                        """.formatted(KB_ID, Q))
                .when().post("/kg/graph-search")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("未授权用户或访问令牌已过期"));
    }

    // ======================== 辅助方法 ========================

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
