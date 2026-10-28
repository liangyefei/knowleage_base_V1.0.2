package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.CategoryCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.CreateKbCommand;
import com.hisilicon.knowledge.platform.knowledge.command.CreateNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.DeleteNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.KnowledgeBaseCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.MetadataCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.MoveCategoryCommand;
import com.hisilicon.knowledge.platform.knowledge.command.NodeCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.SetMetadataCommand;
import com.hisilicon.knowledge.platform.knowledge.command.RenameNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UpdateCategoryCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UpdateKbCommand;
import com.hisilicon.knowledge.platform.knowledge.query.CategoryQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.KbOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.KbSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.NavTreeQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.DictOptionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.CategoryListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchQuery;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NodeCreatedResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOptionGroup;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识库 REST 接口
 */
@Path("/kb")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KnowledgeBaseResource {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseResource.class);

    private final KnowledgeBaseCommandService commandService;
    private final KbSearchQueryService searchQueryService;
    private final KbOptionQueryService kbOptionQueryService;
    private final NavTreeQueryService navTreeQueryService;
    private final NodeCommandService nodeCommandService;
    private final CategoryCommandService categoryCommandService;
    private final CategoryQueryService categoryQueryService;
    private final MetadataCommandService metadataCommandService;
    private final DictOptionQueryService dictOptionQueryService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public KnowledgeBaseResource(KnowledgeBaseCommandService commandService,
                                 KbSearchQueryService searchQueryService,
                                 KbOptionQueryService kbOptionQueryService,
                                 NavTreeQueryService navTreeQueryService,
                                 NodeCommandService nodeCommandService,
                                 CategoryCommandService categoryCommandService,
                                 CategoryQueryService categoryQueryService,
                                 MetadataCommandService metadataCommandService,
                                 DictOptionQueryService dictOptionQueryService) {
        this.commandService = commandService;
        this.searchQueryService = searchQueryService;
        this.kbOptionQueryService = kbOptionQueryService;
        this.navTreeQueryService = navTreeQueryService;
        this.nodeCommandService = nodeCommandService;
        this.categoryCommandService = categoryCommandService;
        this.categoryQueryService = categoryQueryService;
        this.metadataCommandService = metadataCommandService;
        this.dictOptionQueryService = dictOptionQueryService;
    }

    @POST
    @Path("/createKb")
    public Uni<ResponseResult<Void>> createKb(@Valid @NotNull(message = "{app.common.body.notnull}") CreateKbCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 操作员 ID 由 AuthenticationFilter 鉴权后注入
        String operatorId = operatorIdHolder.get();

        logger.info("Create knowledge base request, name={}, operatorId={}, locale={}", command.kbName(), operatorId, locale);

        return commandService.create(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    @Deprecated
    @POST
    @Path("/updateKb")
    public Uni<ResponseResult<Void>> updateKb(@Valid @NotNull(message = "{app.common.body.notnull}") UpdateKbCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 操作员 ID 由 AuthenticationFilter 鉴权后注入
        String operatorId = operatorIdHolder.get();

        logger.info("Update knowledge base request, kbId={}, operatorId={}, locale={}", command.kbId(), operatorId, locale);

        return commandService.update(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    @Deprecated
    @POST
    @Path("/editCategory")
    public Uni<ResponseResult<Void>> editCategory(
            @Valid @NotNull(message = "{app.common.body.notnull}") UpdateCategoryCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 操作员 ID 由 AuthenticationFilter 鉴权后注入
        String operatorId = operatorIdHolder.get();

        logger.info("Edit category request, categoryId={}, operatorId={}, locale={}",
                command.categoryId(), operatorId, locale);

        return categoryCommandService.edit(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    @Deprecated
    @POST
    @Path("/moveCategory")
    public Uni<ResponseResult<Void>> moveCategory(
            @Valid @NotNull(message = "{app.common.body.notnull}") MoveCategoryCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 操作员 ID 由 AuthenticationFilter 鉴权后注入
        String operatorId = operatorIdHolder.get();

        logger.info("Move category request, categoryId={}, targetParentId={}, operatorId={}, locale={}",
                command.categoryId(), command.targetParentId(), operatorId, locale);

        return categoryCommandService.move(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    @Deprecated
    @GET
    @Path("/search")
    public Uni<ResponseResult<PageResult<KbSearchItem>>> search(
            @QueryParam("kbName") String kbName,
            @QueryParam("status") Short status,
            @QueryParam("createdBy") String createdBy,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 校验 status：不为空时只能为 0 或 1
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.invalidStatusValue());
        }

        // 校验 pageSize：最大为 1000
        if (pageSize != null && pageSize > 1000) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.invalidPageSizeValue());
        }

        KbSearchQuery query = new KbSearchQuery(kbName, status, createdBy, page, pageSize);
        logger.info("Knowledge base search request, kbName={}, status={}, createdBy={}, page={}, pageSize={}, locale={}",
                kbName, status, createdBy, page, pageSize, locale);

        return searchQueryService.search(query)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 导航树（左侧面板，懒加载 / 搜索，不分页）
     * <p>无 {@code parentId} → 返回全部活跃知识库 + 每个 KB 的根子类别（深度 = 1）；
     * 带 {@code parentId} → 返回该类别下的直接子类别（再下一层）。
     * 类别节点的子级由前端带 {@code parentId=当前节点id} 再次调用本接口加载，
     * 前端始终只依赖这一个接口。</p>
     * <p>{@code keyword} 非空 → 搜索模式：按名称模糊匹配（知识库 + 子类别，不区分大小写），
     * 返回过滤后的完整树（命中节点 + 祖先链 + 命中节点后代），未命中兄弟裁剪；
     * 与 {@code parentId} 同时传时在直接子级别中过滤。</p>
     * <p>documentCount/categoryCount 为该节点及可见后代的递归计数。</p>
     */
    @GET
    @Path("/navTree")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<List<NavTreeItem>>> navTree(
            @QueryParam("parentId") String parentId,
            @QueryParam("keyword") String keyword) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Query nav tree request, parentId={}, keyword={}, locale={}", parentId, keyword, locale);
        return navTreeQueryService.navTree(parentId, keyword)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 新建子文件夹（统一树节点操作）
     * <p>父节点为知识库 → 创建根类别；父节点为类别 → 创建子类别。
     * 返回新节点信息（nodeId/nodeType/name）供前端插入树结构。</p>
     */
    @POST
    @Path("/node/create")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<NodeCreatedResult>> createNode(
            @Valid @NotNull(message = "{app.common.body.notnull}") CreateNodeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Create sub folder request, parentId={}, parentNodeType={}, name={}, operatorId={}, locale={}",
                command.parentId(), command.parentNodeType(), command.name(), operatorId, locale);
        return nodeCommandService.create(operatorId, command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 重命名节点（统一树节点操作，仅改名称）
     * <p>不触碰其他字段：KB 的 description、类别的 sortOrder/inheritParent 保持原值。</p>
     */
    @POST
    @Path("/node/rename")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> renameNode(
            @Valid @NotNull(message = "{app.common.body.notnull}") RenameNodeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Rename node request, nodeId={}, nodeType={}, name={}, operatorId={}, locale={}",
                command.nodeId(), command.nodeType(), command.name(), operatorId, locale);
        return nodeCommandService.rename(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除节点（统一树节点操作）
     * <p>知识库节点 → 级联软删全部类别 + 发 KB_DELETED 事件；
     * 类别节点 → 级联软删全部后代。已删除/不存在 → A0430。</p>
     */
    @POST
    @Path("/node/delete")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> deleteNode(
            @Valid @NotNull(message = "{app.common.body.notnull}") DeleteNodeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Delete node request, nodeId={}, nodeType={}, operatorId={}, locale={}",
                command.nodeId(), command.nodeType(), operatorId, locale);
        return nodeCommandService.delete(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 设置元数据（统一元数据编辑接口）
     * <p>entityType = KB / CATEGORY / DOCUMENT，对指定实体的元数据执行全量替换：
     * 传入的 Map 完全覆盖已有元数据，不传的 key 被清除，空 Map {@code {}} 表示清空全部。
     * key/value 有格式校验（key 为字母开头的字母/数字/下划线且长度≤50，value 长度 1~200 且不含控制字符），
     * 防止直接调用接口的恶意请求。</p>
     */
    @POST
    @Path("/metadata/set")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> setMetadata(
            @Valid @NotNull(message = "{app.common.body.notnull}") SetMetadataCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Set metadata request, entityType={}, entityId={}, entries={}, operatorId={}, locale={}",
                command.entityType(), command.entityId(),
                command.metadata() != null ? command.metadata().size() : 0, operatorId, locale);
        return metadataCommandService.set(operatorId, command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 上传页字典下拉选项（一个接口返回 4 组）
     * <p>数据源 = 系统字典表（status=1），动态绑定：字典类型的 {@code controlledMetaKeys}
     * 与上传表单支持的元数据 key（product/chip_model/version/sensitivity）求交集。
     * 顺序固定；某 key 无管控字典时 options 为空列表。multiSelect=true 表示前端渲染多选，
     * 提交时多个值逗号 join 后写入 document_metadata（存储/检索链路已支持逗号多值）。</p>
     */
    @GET
    @Path("/metadata/dict-options")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<List<UploadDictOptionGroup>>> dictOptions() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Upload dict options request, locale={}", locale);
        return dictOptionQueryService.getUploadOptions(LocaleUtil.findLocale(locale))
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 可检索知识库下拉选项（无参）
     * <p>返回其下至少存在一篇「已发布且向量化完成」文档的活跃就绪知识库
     * （kb.status=1 且 kb.processStatus=COMPLETED，且 exists documents where
     * status=PUBLISHED and processStatus=COMPLETED），按 createdAt 升序。
     * 仅返回 kbId + kbName，供前端下拉选择。不做 ABAC 权限过滤
     * （与 navTree/search/dictOptions 一致）。</p>
     */
    @GET
    @Path("/options")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<List<KbOption>>> listOptions() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Query searchable knowledge base options request, locale={}", locale);
        return kbOptionQueryService.listOptions()
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 浏览子类别（扁平列表，不分页）
     * <p>parentId 为空 → 返回知识库根类别；非空 → 返回指定类别的直接子类别。
     * 返回列表按 sortOrder 升序排列，附带 hasChildren 标记。</p>
     */
    @GET
    @Path("/categories")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<List<CategoryListItem>>> listCategories(
            @QueryParam("kbId") @NotBlank(message = "{app.kb.id.notblank}") String kbId,
            @QueryParam("parentId") String parentId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Browse sub categories request, kbId={}, parentId={}, locale={}", kbId, parentId, locale);
        return categoryQueryService.listCategories(kbId.trim(), parentId)
                .map(result -> ResponseResult.ok(result, locale));
    }
}
