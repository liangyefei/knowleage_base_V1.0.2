package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.DeleteDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.BatchDeleteCommand;
import com.hisilicon.knowledge.platform.knowledge.command.BatchReviewCommand;
import com.hisilicon.knowledge.platform.knowledge.command.BatchSubmitReviewCommand;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentImportService;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentVersionService;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentIdCommand;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentVersionCommand;
import com.hisilicon.knowledge.platform.knowledge.command.DownloadDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.EditDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.RejectDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GlobalDocumentSearchCommand;
import com.hisilicon.knowledge.platform.knowledge.command.SearchDocumentsCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UploadDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.VersionDiffCommand;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentGlobalSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentVersionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.GlobalSearchQuery;
import com.hisilicon.knowledge.platform.knowledge.query.ListDocumentsQuery;
import com.hisilicon.knowledge.platform.knowledge.query.VersionDiffQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.MarkdownImageRewriter;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchReviewResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchSubmitReviewResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentMetadataView;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentNameOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileOptionNode;
import com.hisilicon.knowledge.platform.knowledge.query.dto.GlobalDocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.MetadataOptionGroup;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentVersionOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDocumentResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionOverrideResult;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import jakarta.ws.rs.PathParam;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 文档 REST 接口（生命周期状态流转）
 */
