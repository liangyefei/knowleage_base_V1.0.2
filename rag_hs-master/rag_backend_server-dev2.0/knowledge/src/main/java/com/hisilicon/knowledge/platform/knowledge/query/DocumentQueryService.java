package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.i18n.validation.FieldNameResolver;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ReviewHistoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentMetadataPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ReviewHistoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentListItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentMetadataView;
import com.hisilicon.knowledge.platform.knowledge.query.dto.MetadataOptionGroup;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ReviewHistoryItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 文档查询服务（CQRS 查询侧，分页 + 过滤 + 元数据）
 */
@ApplicationScoped
public class DocumentQueryService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(DocumentQueryService.class);

    private final DocumentPanacheRepository documentPanacheRepository;
    private final DocumentMetadataPanacheRepository documentMetadataPanacheRepository;
    private final ReviewHistoryPanacheRepository reviewHistoryPanacheRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentVersionPanacheRepository
            documentVersionPanacheRepository;
    private final FieldNameResolver fieldNameResolver;

    /** 下拉选项白名单：仅展示用户可自由设置的元数据 key（ABAC 内部属性不展示） */
    private static final Set<String> METADATA_OPTION_WHITELIST =
            Set.of("chip_model", "version", "product");

    public DocumentQueryService(DocumentPanacheRepository documentPanacheRepository,
                                DocumentMetadataPanacheRepository documentMetadataPanacheRepository,
                                ReviewHistoryPanacheRepository reviewHistoryPanacheRepository,
                                CategoryPanacheRepository categoryPanacheRepository,
                                com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentVersionPanacheRepository
                                        documentVersionPanacheRepository,
                                FieldNameResolver fieldNameResolver) {
        this.documentPanacheRepository = documentPanacheRepository;
        this.documentMetadataPanacheRepository = documentMetadataPanacheRepository;
        this.reviewHistoryPanacheRepository = reviewHistoryPanacheRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.documentVersionPanacheRepository = documentVersionPanacheRepository;
        this.fieldNameResolver = fieldNameResolver;
    }

    /** 类别过滤模式 */
    private enum CategoryScope {
        /** 整库：仅根目录 + 活跃类别子树（排除孤儿文档，见 {@link DocumentPanacheRepository#VISIBLE_IN_KB}） */
        NONE,
        /** 根目录：category_id IS NULL */
        ROOT_ONLY,
        /** 指定类别直属：category_id = :categoryId */
        EXACT,
        /** 子树：category_id in (:categoryIds) */
        SUBTREE
    }

    /**
     * 分页查询知识库下指定类别内的文档（多选过滤 + 元数据筛选）
     *
     * @param query 查询条件（类别/递归/名称/类型/状态/元数据/分页，见 {@link ListDocumentsQuery}）
     * @return 分页文档列表（版本级结果行：每个版本一行，携带 version/isCurrentVersion 与完整 metadata Map；
     *         未传 version 过滤 = 当前版 + 全部历史版各一行；传 version = 仅保留命中版本的行）
     * <p>默认排序：一级按生命周期状态优先级（DRAFT→REVIEWING→PUBLISHED→ARCHIVED→DEPRECATED），
     * 二级同状态内按 updated_at 倒序（最新在前）。</p>
     * <p>整库范围（{@code includeDescendants=true} 且 categoryId 为空）只返回根目录与活跃类别
     * 子树下的文档——categoryId 指向已软删/不存在/跨库类别的孤儿文档被排除（历史存量数据，
     * 见 docs/sql/cleanup_orphan_documents.sql），与 navTree 的 documentCount 口径一致。</p>
     */
    @WithSession
    public Uni<PageResult<DocumentListItem>> listDocuments(ListDocumentsQuery query) {
        String catId = (query.categoryId() == null || query.categoryId().isBlank())
                ? null : query.categoryId().trim();
        ListDocumentsRequest request = new ListDocumentsRequest(query, catId);
        // 非递归（默认）：null = 根目录直属文档（category_id IS NULL）；非空 = 该类别直属文档。
        if (!query.includeDescendants()) {
            return doListDocuments(request.withScope(
                    catId == null ? CategoryScope.ROOT_ONLY : CategoryScope.EXACT, null));
        }
        // 递归：null = 整个知识库（不过滤）；非空 = 该类别及其全部后代（含自身）。
        if (catId == null) {
            return doListDocuments(request.withScope(CategoryScope.NONE, null));
        }
        return categoryPanacheRepository.find("kbId = ?1 and status = 1", query.kbId()).list()
                .map(all -> collectSubtreeIncludingRoot(all, catId))
                .flatMap(subtree -> doListDocuments(request.withScope(CategoryScope.SUBTREE, subtree)));
    }

    /** 解析后的单库文档列表请求（查询条件 + 类别过滤模式） */
    private record ListDocumentsRequest(ListDocumentsQuery query, String categoryId,
                                        CategoryScope scope, Set<String> subtreeIds) {
        ListDocumentsRequest(ListDocumentsQuery query, String categoryId) {
            this(query, categoryId, null, null);
        }

        ListDocumentsRequest withScope(CategoryScope scope, Set<String> subtreeIds) {
            return new ListDocumentsRequest(query, categoryId, scope, subtreeIds);
        }
    }

    /**
     * 执行文档分页查询（类别过滤已由 {@link #listDocuments} 解析为 {@link CategoryScope}，
     * HQL 条件经 {@link DocumentListQueryBuilder} 构建）。
     */
    private Uni<PageResult<DocumentListItem>> doListDocuments(ListDocumentsRequest req) {
        ListDocumentsQuery query = req.query();
        DocumentListQueryBuilder builder = new DocumentListQueryBuilder(query.kbId());
        builder.applyCategory(req.scope(), req.categoryId(), req.subtreeIds());
        builder.applyDocName(query.docName());
        builder.applyDocTypes(query.docTypes());
        builder.applyProcessStatuses(query.processStatuses());
        Set<String> versionFilter = builder.applyMetadata(query.metadata());

        var panacheQuery = documentPanacheRepository.find(builder.hql(), builder.params());
        // 版本级结果行：分页对象是「版本行」而非文档行——须先取全量命中文档（不带 limit），
        // 组装当前版+历史版行后再内存排序分页。KB 级文档量级可控（列表本就按 KB/类别过滤）；
        // 实体查询串行（同 session 并发实体查询破坏 LoadContexts，同原实现）。
        return panacheQuery.list()
                .flatMap(docs -> {
                    if (docs.isEmpty()) {
                        return Uni.createFrom().item(new PageResult<>(query.page(), query.pageSize(),
                                0, 0L, List.<DocumentListItem>of()));
                    }
                    List<String> docIds = docs.stream().map(po -> po.getId()).toList();
                    return documentVersionPanacheRepository.find("documentId in ?1", docIds).list()
                            .flatMap(vpos -> documentMetadataPanacheRepository
                                    .find("documentId in ?1", docIds).list()
                                    .map(metas -> pageVersionRows(new VersionRowContext(
                                            docs, vpos, groupMetadata(metas), versionFilter,
                                            query.statuses() == null ? null : Set.copyOf(query.statuses()),
                                            query.page(), query.pageSize()))));
                });
    }

    /**
     * 文档列表 HQL 构建器（query + params 打包的小对象，替代散落局部变量）。
     * <p>方法按过滤维度拆分（类别/名称/类型/处理状态/元数据），条件按调用顺序追加。</p>
     */
    private static final class DocumentListQueryBuilder {
        private final StringBuilder query = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();

        DocumentListQueryBuilder(String kbId) {
            query.append("from DocumentPO d where d.kbId = :kbId");
            params.put("kbId", kbId);
        }

        /** 类别过滤（scope 已由调用方解析，见 {@link CategoryScope}） */
        void applyCategory(CategoryScope scope, String exactId, Set<String> subtreeIds) {
            if (scope == CategoryScope.ROOT_ONLY) {
                query.append(" and d.categoryId is null");
            } else if (scope == CategoryScope.EXACT) {
                // 防御纵深：仅命中活跃类别（status=1）下的文档——类别已软删时返回空页，
                // 防止前端 stale categoryId 查到任何残留数据（正常情况下删除已级联清空文档）
                query.append(" and d.categoryId = :categoryId")
                        .append(" and ").append(DocumentPanacheRepository.ACTIVE_CATEGORY_EXISTS);
                params.put("categoryId", exactId);
            } else if (scope == CategoryScope.SUBTREE) {
                query.append(" and d.categoryId in :categoryIds");
                params.put("categoryIds", subtreeIds);
            } else {
                // NONE：整个知识库，仅根目录（categoryId is null）或活跃类别子树下的文档可见。
                // 排除孤儿文档（categoryId 指向已删/不存在/跨库类别的历史存量），
                // 与 navTree documentCount 口径一致，保证树计数与列表总数一致。
                query.append(" and ").append(DocumentPanacheRepository.VISIBLE_IN_KB);
            }
        }

        /** 文档名称模糊（大小写不敏感；null/空白 = 不加条件） */
        void applyDocName(String docName) {
            if (docName != null && !docName.isBlank()) {
                query.append(" and lower(d.title) like :docName");
                params.put("docName", "%" + docName.toLowerCase(Locale.ROOT) + "%");
            }
        }

        /** 文档类型多选（null/空 = 不加条件） */
        void applyDocTypes(List<String> docTypes) {
            if (docTypes != null && !docTypes.isEmpty()) {
                query.append(" and d.docType in :docTypes");
                params.put("docTypes", docTypes);
            }
        }

        /** 模型处理状态多选（文档级 DB 过滤；statuses 的版本级行过滤在 pageVersionRows 内存施加） */
        void applyProcessStatuses(List<String> processStatuses) {
            if (processStatuses != null && !processStatuses.isEmpty()) {
                query.append(" and d.processStatus in :processStatuses");
                params.put("processStatuses", processStatuses);
            }
        }

        /**
         * 元数据筛选：每个 meta_key 追加一个 EXISTS 子句（多 key 之间 AND）；
         * 同一 key 的值按逗号拆分为原子值，原子值之间 OR（精确 / 首段 / 末段 / 中段 四段匹配）。
         * 匹配规则与 DocumentGlobalSearchQueryService#appendChipModelExists 一致。
         *
         * @return version 过滤值集合（多值 OR，当前版本 OR 历史归档版本）；未传 version 过滤时为 null
         */
        Set<String> applyMetadata(Map<String, String> metadata) {
            Set<String> versionFilter = null;
            if (metadata == null) {
                return null;
            }
            int keyIdx = 0;
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                List<String> values = splitCsvValues(entry.getValue());
                if (values.isEmpty()) {
                    continue;
                }
                // version 由 documents.current_version 承载（不入 document_metadata），
                // 特判：当前版本 OR 历史归档版本（document_versions.version_number）任一命中——
                // 版本覆盖后 current_version 已推进为新版号，旧版号仅存在于 document_versions，
                // 按「旧版保持可检索」的设计，按历史版本过滤也须命中该文档；
                // 多值逗号分隔 → in (...) OR（与其它 meta key 多值 OR 语义一致）。
                if ("version".equals(entry.getKey())) {
                    StringBuilder inList = new StringBuilder();
                    int vi = 0;
                    List<String> matched = new ArrayList<>();
                    for (String v : values) {
                        String p = "verVal" + keyIdx + "_" + (vi++);
                        if (inList.length() > 0) {
                            inList.append(",");
                        }
                        inList.append(":").append(p);
                        params.put(p, v);
                        matched.add(v);
                    }
                    query.append(" and (d.currentVersion in (").append(inList).append(")")
                            .append(" or exists (select 1 from DocumentVersionPO v")
                            .append(" where v.documentId = d.id and v.versionNumber in (").append(inList).append(")))");
                    versionFilter = new HashSet<>(matched);
                    keyIdx++;
                    continue;
                }
                String keyParam = "metaKey" + keyIdx;
                params.put(keyParam, entry.getKey());
                StringBuilder or = new StringBuilder();
                int vIdx = 0;
                for (String v : values) {
                    String p = "metaVal" + keyIdx + "_" + (vIdx++);
                    if (or.length() > 0) {
                        or.append(" or ");
                    }
                    or.append("m.metaValue = :").append(p)
                            .append(" or m.metaValue like concat(:").append(p).append(", ',%')")
                            .append(" or m.metaValue like concat('%,', :").append(p).append(")")
                            .append(" or m.metaValue like concat('%,', :").append(p).append(", ',%')");
                    params.put(p, v);
                }
                query.append(" and exists (select 1 from DocumentMetadataPO m")
                        .append(" where m.documentId = d.id and m.metaKey = :").append(keyParam)
                        .append(" and (").append(or).append("))");
                keyIdx++;
            }
            return versionFilter;
        }

        String hql() {
            return query.toString();
        }

        Map<String, Object> params() {
            return params;
        }
    }

    /** 版本级结果行组装上下文（{@link #pageVersionRows} 输入打包） */
    private record VersionRowContext(List<DocumentPO> docs, List<DocumentVersionPO> vpos,
                                     Map<String, Map<String, String>> metadataByDoc,
                                     Set<String> versionFilter, Set<String> statusesFilter,
                                     int page, int pageSize) {
    }

    /** 单文档结果行基础（当前版行与历史版行共用：文档行 + enriched 元数据 + 顶层官方标识） */
    private record DocRowBase(DocumentPO po, Map<String, String> metadata, String official) {
    }

    /**
     * 组装版本级结果行并排序分页。
     * <p>每个文档产出：当前版行（version=currentVersion，file/size/时间取自 documents）+
     * 每个归档版本一行（version=version_number，fileType/size/归档时间/归档人取自 document_versions，
     * title/status 等生命周期字段取自 documents——归档表不保存这些）。
     * version 过滤为版本级：当前版行仅当 currentVersion ∈ filter 保留；历史版行仅当
     * versionNumber ∈ filter 保留。排序：文档级沿用状态优先级 + updatedAt 倒序；
     * 同文档内当前版在前、历史版按归档时间倒序。</p>
     */
    private static PageResult<DocumentListItem> pageVersionRows(VersionRowContext ctx) {
        Map<String, List<DocumentVersionPO>> versionsByDoc = new HashMap<>();
        for (DocumentVersionPO vp : ctx.vpos()) {
            versionsByDoc.computeIfAbsent(vp.getDocumentId(), k -> new ArrayList<>()).add(vp);
        }
        List<DocumentListItem> rows = new ArrayList<>();
        for (DocumentPO po : orderedDocs(ctx.docs())) {
            DocRowBase base = rowBase(po, ctx);
            appendCurrentRow(rows, base, ctx);
            appendHistoricalRows(rows, base, versionsByDoc.get(po.getId()), ctx);
        }
        return paginateRows(rows, ctx.page(), ctx.pageSize());
    }

    /** 文档级排序：状态优先级 → updatedAt 倒序（与原 SQL order by 一致）；行按文档顺序生成（List.sort 稳定） */
    private static List<DocumentPO> orderedDocs(List<DocumentPO> docs) {
        List<DocumentPO> ordered = new ArrayList<>(docs);
        ordered.sort(Comparator
                .comparingInt((DocumentPO po) -> statusRank(po.getStatus()))
                .thenComparing(po -> po.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        return ordered;
    }

    /** 单文档行基础：enriched 元数据 + 顶层官方标识（无 official 元数据行 → null，即「未标注」） */
    private static DocRowBase rowBase(DocumentPO po, VersionRowContext ctx) {
        Map<String, String> meta = enrichMetadata(
                ctx.metadataByDoc().getOrDefault(po.getId(), Map.of()), po);
        return new DocRowBase(po, meta, trimToNull(meta.get("official")));
    }

    /** 当前版行：version=currentVersion；行级 statuses 过滤匹配文档状态 + version 过滤匹配当前版本号 */
    private static void appendCurrentRow(List<DocumentListItem> rows, DocRowBase base, VersionRowContext ctx) {
        DocumentPO po = base.po();
        boolean docStatusVisible = ctx.statusesFilter() == null
                || ctx.statusesFilter().contains(po.getStatus());
        boolean currentVisible = docStatusVisible
                && (ctx.versionFilter() == null
                || (po.getCurrentVersion() != null && ctx.versionFilter().contains(po.getCurrentVersion())));
        if (!currentVisible) {
            return;
        }
        // 当前版行：versionStatus 镜像文档生命周期（当前版在 document_versions 无行，
        // 前端统一按 versionStatus 显示，需恒为有界已知值且草稿不再显示为发布中）
        rows.add(new DocumentListItem(po.getId(), po.getTitle(),
                FileTypeUtil.fromPath(po.getFilePath()).orElse(null),
                po.getDocType(), po.getFileSize(), po.getStatus(), po.getProcessStatus(),
                po.getCreatedBy(), po.getCreatedAt(),
                po.getAuthor(), po.getRemark(),
                po.getCurrentVersion(), po.getCurrentVersion(), true, po.getStatus(),
                versionFilteredMeta(base.metadata(), po.getCurrentVersion()), base.official()));
    }

    /** 历史版行：每版本一行（防御性去重），归档时间倒序；statuses/version 过滤按行施加 */
    private static void appendHistoricalRows(List<DocumentListItem> rows, DocRowBase base,
                                             List<DocumentVersionPO> versions, VersionRowContext ctx) {
        DocumentPO po = base.po();
        if (versions == null) {
            return;
        }
        // 同文档内：历史版按归档时间倒序（最新归档在前）
        versions.sort(Comparator.comparing(vp -> vp.getCreatedAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        // 防御性去重：保证「每版本一行」不变量在任何数据下成立——写侧
        // checkVersionNotPublished 保证 currentVersion 不等于任何归档版本号、
        // 联合主键保证归档版本号互异；此守卫兜底存量/脏数据
        // （versionNumber == currentVersion 或重复版本号行只出一次）
        java.util.Set<String> seenVersions = new HashSet<>();
        if (po.getCurrentVersion() != null && !po.getCurrentVersion().isBlank()) {
            seenVersions.add(po.getCurrentVersion());
        }
        for (DocumentVersionPO vp : versions) {
            if (vp.getVersionNumber() == null || vp.getVersionNumber().isBlank()
                    || !seenVersions.add(vp.getVersionNumber())) {
                continue;
            }
            String versionStatus = vp.getStatus() != null ? vp.getStatus() : DocumentVersion.STATUS_PUBLISHED;
            // 历史版行状态语义（不随当前工作版本生命周期——避免覆盖后旧版误显示草稿）：
            // - 版本级下线（ARCHIVED/DEPRECATED）→ 照实显示行状态；
            // - 文档级下线（doc 状态 ARCHIVED/DEPRECATED）→ 随文档状态（整文档不可检索）；
            // - 其余（文档活跃）→ PUBLISHED（曾发布且可检索）。
            // processStatus 恒为 COMPLETED（历史版本发布时已向量化完成）。
            String rowStatus = resolveHistoricalRowStatus(versionStatus, po.getStatus());
            // 行级 statuses 过滤：与显示一致（null 不限全返回）
            if (ctx.statusesFilter() != null && !ctx.statusesFilter().contains(rowStatus)) {
                continue;
            }
            if (ctx.versionFilter() != null
                    && (vp.getVersionNumber() == null || !ctx.versionFilter().contains(vp.getVersionNumber()))) {
                continue;
            }
            rows.add(new DocumentListItem(po.getId(), po.getTitle(),
                    FileTypeUtil.fromPath(vp.getFilePath()).orElse(null),
                    po.getDocType(), vp.getFileSize(), rowStatus, DocumentEntity.PROCESS_COMPLETED,
                    vp.getCreatedBy() != null ? vp.getCreatedBy() : po.getCreatedBy(),
                    vp.getCreatedAt() != null ? vp.getCreatedAt() : po.getCreatedAt(),
                    po.getAuthor(), po.getRemark(),
                    po.getCurrentVersion(), vp.getVersionNumber(), false, versionStatus,
                    versionFilteredMeta(base.metadata(), vp.getVersionNumber()), base.official()));
        }
    }

    /** 内存分页（rows 已按序）：from 越界返回空页 */
    private static PageResult<DocumentListItem> paginateRows(List<DocumentListItem> rows,
                                                             int page, int pageSize) {
        long total = rows.size();
        int from = (page - 1) * pageSize;
        List<DocumentListItem> items = from >= rows.size()
                ? List.of() : rows.subList(from, Math.min(from + pageSize, rows.size()));
        long totalPage = (total + pageSize - 1) / pageSize;
        return new PageResult<>(page, pageSize, totalPage, total, List.copyOf(items));
    }

    /** 行级 metadata：version = 本行版本号（当前版=currentVersion，历史版=归档版本号） */
    private static Map<String, String> versionFilteredMeta(Map<String, String> base, String rowVersion) {
        Map<String, String> meta = new HashMap<>(base);
        meta.put("version", rowVersion);
        return meta;
    }

    /**
     * 元数据值归一为「有值或 null」：null/空白 → null，否则 trim 后的值。
     * <p>用于返回体顶层官方标识：无 {@code document_metadata.official} 行的文档即 null（未标注），
     * 而不是空串，前端据此显示「未标注」。</p>
     */
    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 历史版本行显示状态：版本级下线照实；文档级下线随文档状态（整文档不可检索）；
     * 文档活跃（DRAFT/REVIEWING/PUBLISHED）时历史版本显示 PUBLISHED（曾发布且可检索）。
     */
    private static String resolveHistoricalRowStatus(String versionStatus, String docStatus) {
        if (!DocumentVersion.STATUS_PUBLISHED.equals(versionStatus)) {
            return versionStatus;
        }
        if (DocumentEntity.STATUS_ARCHIVED.equals(docStatus)
                || DocumentEntity.STATUS_DEPRECATED.equals(docStatus)) {
            return docStatus;
        }
        return DocumentEntity.STATUS_PUBLISHED;
    }

    /** 生命周期状态排序优先级（与原 SQL order by 一致；未知状态兜底最后） */
    private static int statusRank(String status) {
        if (status == null) {
            return 5;
        }
        return switch (status) {
            case "DRAFT" -> 0;
            case "REVIEWING" -> 1;
            case "PUBLISHED" -> 2;
            case "ARCHIVED" -> 3;
            case "DEPRECATED" -> 4;
            default -> 5;
        };
    }

    /** 将逗号分隔的元数据值拆分为原子值：trim、去空、保持顺序。 */
    private static List<String> splitCsvValues(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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
     * 收集 rootId 及其全部后代类别 ID（含 root 自身）。
     * <p>基于同 KB 活跃类别列表在内存建 children 映射后 BFS，避免逐层查库。
     * 仿 {@link com.hisilicon.knowledge.platform.knowledge.query.DocumentGlobalSearchQueryService#collectSubtreeIncludingRoot}
     * （查询文档需命中 root 目录本身，故包含 root）。</p>
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

    /**
     * 元数据下拉选项（按 KB 聚合去重）
     * <p>数据源：该 KB 下全部文档已存的元数据值（白名单 key 内）。
     * 逗号分隔的多值（如 "CV620,CV610"）拆分为独立原子选项；值按字典序去重排序。
     * label 从 common-fieldNames 国际化解析，取不到时回退为 metaKey 原值。</p>
     * <p>特判 {@code version}：不入 document_metadata（由 documents.current_version 承载），
     * 从 documents.current_version + document_versions.version_number 聚合（含历史归档版本号），
     * 与搜索过滤的 version 特判同口径。</p>
     *
     * @param kbId   知识库 ID
     * @param locale 语言（用于 label）
     * @return 分组后的选项列表（按 metaKey 字典序，无数据返回空列表）
     */
    @WithSession
    public Uni<List<MetadataOptionGroup>> getMetadataOptions(String kbId, Locale locale) {
        // 串行：先取 KB 下可见文档（根目录 + 活跃类别子树，排除孤儿），再查这些文档的元数据
        // （实体查询勿并发）。口径与 listDocuments 整库范围一致。
        return documentPanacheRepository
                .find("from DocumentPO d where d.kbId = ?1 and "
                        + DocumentPanacheRepository.VISIBLE_IN_KB, kbId).list()
                .flatMap(docs -> {
                    if (docs.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    // version 由 documents.current_version 承载（不入 document_metadata），
                    // 从文档列聚合，与搜索过滤的 version 特判同源
                    TreeSet<String> versions = new TreeSet<>();
                    for (DocumentPO po : docs) {
                        if (po.getCurrentVersion() != null && !po.getCurrentVersion().isBlank()) {
                            versions.add(po.getCurrentVersion().trim());
                        }
                    }
                    List<String> docIds = docs.stream().map(po -> po.getId()).toList();
                    // version 选项聚合扩展到 document_versions.version_number：版本覆盖后旧版本号
                    // 仅存在于归档表，筛选面板须包含历史版本号（否则用户无从选择旧版过滤）
                    return documentVersionPanacheRepository.find("documentId in ?1", docIds).list()
                            .flatMap(vpos -> {
                                for (var vp : vpos) {
                                    if (vp.getVersionNumber() != null && !vp.getVersionNumber().isBlank()) {
                                        versions.add(vp.getVersionNumber().trim());
                                    }
                                }
                                return documentMetadataPanacheRepository
                                        .find("documentId in ?1", docIds).list()
                                        .map(metas -> groupOptions(metas, versions, locale));
                            });
                });
    }

    /** 白名单过滤 + 逗号拆分 + 去重排序 + 分组；version 特判从 current_version 聚合（不入 document_metadata） */
    private List<MetadataOptionGroup> groupOptions(List<DocumentMetadataPO> metas,
                                                   Set<String> versions, Locale locale) {
        Map<String, TreeSet<String>> valuesByKey = new HashMap<>();
        for (DocumentMetadataPO meta : metas) {
            if (!METADATA_OPTION_WHITELIST.contains(meta.getMetaKey())) {
                continue;
            }
            // version 不从 document_metadata 读取（生产无此行），由调用方从 current_version 聚合传入
            if ("version".equals(meta.getMetaKey())) {
                continue;
            }
            if (meta.getMetaValue() == null || meta.getMetaValue().isBlank()) {
                continue;
            }
            for (String part : meta.getMetaValue().split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    valuesByKey.computeIfAbsent(meta.getMetaKey(), k -> new TreeSet<>()).add(value);
                }
            }
        }
        if (!versions.isEmpty()) {
            valuesByKey.put("version", new TreeSet<>(versions));
        }
        return valuesByKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new MetadataOptionGroup(
                        e.getKey(), fieldNameResolver.resolve(e.getKey(), locale), List.copyOf(e.getValue())))
                .toList();
    }

    /**
     * 查看指定文档的元数据信息（编辑回填用）
     * <p>聚合 documents 表可编辑列（title/author/tags/remark）+ currentVersion +
     * document_metadata 全部 key-value，单次请求即可回填编辑表单。
     * 所有生命周期状态均可查看（状态约束仅在 edit 写路径施加）。</p>
     *
     * @param documentId 文档 ID
     * @param locale     语言环境（用于异常消息国际化）
     * @return 文档元数据视图
     */
    @WithSession
    public Uni<DocumentMetadataView> getDocumentMetadata(String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 实体查询串行：同一 session 上并发实体查询破坏 LoadContexts（同 listDocuments）。
        // 仅可见文档可按 id 读取：孤儿（categoryId 指向已删/不存在/跨库类别）视同不存在
        return documentPanacheRepository.findVisibleById(documentId).firstResult()
                .onItem().ifNull().failWith(() -> new DocumentNotFoundException(appMessages.documentNotFound()))
                .flatMap(po -> documentMetadataPanacheRepository.find("documentId", documentId).list()
                        .map(metas -> {
                            Map<String, String> metadata = metas.stream()
                                    .collect(Collectors.toMap(m -> m.getMetaKey(), m -> m.getMetaValue()));
                            return new DocumentMetadataView(po.getId(), po.getTitle(), po.getAuthor(),
                                    po.getRemark(), po.getCurrentVersion(), enrichMetadata(metadata, po));
                        }));
    }

    /**
     * 查看文档详情（聚合基础信息 + 知识属性 + 切片统计 + 审核历史）
     * <p>聚合 documents 表全部字段（含 chunkCount 切片数）、document_metadata 全部 key-value
     * （含 chip_model/sensitivity/product 等标签信息）、review_history 审核历史（按时间倒序），
     * 供文档详情页一次性渲染。</p>
     * <p>version 非空时为<b>版本级详情</b>：fileSize/createdAt/createdBy 取自 document_versions
     * 版本行，metadata.version=该版本号；版本已下线（status != PUBLISHED）→ A0508
     * 「文档已归档/已废弃」；版本不存在 → A0430。version 为空时行为与历史版本一致（文档级）。</p>
     *
     * @param documentId 文档 ID
     * @param version    版本号（可选；非空=版本级详情）
     * @param locale     语言环境（用于异常消息国际化）
     * @return 文档详情视图
     */
    @WithSession
    public Uni<DocumentDetailDTO> getDocumentDetail(String documentId, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 实体查询串行：同一 session 上并发实体查询破坏 LoadContexts（同 listDocuments）。
        // 仅可见文档可按 id 读取：孤儿（categoryId 指向已删/不存在/跨库类别）视同不存在
        return documentPanacheRepository.findVisibleById(documentId).firstResult()
                .onItem().ifNull().failWith(() -> new DocumentNotFoundException(appMessages.documentNotFound()))
                .flatMap(po -> {
                    String target = version == null ? null : version.trim();
                    if (target == null || target.isBlank()) {
                        return buildDetail(po, null, appMessages);
                    }
                    return documentVersionPanacheRepository
                            .findById(new com.hisilicon.knowledge.platform.knowledge.infrastructure.po
                                    .DocumentVersionPO.DocumentVersionPK(po.getId(), target))
                            .flatMap(vp -> {
                                if (vp != null) {
                                    return buildDetail(po, vp, appMessages);
                                }
                                if (!target.equals(po.getCurrentVersion())) {
                                    // 版本表无该行且不是当前版 → 真不存在
                                    logger.error("Document version not found: documentId={}, version={}", documentId, target);
                                    return Uni.createFrom().failure(
                                            new DocumentNotFoundException(appMessages.documentVersionNotFound()));
                                }
                                // 当前版正常态：document_versions 无行，按文档级（documents 行）返回
                                return buildDetail(po, null, appMessages);
                            });
                });
    }

    /** 组装详情 DTO：versionPO 为 null = 文档级（当前版本）；非 null = 版本级（历史版本行） */
    private Uni<DocumentDetailDTO> buildDetail(DocumentPO po, DocumentVersionPO vp, AppMessages appMessages) {
        boolean versionLevel = vp != null;
        String versionStatus = versionLevel
                ? (vp.getStatus() != null ? vp.getStatus() : DocumentVersion.STATUS_PUBLISHED)
                : po.getStatus();
        if (versionLevel && !DocumentVersion.STATUS_PUBLISHED.equals(versionStatus)) {
            // 下线版本：A0508 + 后端明确消息（detail 与 download 同文案同源）
            String message = DocumentVersion.STATUS_ARCHIVED.equals(versionStatus)
                    ? appMessages.versionArchived() : appMessages.versionDeprecated();
            logger.warn("Version already offline, detail view rejected: documentId={}, version={}, status={}",
                    po.getId(), vp.getVersionNumber(), versionStatus);
            return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException(
                    com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum.USER_ERROR_A0508,
                    message));
        }
        String rowVersion = versionLevel ? vp.getVersionNumber() : po.getCurrentVersion();
        Long fileSize = versionLevel && vp.getFileSize() != null ? vp.getFileSize() : po.getFileSize();
        String createdBy = versionLevel && vp.getCreatedBy() != null ? vp.getCreatedBy() : po.getCreatedBy();
        java.time.LocalDateTime createdAt = versionLevel && vp.getCreatedAt() != null ? vp.getCreatedAt() : po.getCreatedAt();
        // 历史版详情 status：与列表版本行语义一致（版本级下线照实；文档级下线随文档状态；
        // 文档活跃时显示 PUBLISHED）
        String rowStatus = versionLevel
                ? resolveHistoricalRowStatus(versionStatus, po.getStatus())
                : po.getStatus();
        return documentMetadataPanacheRepository.find("documentId", po.getId()).list()
                .flatMap(metas -> reviewHistoryPanacheRepository
                        .find("documentId = ?1 order by createdAt desc", po.getId()).list()
                        .map(history -> {
                            Map<String, String> metadata = metas.stream()
                                    .collect(Collectors.toMap(m -> m.getMetaKey(), m -> m.getMetaValue()));
                            Map<String, String> enriched = enrichMetadata(metadata, po);
                            if (versionLevel) {
                                enriched.put("version", vp.getVersionNumber());
                            }
                            List<String> tags = (po.getTags() != null && !po.getTags().isBlank())
                                    ? splitCsvValues(po.getTags()) : List.of();
                            List<ReviewHistoryItem> reviewHistory = history.stream()
                                    .map(DocumentQueryService::toHistoryItem)
                                    .toList();
                            return new DocumentDetailDTO(po.getId(), po.getTitle(), po.getDocType(),
                                    rowStatus, po.getCurrentVersion(),
                                    rowVersion, !versionLevel, versionStatus,
                                    fileSize, po.getAuthor(), createdBy,
                                    createdAt, po.getUpdatedAt(),
                                    po.getChunkCount(),
                                    tags,
                                    enriched,
                                    reviewHistory);
                        }));
    }

    /** 将审核历史 PO 转换为 DTO */
    private static ReviewHistoryItem toHistoryItem(ReviewHistoryPO po) {
        return new ReviewHistoryItem(po.getId(), po.getAction(), po.getReviewerId(),
                po.getFromStatus(), po.getToStatus(), po.getVersion(), po.getComment(), po.getCreatedAt());
    }

    /**
     * 扩充 metadata Map：纳入上传 {@code UploadDocumentCommand} 的全部字段。
     * <p>document_metadata 原有 key（product/chip_model/sensitivity/official/access_policy 等）保留，
     * 另从 documents 列组装 title/author/tag(逗号串)/remark/version(=currentVersion)，
     * key 名对齐上传 JSON 名，使前端从 metadata 一处即可取上传时的所有项。
     * null 值亦放入（序列化为 null），保证上传字段始终存在于 metadata，与上传结构一致。</p>
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
}
