package com.hisilicon.knowledge.platform.commons.ai.api;

import com.hisilicon.knowledge.platform.commons.ai.client.DocCodeMappingClientImpl;
import com.hisilicon.knowledge.platform.commons.ai.client.KnowledgeGraphClientImpl;
import com.hisilicon.knowledge.platform.commons.ai.client.RestClientRequestLogger;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocCodeFilesRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocCodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphFragmentRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphKgRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphNodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSearchRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSuggestRequest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * dual-mcp 知识图谱 REST Client（独立模型服务，区别于 {@link ModelServiceApi} 的 RAG 模型服务）。
 *
 * <p>configKey {@code "kg-service"}（{@code quarkus.rest-client.kg-service.url}），基址默认
 * {@code http://haisi-dual-mcp:9789}（集群内 Service DNS）或本地 port-forward 9789。无类级 {@code @Path}，
 * 方法 {@code @Path} 直接拼基址。端点集对齐《API-GRAPH2.md》：GET /projects、POST /graph_search、
 * POST /graph_node、POST /graph_kg、POST /graph_suggest（文档 KG 由 dual-mcp 代理 mempalace，平台不直连
 * mempalace）；POST /doc_code 为独立代码↔文档映射功能，保留本接口。</p>
 *
 * <p><b>边界契约：各端点统一返回原始响应体 {@code String}，不做强类型绑定。</b>模型字段漂移
 * （改名/新增/挪位置/类型变化）在反序列化层不可见，强类型 DTO 会静默丢字段或直接抛
 * {@code InvalidFormatException}（2026-10 已发生：{@code /graph_node} 的 {@code in_types/out_types/source}
 * 挪进 {@code node}、{@code /doc_code} 的 {@code matched} 由数值变字符串）。原始 body 由
 * {@link KnowledgeGraphClientImpl} / {@link DocCodeMappingClientImpl} 容错解析并组装对外契约，
 * 同时用 {@code ModelResponseDrift} 做漂移告警。</p>
 *
 * <p>各图谱端点响应均为直接对象（无 {@code code/msg/data} 信封），HTTP 200=成功。</p>
 *
 * <p>{@link RegisterProvider} 注册 {@link RestClientRequestLogger} 记录实际出站 URI。</p>
 */
@RegisterRestClient(configKey = "kg-service")
@RegisterProvider(RestClientRequestLogger.class)
public interface KgServiceApi {

    /**
     * 已索引项目列表（GET /projects，§2）。
     *
     * @return 原始响应体（直接对象，无信封）
     */
    @GET
    @Path("/projects")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> projects();

    /**
     * 代码图谱邻接查询（POST /graph_search，§3）。
     *
     * @param request {@code q}（必填）+ {@code project} + {@code limit} + {@code candidates}
     * @return 原始响应体（center/candidates/nodes/edges/hint，直接对象无信封）
     */
    @POST
    @Path("/graph_search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> graphSearch(GraphSearchRequest request);

    /**
     * 代码图谱节点详情（POST /graph_node，§4）。
     *
     * @param request {@code name}（模型必填）+ {@code id} + {@code project} + {@code file_path} + {@code with_source}
     * @return 原始响应体（增强字段在 {@code node} 内，由 {@link KnowledgeGraphClientImpl} 提升为对外契约）
     */
    @POST
    @Path("/graph_node")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> graphNode(GraphNodeRequest request);

    /**
     * 文档知识图谱一跳邻接查询（POST /graph_kg，§5；dual-mcp 代理 mempalace）。
     *
     * @param request {@code q}（KG 实体名，精确匹配）+ {@code palace}（kbId，null=全库，向后兼容）
     * @return 原始响应体（nodes/edges/count/hint/palace 回显，直接对象无信封；mempalace 故障时带 {@code error}）
     */
    @POST
    @Path("/graph_kg")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> graphKg(GraphKgRequest request);

    /**
     * 文档图谱关系片段查询（POST /graph_fragment，《API-GRAPH-FRAGMENT.md》2026-10-23；
     * dual-mcp 代理 mempalace）。
     *
     * @param request {@code subject}（必填）+ {@code predicate} + {@code object}（缺省按主体+客体匹配）
     *                + {@code palace}（kbId，必填）
     * @return 原始响应体（fragments/count/elapsed_ms 回显，直接对象无信封；
     *         mempalace 故障时带 {@code error}；count=0 为数据真值非错误）
     */
    @POST
    @Path("/graph_fragment")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> graphFragment(GraphFragmentRequest request);

    /**
     * 搜索框联想（POST /graph_suggest，§4.5；代码图谱 + 文档图谱双源聚合）。
     *
     * @param request {@code q}（必填）+ {@code project} + {@code limit} + {@code palace}（kbId，KG 源按库过滤）
     * @return 原始响应体（suggestions 带 source=code/kg 来源徽标，直接对象无信封）
     */
    @POST
    @Path("/graph_suggest")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> graphSuggest(GraphSuggestRequest request);

    /**
     * 代码↔文档映射查询（dual-mcp /doc_code 接口，独立功能保留）。
     *
     * @param request {@code file}（必填）+ {@code project} + {@code direction} + {@code limit} 等
     * @return 原始响应体（直接对象，无信封）；不做强类型绑定，由 {@link DocCodeMappingClientImpl}
     *         容错解析——测试/生产 dual-mcp 版本 schema 存在漂移（如 matched 非数值），解析失败时
     *         保留原始 body 便于定位
     */
    @POST
    @Path("/doc_code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> docCode(DocCodeRequest request);

    /**
     * 已建立映射关系的文件清单（dual-mcp /doc_code/files 接口，《API-DOCCODE-FILES.md》，2026-10-22 上线）。
     *
     * <p>按 kbId 返回存在 DOCUMENTS_CODE 映射边的文件列表（type=code 代码文件 / doc 文档文件），
     * 供 codemap 页浏览映射全貌。count=0 为数据真值（该库尚无映射），非错误。</p>
     *
     * @param request {@code project}（必填，= kbId）+ {@code type}（可选：code/doc，缺省 code）
     * @return 原始响应体（直接对象，无信封）；不做强类型绑定，由 {@link KnowledgeGraphClientImpl}
     *         容错解析（count/refs/elapsed_ms 曾有非数值漂移），并重写 files[].url 为平台 doc-view 地址
     */
    @POST
    @Path("/doc_code/files")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> docCodeFiles(DocCodeFilesRequest request);
}