@Path("/kb/documents")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocumentResource {

    private static final Logger logger = LoggerFactory.getLogger(DocumentResource.class);

    /**
     * metadata JSON 反序列化专用 ObjectMapper。
     * <p>开启 {@link DeserializationFeature#ACCEPT_SINGLE_VALUE_AS_ARRAY}：前端多选标签
     * （dict-options metaKey={@code tag}）可能以单值字符串 {@code "tag":"GPIO"} 提交，
     * 归一为单元素列表以匹配 {@code List<String> tags} 字段。</p>
     */
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private final DocumentCommandService documentCommandService;
    private final DocumentImportService documentImportService;
    private final DocumentVersionService documentVersionService;
    private final DocumentQueryService documentQueryService;
    private final DocumentGlobalSearchQueryService documentGlobalSearchQueryService;
    private final DocumentVersionQueryService documentVersionQueryService;
    private final VersionDiffQueryService versionDiffQueryService;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadSessionRepository uploadSessionRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final MarkdownImageRewriter markdownImageRewriter;
    private final com.hisilicon.knowledge.platform.knowledge.command.ConflictAutoSwitchSyncService conflictAutoSwitchSyncService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public DocumentResource(DocumentCommandService documentCommandService,
                            DocumentImportService documentImportService,
                            DocumentVersionService documentVersionService,
                            DocumentQueryService documentQueryService,
                            DocumentGlobalSearchQueryService documentGlobalSearchQueryService,
                            DocumentVersionQueryService documentVersionQueryService,
                            VersionDiffQueryService versionDiffQueryService,
                            DocumentRepository documentRepository,
                            FileStorageService fileStorageService,
                            UploadSessionRepository uploadSessionRepository,
                            CategoryPanacheRepository categoryPanacheRepository,
                            MarkdownImageRewriter markdownImageRewriter,
                            com.hisilicon.knowledge.platform.knowledge.command.ConflictAutoSwitchSyncService conflictAutoSwitchSyncService) {
        this.documentCommandService = documentCommandService;
        this.documentImportService = documentImportService;
        this.documentVersionService = documentVersionService;
        this.documentQueryService = documentQueryService;
        this.documentGlobalSearchQueryService = documentGlobalSearchQueryService;
        this.documentVersionQueryService = documentVersionQueryService;
        this.versionDiffQueryService = versionDiffQueryService;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.uploadSessionRepository = uploadSessionRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.markdownImageRewriter = markdownImageRewriter;
        this.conflictAutoSwitchSyncService = conflictAutoSwitchSyncService;
    }

    /**
     * 文档搜索（统一查询接口，POST body，含元数据筛选）
     * <p>筛选：类别 / 是否含子树 / 文档名称模糊 / 文档类型多选 / 生命周期状态多选 / 向量状态多选 /
     * 官方标识多选（{@code officials}，等价 {@code metadata.official} 且优先）/ 元数据多 key AND 匹配
     * （meta_key → 值，值逗号分隔多值时按片段匹配）。
     * {@code includeDescendants=true} 时：无 categoryId 查整库；有 categoryId 查该节点及全部后代。
     * page/pageSize 默认 1/20，pageSize 最大 1000。</p>
     * <p>返回列表项含顶层 {@code official}（OFFICIAL/NON_OFFICIAL；文档无该元数据行 → null，即「未标注」，
     * 且不被 OFFICIAL/NON_OFFICIAL 任一过滤命中）。</p>
     */
    @POST
    @Path("/search")
    public Uni<ResponseResult<PageResult<DocumentListItem>>> searchDocuments(
            @Valid @NotNull(message = "{app.common.body.notnull}") SearchDocumentsCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 校验 pageSize：最大为 1000
        if (command.pageSize() != null && command.pageSize() > 1000) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.invalidPageSizeValue());
        }

        int p = (command.page() == null || command.page() < 1) ? 1 : command.page();
        int ps = (command.pageSize() == null || command.pageSize() < 1) ? 20 : command.pageSize();
        boolean includeDescendants = Boolean.TRUE.equals(command.includeDescendants());
        // officials 一等入参归一到既有 metadata 过滤通道（服务层签名与元数据过滤逻辑不变）
        Map<String, String> effectiveMetadata = mergeOfficials(command.officials(), command.metadata());
        logger.info("Document search request, kbId={}, categoryId={}, includeDescendants={}, docName={}, docTypes={}, statuses={}, processStatuses={}, officials={}, metadata={}, page={}, pageSize={}, locale={}",
                command.kbId(), command.categoryId(), includeDescendants, command.docName(),
                command.docTypes(), command.statuses(), command.processStatuses(), command.officials(),
                effectiveMetadata, p, ps, locale);
        return documentQueryService.listDocuments(new ListDocumentsQuery(command.kbId().trim(),
                        command.categoryId(), includeDescendants, command.docName(), command.docTypes(),
                        command.statuses(), command.processStatuses(), effectiveMetadata, p, ps))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 官方标识入参归一到既有元数据过滤通道：{@code officials} → {@code metadata["official"]}
     * （逗号 join，复用服务层「同 key 多值 OR + 四段片段匹配」的既有实现）。
     *
     * <p>冲突规则：请求同时传 {@code officials} 与 {@code metadata["official"]} 时<b>以 officials 为准</b>
     * 并记 WARN——同一 meta_key 两个来源若静默叠加会变成 AND，值不一致时直接查出空页，前端难以排查。</p>
     *
     * <p>未传 / 全空白：<b>原样返回入参 Map（保持 null 语义）</b>，服务层行为与引入本字段前完全一致。</p>
     *
     * @param officials 官方标识多选（可空）
     * @param metadata  原元数据筛选 Map（可空）
     * @return 归一后的元数据筛选 Map
     */
    private static Map<String, String> mergeOfficials(List<String> officials, Map<String, String> metadata) {
        if (officials == null || officials.isEmpty()) {
            return metadata;
        }
        List<String> cleaned = new ArrayList<>(officials.size());
        for (String value : officials) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return metadata;
        }
        String existing = metadata == null ? null : metadata.get("official");
        if (existing != null && !existing.isBlank() && !existing.trim().equals(String.join(",", cleaned))) {
            logger.warn("Official filter provided by both officials and metadata.official, officials wins: officials={}, metadata.official={}",
                    cleaned, existing);
        }
        Map<String, String> merged = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        merged.put("official", String.join(",", cleaned));
        return merged;
    }

    /**
     * 文档全局搜索（跨知识库，POST body）
     * <p>不绑定知识库，按文档类型分类多选 / 文档名称模糊 / 芯片型号多选 / 搜索范围 检索已发布
     * 且向量化完成（processStatus=COMPLETED）的文档。
     * 芯片型号多选对应<b>知识库名称</b>（KB 以芯片型号命名，大小写不敏感，任一命中即返回），
     * 不查 document_metadata。默认仅返回 status=PUBLISHED 且所属知识库活跃的文档，按更新时间倒序。
     * searchField 控制关键词匹配范围：title=仅标题、content=仅文件抽取内容（Tika 异步抽取，
     * 仅"审核通过的最新版"可命中）、all=标题或内容（默认，可空）。
     * sortField 可选 time/title（默认 time）；relevance 相关度排序已下线，仅为兼容保留入参，
     * 传入后按 time 处理（前端已不再展示该选项）。page/pageSize 默认 1/20，pageSize 最大 1000。
     * 原 {@code POST /kb/documents/search} 不变。</p>
     */
    @POST
    @Path("/global-search")
    public Uni<ResponseResult<PageResult<GlobalDocumentListItem>>> globalSearchDocuments(
            @Valid @NotNull(message = "{app.common.body.notnull}") GlobalDocumentSearchCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 校验 pageSize：最大为 1000
        if (command.pageSize() != null && command.pageSize() > 1000) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.invalidPageSizeValue());
        }

        // 校验搜索范围：null/空白=默认 all；非空须为 title/content/all（大小写不敏感，归一为小写）
        String rawSearchField = command.searchField();
        String normalizedSearchField = null;
        if (rawSearchField != null && !rawSearchField.isBlank()) {
            normalizedSearchField = rawSearchField.trim().toLowerCase(Locale.ROOT);
            if (!"all".equals(normalizedSearchField) && !"title".equals(normalizedSearchField)
                    && !"content".equals(normalizedSearchField)) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.invalidSearchFieldValue());
            }
        }

        // 校验排序字段：null/空白=默认 time；非空须为 time/title/relevance（大小写不敏感，归一为小写）。
        // relevance 相关度排序已下线（需模型接口），仅为老客户端兼容保留：传入后按 time 处理
        String rawSort = command.sortField();
        String normalizedSort = null;
        if (rawSort != null && !rawSort.isBlank()) {
            normalizedSort = rawSort.trim().toLowerCase(Locale.ROOT);
            if (!"time".equals(normalizedSort) && !"title".equals(normalizedSort)
                    && !"relevance".equals(normalizedSort)) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.invalidSortFieldValue());
            }
            if ("relevance".equals(normalizedSort)) {
                logger.info("sortField=relevance relevance sorting is retired, this request is handled as time (updated time desc)");
            }
        }

        int p = (command.page() == null || command.page() < 1) ? 1 : command.page();
        int ps = (command.pageSize() == null || command.pageSize() < 1) ? 20 : command.pageSize();
        logger.info("Document global search request, docName={}, docTypes={}, chipModels={}, searchField={}, sortField={}, page={}, pageSize={}, locale={}",
                command.docName(), command.docTypes(), command.chipModels(),
                normalizedSearchField, normalizedSort, p, ps, locale);
        return documentGlobalSearchQueryService.globalSearch(new GlobalSearchQuery(command.docName(),
                        command.docTypes(), command.chipModels(),
                        normalizedSearchField == null ? "all" : normalizedSearchField,
                        normalizedSort, p, ps))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 元数据下拉选项（供查询条件选择）
     * <p>返回该 KB 下文档已存的白名单元数据 key 的去重选项列表；label 国际化。
     * 逗号分隔的多值已拆分为原子选项。</p>
     */
    @GET
    @Path("/metadata-options")
    public Uni<ResponseResult<List<MetadataOptionGroup>>> metadataOptions(
            @QueryParam("kbId") @NotBlank(message = "{app.kb.id.notblank}") String kbId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Metadata options request, kbId={}, locale={}", kbId, locale);
        return documentQueryService.getMetadataOptions(kbId.trim(), LocaleUtil.findLocale(locale))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 全局元数据下拉选项（供全局搜索的芯片型号多选下拉）
     * <p>返回活跃（status=1）且至少有 1 条已发布文档的知识库名称去重选项（KB 以芯片型号命名，
     * KB 名即芯片型号选项），与全局搜索的芯片过滤口径一致；label 国际化。
     * 与 {@link #metadataOptions} 区别：不按 kbId 范围，全局聚合。</p>
     */
    @GET
    @Path("/global-metadata-options")
    public Uni<ResponseResult<List<MetadataOptionGroup>>> globalMetadataOptions() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Global metadata options request, locale={}", locale);
        return documentGlobalSearchQueryService.getGlobalMetadataOptions(LocaleUtil.findLocale(locale))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 文档名称下拉选项（按知识库/目录 + 版本查询已发布文档）
     * <p>nodeType=KB 查整个知识库下全部已发布文档；nodeType=CATEGORY 递归子目录；
     * version 非空按 currentVersion 精确过滤。仅检索 {@code status=PUBLISHED} 且
     * 所属知识库活跃的文档。服务层先查命中条数：超过 50 条返回 {@code A0434}
     * +「数据过多，请缩小查询范围」提示（不返回数据），否则返回 id+title 列表
     * （上限 50，按创建时间倒序）。nodeType 非法（非 KB/CATEGORY）返回 A0420。</p>
     */
    @GET
    @Path("/name-options")
    public Uni<ResponseResult<List<DocumentNameOption>>> nameOptions(
            @QueryParam("nodeType") @NotBlank(message = "{app.node.type.notblank}") String nodeType,
            @QueryParam("nodeId") @NotBlank(message = "{app.node.id.notblank}") String nodeId,
            @QueryParam("version") String version) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String trimmedVersion = version == null ? null : version.trim();
        logger.info("Document name options request, nodeType={}, nodeId={}, version={}, locale={}",
                nodeType, nodeId, trimmedVersion, locale);
        return documentGlobalSearchQueryService.nameOptions(
                        nodeType == null ? null : nodeType.trim(),
                        nodeId == null ? null : nodeId.trim(),
                        trimmedVersion, locale)
                .map(list -> ResponseResult.ok(list, locale));
    }

    /**
     * 按方向搜索文件的树形下拉选项（代码↔文档映射：选择源文件，前端树状展示）
     * <p><b>前端对接</b>：一次性整树返回（无二级懒加载协议）——nodeType=KB + nodeId=kbId
     * 得到以知识库为根的整棵树；nodeType=CATEGORY + nodeId=categoryId 得到以该类别为根的
     * 子树。树节点：KB/CATEGORY 节点 children 递归挂载（类别按 sortOrder 升序），
     * 文档为 {@code nodeType=DOCUMENT} 的叶子（携带 docType/currentVersion/fileName/fileType，
     * fileName 如 {@code test.txt}、fileType 为小写扩展名如 {@code txt}，按创建时间倒序），
     * 空分支已裁剪；keyword 非空时仅保留命中文档及其祖先类别链（命中按文档标题
     * 或存储文件名模糊、大小写不敏感，关键词可传文件名/扩展名如 {@code test.txt}、{@code .txt}）。
     * direction=doc2code 只含 DOCUMENT 类型，code2doc 只含 CODE 类型；
     * 复用名称下拉的查询口径：仅 {@code status=PUBLISHED} 且所属知识库活跃的文档，
     * 孤儿文档不可见；命中文档超过 50 条返回 {@code A0434}（不返回数据）。
     * direction/nodeType 非法返回 A0420；KB/类别不存在或已软删时 data 为 null。</p>
     */
    @GET
    @Path("/file-options")
    public Uni<ResponseResult<FileOptionNode>> fileOptions(
            @QueryParam("direction") @NotBlank(message = "{app.direction.notblank}") String direction,
            @QueryParam("nodeType") @NotBlank(message = "{app.node.type.notblank}") String nodeType,
            @QueryParam("nodeId") @NotBlank(message = "{app.node.id.notblank}") String nodeId,
            @QueryParam("keyword") String keyword) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Directional file tree options request, direction={}, nodeType={}, nodeId={}, keyword={}, locale={}",
                direction, nodeType, nodeId, keyword, locale);
        return documentGlobalSearchQueryService.fileOptions(
                        direction == null ? null : direction.trim(),
                        nodeType == null ? null : nodeType.trim(),
                        nodeId == null ? null : nodeId.trim(),
                        keyword, locale)
                .map(root -> ResponseResult.ok(root, locale));
    }

    /**
     * 查看指定文档的元数据信息（编辑回填用）
     * <p>聚合编辑表单回填所需字段：documents 可编辑列（title/author/tags/remark）+
     * currentVersion + document_metadata 全部 key-value。所有生命周期状态均可查看。
     * 文档不存在 → A0430。</p>
     */
    @GET
    @Path("/metadata")
    public Uni<ResponseResult<DocumentMetadataView>> getMetadata(
            @QueryParam("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("View document metadata request, documentId={}, locale={}", documentId, locale);
        return documentQueryService.getDocumentMetadata(documentId.trim(), locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 查看文档详情（聚合基础信息 + 知识属性 + 切片统计 + 审核历史）
     * <p>返回文档全量信息：基础信息（含切片数 chunkCount）、全部标签（tags 列表 +
     * document_metadata key-value）、审核历史（review_history 按时间倒序）。
     * version 可选（缺省=当前版文档级详情）：传历史版返回版本级详情
     * （fileSize/时间/版本号取自 document_versions；下线版本返回 A0508「文档已归档/已废弃」）；
     * 传当前版且版本表无该行时按文档级（documents 行）返回。文档不存在 → A0430。</p>
     */
    @GET
    @Path("/detail")
    public Uni<ResponseResult<DocumentDetailDTO>> getDetail(
            @QueryParam("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId,
            @QueryParam("version") String version) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("View document detail request, documentId={}, version={}, locale={}", documentId, version, locale);
        return documentQueryService.getDocumentDetail(documentId.trim(), version, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 文档版本列表（版本对比步骤 3：版本选择下拉，不使用路径参数，QueryParam 风格与 detail/metadata 一致）
     * <p>返回当前版（isCurrent=true）+ 全部历史版（document_versions，归档时间倒序），
     * 含状态（ARCHIVED/DEPRECATED 照实返回，前端可显示"已下线"标签并自行决定是否可选）。
     * 文档不存在 / 孤儿文档 → A0430。</p>
     */
    @GET
    @Path("/versions")
    @io.quarkus.hibernate.reactive.panache.common.WithSession
    public Uni<ResponseResult<List<DocumentVersionOption>>> listVersions(
            @QueryParam("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Query document version list request, documentId={}, locale={}", documentId, locale);
        return documentVersionQueryService.listVersions(documentId.trim(), locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 版本内容差异对比（版本对比步骤 4）
     * <p>对同一文档的任意两个版本做「抽取为文本 → 行级 diff」：word/excel/pdf 等二进制格式
     * 不能像 git 一样字节 diff，复用文档内容抽取能力（PDFBox/zip+XML 直读/Tika）后行级对比；
     * 结构化 hunks 供前端渲染左右对照，patch 为统一 diff 字符串（原始视图，可选）。
     * 已下线版本允许参与对比（与 download 的 A0508 拦截不同）。
     * 版本不存在 → A0430；sourceVersion == targetVersion 合法（单版本文档回显场景）：
     * 返回该版本内容，全 SAME 行、无差异。
     * 抽取失败/无文本类型 → diffSupported=false + 原因，不阻塞接口。</p>
     */
    @POST
    @Path("/version-diff")
    @io.quarkus.hibernate.reactive.panache.common.WithSession
    public Uni<ResponseResult<VersionDiffResult>> versionDiff(
            @Valid @NotNull(message = "{app.common.body.notnull}") VersionDiffCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Version content diff request, documentId={}, sourceVersion={}, targetVersion={}, locale={}",
                command.documentId(), command.sourceVersion(), command.targetVersion(), locale);
        return versionDiffQueryService.diff(command.documentId().trim(),
                        command.sourceVersion(), command.targetVersion(), locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 提交审核：DRAFT → REVIEWING
     */
    @POST
    @Path("/submit-review")
    public Uni<ResponseResult<Void>> submitReview(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentIdCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Submit review request, documentId={}, operatorId={}, locale={}", command.documentId(), operatorId, locale);
        return documentCommandService.submitReview(operatorId, command.documentId().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 编辑者撤回审核：REVIEWING → DRAFT
     */
    @POST
    @Path("/cancel-review")
    public Uni<ResponseResult<Void>> cancelReview(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentIdCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Cancel review request, documentId={}, operatorId={}, locale={}", command.documentId(), operatorId, locale);
        return documentCommandService.cancelReview(operatorId, command.documentId().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 审核通过：REVIEWING → PUBLISHED
     */
    @POST
    @Path("/approve")
    public Uni<ResponseResult<Void>> approve(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentIdCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Approve review request, documentId={}, reviewer={}, locale={}", command.documentId(), operatorId, locale);
        return documentCommandService.approve(operatorId, command.documentId().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 审核驳回：REVIEWING → DRAFT（须填驳回原因）
     */
    @POST
    @Path("/reject")
    public Uni<ResponseResult<Void>> reject(
            @Valid @NotNull(message = "{app.common.body.notnull}") RejectDocumentCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Reject review request, documentId={}, reviewer={}, locale={}", command.documentId(), operatorId, locale);
        return documentCommandService.reject(operatorId, command.documentId().trim(), command.reason().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 批量审核：支持 KB / CATEGORY（递归子分类）/ DOCUMENT 三种范围之一，批量通过或驳回。
     * <p>处理逻辑同单个 approve/reject；非 REVIEWING 文档记为 SKIPPED 并返回明细，不影响其他文档。
     * 整批单一事务原子提交（approve 同时写 outbox 通知模型服务切片向量化）。</p>
     */
    @POST
    @Path("/batch-review")
    public Uni<ResponseResult<BatchReviewResponse>> batchReview(
            @Valid @NotNull(message = "{app.common.body.notnull}") BatchReviewCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Batch review request, scope={}, action={}, operatorId={}, locale={}",
                command.scope(), command.action(), operatorId, locale);
        return documentCommandService.batchReview(operatorId, command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 批量提交审核：支持 KB / CATEGORY（递归子分类）/ DOCUMENT 三种范围之一，批量提交审核（DRAFT → REVIEWING）。
     * <p>处理逻辑同单个 submit-review；非 DRAFT 文档记为 SKIPPED 并返回明细，不影响其他文档。
     * 整批单一事务原子提交；不发 outbox（提交审核不触发模型处理），仅写 reviewHistory。</p>
     */
    @POST
    @Path("/batch-submit-review")
    public Uni<ResponseResult<BatchSubmitReviewResponse>> batchSubmitReview(
            @Valid @NotNull(message = "{app.common.body.notnull}") BatchSubmitReviewCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Batch submit review request, scope={}, operatorId={}, locale={}",
                command.scope(), operatorId, locale);
        return documentCommandService.batchSubmitReview(operatorId, command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 文档编辑（title/author/tags/remark + 元数据全量替换 + 可选文件替换）
     * <p>与上传表单字段保持一致。生命周期约束：仅 DRAFT/PUBLISHED 允许编辑。
     * 文件替换仅 DRAFT 允许（同格式替换，processStatus→PENDING）；
     * PUBLISHED 文件替换走版本覆盖端点 POST /kb/{kbId}/documents/{documentId}/versions。</p>
     * <p>metadata JSON 的 null 语义：title=null=不改（特殊例外），
     * author/tags/remark/product 等 null=清空。整个 metadata JSON 不传=不改任何字段。</p>
     */
    @POST
    @Path("/edit")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> edit(
            @RestForm("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId,
            @RestForm("metadata") String metadataJson,
            @RestForm("file") FileUpload file) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 解析 metadata JSON（完全可选：省略或空串 → null，不改任何字段）
        EditDocumentCommand command = null;
        if (metadataJson != null && !metadataJson.isBlank()) {
            command = parseMetadataJson(metadataJson, EditDocumentCommand.class, "编辑", appMessages);
        }

        // 解析可选文件（不传 = null = 不替换文件，走原属性/元数据编辑）
        DocumentCommandService.FileInput fileInput = null;
        if (file != null) {
            try {
                byte[] content = Files.readAllBytes(file.uploadedFile());
                fileInput = new DocumentCommandService.FileInput(
                        file.fileName(), content, file.contentType());
            } catch (IOException e) {
                logger.error("Failed to read edit file: fileName={}, error={}", file.fileName(), e.getMessage());
                throw new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                        appMessages.fileCannotRead());
            }
        }

        logger.info("Document edit request, documentId={}, hasFile={}, operatorId={}, locale={}",
                documentId, fileInput != null, operatorId, locale);
        return documentVersionService.edit(operatorId, documentId.trim(), command, fileInput, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 归档（仅版本级）：将 document_versions 中指定版本行置 ARCHIVED，文档生命周期不变。
     * <p>version 必填；不允许归档整个文档（所有版本）。版本不存在（含指向当前版）→ A0430。</p>
     */
    @POST
    @Path("/archive")
    public Uni<ResponseResult<Void>> archive(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentVersionCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Archive request (version level), documentId={}, version={}, operatorId={}, locale={}",
                command.documentId(), command.version(), operatorId, locale);
        return documentCommandService.archive(operatorId, command.documentId().trim(),
                        command.version().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 废弃（仅版本级）：将 document_versions 中指定版本行置 DEPRECATED，文档生命周期不变。
     * <p>version 必填；不允许废弃整个文档（所有版本）。版本不存在（含指向当前版）→ A0430。</p>
     */
    @POST
    @Path("/deprecate")
    public Uni<ResponseResult<Void>> deprecate(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentVersionCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Deprecate request (version level), documentId={}, version={}, operatorId={}, locale={}",
                command.documentId(), command.version(), operatorId, locale);
        return documentCommandService.deprecate(operatorId, command.documentId().trim(),
                        command.version().trim(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 物理删除（<b>仅版本级</b>，version 必传）：移除 document_versions 指定版本行 + 文件 + 向量。
     * <p>传历史版本号：仅删版本行+该版本文件+该版本向量，文档本体不变；
     * 传当前版本号：删除当前版文件并级联删除 documents 行（document_versions 历史行保留，
     * current_version 随 documents 行消失）。</p>
     */
    @POST
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(
            @Valid @NotNull(message = "{app.common.body.notnull}") DeleteDocumentCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Physical delete request, documentId={}, version={}, operatorId={}, locale={}",
                command.documentId(), command.version(), operatorId, locale);
        return documentCommandService.delete(operatorId, command.documentId().trim(),
                        command.version(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 批量物理删除（仅版本级）：逐条复用单个版本级删除语义（移除指定版本行 + 文件回收 + 清向量），
     * 逐条独立执行（各自独立事务），单条失败不影响其余。
     * <p>防误操作护栏：不支持文档级批量删除，每个条目必须携带 version（缺 version → A0420 整批拒绝）。
     * 请求体 {@code items} 每条 {documentId, version} 经 trim 去空后要求 1~100 条，超限抛 A0420
     * （HTTP 层始终 200）。每条返回 DELETED（删除成功）/ SKIPPED（版本不存在 A0430）/
     * FAILED（执行异常）并附原因与错误码；整体恒为 code=0（部分失败也成功返回）。</p>
     */
    @POST
    @Path("/batch-delete")
    public Uni<ResponseResult<BatchDeleteResponse>> batchDelete(
            @Valid @NotNull(message = "{app.common.body.notnull}") BatchDeleteCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Batch delete request (version level), itemCount={}, operatorId={}, locale={}",
                command.items() == null ? 0 : command.items().size(), operatorId, locale);
        return documentCommandService.batchDelete(operatorId, command.items(), locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 下载文档文件（version 必传）
     * <p>传当前版本号 = 下载 documents.file_path 当前版文件；传历史版本号 = 下载
     * document_versions 中的版本行文件（版本行已下线 ARCHIVED/DEPRECATED → A0508）。
     * 全部生命周期状态均可下载。</p>
     * <p>md 文件（含历史版本）的相对图片引用会被重写为免登录签名 URL
     * （{@link MarkdownImageRewriter} + {@link DocumentAssetResource}），响应
     * Content-Type 为 {@code text/markdown; charset=utf-8}；其余文件经
     * {@link FileStorageService#resolveReadableFile} 以流式（chunked）返回，
     * 首字节时间与文件大小解耦，服务端不再整文件读入内存。</p>
     */
    @POST
    @Path("/download")
    @io.quarkus.hibernate.reactive.panache.common.WithSession
    public Uni<Response> download(
            @Valid @NotNull(message = "{app.common.body.notnull}") DownloadDocumentCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();
        logger.info("Download document request, documentId={}, version={}, operatorId={}, locale={}",
                command.documentId(), command.version(), operatorId, locale);

        return documentRepository.findById(DocumentId.of(command.documentId().trim()))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", command.documentId());
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> assertVisibleForDownload(entity, appMessages).replaceWith(entity))
                .flatMap(entity -> resolveVersion(entity, command.version(), locale))
                .flatMap(fileInfo -> {
                    // md 在线查看：需要全文重写图片引用，维持全量读取（md 为文本、体积小）；
                    // 其余文件流式返回（Path 实体由 RESTEasy Reactive chunked 边读边写，
                    // 首字节时间与文件大小解耦，服务端不再整文件进堆）
                    if (MarkdownImageRewriter.isMarkdownPath(fileInfo.filePath())) {
                        return fileStorageService.read(fileInfo.filePath())
                                .onFailure().transform(err -> {
                                    logger.error("Failed to read file: path={}, error={}", fileInfo.filePath(), err.getMessage());
                                    return new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                                            appMessages.fileCannotRead());
                                })
                                .map(bytes -> {
                                    // 相对图片引用重写为免登录签名 URL（MarkdownImageRewriter，
                                    // 签发/校验口径见 DocumentAssetResource）
                                    String rewritten = markdownImageRewriter.rewrite(
                                            command.documentId().trim(), fileInfo.filePath(), bytes);
                                    return Response.ok(rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                            .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=utf-8")
                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                    buildContentDisposition(fileInfo.title(), fileInfo.mimeType()))
                                            .build();
                                });
                    }
                    return fileStorageService.resolveReadableFile(fileInfo.filePath())
                            .onFailure().transform(err -> {
                                logger.error("Failed to resolve file for streaming: path={}, error={}",
                                        fileInfo.filePath(), err.getMessage());
                                return new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                                        appMessages.fileCannotRead());
                            })
                            .map(path -> (Response) Response.ok((Object) path)
                                    .header(HttpHeaders.CONTENT_TYPE, fileInfo.mimeType())
                                    .header(HttpHeaders.CONTENT_DISPOSITION,
                                            buildContentDisposition(fileInfo.title(), fileInfo.mimeType()))
                                    .build());
                });
    }

    /** 版本解析结果：文件相对路径 + MIME + 下载文件名 */
    private record DownloadFileInfo(String filePath, String mimeType, String title) {}

    /**
     * 下载可见性守卫：孤儿文档（categoryId 指向已删/不存在/跨库类别的历史存量）
     * 视同不存在，禁止下载——与列表/搜索口径一致（完全不可见）。命令路径不受影响。
     */
    private Uni<Void> assertVisibleForDownload(com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                               AppMessages appMessages) {
        if (entity.getCategoryId() == null) {
            return Uni.createFrom().voidItem();
        }
        return categoryPanacheRepository.count("id = ?1 and kbId = ?2 and status = 1",
                        entity.getCategoryId(), entity.getKbId())
                .flatMap(c -> {
                    if (c > 0) {
                        return Uni.createFrom().voidItem();
                    }
                    logger.error("Orphan document cannot be downloaded: documentId={}, categoryId={}",
                            entity.getId(), entity.getCategoryId());
                    return Uni.createFrom().failure(new DocumentNotFoundException(appMessages.documentNotFound()));
                });
    }

    /**
     * 解析下载目标：version 缺省或 == currentVersion 用当前版（documents 行）；否则查
     * document_versions 版本行。版本行已下线（ARCHIVED/DEPRECATED）→ A0508「文档已归档/已废弃」
     * （与 detail 同文案同源）；版本不存在 → A0430。缺省兜底供问答参考文档等
     * 无法保证携带版本的调用方使用（存量历史消息、解析未命中的 docs 项）。
     */
    private Uni<DownloadFileInfo> resolveVersion(com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                                 String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String target = version == null ? null : version.trim();
        if (target == null || target.isBlank() || target.equals(entity.getCurrentVersion())) {
            // 当前版（含缺省兜底）：文件取 documents 行（版本表无该行时的正常态）
            return Uni.createFrom().item(new DownloadFileInfo(
                    entity.getFilePath(), entity.getMimeType(), entity.getTitle()));
        }
        return documentRepository.findVersion(entity.getId().value(), target)
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document version not found: documentId={}, version={}", entity.getId().value(), target);
                    return new DocumentNotFoundException(appMessages.documentVersionNotFound());
                })
                .flatMap(v -> {
                    String status = v.status() != null ? v.status()
                            : com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion.STATUS_PUBLISHED;
                    if (!com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion
                            .STATUS_PUBLISHED.equals(status)) {
                        String message = com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion
                                .STATUS_ARCHIVED.equals(status)
                                ? appMessages.versionArchived() : appMessages.versionDeprecated();
                        logger.warn("Version is offline, download rejected: documentId={}, version={}, status={}",
                                entity.getId().value(), version, status);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0508, message));
                    }
                    return Uni.createFrom().item(new DownloadFileInfo(v.filePath(), v.mimeType(), entity.getTitle()));
                });
    }

    /** RFC 5987 文件名编码（支持中文）：attachment; filename*=UTF-8''{encode} */
    private String buildContentDisposition(String title, String mimeType) {
        // URLEncoder.encode(String, Charset)（Java 10+）不抛受检异常，无需兜底 catch
        String encoded = java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    /**
     * 上传文档（支持普通文件 + ZIP 压缩包混传，支持元数据）
     * <p>ZIP 文件自动解压，包内目录层级映射为知识库类别；同名文件按状态处理：
     * DRAFT→覆盖、REVIEWING/PUBLISHED/ARCHIVED→跳过、DEPRECATED→新建。
     * 元数据通过 {@code metadata} JSON 字段传入。</p>
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<UploadDocumentResponse>> upload(
            @RestForm("files") @NotNull(message = "{app.common.file.notblank}") List<FileUpload> files,
            @RestForm("kbId") @NotBlank(message = "{app.kb.id.notblank}") String kbId,
            @RestForm("categoryId") String categoryId,
            @RestForm("metadata") String metadataJson,
            @RestForm("autoConflictDetection") Boolean autoConflictDetection) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 解析 metadata JSON（完全可选：省略或空串 → null，无任何副作用）
        UploadDocumentCommand metadata = null;
        if (metadataJson != null && !metadataJson.isBlank()) {
            metadata = parseMetadataJson(metadataJson, UploadDocumentCommand.class, "上传", appMessages);
        }

        // 校验文件列表非空（kbId 由 @NotBlank + Bean Validation 保证）
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME,
                    appMessages.fileCannotEmpty());
        }

        // 将 FileUpload 转换为 FileInput
        List<DocumentCommandService.FileInput> fileInputs = new ArrayList<>();
        for (FileUpload upload : files) {
            try {
                byte[] content = Files.readAllBytes(upload.uploadedFile());
                fileInputs.add(new DocumentCommandService.FileInput(
                        upload.fileName(), content, upload.contentType()));
            } catch (IOException e) {
                logger.error("Failed to read upload file: fileName={}, error={}", upload.fileName(), e.getMessage());
                throw new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                        appMessages.fileCannotRead());
            }
        }

        logger.info("Upload document request, kbId={}, categoryId={}, fileCount={}, operatorId={}, locale={}",
                kbId, categoryId, fileInputs.size(), operatorId, locale);

        return documentImportService.upload(new DocumentImportService.UploadRequest(
                        operatorId, kbId.trim(), categoryId, fileInputs, metadata, locale))
                .map(result -> {
                    // 任一失败 → 整体非 0 code + message（取首个 failed）；
                    // 否则（全成功 / 含跳过）整体成功 code=0——不支持类型的跳过是警告非错误，
                    // 详情见 results[].reason/code + summary.skipped
                    var failed = result.getResults().stream()
                            .filter(r -> "FAILED".equals(r.getStatus())).findFirst();
                    if (failed.isPresent()) {
                        FileUploadResult f = failed.get();
                        return ResponseResult.<UploadDocumentResponse>builder()
                                .code(f.getCode())
                                .data(result)
                                .message(f.getReason())
                                .build();
                    }
                    return ResponseResult.ok(result, locale);
                })
                // 上传成功后旁路同步模型端 auto_switch（best-effort，失败不影响上传返回）
                .chain(resp -> conflictAutoSwitchSyncService.syncOnUpload(kbId.trim(), operatorId, autoConflictDetection)
                        .replaceWith(resp));
    }

    /**
     * 版本覆盖：已发布文档上传新版本（仅 PUBLISHED 允许）
     * <p>旧版归档 document_versions（was_published=TRUE），新版本进入 DRAFT 重新审核。</p>
     *
     * @param kbId       知识库 ID
     * @param documentId 文档 ID
     * @param file       新版本文件（单文件）
     */
    @POST
    @Path("/versions")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<VersionOverrideResult>> overrideVersion(
            @RestForm("kbId") @NotBlank(message = "{app.kb.id.notblank}") String kbId,
            @RestForm("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId,
            @RestForm("file") FileUpload file,
            @RestForm("metadata") String metadataJson) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 解析 metadata JSON（版本覆盖与上传一致，使用 UploadDocumentCommand）
        UploadDocumentCommand metadata = null;
        if (metadataJson != null && !metadataJson.isBlank()) {
            metadata = parseMetadataJson(metadataJson, UploadDocumentCommand.class, "版本覆盖", appMessages);
        }

        // 校验文件非空
        if (file == null) {
            throw new BusinessException(ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME,
                    appMessages.fileCannotEmpty());
        }

        DocumentCommandService.FileInput fileInput;
        try {
            byte[] content = Files.readAllBytes(file.uploadedFile());
            fileInput = new DocumentCommandService.FileInput(
                    file.fileName(), content, file.contentType());
        } catch (IOException e) {
            logger.error("Failed to read version file: fileName={}, error={}", file.fileName(), e.getMessage());
            throw new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                    appMessages.fileCannotEmpty());
        }

        logger.info("Version override request, kbId={}, documentId={}, fileName={}, operatorId={}, locale={}",
                kbId, documentId, file.fileName(), operatorId, locale);

        return documentVersionService.overrideVersion(new DocumentVersionService.OverrideRequest(
                operatorId, kbId.trim(), documentId, fileInput, metadata, locale))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 断点发现：查询当前用户在目标文档上的活跃 tus 会话（DOC_EDIT / VERSION_OVERRIDE）。
     * <p>前端进入编辑/版本覆盖弹窗时调用：有活跃会话 → 持 sessionId 经 tus HEAD 查断点续传
     * （刷新/换设备不丢上传进度）；无 → data=null，正常新建会话。仅返回本人会话（他人不可见）。</p>
     */
    @GET
    @Path("/{documentId}/upload-session")
    public Uni<ResponseResult<DocumentUploadSession>> activeUploadSession(
            @PathParam("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        return uploadSessionRepository.findActiveByTargetDocument(documentId.trim(), operatorId)
                .map(sessions -> {
                    if (sessions == null || sessions.isEmpty()) {
                        return ResponseResult.<DocumentUploadSession>ok(null, locale);
                    }
                    UploadSession s = sessions.getFirst();
                    if (sessions.size() > 1) {
                        logger.warn("Multiple active sessions found for target document (using earliest created): documentId={}, count={}",
                                documentId, sessions.size());
                    }
                    return ResponseResult.ok(new DocumentUploadSession(
                            s.getId().value(), s.getPurpose().name(), s.getFileName(),
                            s.getUploadOffset(), s.getUploadLength(), s.getExpiresAt()), locale);
                });
    }

    /** 断点发现响应体：活跃会话关键信息（sessionId 供 tus HEAD/PATCH 续传） */
    public record DocumentUploadSession(String sessionId, String purpose, String fileName,
                                        long uploadOffset, long uploadLength,
                                        LocalDateTime expiresAt) {
    }

    /**
     * 解析 metadata JSON（upload/edit/versions 三处统一复用）
     * <p>解析失败时提取 Jackson 异常的字段名（如未识别字段 title），日志结构化打印 field=，
     * 返回带字段名的业务异常（A0400）便于前端/排查定位。保留对未知字段的严格检测
     * （未关 FAIL_ON_UNKNOWN_PROPERTIES），仅接受已知字段，其余字段名拼错仍精确报错。</p>
     */
    private static <T> T parseMetadataJson(String json, Class<T> type, String scene, AppMessages appMessages) {
        try {
            return METADATA_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            String field = extractMetadataFieldName(e);
            logger.error("{} metadata JSON parse failed: field={}, error={}", scene, field, e.getOriginalMessage());
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400,
                    appMessages.metadataFieldUnrecognized(field));
        }
    }

    /** 从 Jackson 异常提取出错的字段名；提取不到回退 "unknown"（tus 侧 metadata 解析复用） */
    static String extractMetadataFieldName(JsonProcessingException e) {
        if (e instanceof UnrecognizedPropertyException upe) {
            return upe.getPropertyName();
        }
        if (e instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String name = jme.getPath().getLast().getFieldName();
            if (name != null) {
                return name;
            }
        }
        return "unknown";
    }
}
