package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.i18n.validation.FieldNameResolver;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentContentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentContentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentMetadataPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentNameOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileOptionNode;
import com.hisilicon.knowledge.platform.knowledge.query.dto.GlobalDocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.MetadataOptionGroup;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 文档全局搜索查询服务（CQRS 查询侧，跨知识库）
 * <p>与 {@link DocumentQueryService} 并存：后者按 {@code kbId} 限定单知识库范围，
 * 本服务面向"按文档分类/名称/芯片型号检索相关文档"的跨库检索场景。默认仅检索
 * {@code status=PUBLISHED} 且所属知识库活跃（{@code kb.status=1}）的文档。</p>
 * <p>芯片型号匹配口径：对应<b>知识库名称</b>（KB 以芯片型号命名，如 CV610），大小写不敏感，
 * 多选 OR；不查 {@code document_metadata.chip_model}。全局芯片下拉
 * {@link #getGlobalMetadataOptions} 同口径返回活跃且有已发布文档的 KB 名称。</p>
 * <p>查询模式与 {@code DocumentQueryService.listDocuments} 一致：先分页查文档页，
 * 再批量补元数据 + 出处（KB/类别）名，避免 N+1。实体查询全部串行（同一 Mutiny.Session
 * 上并发实体查询会破坏 LoadContexts）。</p>
 * <p>辅助逻辑（MIME 映射/元数据分组/元数据扩充）与 {@code DocumentQueryService}
 * 保持一致，按方案 B 本服务内自持副本，不改动既有已测服务。</p>
 */
@ApplicationScoped
public class DocumentGlobalSearchQueryService {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 模型处理完成：检索口径要求已发布且向量化完成（含 title 命中） */
    private static final String PROCESS_COMPLETED = "COMPLETED";
    /** 芯片型号下拉/过滤对外的 metaKey 标识（历史命名，实际语义 = 知识库名称即芯片型号） */
    private static final String META_KEY_CHIP_MODEL = "chip_model";
    private static final Short KB_STATUS_ACTIVE = 1;

    /** 文档名称下拉选项返回上限：命中超过此数即提示用户输入更准确名称，不再返回数据 */
    private static final int MAX_NAME_OPTIONS = 50;

    private final DocumentPanacheRepository documentPanacheRepository;
    private final DocumentContentPanacheRepository documentContentPanacheRepository;
    private final DocumentMetadataPanacheRepository documentMetadataPanacheRepository;
    private final KnowledgeBasePanacheRepository knowledgeBasePanacheRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final FieldNameResolver fieldNameResolver;

    public DocumentGlobalSearchQueryService(DocumentPanacheRepository documentPanacheRepository,
                                            DocumentContentPanacheRepository documentContentPanacheRepository,
                                            DocumentMetadataPanacheRepository documentMetadataPanacheRepository,
                                            KnowledgeBasePanacheRepository knowledgeBasePanacheRepository,
                                            CategoryPanacheRepository categoryPanacheRepository,
                                            FieldNameResolver fieldNameResolver) {
        this.documentPanacheRepository = documentPanacheRepository;
        this.documentContentPanacheRepository = documentContentPanacheRepository;
        this.documentMetadataPanacheRepository = documentMetadataPanacheRepository;
        this.knowledgeBasePanacheRepository = knowledgeBasePanacheRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.fieldNameResolver = fieldNameResolver;
    }

    /** 全局搜索 WHERE 构建产物（HQL + 参数 + 关键词 + 生效匹配域） */
    private record GlobalSearchFilter(String hql, Map<String, Object> params,
                                      String keyword, String effectiveSearchField) {
    }

    /**
     * 跨知识库分页搜索文档（按 docType 多选 + 关键词 + 芯片型号多选 过滤）
     * <p>检索口径：{@code status=PUBLISHED 且 processStatus=COMPLETED}（已发布且向量化完成）。</p>
     *
     * @param query 查询条件（关键词/类型/芯片型号/匹配域/排序/分页，见 {@link GlobalSearchQuery}）
     * @return 分页文档列表（每条携带完整 metadata Map + 出处 kbId/kbName/categoryId/categoryName；
     *         内容命中时 content=首个关键词片段，其余为 null）
     */
    @WithSession
    public Uni<PageResult<GlobalDocumentListItem>> globalSearch(GlobalSearchQuery query) {
        GlobalSearchFilter filter = buildFilter(query);

        var panacheQuery = documentPanacheRepository.find(filter.hql(), filter.params());
        // list()（实体查询）与 count() 必须串行：同一 session 上并发实体查询破坏 LoadContexts
        return panacheQuery.page(query.page() - 1, query.pageSize()).list()
                .flatMap(list -> panacheQuery.count()
                        .flatMap(total -> {
                            if (list.isEmpty()) {
                                return Uni.createFrom().item(new PageResult<>(query.page(),
                                        query.pageSize(), 0, total, List.of()));
                            }
                            return assemblePage(list, total, filter, query);
                        }));
    }

    /**
     * 构建全局搜索 WHERE 条件：PUBLISHED+COMPLETED 口径、活跃 KB（芯片型号=KB 名称匹配）、
     * 孤儿排除、关键词匹配域（title/content/all）、docType 多选与排序。
     */
    private GlobalSearchFilter buildFilter(GlobalSearchQuery query) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder hql = new StringBuilder("from DocumentPO d where d.status = :status")
                .append(" and d.processStatus = :processStatus");
        params.put("status", STATUS_PUBLISHED);
        params.put("processStatus", PROCESS_COMPLETED);
        // 排除已删知识库（kb.status=1）；芯片型号过滤=知识库名称匹配（大小写不敏感），并入同一 exists 子句
        hql.append(" and exists (select 1 from KnowledgeBasePO k")
                .append(" where k.id = d.kbId and k.status = :kbStatus");
        params.put("kbStatus", KB_STATUS_ACTIVE);
        List<String> chipNames = cleanChipNames(query.chipModels());
        if (!chipNames.isEmpty()) {
            hql.append(" and lower(k.name) in :chipNames");
            params.put("chipNames", chipNames);
        }
        hql.append(")");
        // 排除孤儿文档（categoryId 指向已删/不存在/跨库类别的历史存量）：
        // 与 navTree/documentCount、单库搜索口径一致，仅根目录 + 活跃类别子树下的文档可检索
        hql.append(" and ").append(DocumentPanacheRepository.VISIBLE_IN_KB);
        String keyword = (query.docName() != null && !query.docName().isBlank())
                ? query.docName().toLowerCase(Locale.ROOT) : null;
        String searchField = query.searchField();
        if (keyword != null) {
            params.put("docName", "%" + keyword + "%");
            // 内容命中条件限定 READY + filePath 等值（= 审核通过的最新版的抽取文本）
            String contentExists = "exists (select 1 from DocumentContentPO c"
                    + " where c.documentId = d.id and c.contentStatus = 'READY'"
                    + " and c.filePath = d.filePath and c.contentText ilike :docName)";
            boolean byTitle = !"content".equals(searchField);
            boolean byContent = "all".equals(searchField) || "content".equals(searchField);
            if (byTitle && byContent) {
                // all：标题或内容（OR）
                hql.append(" and (lower(d.title) like :docName or ").append(contentExists).append(")");
            } else if (byContent) {
                hql.append(" and ").append(contentExists);
            } else {
                // title：维持原口径
                hql.append(" and lower(d.title) like :docName");
            }
        } else {
            // 无关键词：content 模式无匹配依据，退化为 title 口径（无过滤），不取片段
            searchField = "title";
        }
        if (query.docTypes() != null && !query.docTypes().isEmpty()) {
            hql.append(" and d.docType in :docTypes");
            params.put("docTypes", query.docTypes());
        }
        appendGlobalOrderBy(hql, query.sortField());
        return new GlobalSearchFilter(hql.toString(), params, keyword, searchField);
    }

    /** 命中页组装：批量元数据（一次 IN 查询，避免 N+1）→ 批量 KB 名 → 批量类别名 → 批量内容片段，全部串行 */
    private Uni<PageResult<GlobalDocumentListItem>> assemblePage(List<DocumentPO> list, long total,
                                                                 GlobalSearchFilter filter,
                                                                 GlobalSearchQuery query) {
        List<String> docIds = list.stream().map(po -> po.getId()).toList();
        return documentMetadataPanacheRepository
                .find("documentId in ?1", docIds).list()
                .flatMap(metas -> {
                    Map<String, Map<String, String>> metadataByDoc = groupMetadata(metas);
                    return loadKbNames(list)
                            .flatMap(kbNameById -> loadCategoryNames(list)
                                    .flatMap(catNameById -> loadSnippets(
                                            // 无关键词时 buildFilter 已退化为 title，不取片段
                                            filter.effectiveSearchField(), filter.keyword(), list)
                                            .map(snippetByDoc -> {
                                                List<GlobalDocumentListItem> items = list.stream()
                                                        .map(po -> toGlobalItem(po,
                                                                metadataByDoc.getOrDefault(po.getId(), Map.of()),
                                                                kbNameById.get(po.getKbId()),
                                                                po.getCategoryId() == null
                                                                        ? null
                                                                        : catNameById.get(po.getCategoryId()),
                                                                snippetByDoc.get(po.getId())))
                                                        .toList();
                                                long totalPage = (total + query.pageSize() - 1) / query.pageSize();
                                                return new PageResult<>(query.page(), query.pageSize(),
                                                        totalPage, total, items);
                                            })));
                });
    }

    /** 内容命中片段加载上限口径：仅取 READY 且 filePath 与 documents.file_path 等值的行 */
    private Uni<Map<String, String>> loadSnippets(String searchField, String keyword, List<DocumentPO> docs) {
        if ("title".equals(searchField) || keyword == null) {
            return Uni.createFrom().item(Map.of());
        }
        List<String> docIds = docs.stream().map(po -> po.getId()).toList();
        return documentContentPanacheRepository
                .find("documentId in ?1 and contentStatus = ?2", docIds,
                        DocumentContentIndexService.STATUS_READY).list()
                .map(contents -> {
                    Map<String, String> byDoc = new HashMap<>();
                    Map<String, String> pathByDoc = docs.stream()
                            .collect(Collectors.toMap(po -> po.getId(), po -> po.getFilePath(), (a, b) -> a));
                    for (DocumentContentPO c : contents) {
                        // filePath 等值校验（与查询命中条件一致；不等值 = 抽取滞后/旧版本，无片段）
                        if (!java.util.Objects.equals(c.getFilePath(), pathByDoc.get(c.getDocumentId()))) {
                            continue;
                        }
                        String snippet = DocumentContentIndexService.buildSnippet(c.getContentText(), keyword).orElse(null);
                        if (snippet != null) {
                            byDoc.put(c.getDocumentId(), snippet);
                        }
                    }
                    return byDoc;
                });
    }

    /**
     * 按知识库或目录（CATEGORY 递归子目录）+ 可选版本查询已发布文档的下拉选项。
     * <p>仅检索 {@code status=PUBLISHED} 且所属知识库活跃（{@code kb.status=1}）的文档。
     * nodeType=KB 时查整个知识库下全部已发布文档（含根目录与所有子目录）；
     * nodeType=CATEGORY 时递归收集 nodeId 及全部后代活跃类别下的文档。
     * version 非空时按 {@code currentVersion} 精确过滤。先查命中条数：
     * 超过 {@value #MAX_NAME_OPTIONS} 条抛 {@link BusinessException}（A0434，
     * 提示缩小查询范围，不返回数据），否则返回 id+title 列表
     * （上限 {@value #MAX_NAME_OPTIONS}，按创建时间倒序）。</p>
     *
     * @param nodeType 节点类型：{@link NavTreeItem#TYPE_KB} / {@link NavTreeItem#TYPE_CATEGORY}（必填，非法抛 A0420）
     * @param nodeId   节点 ID（KB ID 或类别 ID）
     * @param version  版本号（null/空白=不限，按 documents.current_version 精确匹配）
     * @param locale   语言环境（用于超量提示消息 i18n）
     * @return 文档 id+title 下拉选项列表（命中超量时抛异常，不返回）
     */
    @WithSession
    public Uni<List<DocumentNameOption>> nameOptions(String nodeType, String nodeId,
                                                     String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String type = nodeType == null ? null : nodeType.trim();
        if (!NavTreeItem.TYPE_KB.equals(type) && !NavTreeItem.TYPE_CATEGORY.equals(type)) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }
        String id = nodeId == null ? null : nodeId.trim();
        String ver = version == null || version.isBlank() ? null : version.trim();

        Map<String, Object> params = new HashMap<>();
        params.put("status", STATUS_PUBLISHED);
        params.put("kbStatus", KB_STATUS_ACTIVE);
        StringBuilder query = new StringBuilder("from DocumentPO d where d.status = :status")
                .append(" and exists (select 1 from KnowledgeBasePO k")
                .append(" where k.id = d.kbId and k.status = :kbStatus)");

        if (NavTreeItem.TYPE_KB.equals(type)) {
            query.append(" and d.kbId = :nodeId")
                    // KB 范围：排除孤儿文档，与 navTree 口径一致（根目录 + 活跃类别子树）
                    .append(" and ").append(DocumentPanacheRepository.VISIBLE_IN_KB);
            params.put("nodeId", id);
            return runNameOptionsQuery(query, params, ver, appMessages);
        }
        // CATEGORY：先取该类别（校验存在/活跃 + 拿 kbId），再递归收集子树，最后查文档。
        // 实体查询全程串行：同一 Mutiny.Session 上并发实体查询破坏 LoadContexts（同 globalSearch）。
        return categoryPanacheRepository.findById(id)
                .flatMap(cat -> {
                    if (cat == null || !KB_STATUS_ACTIVE.equals(cat.getStatus())) {
                        // 类别不存在或已软删（视同不存在）→ 空列表
                        return Uni.createFrom().item(List.<DocumentNameOption>of());
                    }
                    return categoryPanacheRepository.find("kbId = ?1 and status = 1", cat.getKbId()).list()
                            .flatMap(all -> {
                                Set<String> targetIds = collectSubtreeIncludingRoot(all, id);
                                query.append(" and d.categoryId in :categoryIds");
                                params.put("categoryIds", targetIds);
                                return runNameOptionsQuery(query, params, ver, appMessages);
                            });
                });
    }

    /**
     * 执行名称下拉查询：count → 超量抛异常 → 取前 {@value #MAX_NAME_OPTIONS} 条映射为 id+title。
     * count 与 list 串行（同一 Mutiny.Session 上并发实体查询破坏 LoadContexts）。
     * 超量直接失败提示，不再发 list 查询。
     */
    private Uni<List<DocumentNameOption>> runNameOptionsQuery(StringBuilder query, Map<String, Object> params,
                                                              String version, AppMessages appMessages) {
        if (version != null) {
            // 版本过滤同口径（与 DocumentQueryService version 特判一致）：当前版本 OR
            // document_versions 历史归档版本——版本覆盖后旧版本号仅存在于归档表，
            // 按「旧版保持可检索」设计，按旧版本查询下拉选项也须命中
            query.append(" and (d.currentVersion = :version")
                    .append(" or exists (select 1 from DocumentVersionPO v")
                    .append(" where v.documentId = d.id and v.versionNumber = :version))");
            params.put("version", version);
        }
        query.append(" order by d.createdAt desc");
        var panacheQuery = documentPanacheRepository.find(query.toString(), params);
        return panacheQuery.count()
                .flatMap(total -> {
                    if (total > MAX_NAME_OPTIONS) {
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.DOCUMENT_SEARCH_RESULT_TOO_MANY,
                                appMessages.documentSearchResultTooMany()));
                    }
                    return panacheQuery.page(0, MAX_NAME_OPTIONS).list()
                            .map(list -> list.stream()
                                    .map(po -> new DocumentNameOption(po.getId(), po.getTitle()))
                                    .toList());
                });
    }

    /**
     * 方向常量：文档→代码（只查 {@link DocumentEntity#DOC_TYPE_DOCUMENT}）
     */
    public static final String DIRECTION_DOC2CODE = "doc2code";
    /**
     * 方向常量：代码→文档（只查 {@link DocumentEntity#DOC_TYPE_CODE}）
     */
    public static final String DIRECTION_CODE2DOC = "code2doc";

    /**
     * 按方向搜索文件的树形下拉选项（代码↔文档映射功能：选择源文件，前端树状展示）。
     * <p><b>加载策略：一次性整树</b>（不做 parentId 懒加载）。理由：本接口已由
     * nodeType/nodeId 限定在单个 KB（或单个类别子树）内，无论懒加载与否都必须加载
     * 全部候选文档（docType 过滤、PUBLISHED 口径与 50 条护栏都依赖命中总数），
     * 懒加载只会增加往返次数而不减少任何查询；一次性返回整棵（过滤后的）树，
     * 前端直接渲染，无二级加载协议。前端对接：传 nodeType=KB + nodeId=kbId 得到以
     * 知识库为根的整棵树；传 nodeType=CATEGORY + nodeId=categoryId 得到以该类别为根的子树。</p>
     *
     * <p>树结构：类别节点 children 递归挂载（含文档叶子，空分支裁剪）；文档为
     * {@code nodeType=DOCUMENT} 的叶子（携带 docType/currentVersion）。节点排序：
     * 类别按 sortOrder 升序，文档按创建时间倒序。</p>
     *
     * <p>查询口径与护栏不变：仅 {@code status=PUBLISHED} 且所属知识库活跃
     * （{@code kb.status=1}）、孤儿文档不可见（仅根目录 + 活跃类别子树）；
     * direction=doc2code 只查 {@link DocumentEntity#DOC_TYPE_DOCUMENT}，
     * direction=code2doc 只查 {@link DocumentEntity#DOC_TYPE_CODE}；
     * keyword 按文档标题或存储文件名（含扩展名，取自 file_path 末段）模糊
     * （大小写不敏感，null/空白=不限），命中数（即命中文档数）
     * 超过 {@value #MAX_NAME_OPTIONS} 条抛 {@link BusinessException}（A0434）。
     * keyword 非空时返回过滤后的树：命中文档 + 其祖先类别链，未命中分支裁剪
     * （与 navTree 搜索的「命中+祖先链+裁剪兄弟」模式一致——本接口命中对象为文档叶子）。
     * DOCUMENT 叶子携带 fileName（如 {@code test.txt}）与 fileType（小写扩展名，
     * 如 {@code txt}，无扩展名为 null）。</p>
     *
     * @param direction 映射方向：{@link #DIRECTION_DOC2CODE} / {@link #DIRECTION_CODE2DOC}（必填，非法抛 A0420）
     * @param nodeType  节点类型：{@link NavTreeItem#TYPE_KB} / {@link NavTreeItem#TYPE_CATEGORY}（必填，非法抛 A0420）
     * @param nodeId    节点 ID（KB ID 或类别 ID）
     * @param keyword   文档标题/存储文件名关键词（null/空白=不限）
     * @param locale    语言环境（用于错误提示 i18n）
     * @return 单根树：KB 模式根为知识库节点；CATEGORY 模式根为该类别节点
     *         （类别不存在或已软删时返回 null）
     */
    @WithSession
    public Uni<FileOptionNode> fileOptions(String direction, String nodeType, String nodeId,
                                           String keyword, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String dir = direction == null ? null : direction.trim();
        String docType;
        if (DIRECTION_DOC2CODE.equals(dir)) {
            docType = DocumentEntity.DOC_TYPE_DOCUMENT;
        } else if (DIRECTION_CODE2DOC.equals(dir)) {
            docType = DocumentEntity.DOC_TYPE_CODE;
        } else {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidDirectionValue()));
        }
        String type = nodeType == null ? null : nodeType.trim();
        if (!NavTreeItem.TYPE_KB.equals(type) && !NavTreeItem.TYPE_CATEGORY.equals(type)) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }
        String id = nodeId == null ? null : nodeId.trim();
        String name = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);

        if (NavTreeItem.TYPE_KB.equals(type)) {
            return knowledgeBasePanacheRepository.find("id = ?1 and status = 1", id).firstResult()
                    .flatMap(kb -> {
                        if (kb == null) {
                            // KB 不存在或已软删（视同不存在）→ null 根
                            return Uni.createFrom().nullItem();
                        }
                        // 串行：类别 → 文档（同一 Mutiny.Session 上并发实体查询破坏 LoadContexts）
                        return categoryPanacheRepository.find("kbId = ?1 and status = 1", id).list()
                                .flatMap(cats -> queryFileDocs(id, null, docType, name, appMessages)
                                        .map(docs -> buildKbRoot(kb, cats, docs)));
                    });
        }
        // CATEGORY：先取该类别（校验存在/活跃 + 拿 kbId），再取同 KB 全部活跃类别 + 子树文档。
        return categoryPanacheRepository.findById(id)
                .flatMap(cat -> {
                    if (cat == null || !KB_STATUS_ACTIVE.equals(cat.getStatus())) {
                        // 类别不存在或已软删（视同不存在）→ null 根
                        return Uni.createFrom().nullItem();
                    }
                    return categoryPanacheRepository.find("kbId = ?1 and status = 1", cat.getKbId()).list()
                            .flatMap(cats -> queryFileDocs(cat.getKbId(), collectSubtreeIncludingRoot(cats, id),
                                    docType, name, appMessages)
                                    .map(docs -> buildCategoryRoot(cat, cats, docs)));
                });
    }

    /**
     * 查询范围内命中文件（count 护栏 + 列表）。
     * <p>KB 活跃校验在调用方完成（KB 模式查 kb 时过滤 status=1；CATEGORY 模式校验类别活跃）。</p>
     *
     * @param kbId       知识库 ID
     * @param categoryIds 类别子树 ID 集合（null = 不限类别，即 KB 整库 + 孤儿排除）
     */
    private Uni<List<DocumentPO>> queryFileDocs(String kbId, Set<String> categoryIds,
                                                String docType, String name, AppMessages appMessages) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", STATUS_PUBLISHED);
        params.put("docType", docType);
        params.put("nodeId", kbId);
        StringBuilder query = new StringBuilder("from DocumentPO d where d.status = :status")
                .append(" and d.docType = :docType")
                .append(" and d.kbId = :nodeId")
                // 孤儿文档排除，与 navTree 口径一致（根目录 + 活跃类别子树）
                .append(" and ").append(DocumentPanacheRepository.VISIBLE_IN_KB);
        if (categoryIds != null) {
            query.append(" and d.categoryId in :categoryIds");
            params.put("categoryIds", categoryIds);
        }
        if (name != null) {
            // keyword 匹配标题或存储文件路径（含文件名与扩展名，如 test.txt / .txt）
            query.append(" and (lower(d.title) like :docName or lower(d.filePath) like :docName)");
            params.put("docName", "%" + name + "%");
        }
        query.append(" order by d.createdAt desc");
        var panacheQuery = documentPanacheRepository.find(query.toString(), params);
        return panacheQuery.count()
                .flatMap(total -> {
                    if (total > MAX_NAME_OPTIONS) {
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.DOCUMENT_SEARCH_RESULT_TOO_MANY,
                                appMessages.documentSearchResultTooMany()));
                    }
                    // count 与 list 串行（同一 Mutiny.Session 上并发实体查询破坏 LoadContexts）
                    return panacheQuery.page(0, MAX_NAME_OPTIONS).list();
                });
    }

    /** KB 根节点：children = 有命中的根类别（空分支裁剪）+ 根目录文档叶子 */
    private FileOptionNode buildKbRoot(KnowledgeBasePO kb, List<CategoryPO> cats, List<DocumentPO> docs) {
        return buildRoot(NavTreeItem.TYPE_KB, kb.getId(), kb.getName(), null, cats, docs);
    }

    /** 类别根节点：children = 有命中的子类别（空分支裁剪）+ 本类别文档叶子 */
    private FileOptionNode buildCategoryRoot(CategoryPO cat, List<CategoryPO> cats, List<DocumentPO> docs) {
        return buildRoot(NavTreeItem.TYPE_CATEGORY, cat.getId(), cat.getName(), cat.getKbId(), cats, docs);
    }

    private FileOptionNode buildRoot(String nodeType, String id, String title, String kbId,
                                     List<CategoryPO> cats, List<DocumentPO> docs) {
        Map<String, List<DocumentPO>> docsByCat = new HashMap<>();
        List<DocumentPO> rootDocs = new ArrayList<>();
        for (DocumentPO doc : docs) {
            if (doc.getCategoryId() == null) {
                rootDocs.add(doc);
            } else {
                docsByCat.computeIfAbsent(doc.getCategoryId(), k -> new ArrayList<>()).add(doc);
            }
        }
        Map<String, List<CategoryPO>> childrenByParent = buildFileChildrenMap(cats);
        Set<String> hitCategories = docsByCat.keySet();

        List<FileOptionNode> children = new ArrayList<>();
        List<CategoryPO> roots = childrenByParent.get(NavTreeItem.TYPE_KB.equals(nodeType) ? null : id);
        if (roots != null) {
            roots.sort(FILE_CATEGORY_ORDER);
            for (CategoryPO root : roots) {
                FileOptionNode node = buildCategoryNode(root, childrenByParent, docsByCat, hitCategories);
                if (node != null) {
                    children.add(node);
                }
            }
        }
        for (DocumentPO doc : rootDocs) {
            children.add(docLeaf(doc));
        }
        return new FileOptionNode(id, title, nodeType, null, null, null, null, children);
    }

    /**
     * 递归组装类别节点：子树无命中文档（含后代类别）时裁剪返回 null（空分支裁剪）。
     * 本类别命中文档作为叶子挂在 children 首位之前（文档按创建时间倒序已排序）。
     */
    private FileOptionNode buildCategoryNode(CategoryPO cat,
                                             Map<String, List<CategoryPO>> childrenByParent,
                                             Map<String, List<DocumentPO>> docsByCat,
                                             Set<String> hitCategories) {
        List<FileOptionNode> children = new ArrayList<>();
        List<CategoryPO> kids = childrenByParent.get(cat.getId());
        if (kids != null) {
            kids.sort(FILE_CATEGORY_ORDER);
            for (CategoryPO kid : kids) {
                FileOptionNode node = buildCategoryNode(kid, childrenByParent, docsByCat, hitCategories);
                if (node != null) {
                    children.add(node);
                }
            }
        }
        List<DocumentPO> catDocs = docsByCat.get(cat.getId());
        if (catDocs != null) {
            for (DocumentPO doc : catDocs) {
                children.add(docLeaf(doc));
            }
        }
        // 空分支裁剪：本类别及全部后代均无命中文档 → 不保留
        if (children.isEmpty() && !hitCategories.contains(cat.getId())) {
            return null;
        }
        return new FileOptionNode(cat.getId(), cat.getName(), NavTreeItem.TYPE_CATEGORY, null, null, null, null, children);
    }

    private static FileOptionNode docLeaf(DocumentPO doc) {
        String fileName = extractFileName(doc.getFilePath());
        return new FileOptionNode(doc.getId(), doc.getTitle(), FileOptionNode.NODE_DOCUMENT,
                doc.getDocType(), doc.getCurrentVersion(), fileName, extractFileType(fileName), List.of());
    }

    /** 存储路径末段文件名（如 {@code kb/dir/cat/test.txt} → {@code test.txt}；null/空安全） */
    private static String extractFileName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        String name = filePath;
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        return name.isBlank() ? null : name;
    }

    /** 文件名的小写扩展名（{@code test.txt} → {@code txt}；无扩展名/异常命名返回 null） */
    private static String extractFileType(String fileName) {
        if (fileName == null) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0 || idx == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(idx + 1).trim().toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? null : ext;
    }

    /** 类别 children 映射（parentId → 子类别列表；KB 根类别的 parentId 为 null） */
    private static Map<String, List<CategoryPO>> buildFileChildrenMap(List<CategoryPO> cats) {
        Map<String, List<CategoryPO>> childrenByParent = new HashMap<>();
        for (CategoryPO cat : cats) {
            childrenByParent.computeIfAbsent(cat.getParentId(), k -> new ArrayList<>()).add(cat);
        }
        return childrenByParent;
    }

    private static final java.util.Comparator<CategoryPO> FILE_CATEGORY_ORDER =
            java.util.Comparator.comparingInt(c -> c.getSortOrder() != null ? c.getSortOrder() : 0);

    /**
     * 全局元数据下拉选项（供芯片型号多选筛选下拉）
     * <p>数据源与 {@link #globalSearch} 的芯片过滤口径一致：返回<b>活跃知识库的名称</b>
     * （{@code status=1} 且至少有 1 条 {@code status=PUBLISHED} 文档），去重排序，
     * 单组输出 {@code metaKey=chip_model}、label 国际化。KB 以芯片型号命名，
     * 故 KB 名即芯片型号选项；不再聚合 document_metadata（历史脏值会导致选项与命中错位）。</p>
     *
     * @param locale 语言（用于 label）
     * @return 分组后的选项列表（无活跃知识库时返回空列表）
     */
    @WithSession
    public Uni<List<MetadataOptionGroup>> getGlobalMetadataOptions(Locale locale) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", STATUS_PUBLISHED);
        params.put("kbStatus", KB_STATUS_ACTIVE);
        String query = "from KnowledgeBasePO k where k.status = :kbStatus"
                + " and exists (select 1 from DocumentPO d"
                + " where d.kbId = k.id and d.status = :status"
                // 排除孤儿文档（与 globalSearch 口径一致）
                + " and " + DocumentPanacheRepository.VISIBLE_IN_KB + ")";
        return knowledgeBasePanacheRepository.find(query, params).list()
                .map(kbs -> {
                    TreeSet<String> names = new TreeSet<>();
                    for (KnowledgeBasePO kb : kbs) {
                        if (kb.getName() != null && !kb.getName().isBlank()) {
                            names.add(kb.getName());
                        }
                    }
                    if (names.isEmpty()) {
                        return List.<MetadataOptionGroup>of();
                    }
                    return List.of(new MetadataOptionGroup(
                            META_KEY_CHIP_MODEL,
                            fieldNameResolver.resolve(META_KEY_CHIP_MODEL, locale),
                            List.copyOf(names)));
                });
    }

    // ======================== 查询构建辅助 ========================

    /**
     * 追加全局搜索排序子句。
     * <p>sortField 取值：</p>
     * <ul>
     *   <li>{@code title}：标题升序（不区分大小写，与 docName 过滤口径一致），同标题按更新时间倒序</li>
     *   <li>{@code time} / {@code relevance}（已下线，兼容按时间处理）/ {@code null} / 空白 / 未知兜底：
     *       更新时间倒序</li>
     * </ul>
     * 末位追加 {@code d.id} 作最终稳定 tiebreaker，保证分页在 updatedAt/title 并列时顺序不抖动。
     */
    private static void appendGlobalOrderBy(StringBuilder query, String sortField) {
        if ("title".equals(sortField)) {
            query.append(" order by lower(d.title) asc, d.updatedAt desc, d.id");
        } else {
            // time / relevance（本期回退）/ null/空白/兜底 → 更新时间倒序
            query.append(" order by d.updatedAt desc, d.id");
        }
    }

    /**
     * 芯片型号入参清洗：trim、剔空白、转小写（与 KB 名称匹配口径一致：大小写不敏感）、去重保序。
     * <p>全为空白时返回空列表，调用方不追加过滤，避免空 IN 子句导致非法 HQL。</p>
     */
    private static List<String> cleanChipNames(List<String> chipModels) {
        if (chipModels == null || chipModels.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : chipModels) {
            if (raw == null) {
                continue;
            }
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) {
                seen.add(v);
            }
        }
        return List.copyOf(seen);
    }

    /** 按 documentId 分组元数据：documentId → (metaKey → metaValue) */
    private static Map<String, Map<String, String>> groupMetadata(List<DocumentMetadataPO> metas) {
        Map<String, Map<String, String>> byDoc = new HashMap<>();
        for (DocumentMetadataPO meta : metas) {
            byDoc.computeIfAbsent(meta.getDocumentId(), k -> new HashMap<>())
                    .put(meta.getMetaKey(), meta.getMetaValue());
        }
        return byDoc;
    }

    /**
     * 批量加载本页文档所属知识库名：kbId → kbName。KB 已删或不复存在时不出现于映射，
     * 调用方按 {@code get(kbId)} 取到 null 即为缺失。
     */
    private Uni<Map<String, String>> loadKbNames(List<DocumentPO> docs) {
        List<String> kbIds = docs.stream()
                .map(po -> po.getKbId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (kbIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return knowledgeBasePanacheRepository.find("id in ?1", kbIds).list()
                .map(kbs -> kbs.stream()
                        .collect(Collectors.toMap(k -> k.getId(), k -> k.getName(), (a, b) -> a)));
    }

    /**
     * 批量加载本页文档所属类别名：categoryId → categoryName。根目录文档（categoryId=null）
     * 不参与查询，调用方对 null categoryId 直接取 null。
     */
    private Uni<Map<String, String>> loadCategoryNames(List<DocumentPO> docs) {
        List<String> catIds = docs.stream()
                .map(po -> po.getCategoryId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (catIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return categoryPanacheRepository.find("id in ?1", catIds).list()
                .map(cats -> cats.stream()
                        .collect(Collectors.toMap(c -> c.getId(), c -> c.getName(), (a, b) -> a)));
    }

    // ======================== 组装辅助 ========================

    /**
     * 扩充 metadata Map：纳入上传 {@code UploadDocumentCommand} 的全部字段（与
     * {@link DocumentQueryService} 一致）。
     */
    private static Map<String, String> enrichMetadata(Map<String, String> documentMetadata, DocumentPO po) {
        Map<String, String> meta = new HashMap<>(documentMetadata);
        meta.put("title", po.getTitle());
        meta.put("author", po.getAuthor());
        meta.put("tag", po.getTags());
        meta.put("remark", po.getRemark());
        meta.put("version", po.getCurrentVersion());
        return meta;
    }

    /**
     * 组装全局搜索列表项；content=内容命中片段（title 命中/无片段为 null）
     */
    private static GlobalDocumentListItem toGlobalItem(DocumentPO po, Map<String, String> metadata,
                                                       String kbName, String categoryName,
                                                       String snippet) {
        return new GlobalDocumentListItem(
                po.getId(), po.getKbId(), kbName, po.getCategoryId(), categoryName,
                po.getTitle(),
                FileTypeUtil.fromPath(po.getFilePath()).orElse(null),
                po.getDocType(), po.getFileSize(), po.getStatus(), po.getProcessStatus(),
                po.getCreatedBy(), po.getCreatedAt(),
                po.getAuthor(), po.getRemark(), po.getCurrentVersion(),
                enrichMetadata(metadata, po),
                snippet, po.getUpdatedAt());
    }

    /**
     * 收集 rootId 及其全部后代类别 ID（含 root 自身）。
     * <p>基于同 KB 活跃类别列表在内存建 children 映射后 BFS，避免逐层查库。
     * 仿 {@link com.hisilicon.knowledge.platform.knowledge.domain.service.CategoryTreeService#collectSubtreeIds}
     * 但操作 {@link CategoryPO} 且含 root（查询文档需命中 root 目录下文档）。
     */
    private static Set<String> collectSubtreeIncludingRoot(List<CategoryPO> all, String rootId) {
        Map<String, List<String>> children = new HashMap<>();
        for (CategoryPO c : all) {
            if (c.getParentId() != null) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        Set<String> ids = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!ids.add(current)) {
                continue;
            }
            List<String> kids = children.get(current);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        return ids;
    }
}
