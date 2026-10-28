package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeFileListResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.knowledge.command.DocCodeFilesCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphFragmentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphKgCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphSearchCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphSuggestCommand;
import com.hisilicon.knowledge.platform.knowledge.query.KnowledgeGraphService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识图谱 REST 接口（对齐《API-GRAPH.md》dual-mcp 端点集，对前端镜像新端点）。
 *
 * <p>端点映射：GET /kg/projects → 模型 GET /projects；POST /kg/graph-search → POST /graph_search；
 * POST /kg/graph-node → POST /graph_node；POST /kg/graph-kg → POST /graph_kg（dual-mcp 代理 mempalace，
 * 平台不直连 mempalace）；GET /kg/doc-view → 303 跳转模型侧原文查看页（文件流不经平台代理）。
 * 全部数据端点统一 {@code ResponseResult} 信封（code=0 + data）；空结果 → A0430「未找到相关信息」；
 * 模型失败 → C0001，均经统一异常处理。</p>
 */
@Path("/kg")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KnowledgeGraphResource {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphResource.class);

    private final KnowledgeGraphService knowledgeGraphService;

    /** 模型侧原文查看页公网基址（303 跳转目标） */
    @ConfigProperty(name = "kb.kg.public-url", defaultValue = "")
    String kgPublicUrl;

    @Context
    HttpHeaders httpHeaders;

    public KnowledgeGraphResource(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * 已索引项目列表（含图谱规模与知识库名 kb_name）。
     */
    @GET
    @Path("/projects")
    public Uni<ResponseResult<GraphProjectsResult>> projects() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Knowledge graph project list request");
        return knowledgeGraphService.projects(locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 代码图谱邻接查询（按关键字查中心实体的邻接子图）。
     */
    @POST
    @Path("/graph-search")
    public Uni<ResponseResult<GraphSearchResult>> graphSearch(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            GraphSearchCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Code graph adjacency query request, kbId={}, q={}", command.kbId(), command.q());
        return knowledgeGraphService.search(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 代码图谱节点详情（属性/邻边统计/源码段）。
     */
    @POST
    @Path("/graph-node")
    public Uni<ResponseResult<GraphNodeResult>> graphNode(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            GraphNodeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Code graph node detail request, kbId={}, name={}, id={}", command.kbId(), command.name(), command.id());
        return knowledgeGraphService.node(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 文档知识图谱一跳邻接查询（实体名精确匹配）。
     */
    @POST
    @Path("/graph-kg")
    public Uni<ResponseResult<GraphKgResult>> graphKg(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            GraphKgCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Document knowledge graph query request, kbId={}, q={}", command.kbId(), command.q());
        return knowledgeGraphService.kg(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 搜索联想（双源：代码图谱 + 文档图谱；空结果为正常态，透传空列表）。
     */
    @POST
    @Path("/graph-suggest")
    public Uni<ResponseResult<GraphSuggestResult>> graphSuggest(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            GraphSuggestCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Search suggestion request, kbId={}, q={}, limit={}", command.kbId(), command.q(), command.limit());
        return knowledgeGraphService.suggest(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 文档图谱关系片段查询（图谱页关系边面板「片段」按钮）。
     * {@code count=0} 为数据真值（关系来源文档未入 mempalace 等），正常透传空列表不报错。
     */
    @POST
    @Path("/graph-fragment")
    public Uni<ResponseResult<GraphFragmentResult>> graphFragment(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            GraphFragmentCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Graph fragment query request, kbId={}, subject={}, predicate={}, object={}",
                command.kbId(), command.subject(), command.predicate(), command.object());
        return knowledgeGraphService.graphFragment(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 已建立映射关系的文件清单（codemap 页浏览 DOCUMENTS_CODE 映射全貌；《API-DOCCODE-FILES.md》）。
     * {@code count=0} 为数据真值（该库尚无映射），正常透传不报错。
     */
    @POST
    @Path("/doc-code-files")
    public Uni<ResponseResult<DocCodeFileListResult>> docCodeFiles(
            @Valid @jakarta.validation.constraints.NotNull(message = "{app.common.body.notnull}")
            DocCodeFilesCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Doc-code mapped file list request, kbId={}, type={}", command.kbId(), command.type());
        return knowledgeGraphService.docCodeFiles(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 原文查看页跳转：默认 303 See Other 到模型侧 GET /doc_view（文件流不经平台代理）。
     *
     * <p>{@code mode=url} 时返回 JSON 信封 {@code data.url}（HTTP 200）而非 303：前端 XHR 场景下
     * 浏览器会跟随 303 对模型地址发跨域请求（模型侧无 CORS 头会被拦），此时前端应取 url 用
     * {@code window.open} 新标签页导航打开——导航请求无 CORS 概念，模型页相对资源也正常解析。</p>
     *
     * <p>入参不变，两种调用方式：代码/文档节点（{@code project}+{@code file}(+{@code line})）或
     * KG 文档实体（{@code doc}+{@code kb}）。参数组合非法 → A0420。</p>
     *
     * <p>{@code project+file} 方式下按 file 末段<b>扩展名自动分流</b>模型入口：
     * 文档类扩展名（md/pdf/office/压缩包/图片等）→ 模型 KG 文档入口 {@code doc={文件名末段}&kb={project}}
     * （文档在模型侧位于 /projects/&lt;kbId&gt;/ 文档根下，代码入口按项目根拼路径会 404，且按文件名定位需取末段）；
     * 代码类及无/未知扩展名（兜底）→ 代码入口 {@code project&file(裁剪后)&line}，file 先经
     * {@link KnowledgeGraphService#normalizeDocViewFile} 裁剪 {@code kb/{dirName}/...} 存储前缀。</p>
     */
    @GET
    @Path("/doc-view")
    public Uni<Response> docView(@QueryParam("project") String project,
                                 @QueryParam("file") String file,
                                 @QueryParam("line") Integer line,
                                 @QueryParam("doc") String doc,
                                 @QueryParam("kb") String kb,
                                 @QueryParam("mode") String mode) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        boolean urlMode = "url".equalsIgnoreCase(mode == null ? "" : mode.trim());
        boolean codeEntry = notBlank(project) && notBlank(file);
        boolean kgEntry = notBlank(doc) && notBlank(kb);
        if (codeEntry == kgEntry) {
            // 两者都缺或都给：非法组合
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidParameter()));
        }
        if (kgPublicUrl == null || kgPublicUrl.isBlank()) {
            logger.error("kg.public-url (model service public URL) is not configured, cannot redirect to source view page");
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.SERVICE_ERROR_C0001, appMessages.modelServiceError()));
        }
        UriBuilder uri = UriBuilder.fromUri(kgPublicUrl.trim()).path("/doc_view");
        // 配置守卫：303/URL 由用户浏览器直接打开，配置成集群内部 DNS（.svc）会解析失败。
        // 日志显式报错，提示改 KB_KG_PUBLIC_URL 为公网地址（如 http://139.9.104.64:9789）。
        if (isClusterInternalUrl(kgPublicUrl.trim())) {
            logger.error("kb.kg.public-url is a cluster-internal address ({}): the target is opened by the user's browser "
                    + "and cannot resolve in-cluster DNS. Set env KB_KG_PUBLIC_URL to the public model address, e.g. http://139.9.104.64:9789 "
                    + "(the in-cluster address is only for quarkus.rest-client.kg-service.url, server-side API calls)",
                    kgPublicUrl.trim());
        }
        if (codeEntry) {
            String kbId = project.trim();
            // 按扩展名分流：文档类 → 模型 KG 文档入口（doc=文件名末段 + kb）
            if (isDocumentPath(file)) {
                String docName = baseName(file.trim());
                logger.info("doc_view entry by extension, kbId={}, file={}, docName={}, entry=doc, mode={}", kbId, file, docName, mode);
                java.net.URI docTarget = uri.queryParam("doc", docName).queryParam("kb", kbId).build();
                return Uni.createFrom().item(respond(docTarget, urlMode, locale));
            }
            // 代码类/未知扩展名兜底 → 代码入口（存储前缀裁剪后拼 project/file/line）
            return knowledgeGraphService.normalizeDocViewFile(kbId, file)
                    .map(normalizedFile -> {
                        UriBuilder target = uri.queryParam("project", kbId).queryParam("file", normalizedFile);
                        if (line != null) {
                            target = target.queryParam("line", line);
                        }
                        java.net.URI redirect = target.build();
                        return respond(redirect, urlMode, locale);
                    });
        }
        uri.queryParam("doc", doc.trim()).queryParam("kb", kb.trim());
        java.net.URI target = uri.build();
        return Uni.createFrom().item(respond(target, urlMode, locale));
    }

    /** mode=url → 200 + JSON 信封 data.url（前端 window.open 用）；否则 303 See Other */
    private Response respond(java.net.URI target, boolean urlMode, String locale) {
        logger.info("Redirect to source view page, target={}", target);
        if (urlMode) {
            return Response.ok(ResponseResult.ok(new DocViewUrl(target.toString()), locale))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        return Response.seeOther(target).build();
    }

    /** mode=url 响应体：模型侧原文查看页完整地址（前端 window.open 新标签页打开） */
    public record DocViewUrl(String url) {
    }

    /** 文档类扩展名（→ 模型 KG 文档入口 doc+kb）；代码类及无/未知扩展名不在其列，兜底走代码入口 */
    private static final java.util.Set<String> DOCUMENT_EXTENSIONS = java.util.Set.of(
            "md", "markdown", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z",
            "png", "jpg", "jpeg", "gif", "bmp", "svg");

    /**
     * 按 file 路径末段扩展名判断是否文档类。无扩展名或未知扩展名 → false（兜底代码入口，
     * 与既有行为一致，避免把代码配置文件误导向按名定位的文档入口）。
     */
    private static boolean isDocumentPath(String file) {
        String ext = extensionOf(file);
        return ext != null && DOCUMENT_EXTENSIONS.contains(ext);
    }

    /** 路径末段文件名的扩展名（小写）；无扩展名返回 null（兼容 / 与 \ 分隔符） */
    private static String extensionOf(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 路径末段文件名（模型 doc+kb 入口按文件名定位用） */
    private static String baseName(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** host 是否为 K8s 集群内部 DNS（.svc / .svc.cluster.local 结尾）——浏览器无法解析，不能用于 303 跳转 */
    static boolean isClusterInternalUrl(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String h = host.toLowerCase(java.util.Locale.ROOT);
            return h.endsWith(".svc") || h.endsWith(".svc.cluster.local");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
