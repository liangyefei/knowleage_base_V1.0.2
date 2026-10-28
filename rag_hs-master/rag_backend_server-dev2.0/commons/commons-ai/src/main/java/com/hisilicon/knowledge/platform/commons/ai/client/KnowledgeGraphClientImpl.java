package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.ai.api.KgServiceApi;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocCodeFilesRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphFragmentRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphKgRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphNodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSearchRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSuggestRequest;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeFileListResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeGraphClient;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱模型调用实现（{@link KnowledgeGraphClient} 端口，llm 侧）。
 *
 * <p>对齐《API-GRAPH2.md》端点集：GET /projects、POST /graph_search、POST /graph_node、POST /graph_kg、
 * POST /graph_suggest（文档 KG 由 dual-mcp 代理 mempalace，平台不直连 mempalace）。各端点响应均为直接
 * 对象（无 code/msg/data 信封），按 HTTP 状态码判成败；非 200 或调用异常 → 抛
 * {@link ModelServiceException}（C0001 + modelServiceError 国际化），经 {@code BusinessExceptionMapper}
 * 统一返回 {@code code=0} 外壳内 {@code code=C0001}。</p>
 *
 * <p><b>边界不做强类型绑定</b>：{@link KgServiceApi} 各图谱端点统一返回原始响应体 {@code String}，
 * 本类先解析成 {@link JsonNode} 再容错组装对外契约 DTO。好处：模型新增字段不会被静默丢弃（由漂移告警
 * 暴露）、字段挪位置可由适配层同时兼容（如 2026-10 把 {@code source/in_types/out_types} 挪进
 * {@code node}）；{@code graph_node} 的标量读不到时按 null 降级，其余端点的契约字段类型不匹配时以
 * C0001 暴露并打印原始 body（不会静默错值）。适配集中在本类；同时用 {@link ModelResponseDrift} 对
 * 文档声明字段与实际响应做漂移探测（未声明字段/必需字段缺失 → WARN 一次），变化不再"悄悄发生"。</p>
 *
 * <p>对外契约保持稳定：{@code /graph_node} 返回 {@code node} 基础字段 + 顶层
 * {@code source/in_types/out_types/props/file_version}，由 {@link #toGraphNode} 从模型 {@code node} 内
 * 提升（模型把增强字段放在 node 内；顶层有值时优先顶层，兼容旧版模型）。</p>
 *
 * <p>运行期由 service 聚合注入 knowledge 的 KnowledgeGraphService；knowledge 测试侧无本类（不在 classpath），
 * 由 MockKnowledgeGraphClient 满足注入。</p>
 *
 * <p>排查日志：调用前记 url+endpoint+请求参数；成功记响应全文（{@link LogTruncate} 截断）；失败记原始错误
 * （transform 前）；null/非法 JSON 响应记 error 带 body。url 取自
 * {@code quarkus.rest-client.kg-service.url}（已表达式解析）。</p>
 */
@ApplicationScoped
public class KnowledgeGraphClientImpl implements KnowledgeGraphClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphClientImpl.class);

    // ======================== 文档声明字段集合（API-GRAPH2.md / API-DOCCODE2.md） ========================
    // 包私有而非 private：ModelContractTest 用同一套声明断言真实服务响应，避免契约出现第二份副本。

    static final Set<String> PROJECTS_FIELDS = Set.of("projects");
    static final Set<String> PROJECT_ITEM_FIELDS =
            Set.of("name", "nodes", "edges", "root_path", "kb_name");

    static final Set<String> SEARCH_FIELDS = Set.of(
            "query", "project", "center", "candidates", "nodes", "edges", "hint", "elapsed_ms");
    static final Set<String> SEARCH_REQUIRED = Set.of("center", "candidates", "nodes", "edges");
    static final Set<String> SEARCH_NODE_FIELDS = Set.of(
            "id", "label", "name", "qualified_name", "file_path", "start_line", "end_line",
            "matched", "deg_local", "in_degree", "out_degree");
    static final Set<String> SEARCH_EDGE_FIELDS = Set.of("source", "target", "type", "ref_name", "line");

    static final Set<String> NODE_FIELDS = Set.of(
            "node", "source", "file_version", "in_types", "out_types", "props", "elapsed_ms");
    static final Set<String> NODE_REQUIRED = Set.of("node");
    /** 模型 node 详情字段（增强字段在 node 内：source/in_types/out_types/props/file_version） */
    static final Set<String> NODE_INNER_FIELDS = Set.of(
            "id", "label", "name", "qualified_name", "file_path", "start_line", "end_line",
            "matched", "deg_local", "in_degree", "out_degree",
            "in_types", "out_types", "props", "source", "file_version");
    static final Set<String> NODE_INNER_REQUIRED = Set.of("id", "name");

    static final Set<String> KG_FIELDS = Set.of(
            "query", "palace", "source", "center", "count", "nodes", "edges", "hint", "elapsed_ms", "error");
    static final Set<String> KG_REQUIRED = Set.of("count", "nodes", "edges");
    static final Set<String> KG_NODE_FIELDS = Set.of("id", "name", "full_len", "type", "matched");
    static final Set<String> KG_EDGE_FIELDS = Set.of(
            "source", "target", "type", "label", "confidence", "valid_from", "valid_to", "current");

    static final Set<String> SUGGEST_FIELDS = Set.of("query", "project", "suggestions", "elapsed_ms");
    static final Set<String> SUGGEST_REQUIRED = Set.of("suggestions");
    static final Set<String> SUGGESTION_FIELDS = Set.of("name", "label", "source", "file", "deg");

    static final Set<String> FRAGMENT_FIELDS = Set.of(
            "subject", "predicate", "object", "count", "fragments", "elapsed_ms", "error");
    static final Set<String> FRAGMENT_REQUIRED = Set.of("count", "fragments");
    static final Set<String> FRAGMENT_ITEM_FIELDS = Set.of("text", "source_file", "room", "chunk_index");

    static final Set<String> DOC_CODE_FILES_FIELDS = Set.of("project", "type", "count", "files", "elapsed_ms");
    static final Set<String> DOC_CODE_FILES_REQUIRED = Set.of("project", "type", "count", "files");
    static final Set<String> DOC_CODE_FILE_ITEM_FIELDS = Set.of("file", "name", "refs", "url");

    /** 属性袋反序列化目标类型（免 unchecked 强转） */
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private final KgServiceApi api;
    private final String kgServiceUrl;
    private final ObjectMapper mapper;

    public KnowledgeGraphClientImpl(@RestClient KgServiceApi api,
                                    @ConfigProperty(name = "quarkus.rest-client.kg-service.url", defaultValue = "")
                                    String kgServiceUrl,
                                    ObjectMapper mapper) {
        this.api = api;
        this.kgServiceUrl = kgServiceUrl;
        this.mapper = mapper;
    }

    @Override
    public Uni<GraphProjectsResult> projects(String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp project list, url={}, endpoint=GET /projects", kgServiceUrl);
        return api.projects()
                .onFailure().invoke(t -> log.error("dual-mcp /projects call failed, url={}, error: {}",
                        kgServiceUrl, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "GET /projects", appMessages);
                    ModelResponseDrift.check("GET /projects", root, PROJECTS_FIELDS, PROJECTS_FIELDS);
                    checkItems("GET /projects.projects[]", root, "projects", PROJECT_ITEM_FIELDS);
                    GraphProjectsResult result = convert(root, GraphProjectsResult.class, "GET /projects", appMessages);
                    log.info("dual-mcp /projects response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    @Override
    public Uni<GraphSearchResult> search(String project, String q, Integer limit, Integer candidates,
                                         String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp code graph adjacency search, url={}, endpoint=POST /graph_search, request params: q={}, project={}, limit={}, candidates={}",
                kgServiceUrl, q, project, limit, candidates);
        return api.graphSearch(new GraphSearchRequest(q, project, limit, candidates))
                .onFailure().invoke(t -> log.error("dual-mcp /graph_search call failed, url={}, request: q={}, project={}, error: {}",
                        kgServiceUrl, q, project, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /graph_search", appMessages);
                    ModelResponseDrift.check("POST /graph_search", root, SEARCH_FIELDS, SEARCH_REQUIRED);
                    checkItems("POST /graph_search.candidates[]", root, "candidates", SEARCH_NODE_FIELDS);
                    checkItems("POST /graph_search.nodes[]", root, "nodes", SEARCH_NODE_FIELDS);
                    checkItems("POST /graph_search.edges[]", root, "edges", SEARCH_EDGE_FIELDS);
                    GraphSearchResult result = convert(root, GraphSearchResult.class, "POST /graph_search", appMessages);
                    log.info("dual-mcp /graph_search response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    @Override
    public Uni<GraphNodeResult> node(String project, String name, Integer id, String filePath, Boolean withSource,
                                     String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp code graph node detail, url={}, endpoint=POST /graph_node, request params: name={}, id={}, project={}, file_path={}, with_source={}",
                kgServiceUrl, name, id, project, filePath, withSource);
        return api.graphNode(new GraphNodeRequest(name, id, project, filePath, withSource))
                .onFailure().invoke(t -> log.error("dual-mcp /graph_node call failed, url={}, request: name={}, id={}, project={}, error: {}",
                        kgServiceUrl, name, id, project, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /graph_node", appMessages);
                    if (!root.isObject()) {
                        log.error("dual-mcp POST /graph_node response is not a JSON object, url={}, body={}",
                                kgServiceUrl, LogTruncate.truncate(body));
                        throw new ModelServiceException(appMessages.modelServiceError());
                    }
                    ModelResponseDrift.check("POST /graph_node", root, NODE_FIELDS, NODE_REQUIRED);
                    GraphNodeResult result = toGraphNode(root, mapper);
                    log.info("dual-mcp /graph_node response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    /**
     * 模型 {@code /graph_node} 响应 → 对外契约 {@link GraphNodeResult}。
     *
     * <p>模型把 {@code source/in_types/out_types/props/file_version} 放在 {@code node} 内
     * （API-GRAPH2.md §4、API-DOCCODE2.md §10）；对外契约把它们提升到顶层。node 内、顶层都有时
     * 优先 node（新结构），仅顶层有值时回退（兼容按旧版文档实现的模型）。{@code node=null} 原样保留，
     * 由编排层转 A0430。</p>
     */
    static GraphNodeResult toGraphNode(JsonNode root, ObjectMapper mapper) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode n = root.get("node");
        if (n == null || n.isNull() || !n.isObject()) {
            return new GraphNodeResult(null, text(root, "source"), text(root, "file_version"),
                    intMap(root, "in_types"), intMap(root, "out_types"), objectMap(root, "props", mapper),
                    null, doubleValue(root, "elapsed_ms"));
        }
        GraphSearchResult.GraphNode node = new GraphSearchResult.GraphNode(
                intValue(n, "id"), text(n, "label"), text(n, "name"), text(n, "qualified_name"),
                text(n, "file_path"), intValue(n, "start_line"), intValue(n, "end_line"),
                boolValue(n, "matched"), intValue(n, "deg_local"),
                intValue(n, "in_degree"), intValue(n, "out_degree"));
        ModelResponseDrift.check("POST /graph_node.node", n, NODE_INNER_FIELDS, NODE_INNER_REQUIRED);
        return new GraphNodeResult(
                node,
                firstNonNull(text(n, "source"), text(root, "source")),
                firstNonNull(text(n, "file_version"), text(root, "file_version")),
                firstNonNull(intMap(n, "in_types"), intMap(root, "in_types")),
                firstNonNull(intMap(n, "out_types"), intMap(root, "out_types")),
                firstNonNull(objectMap(n, "props", mapper), objectMap(root, "props", mapper)),
                nodeExtra(n, mapper),
                doubleValue(root, "elapsed_ms"));
    }

    /**
     * node 内未纳入对外契约的字段原样透传（模型新增字段自动出现在 {@code extra}，后端无需改代码）。
     * 无未知字段时返回 null（不输出空对象，减少响应噪声）。
     */
    private static Map<String, Object> nodeExtra(JsonNode node, ObjectMapper mapper) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!NODE_INNER_FIELDS.contains(name)) {
                extra.put(name, mapper.convertValue(node.get(name), Object.class));
            }
        }
        return extra.isEmpty() ? null : extra;
    }

    @Override
    public Uni<GraphKgResult> kg(String palace, String q, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp document knowledge graph query, url={}, endpoint=POST /graph_kg, request params: q={}, palace={}",
                kgServiceUrl, q, palace);
        return api.graphKg(new GraphKgRequest(q, palace))
                .onFailure().invoke(t -> log.error("dual-mcp /graph_kg call failed, url={}, request: q={}, palace={}, error: {}",
                        kgServiceUrl, q, palace, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /graph_kg", appMessages);
                    ModelResponseDrift.check("POST /graph_kg", root, KG_FIELDS, KG_REQUIRED);
                    checkItems("POST /graph_kg.nodes[]", root, "nodes", KG_NODE_FIELDS);
                    checkItems("POST /graph_kg.edges[]", root, "edges", KG_EDGE_FIELDS);
                    JsonNode error = root.get("error");
                    if (error != null && !error.isNull()) {
                        // 文档 §5：mempalace 不可达时响应带 error（正常响应无此字段）。记 error 便于定位
                        // "查不到"与"后端故障"的区别；响应仍按空结果语义透传。
                        log.error("dual-mcp /graph_kg upstream mempalace error, url={}, q={}: {}",
                                kgServiceUrl, q, LogTruncate.truncate(error));
                    }
                    GraphKgResult result = convert(root, GraphKgResult.class, "POST /graph_kg", appMessages);
                    log.info("dual-mcp /graph_kg response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    @Override
    public Uni<GraphSuggestResult> suggest(String project, String palace, String q, Integer limit, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp search suggestion, url={}, endpoint=POST /graph_suggest, request params: q={}, project={}, palace={}, limit={}",
                kgServiceUrl, q, project, palace, limit);
        return api.graphSuggest(new GraphSuggestRequest(q, project, limit, palace))
                .onFailure().invoke(t -> log.error("dual-mcp /graph_suggest call failed, url={}, request: q={}, project={}, palace={}, error: {}",
                        kgServiceUrl, q, project, palace, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /graph_suggest", appMessages);
                    ModelResponseDrift.check("POST /graph_suggest", root, SUGGEST_FIELDS, SUGGEST_REQUIRED);
                    checkItems("POST /graph_suggest.suggestions[]", root, "suggestions", SUGGESTION_FIELDS);
                    GraphSuggestResult result = convert(root, GraphSuggestResult.class, "POST /graph_suggest", appMessages);
                    log.info("dual-mcp /graph_suggest response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    /**
     * 已建立映射关系的文件清单（POST /doc_code/files，《API-DOCCODE-FILES.md》§4）。
     *
     * <p>容错解析而非 {@link #convert} 强绑定：文档模型侧历史上有 count/refs/elapsed_ms 数值漂移
     * （曾出现过字符串/浮点形态），强类型绑定会把"数值能解析但类型不符"打成 C0001；这里用宽容取值
     * （非数值 → WARN + null），只有解析不出来才影响展示列，不影响成败。</p>
     *
     * <p>{@code files[].url} <b>不透传</b>模型返回的绝对地址（可能是集群内 svc 名或模型侧直连地址，
     * 浏览器不可达），统一重写为平台自身 {@code /kg/doc-view?project={kbId}&file={file}} 查询串，
     * 复用既有 doc-view 门面。{@code count=0} 为数据真值，正常透传不转业务异常。</p>
     */
    @Override
    public Uni<DocCodeFileListResult> docCodeFiles(String project, String type, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp doc-code mapped file list, url={}, endpoint=POST /doc_code/files, request params: project={}, type={}",
                kgServiceUrl, project, type);
        return api.docCodeFiles(new DocCodeFilesRequest(project, type))
                .onFailure().invoke(t -> log.error("dual-mcp /doc_code/files call failed, url={}, request: project={}, type={}, error: {}",
                        kgServiceUrl, project, type, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /doc_code/files", appMessages);
                    if (!root.isObject()) {
                        log.error("dual-mcp POST /doc_code/files response is not a JSON object, url={}, body={}",
                                kgServiceUrl, LogTruncate.truncate(body));
                        throw new ModelServiceException(appMessages.modelServiceError());
                    }
                    ModelResponseDrift.check("POST /doc_code/files", root, DOC_CODE_FILES_FIELDS, DOC_CODE_FILES_REQUIRED);
                    JsonNode files = root.get("files");
                    List<DocCodeFileListResult.DocCodeFileItem> items = files != null && files.isArray()
                            ? toDocCodeFileItems(files, project)
                            : null;
                    DocCodeFileListResult result = new DocCodeFileListResult(
                            text(root, "project"), text(root, "type"),
                            intValue(root, "count"), items, doubleValue(root, "elapsed_ms"));
                    log.info("dual-mcp /doc_code/files response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    /**
     * files[] → 对外清单项：逐项漂移探测 + 宽容取值 + url 重写为平台 /kg/doc-view 查询串。
     * file 缺失的项无法构造 doc-view 链接，记 WARN 后跳过（不应出现，属于模型侧契约破坏）。
     */
    private List<DocCodeFileListResult.DocCodeFileItem> toDocCodeFileItems(JsonNode files, String project) {
        List<DocCodeFileListResult.DocCodeFileItem> items = new ArrayList<>(files.size());
        for (JsonNode item : files) {
            ModelResponseDrift.check("POST /doc_code/files.files[]", item, DOC_CODE_FILE_ITEM_FIELDS, Set.of());
            String file = text(item, "file");
            if (file == null) {
                log.warn("dual-mcp /doc_code/files item without file, url={}, item={}, skipped",
                        kgServiceUrl, LogTruncate.truncate(item));
                continue;
            }
            JsonNode refs = item.get("refs");
            if (refs != null && !refs.isNull() && !refs.isNumber()) {
                log.warn("dual-mcp /doc_code/files item refs is not a number, url={}, file={}, refs={}",
                        kgServiceUrl, file, refs.asText());
            }
            items.add(new DocCodeFileListResult.DocCodeFileItem(
                    file, text(item, "name"), intValue(item, "refs"),
                    "/kg/doc-view?project=" + URLEncoder.encode(project, StandardCharsets.UTF_8)
                            + "&file=" + URLEncoder.encode(file, StandardCharsets.UTF_8)));
        }
        return items;
    }

    /**
     * 文档图谱关系片段查询（POST /graph_fragment，《API-GRAPH-FRAGMENT.md》§2，2026-10-23）。
     *
     * <p>强类型 {@link #convert} 解析（字段类型契约简单稳定，非 doc_code/files 的历史漂移场景）+
     * 逐项漂移探测。mempalace 不可达为 200+error 信封（同 /graph_kg），记 error 后透传空结果语义。
     * {@code fragments[].docViewUrl} 平台重写：不透传模型侧 {@code source_file} 绝对路径，
     * 按 {@code room + palace} 拼平台 {@code /kg/doc-view?doc={room}&kb={palace}} 查询串
     * （对齐 doc_code/files 的 url 重写先例），room 缺失的项记 WARN 后 docViewUrl 置 null。</p>
     */
    @Override
    public Uni<GraphFragmentResult> graphFragment(String palace, String subject, String predicate, String object,
                                                  String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        log.info("Calling dual-mcp graph fragment query, url={}, endpoint=POST /graph_fragment, request params: "
                        + "subject={}, predicate={}, object={}, palace={}",
                kgServiceUrl, subject, predicate, object, palace);
        return api.graphFragment(new GraphFragmentRequest(subject, predicate, object, palace))
                .onFailure().invoke(t -> log.error("dual-mcp /graph_fragment call failed, url={}, request: subject={}, "
                                + "palace={}, error: {}", kgServiceUrl, subject, palace, t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transform(body -> {
                    JsonNode root = readTree(body, "POST /graph_fragment", appMessages);
                    ModelResponseDrift.check("POST /graph_fragment", root, FRAGMENT_FIELDS, FRAGMENT_REQUIRED);
                    checkItems("POST /graph_fragment.fragments[]", root, "fragments", FRAGMENT_ITEM_FIELDS);
                    JsonNode error = root.get("error");
                    if (error != null && !error.isNull()) {
                        // 文档 §2.6：mempalace 不可达时响应带 error（正常响应无此字段）。记 error 便于
                        // 区分"无片段"与"后端故障"；响应仍按空结果语义透传。
                        log.error("dual-mcp /graph_fragment upstream mempalace error, url={}, subject={}, palace={}: {}",
                                kgServiceUrl, subject, palace, LogTruncate.truncate(error));
                    }
                    JsonNode fragments = root.get("fragments");
                    List<GraphFragmentResult.Fragment> items = fragments != null && fragments.isArray()
                            ? toGraphFragmentItems(fragments, palace)
                            : null;
                    GraphFragmentResult result = new GraphFragmentResult(
                            text(root, "subject"), text(root, "predicate"), text(root, "object"),
                            intValue(root, "count"), items, doubleValue(root, "elapsed_ms"), text(root, "error"));
                    log.info("dual-mcp /graph_fragment response, url={}: {}", kgServiceUrl, LogTruncate.truncate(result));
                    return result;
                });
    }

    /**
     * fragments[] → 对外片段项：宽容取值 + docViewUrl 重写为平台 /kg/doc-view 查询串
     * （doc=room 文档名 + kb=palace，走门面 KG 文档入口）。
     */
    private List<GraphFragmentResult.Fragment> toGraphFragmentItems(JsonNode fragments, String palace) {
        List<GraphFragmentResult.Fragment> items = new ArrayList<>(fragments.size());
        for (JsonNode item : fragments) {
            String room = text(item, "room");
            String docViewUrl = room == null || room.isBlank() ? null
                    : "/kg/doc-view?doc=" + URLEncoder.encode(room, StandardCharsets.UTF_8)
                            + "&kb=" + URLEncoder.encode(palace, StandardCharsets.UTF_8);
            items.add(new GraphFragmentResult.Fragment(
                    text(item, "text"), text(item, "source_file"), room,
                    text(item, "chunk_index"), docViewUrl));
        }
        return items;
    }

    // ======================== 原始 body → JsonNode → 对外 DTO ========================

    /** 原始响应体 → JsonNode；空体/非法 JSON 记 error（带 body）后抛 C0001 */
    private JsonNode readTree(String body, String endpoint, AppMessages appMessages) {
        if (body == null || body.isBlank()) {
            log.error("dual-mcp {} response is null or empty, url={}", endpoint, kgServiceUrl);
            throw new ModelServiceException(appMessages.modelServiceError());
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.error("dual-mcp {} response is not valid JSON, url={}, body={}",
                    endpoint, kgServiceUrl, LogTruncate.truncate(body));
            throw new ModelServiceException(appMessages.modelServiceError(), e);
        }
    }

    /** JsonNode → 对外 DTO（未知字段由 DTO 的 @JsonIgnoreProperties 忽略；类型不匹配按 C0001 处理） */
    private <T> T convert(JsonNode root, Class<T> type, String endpoint, AppMessages appMessages) {
        if (root == null || !root.isObject()) {
            log.error("dual-mcp {} response is not a JSON object, url={}, body={}",
                    endpoint, kgServiceUrl, LogTruncate.truncate(root));
            throw new ModelServiceException(appMessages.modelServiceError());
        }
        try {
            return mapper.treeToValue(root, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("dual-mcp {} response cannot be mapped to {}, url={}, body={}: {}",
                    endpoint, type.getSimpleName(), kgServiceUrl, LogTruncate.truncate(root), e.toString());
            throw new ModelServiceException(appMessages.modelServiceError(), e);
        }
    }

    /** 数组字段逐项做漂移探测（只查未声明字段，不查必需字段——数组项字段缺失不阻断业务） */
    private static void checkItems(String endpoint, JsonNode root, String arrayField, Set<String> documented) {
        JsonNode array = root.get(arrayField);
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            ModelResponseDrift.check(endpoint, item, documented, Set.of());
        }
    }

    // ======================== JsonNode 宽容取值（字段缺失/类型不符返回 null，不抛异常） ========================

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static Integer intValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.intValue() : null;
    }

    private static Double doubleValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.doubleValue() : null;
    }

    private static Boolean boolValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            return null;
        }
        return v.isBoolean() ? v.booleanValue() : null;
    }

    /** 对象字段 → Map<String,Integer>（只收数值项；非对象返回 null） */
    private static Map<String, Integer> intMap(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isObject()) {
            return null;
        }
        Map<String, Integer> map = new LinkedHashMap<>();
        Iterator<String> names = v.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = v.get(name);
            if (value != null && value.isNumber()) {
                map.put(name, value.intValue());
            }
        }
        return map;
    }

    /** 对象字段 → Map<String,Object>（属性袋透传；非对象返回 null） */
    private static Map<String, Object> objectMap(JsonNode node, String field, ObjectMapper mapper) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isObject()) {
            return null;
        }
        return mapper.convertValue(v, OBJECT_MAP_TYPE);
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
