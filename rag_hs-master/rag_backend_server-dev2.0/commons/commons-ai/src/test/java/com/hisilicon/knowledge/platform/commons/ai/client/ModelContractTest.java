package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 dual-mcp 契约测试（<b>默认跳过</b>：只有设置环境变量 {@code MODEL_CONTRACT_BASE_URL} 才执行）。
 *
 * <p>与普通单测的分工：单测用线上样例回归解析逻辑；本类直接调用真实模型，验证<b>文档声明的契约</b>
 * 是否仍然成立——模型字段改名/挪位置时先在这里失败，而不是等前端空白或用户报障。</p>
 *
 * <p>覆盖《API-GRAPH2.md》《API-DOCCODE2.md》《API-GRAPH-FRAGMENT.md》声明的全部端点：{@code GET /projects}、
 * {@code POST /graph_search}、{@code POST /graph_node}、{@code POST /graph_kg}、
 * {@code POST /graph_suggest}、{@code POST /graph_fragment}、{@code POST /doc_code}、{@code GET /doc_view}。每个端点除断言关键字段
 * 类型外，还用生产侧<b>同一套</b>契约声明（{@code KnowledgeGraphClientImpl} /
 * {@code DocCodeMappingClientImpl} 的字段集合）与 {@link ModelResponseDrift#findings} 断言"真实响应无漂移"，
 * 因此模型新增/改名字段会在这里红，而不是只留一条运行期 WARN。</p>
 *
 * <p>运行方式（联调前手动跑）：</p>
 * <pre>
 *   $env:MODEL_CONTRACT_BASE_URL='http://139.9.104.64:9789'
 *   .\gradlew.bat :commons:commons-ai:test --tests "*ModelContractTest" --console=plain
 * </pre>
 *
 * <p>用例不绑定具体项目/符号：先 GET /projects 取一个项目，再 POST /graph_search 取一个真实节点、
 * 用 /graph_suggest 找一个真实 KG 实体，因此换环境（DEV/生产）也能跑；环境里确实没有对应数据时用
 * {@code assumeTrue} 跳过并说明原因，不产生假绿。</p>
 */
@Tag("model-contract")
@EnabledIfEnvironmentVariable(named = "MODEL_CONTRACT_BASE_URL", matches = ".+")
class ModelContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl = System.getenv("MODEL_CONTRACT_BASE_URL");

    // ======================== /projects：项目清单 ========================

    @Test
    void projectsShouldKeepDocumentedShape() throws Exception {
        JsonNode response = get("/projects");
        assertNoDrift("GET /projects", response,
                KnowledgeGraphClientImpl.PROJECTS_FIELDS, KnowledgeGraphClientImpl.PROJECTS_FIELDS);

        JsonNode list = response.path("projects");
        assertTrue(list.isArray() && !list.isEmpty(), "/projects 应返回非空数组");
        for (JsonNode item : list) {
            assertNoDrift("GET /projects.projects[]", item,
                    KnowledgeGraphClientImpl.PROJECT_ITEM_FIELDS, Set.of());
        }
        GraphProjectsResult parsed = mapper.treeToValue(response, GraphProjectsResult.class);
        assertEquals(list.size(), parsed.projects().size(), "项目清单应完整反序列化");
    }

    // ======================== /graph_search：网状子图 ========================

    @Test
    void graphSearchShouldKeepDocumentedShape() throws Exception {
        JsonNode response = post("/graph_search", mapOf(
                "q", "main", "project", project(), "limit", 8, "candidates", 3));
        assertNoDrift("POST /graph_search", response,
                KnowledgeGraphClientImpl.SEARCH_FIELDS, KnowledgeGraphClientImpl.SEARCH_REQUIRED);
        assertNoItemDrift("POST /graph_search.candidates[]", response, "candidates",
                KnowledgeGraphClientImpl.SEARCH_NODE_FIELDS);
        assertNoItemDrift("POST /graph_search.nodes[]", response, "nodes",
                KnowledgeGraphClientImpl.SEARCH_NODE_FIELDS);
        assertNoItemDrift("POST /graph_search.edges[]", response, "edges",
                KnowledgeGraphClientImpl.SEARCH_EDGE_FIELDS);

        GraphSearchResult parsed = mapper.treeToValue(response, GraphSearchResult.class);
        assertNotNull(parsed.nodes(), "nodes 应可反序列化");
        assertNotNull(parsed.edges(), "edges 应可反序列化");
        assumeTrue(parsed.center() != null, "以 main 查询未命中节点，跳过命中后的结构断言");
        assertFalse(parsed.nodes().isEmpty(), "命中后 nodes 不应为空（center 也在 nodes 内）");
    }

    // ======================== /graph_node：node 内嵌增强字段 + 适配层提升 ========================

    @Test
    void graphNodeShouldKeepDocumentedShapeAndLiftNodeFields() throws Exception {
        JsonNode center = codeCenter(project());
        assumeTrue(center != null, "环境中没有可用的代码节点，跳过 /graph_node 契约测试");
        String name = center.path("name").asText();
        Integer id = center.path("id").isNumber() ? center.path("id").asInt() : null;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        if (id != null) {
            request.put("id", id);
        }
        request.put("project", project());
        request.put("with_source", false);
        JsonNode response = post("/graph_node", request);

        assertNoDrift("POST /graph_node", response,
                KnowledgeGraphClientImpl.NODE_FIELDS, KnowledgeGraphClientImpl.NODE_REQUIRED);
        JsonNode node = response.path("node");
        assertTrue(node.isObject(), "node 应为对象");
        assertNoDrift("POST /graph_node.node", node,
                KnowledgeGraphClientImpl.NODE_INNER_FIELDS, KnowledgeGraphClientImpl.NODE_INNER_REQUIRED);
        assertEquals(id, node.path("id").isNumber() ? node.path("id").asInt() : null, "按 id 查询应回同一节点");
        assertTrue(node.path("in_types").isObject(), "文档 §4：in_types 在 node 内");
        assertTrue(node.path("out_types").isObject(), "文档 §4：out_types 在 node 内");

        // 适配层：node 内字段必须提升到对外契约顶层（2026-10 线上故障点）
        GraphNodeResult adapted = KnowledgeGraphClientImpl.toGraphNode(response, mapper);
        assertNotNull(adapted.node(), "适配层应产出 node");
        assertEquals(node.path("id").asInt(), adapted.node().id());
        assertEquals(node.path("in_degree").asInt(), adapted.node().inDegree());
        assertNotNull(adapted.inTypes(), "适配层必须把 node.in_types 提升到顶层");
        assertNotNull(adapted.outTypes(), "适配层必须把 node.out_types 提升到顶层");
    }

    // ======================== /graph_kg：文档知识图谱 ========================

    @Test
    void graphKgShouldKeepDocumentedShape() throws Exception {
        String palace = kbPalace();
        String entity = palace != null ? kgEntity(palace) : kgEntity(project());
        JsonNode response = post("/graph_kg", palace != null
                ? mapOf("q", entity, "palace", palace)
                : mapOf("q", entity));
        assertNoDrift("POST /graph_kg", response,
                KnowledgeGraphClientImpl.KG_FIELDS, KnowledgeGraphClientImpl.KG_REQUIRED);
        assertNoItemDrift("POST /graph_kg.nodes[]", response, "nodes",
                KnowledgeGraphClientImpl.KG_NODE_FIELDS);
        assertNoItemDrift("POST /graph_kg.edges[]", response, "edges",
                KnowledgeGraphClientImpl.KG_EDGE_FIELDS);

        GraphKgResult parsed = mapper.treeToValue(response, GraphKgResult.class);
        assertTrue(parsed.count() >= 1, "KG 实体应命中（count>0）");
        assertEquals(response.path("nodes").size(), parsed.nodes().size(), "KG 节点应完整反序列化");
        if (palace != null) {
            assertEquals(palace, response.path("palace").asText(),
                    "带 palace 请求应回显 palace（按库模式）");
        }
    }

    @Test
    void graphKgShouldFilterByPalaceWhenKbProjectExists() throws Exception {
        String palace = kbPalace();
        assumeTrue(palace != null, "环境无 projects-<kbId> 项目，跳过按库过滤契约测试");
        String entity = kgEntity(palace);
        JsonNode byKb = post("/graph_kg", mapOf("q", entity, "palace", palace));
        assertEquals(palace, byKb.path("palace").asText(), "按库模式应回显 palace");
        assertTrue(byKb.path("count").asInt() >= 1, "实体由同库 suggest 候选取得，按库查询应有结果");
        // 全库模式响应 palace 应为 null/缺失（向后兼容，不漂移）
        JsonNode all = post("/graph_kg", mapOf("q", entity));
        assertTrue(all.path("count").asInt() >= 1, "全库查询同实体应有结果");
        assertNoDrift("POST /graph_kg(no palace)", all,
                KnowledgeGraphClientImpl.KG_FIELDS, KnowledgeGraphClientImpl.KG_REQUIRED);
    }

    // ======================== /graph_fragment：关系来源片段（API-GRAPH-FRAGMENT.md） ========================

    /**
     * 入参联动：subject/predicate/object 来自 /graph_kg 响应 edges[]（source/type/target），
     * 实测文档所述「部分入参要调用另外一个接口获取」的完整链路。
     */
    @Test
    void graphFragmentShouldKeepDocumentedShape() throws Exception {
        String palace = kbPalace();
        String entity = palace != null ? kgEntity(palace) : kgEntity(project());
        JsonNode kg = post("/graph_kg", palace != null
                ? mapOf("q", entity, "palace", palace)
                : mapOf("q", entity));
        JsonNode edges = kg.path("edges");
        assumeTrue(edges.isArray() && !edges.isEmpty(), "环境无 KG 关系边，跳过 /graph_fragment 契约测试");
        JsonNode edge = edges.get(0);

        JsonNode response = post("/graph_fragment", mapOf(
                "subject", edge.path("source").asText(),
                "predicate", edge.path("type").asText(),
                "object", edge.path("target").asText(),
                "palace", palace));
        assertNoDrift("POST /graph_fragment", response,
                KnowledgeGraphClientImpl.FRAGMENT_FIELDS, KnowledgeGraphClientImpl.FRAGMENT_REQUIRED);
        assertNoItemDrift("POST /graph_fragment.fragments[]", response, "fragments",
                KnowledgeGraphClientImpl.FRAGMENT_ITEM_FIELDS);

        GraphFragmentResult parsed = mapper.treeToValue(response, GraphFragmentResult.class);
        assumeTrue(parsed.count() > 0, "关系边存在但片段未入 mempalace（count=0 是数据真值），跳过内容断言");
        assertEquals(edges.size() > 0 ? edge.path("source").asText() : null, parsed.subject(), "subject 应回显");
        assertTrue(parsed.fragments() != null && !parsed.fragments().isEmpty(), "count>0 时 fragments 不应为空");
        for (GraphFragmentResult.Fragment fragment : parsed.fragments()) {
            assertTrue(fragment.text() != null && !fragment.text().isEmpty(), "fragments[].text 应为非空字符串");
            assertTrue(fragment.room() != null, "fragments[].room 应存在（doc-view 入参）");
        }
    }

    @Test
    void graphFragmentCountZeroShouldBeDataTruthNotError() throws Exception {
        // 文档 §2.6：count=0 是数据真值（来源文档未入 mempalace），HTTP 200 正常响应，不得报错
        String palace = kbPalace();
        JsonNode response = post("/graph_fragment", mapOf(
                "subject", "不存在的实体_" + System.currentTimeMillis(), "palace", palace));
        assertEquals(0, response.path("count").asInt(), "不存在的主体应 count=0");
        assertTrue(response.path("fragments").isArray(), "count=0 也应返回空数组（数据真值，非错误）");
        assertTrue(response.path("error").isNull() || response.path("error").isMissingNode(),
                "count=0 时 error 应为 null/缺失");
    }

    // ======================== /graph_suggest：搜索联想（双源） ========================

    @Test
    void graphSuggestShouldKeepDocumentedShape() throws Exception {
        JsonNode response = post("/graph_suggest", mapOf(
                "q", "s", "project", project(), "limit", 5, "palace", kbPalace()));
        assertNoDrift("POST /graph_suggest", response,
                KnowledgeGraphClientImpl.SUGGEST_FIELDS, KnowledgeGraphClientImpl.SUGGEST_REQUIRED);
        assertNoItemDrift("POST /graph_suggest.suggestions[]", response, "suggestions",
                KnowledgeGraphClientImpl.SUGGESTION_FIELDS);

        GraphSuggestResult parsed = mapper.treeToValue(response, GraphSuggestResult.class);
        assertFalse(parsed.suggestions().isEmpty(), "联想应有候选");
        assertTrue(parsed.suggestions().stream()
                        .allMatch(item -> "code".equals(item.source()) || "kg".equals(item.source())),
                "文档 §4.5：suggestions[].source 只能是 code 或 kg");
    }

    // ======================== /doc_code：matched 为字符串判向结果 ========================

    @Test
    void docCodeShouldReturnStringMatchedAndNumericCount() throws Exception {
        JsonNode response = post("/doc_code", mapOf(
                "file", "README.md", "project", project(), "direction", "doc2code",
                "limit", 3, "with_source", 1, "with_doc", 1));
        assertNoDrift("POST /doc_code", response,
                DocCodeMappingClientImpl.DOC_CODE_FIELDS, DocCodeMappingClientImpl.DOC_CODE_REQUIRED);

        assertTrue(response.path("matched").isNull() || response.path("matched").isTextual(),
                "matched 是 document|code_file|null 字符串（文档 §4），不得为数值");
        assertTrue(response.path("count").isNumber(), "count 应为数值");
        assertTrue(response.path("results").isArray(), "results 应为数组");
        assertTrue(response.path("direction").isNull() || response.path("direction").isTextual(),
                "direction 为 doc2code/code2doc/null");
    }

    // ======================== /doc_code/files：映射文件清单（API-DOCCODE-FILES.md） ========================

    @Test
    void docCodeFilesShouldKeepDocumentedShape() throws Exception {
        String project = kbProject();
        JsonNode response = post("/doc_code/files", mapOf("project", project, "type", "code"));
        assertNoDrift("POST /doc_code/files", response,
                KnowledgeGraphClientImpl.DOC_CODE_FILES_FIELDS, KnowledgeGraphClientImpl.DOC_CODE_FILES_REQUIRED);
        assertNoItemDrift("POST /doc_code/files.files[]", response, "files",
                KnowledgeGraphClientImpl.DOC_CODE_FILE_ITEM_FIELDS);

        assertTrue(response.path("count").isNumber(), "count 应为数值");
        assertTrue(response.path("files").isArray(), "files 应为数组");
        for (JsonNode item : response.path("files")) {
            assertTrue(item.path("file").isTextual() && !item.path("file").asText().isEmpty(),
                    "files[].file 应为非空字符串");
            assertTrue(item.path("refs").isNumber(),
                    "files[].refs 应为数值（适配层宽容解析但契约要求模型守约）");
            assertTrue(item.path("url").isTextual(), "files[].url 应为字符串（适配层会重写为平台 doc-view 串）");
        }
    }

    @Test
    void docCodeFilesShouldRejectUnknownType() throws Exception {
        // 文档 §5：type 非法 → 400（平台侧已前置拦截，此处验证模型侧兜底同样拒绝）
        HttpResponse<String> resp = sendRawPost("/doc_code/files", mapOf("project", project(), "type", "docx"));
        assertEquals(400, resp.statusCode(), "type 非法应 400");
    }

    // ======================== /doc_view：原文查看页 ========================

    @Test
    void docViewShouldRenderHtmlAndRejectTraversal() throws Exception {
        // doc_view 只在服务端存储根下解析文件：取 projects-<kbId> 项目（/opt/... 类项目会 404）
        String project = kbProject();
        JsonNode center = codeCenter(project);
        assumeTrue(center != null, "环境中没有可用的代码节点，跳过 /doc_view 契约测试");
        String file = center.path("file_path").asText();
        int line = center.path("start_line").asInt(1);

        HttpResponse<String> page = sendRaw("/doc_view?project=" + q(project)
                + "&file=" + q(file) + "&line=" + line);
        assertEquals(200, page.statusCode(), "/doc_view 正常路径应 200");
        assertTrue(header(page, "content-type").startsWith("text/html"),
                "文档 §6：文本/代码/Markdown 渲染为行号页（HTML）");
        String fileName = file.substring(file.lastIndexOf('/') + 1);
        assertTrue(page.body().contains(fileName), "渲染页应包含文件名 " + fileName);

        HttpResponse<String> traversal = sendRaw("/doc_view?project=" + q(project)
                + "&file=" + q("../../etc/passwd"));
        assertEquals(404, traversal.statusCode(), "文档 §6：../ 越界必须 404");
    }

    // ======================== 契约断言（与生产共用声明与判定） ========================

    /** 断言响应对象的字段集合与文档声明一致（未声明字段 = 模型漂移）。 */
    private static void assertNoDrift(String endpoint, JsonNode body, Set<String> documented, Set<String> required) {
        List<String> findings = ModelResponseDrift.findings(body, documented, required);
        assertTrue(findings.isEmpty(), endpoint + " 与文档声明不一致（模型侧改动？）: " + findings);
    }

    /** 断言数组字段每一项的字段集合与文档声明一致（对应生产侧 {@code checkItems}）。 */
    private static void assertNoItemDrift(String endpoint, JsonNode root, String arrayField,
                                          Set<String> documented) {
        JsonNode array = root.path(arrayField);
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            assertNoDrift(endpoint, item, documented, Set.of());
        }
    }

    // ======================== 测试数据定位（不绑定具体项目/符号） ========================

    /** 取环境中第一个已索引项目名；无项目直接失败（契约测试无数据即无意义）。 */
    private String project() throws Exception {
        JsonNode list = get("/projects").path("projects");
        assertTrue(list.isArray() && !list.isEmpty(), "/projects 至少应有一个项目");
        String name = list.get(0).path("name").asText();
        assertFalse(name.isBlank(), "项目项应含 name");
        return name;
    }

    /** 用真实查询取一个带 file_path 的代码节点（/graph_search 中心点）；无命中返回 null。 */
    private JsonNode codeCenter(String project) throws Exception {
        for (String q : List.of("main", "osal_dump_stack", "build", "init")) {
            JsonNode response = post("/graph_search", mapOf(
                    "q", q, "project", project, "limit", 8, "candidates", 1));
            JsonNode center = response.get("center");
            if (center != null && center.isObject() && center.path("file_path").isTextual()) {
                return center;
            }
        }
        return null;
    }

    /** 取节点最多的 {@code projects-<kbId>} 项目：只有它能被 /doc_view 定位到存储根。 */
    private String kbProject() throws Exception {
        String largest = null;
        int maxNodes = 0;
        for (JsonNode item : get("/projects").path("projects")) {
            String name = item.path("name").asText();
            int nodes = item.path("nodes").asInt();
            if (name.startsWith("projects-") && nodes > maxNodes) {
                largest = name;
                maxNodes = nodes;
            }
        }
        assumeTrue(largest != null, "环境无 projects-<kbId> 项目，/doc_view 无可用存储根，跳过");
        return largest;
    }

    /** 用 /graph_suggest 的 KG 候选找一个真实 KG 实体名；找不到则跳过（环境无 KG 数据）。 */
    private String kgEntity(String project) throws Exception {
        for (String seed : List.of("s", "e", "osal", "cv610")) {
            JsonNode suggestions = post("/graph_suggest", mapOf(
                    "q", seed, "project", project, "limit", 5)).path("suggestions");
            for (JsonNode item : suggestions) {
                String name = item.path("name").asText();
                if ("kg".equals(item.path("source").asText()) && !name.isBlank()) {
                    return name;
                }
            }
        }
        assumeTrue(false, "/graph_suggest 未返回 KG 候选，环境无 KG 数据，跳过 /graph_kg 契约测试");
        return null;
    }

    /** 取第一个 {@code projects-<kbId>} 项目并剥离前缀作为 palace（kbId）；无则返回 null（全库模式）。 */
    private String kbPalace() throws Exception {
        for (JsonNode item : get("/projects").path("projects")) {
            String name = item.path("name").asText();
            if (name.startsWith("projects-") && name.length() > "projects-".length()) {
                return name.substring("projects-".length());
            }
        }
        return null;
    }

    // ======================== HTTP helpers ========================

    private JsonNode get(String path) throws Exception {
        return mapper.readTree(sendRaw(path).body());
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return sendJson(request, path);
    }

    private JsonNode sendJson(HttpRequest request, String path) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " 应返回 200，实际 " + response.body());
        assertFalse(response.body().isBlank(), path + " 响应体不应为空");
        return mapper.readTree(response.body());
    }

    /** 发 GET 并原样返回响应（状态码由调用方断言，用于 404 等非 200 场景）。 */
    private HttpResponse<String> sendRaw(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 发 POST 并原样返回响应（状态码由调用方断言，用于 400 等非 200 场景）。 */
    private HttpResponse<String> sendRawPost(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    /** 查询参数编码：保留路径分隔符，其余按 UTF-8 转义。 */
    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
