package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.CategoryTreeService;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryDepthExceededException;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryMoveCycleException;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNameDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 类别命令服务
 */
@ApplicationScoped
public class CategoryCommandService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryCommandService.class);

    /** 删除阻塞状态：审核中/已发布/已归档的文档不允许随目录删除（须先撤回或废弃） */
    private static final List<String> DELETE_BLOCKING_STATUSES = List.of(
            DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_PUBLISHED, DocumentEntity.STATUS_ARCHIVED);

    /** 随目录级联物理删除的状态：草稿（未处理）+ 已废弃（向量已在废弃时清理） */
    private static final List<String> DELETE_CASCADE_STATUSES = List.of(
            DocumentEntity.STATUS_DRAFT, DocumentEntity.STATUS_DEPRECATED);

    /** 删除类别单次级联物理删除文档上限：防一次删除文件过多导致请求超时/网络错误 */
    private static final int DELETE_MAX_DOCS = 200;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final DocumentCommandService documentCommandService;
    private final DocumentStoragePathResolver storagePathResolver;
    private final com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService fileStorageService;
    private final com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService fileRecycleService;
    private final com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository uploadSessionRepository;
    private final CategoryTreeService categoryTreeService = new CategoryTreeService();

    /**
     * 自注入：delete() 非事务编排 + deleteCategoryInTx() 事务方法的桥接。
     * 直接 this.deleteCategoryInTx() 会绕过 {@code @WithTransaction} 拦截器（CDI 代理不生效）。
     */
    @jakarta.inject.Inject
    CategoryCommandService self;

    public CategoryCommandService(KnowledgeBaseRepository knowledgeBaseRepository,
                                  CategoryRepository categoryRepository,
                                  DocumentRepository documentRepository,
                                  DocumentCommandService documentCommandService,                                  DocumentStoragePathResolver storagePathResolver,

                                  com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService fileStorageService,
                                  com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService fileRecycleService,
                                  com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository uploadSessionRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.documentCommandService = documentCommandService;
        this.storagePathResolver = storagePathResolver;
        this.fileStorageService = fileStorageService;
        this.fileRecycleService = fileRecycleService;
        this.uploadSessionRepository = uploadSessionRepository;
    }

    /**
     * 创建类别（根类别或子类别）
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param kbId       所属知识库 ID
     * @param command    创建命令
     * @param locale     语言环境
     * @return 新创建的类别实体（含 ID）
     */
    @WithTransaction
    public Uni<CategoryEntity> create(String operatorId, String kbId, CreateCategoryCommand command, String locale) {
        String categoryName = command.categoryName().trim();
        String parentId = command.parentId() == null ? null : command.parentId().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Create category: kbId={}, parentId={}, name={}, operatorId={}",
                kbId, parentId, categoryName, operatorId);

        // 1) KB 存在且未删除 → 2) 父类别校验（存在/同库/未删/深度） → 3) 同级重名校验 → 4) 落库
        return requireActiveKb(kbId, appMessages)
                .flatMap(kb -> validateParentCategory(kbId, parentId, appMessages).replaceWith(kb))
                .flatMap(kb -> categoryRepository.findByName(kbId, parentId, categoryName))
                .onItem().ifNotNull().failWith(() -> {
                    logger.error("Sibling category name already exists: kbId={}, parentId={}, name={}", kbId, parentId, categoryName);
                    return new CategoryNameDuplicateException(appMessages.categoryNameAlreadyExists());
                })
                .onItem().ifNull().switchTo(() -> persistNewCategory(kbId, parentId, categoryName));
    }

    /** 校验知识库存在且未删除（已删除视为不存在，不允许创建） */
    private Uni<KnowledgeBaseEntity> requireActiveKb(String kbId, AppMessages appMessages) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", kbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        logger.error("Knowledge base deleted, category creation not allowed: id={}", kbId);
                        return Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    return Uni.createFrom().item(kb);
                });
    }

    /** 父类别校验：存在、属于同一知识库、未删除、子节点深度不超上限（根类别 parentId=null 直通） */
    private Uni<Void> validateParentCategory(String kbId, String parentId, AppMessages appMessages) {
        if (parentId == null) {
            return Uni.createFrom().voidItem();
        }
        return categoryRepository.findById(CategoryId.of(parentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Parent category not found: id={}", parentId);
                    return new CategoryNotFoundException(appMessages.categoryParentNotFound());
                })
                .flatMap(parent -> {
                    if (!kbId.equals(parent.getKbId())
                            || !CategoryEntity.STATUS_ACTIVE.equals(parent.getStatus())) {
                        logger.error("Parent category not found or deleted: id={}, kbId={}", parentId, kbId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryParentNotFound()));
                    }
                    // 深度限制：父类别深度（根=1）+ 1 <= MAX_CATEGORY_DEPTH（20）
                    return countParentDepth(parent, 1)
                            .flatMap(depth -> {
                                if (depth + 1 > CategoryTreeService.MAX_CATEGORY_DEPTH) {
                                    logger.error("Category depth exceeds limit: parentId={}, depth={}", parentId, depth);
                                    return Uni.createFrom().failure(
                                            new CategoryDepthExceededException(
                                                    appMessages.categoryDepthExceeded()));
                                }
                                return Uni.createFrom().voidItem();
                            });
                });
    }

    /**
     * 同级最大 sortOrder + 1 后创建实体并入库（新节点排到同级末尾，首个从 1 开始）；
     * 父目录取父级持久化 dirName（存量回退名称），叶段唯一化后持久化 dirName + 预建物理目录（best-effort）。
     */
    private Uni<CategoryEntity> persistNewCategory(String kbId, String parentId, String categoryName) {
        return categoryRepository.maxSortOrder(kbId, parentId)
                .map(max -> max != null ? max + 1 : 1)
                .flatMap(sortOrder -> {
                    CategoryEntity entity = CategoryEntity.create(kbId, parentId, categoryName, sortOrder);
                    return storagePathResolver.storageDirForCategory(kbId, parentId)
                            // 叶段在父目录下唯一化（旧类别改名后仍占旧名目录，同名须跳号），实际段持久化到 dirName
                            .flatMap(parentDir -> DocumentStoragePathResolver
                                    .uniqueStorageDirSegment(fileStorageService, parentDir,
                                            DocumentStoragePathResolver.dirSegment(
                                                    categoryName, entity.getId().value()))
                                    .flatMap(dirName -> {
                                        CategoryEntity created = entity.withDirName(dirName);
                                        return categoryRepository.persist(created)
                                                .invoke(() -> logger.info(
                                                        "Category created: id={}, name={}, dir={}/{}",
                                                        created.getId().value(), categoryName,
                                                        parentDir, dirName))
                                                // 预建类别物理目录（快照语义，best-effort 失败仅告警）
                                                .call(() -> fileStorageService
                                                        .ensureDirectory(parentDir + "/" + dirName)
                                                        .onFailure().invoke(err -> logger.warn(
                                                                "Category directory pre-creation failed (creation not affected): dir={}/{}, err={}",
                                                                parentDir, dirName, err.getMessage()))
                                                        .onFailure().recoverWithNull())
                                                .replaceWith(created);
                                    }));
                });
    }

    /**
     * 编辑类别基本信息（全量替换语义）
     * <p>不涉及知识库 / 父类别变更（类别移动由专门接口处理）。
     * sortOrder 为 null 时重置为 0，inheritParent 为 null 时重置为 true。</p>
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param command    编辑命令
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> edit(String operatorId, UpdateCategoryCommand command, String locale) {
        String categoryId = command.categoryId().trim();
        String newName = command.categoryName().trim();
        Integer newSortOrder = command.sortOrder();
        Boolean newInheritParent = command.inheritParent();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Edit category: id={}, operatorId={}", categoryId, operatorId);

        // 1) 校验类别存在且未删除（已删除视为不存在）
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", categoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(entity -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Category deleted, cannot edit: id={}", categoryId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    return Uni.createFrom().item(entity);
                })
                // 2) 名称变更时校验同级唯一性（名称未变则跳过，避免自身冲突）
                .flatMap(entity -> {
                    if (!newName.equals(entity.getName())) {
                        return categoryRepository.findByName(
                                        entity.getKbId(), entity.getParentId(), newName)
                                .onItem().ifNotNull().failWith(() -> {
                                    logger.error("Sibling category name already exists: kbId={}, parentId={}, name={}",
                                            entity.getKbId(), entity.getParentId(), newName);
                                    return new CategoryNameDuplicateException(
                                            appMessages.categoryNameAlreadyExists());
                                })
                                .map(ignored -> entity);
                    }
                    return Uni.createFrom().item(entity);
                })
                // 3) 领域实体更新并持久化（全量替换：sortOrder/inheritParent 为 null 时置默认值）
                .flatMap(entity -> {
                    CategoryEntity updated = entity.update(newName, newSortOrder, newInheritParent);
                    return categoryRepository.update(updated)
                            .invoke(() -> logger.info("Category updated: id={}, name={}", categoryId, newName));
                })
                .replaceWithVoid();
    }

    /**
     * 移动类别（同一知识库内变更父类别）
     * <p>不支持跨知识库移动（kbId 保持不变）。子类别和文档随树结构自动跟随。
     * 校验：目标父类别存在且未删除 → 不能移到自身或后代（循环引用）→ 移动后最深节点深度 ≤ {@code MAX_CATEGORY_DEPTH}（20）。</p>
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param command    移动命令
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> move(String operatorId, MoveCategoryCommand command, String locale) {
        String categoryId = command.categoryId().trim();
        String targetParentId = command.targetParentId() == null ? null : command.targetParentId().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Move category: id={}, targetParentId={}, operatorId={}", categoryId, targetParentId, operatorId);

        // 1) 被移动类别必须存在且未删除
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", categoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(entity -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Category deleted, cannot move: id={}", categoryId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    // 2) 加载同 KB 全部未删除类别（列表已限定 kbId + status=1），做树形校验
                    return categoryRepository.findAllByKbId(entity.getKbId())
                            .map(all -> new TreeContext(entity, all));
                })
                .flatMap(ctx -> {
                    CategoryEntity entity = ctx.entity();
                    Map<String, CategoryEntity> byId = ctx.all().stream()
                            .collect(Collectors.toMap(c -> c.getId().value(), c -> c));

                    if (targetParentId != null) {
                        // 3) 不能移到自身或后代类别下（循环引用）
                        if (categoryTreeService.wouldCreateCycle(byId, categoryId, targetParentId)) {
                            logger.error("Cannot move under itself or a descendant category: categoryId={}, targetParentId={}",
                                    categoryId, targetParentId);
                            return Uni.createFrom().failure(
                                    new CategoryMoveCycleException(appMessages.categoryMoveSelfOrDescendant()));
                        }
                        // 4) 目标父类别必须存在且未删除（列表已过滤 status=1 且限定同 KB）
                        if (!byId.containsKey(targetParentId)) {
                            logger.error("Parent category not found: id={}", targetParentId);
                            return Uni.createFrom().failure(
                                    new CategoryNotFoundException(appMessages.categoryParentNotFound()));
                        }
                    }
                    // 5) 深度限制：目标父类别深度 + 被移动子树高度 <= 10
                    if (categoryTreeService.wouldExceedDepth(byId, categoryId, targetParentId)) {
                        logger.error("Category depth exceeds limit after move: categoryId={}, targetParentId={}",
                                categoryId, targetParentId);
                        return Uni.createFrom().failure(
                                new CategoryDepthExceededException(appMessages.categoryDepthExceeded()));
                    }
                    // 7) 领域实体移动并持久化（仅变更 parentId，kbId 不变）
                    CategoryEntity moved = entity.move(targetParentId);
                    return categoryRepository.update(moved)
                            .invoke(() -> logger.info("Category moved: id={}, targetParentId={}",
                                    categoryId, targetParentId));
                })
                .replaceWithVoid();
    }

    /**
     * 删除类别（级联软删除所有后代类别 + 级联物理删除可删文档）
     * <p>两阶段：</p>
     * <ol>
     *   <li>编排（无事务，仅只读）：校验类别存在且未删除 → 计算子树类别 ID →
     *       阻塞校验：子树下存在 REVIEWING/PUBLISHED/ARCHIVED 文档 → 拒绝（A0436，提示先撤回或废弃）；</li>
     *   <li>事务（{@link #deleteCategoryInTx}）：开头锁 KB 行（与上传落库事务互斥，锁序一致无死锁）→
     *       防御性复查阻塞计数 → 批量物理删除 DRAFT/DEPRECATED 文档（3 条语句级联
     *       document_metadata/document_versions，状态守卫防并发半删）→ 软删类别子树 →
     *       被删类别同级（sortOrder 更大者）前移一位。</li>
     * </ol>
     * <p>事务提交后（不阻塞响应）：① 文档文件（当前版本 + 历史版本）异步移入回收站
     * （快照身份校验，防止同名重传覆盖后误删新文件，见 {@link com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService}）；
     * ② 目标子树内的在传上传会话异步作废（ABORTED + 临时文件移回收站）——其后续条目落库会因目录已删干净失败。</p>
     * <p>KB 根目录直属文档（categoryId=null）不属于任何类别，不随类别删除；
     * 级联删除 DRAFT/DEPRECATED 保证删除后查询无孤儿数据，且重建同名类别后可重新上传
     * （同名去重按 kbId+categoryId+title，旧 DRAFT 残留会被同名覆盖写回已删类别）。</p>
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param categoryId 类别 ID
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithSession
    public Uni<Void> delete(String operatorId, String categoryId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Delete category: id={}, operatorId={}", categoryId, operatorId);

        // 1) 校验类别存在且未删除（已删除视为不存在，删除幂等）
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", categoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(entity -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Category already deleted: id={}", categoryId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    // 2) 加载同 KB 全部未删除类别，内存找出被删类别的所有后代
                    return categoryRepository.findAllByKbId(entity.getKbId())
                            .map(all -> new DeleteContext(entity, all));
                })
                .flatMap(ctx -> {
                    Set<String> idsToDelete = categoryTreeService.collectSubtreeIds(
                            ctx.all(), ctx.root().getId().value());
                    idsToDelete.add(ctx.root().getId().value());
                    // 3) 阻塞校验：子树下存在审核中/已发布/已归档文档 → 拒绝删除
                    return documentRepository.countByCategoryIdsAndStatusIn(
                                    ctx.root().getKbId(), idsToDelete, DELETE_BLOCKING_STATUSES)
                            .flatMap(blocking -> {
                                if (blocking > 0) {
                                    logger.error("Category contains undeletable documents (REVIEWING/PUBLISHED/ARCHIVED): categoryId={}, count={}",
                                            categoryId, blocking);
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.DELETE_BLOCKED_BY_DOCUMENTS,
                                            appMessages.categoryDeleteBlockedByDocuments()));
                                }
                                // 3.1) 数量上限：待级联物理删除的 DRAFT/DEPRECATED 文档过多 →
                                //      单次删除耗时超 60s APIG 时限，直接拒绝，避免超时/网络错误
                                return documentRepository.countByCategoryIdsAndStatusIn(
                                                ctx.root().getKbId(), idsToDelete, DELETE_CASCADE_STATUSES)
                                        .flatMap(cascadeCount -> {
                                            if (cascadeCount > DELETE_MAX_DOCS) {
                                                logger.error("Too many documents to cascade-delete under category: categoryId={}, count={}, max={}",
                                                        categoryId, cascadeCount, DELETE_MAX_DOCS);
                                                return Uni.createFrom().failure(new BusinessException(
                                                        ErrorCodeEnum.USER_ERROR_A0420,
                                                        appMessages.categoryDeleteCountExceeded(DELETE_MAX_DOCS)));
                                            }
                                            // 4) 事务：锁 KB 行 + 批量物理删除可删文档 + 软删类别子树，返回路径→快照；
                                            //    提交后（事务 Uni 完成）文件回收 + 在传会话作废异步执行，不阻塞响应
                                            return self.deleteCategoryInTx(appMessages, ctx, idsToDelete)
                                                    .invoke(snapshots -> fileRecycleService
                                                            .recycleAsync("categoryId=" + categoryId, snapshots))
                                                    .invoke(() -> abortActiveSessionsAsync(ctx.root().getKbId(), idsToDelete));
                                        });
                            });
                })
                .replaceWithVoid();
    }

    /**
     * 事务内执行类别删除：锁 KB 行 + 批量物理删除 DRAFT/DEPRECATED 文档 + 软删类别子树 + 同级前移。
     * <p>通过 {@code self} 自注入调用保证 {@code @WithTransaction} 拦截器生效。
     * 返回待回收路径 → 删除时快照（供提交后身份校验回收，防同名重传误删）。</p>
     * <p>开头 {@code findByIdForUpdate} 锁 KB 行：与上传落库事务（upload/completeSingleFile/
     * persistZipEntryAndBump）互斥——落库事务持锁时本事务等待（毫秒级），本事务提交后
     * 落库事务的"类别活跃"校验必然看到已删，杜绝文档插入已删目录产生新孤儿。</p>
     */
    @WithTransaction
    public Uni<Map<String, com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService.FileSnapshot>>
    deleteCategoryInTx(AppMessages appMessages, DeleteContext ctx, Set<String> idsToDelete) {
        String kbId = ctx.root().getKbId();
        // 锁 KB 行（与上传落库互斥，锁序一致：均先 KB 行，无死锁）
        return knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of(kbId))
                // 防御性复查：编排（只读校验）与事务之间的并发窗口内文档状态可能变化
                .flatMap(kb -> documentRepository.countByCategoryIdsAndStatusIn(kbId, idsToDelete, DELETE_BLOCKING_STATUSES))
                .flatMap(blocking -> {
                    if (blocking > 0) {
                        logger.error("Blocking documents still present in category delete transaction recheck: categoryId={}, count={}",
                                ctx.root().getId().value(), blocking);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.DELETE_BLOCKED_BY_DOCUMENTS,
                                appMessages.categoryDeleteBlockedByDocuments()));
                    }
                    return documentRepository.findByCategoryIdsAndStatusIn(kbId, idsToDelete, DELETE_CASCADE_STATUSES);
                })
                // 收集待删除文档的全部文件路径并快照（须在级联删除行之前完成）
                .flatMap(docs -> collectDocFilePaths(docs)
                        .flatMap(snapshots -> documentRepository
                                .deleteAllByIds(docIds(docs), DELETE_CASCADE_STATUSES)
                                .invoke(() -> logger.info("Cascade hard-delete documents under category: categoryId={}, count={}",
                                        ctx.root().getId().value(), docs.size()))
                                .map(v -> snapshots)))
                // 软删类别子树 + 被删类别同级前移
                .chain(snapshots -> softDeleteCategories(ctx, idsToDelete).map(v -> snapshots));
    }

    /** 文档 ID 集合 */
    private static List<String> docIds(List<DocumentEntity> docs) {
        return docs.stream().map(d -> d.getId().value()).toList();
    }

    /** 收集文档全部文件路径并快照身份（当前版本取内存实体 + 历史版本一条批量查询，去重保序） */
    private Uni<Map<String, com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService.FileSnapshot>>
    collectDocFilePaths(List<DocumentEntity> docs) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (DocumentEntity doc : docs) {
            if (doc.getFilePath() != null && !doc.getFilePath().isBlank()) {
                paths.add(doc.getFilePath());
            }
        }
        return documentRepository.findVersionFilePathsByDocumentIds(docIds(docs))
                .invoke(versionPaths -> versionPaths.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .forEach(paths::add))
                .flatMap(v -> fileRecycleService.snapshot(paths));
    }

    /** 类别软删子树 + 被删类别的直接同级（sortOrder 更大者）前移一位，保持排序连续 */
    private Uni<Void> softDeleteCategories(DeleteContext ctx, Set<String> idsToDelete) {
        List<Uni<CategoryEntity>> updates = ctx.all().stream()
                .filter(c -> idsToDelete.contains(c.getId().value()))
                .map(c -> categoryRepository.update(c.delete()))
                .collect(Collectors.toCollection(ArrayList::new));

        CategoryEntity root = ctx.root();
        ctx.all().stream()
                .filter(c -> !idsToDelete.contains(c.getId().value()))
                .filter(c -> Objects.equals(c.getParentId(), root.getParentId()))
                .filter(c -> root.getSortOrder() != null && c.getSortOrder() != null
                        && c.getSortOrder() > root.getSortOrder())
                .forEach(s -> updates.add(categoryRepository.update(
                        s.update(s.getName(), s.getSortOrder() - 1, s.getInheritParent()))));

        return Uni.combine().all().unis(updates).discardItems()
                .invoke(() -> logger.info("Category deleted: id={}, {} categories cascade-deleted, {} siblings shifted forward",
                        ctx.root().getId().value(), idsToDelete.size(), updates.size() - idsToDelete.size()));
    }

    /**
     * 作废目标子树内的在传上传会话——fire-and-forget（提交后调用，不阻塞删除响应）。
     * <p>删除提交后其后续落库校验必然失败；显式作废（ABORTED + 临时文件移回收站）
     * 比让会话在解压完成后逐条目报错收尾更及时。逐会话 best-effort。</p>
     */
    private void abortActiveSessionsAsync(String kbId, Set<String> idsToDelete) {
        uploadSessionRepository.findActiveByKbId(kbId)
                .invoke(sessions -> {
                    List<com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession> targets =
                            sessions.stream()
                                    .filter(s -> s.getCategoryId() != null && idsToDelete.contains(s.getCategoryId()))
                                    .toList();
                    if (targets.isEmpty()) {
                        return;
                    }
                    logger.info("Abort upload sessions in target category: kbId={}, categoryId={}, count={}",
                            kbId, idsToDelete, targets.size());
                    for (com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession s : targets) {
                        self.abortUploadSessionInTx(s)
                                .subscribe().with(v -> {},
                                        err -> logger.error("Failed to abort upload session (session cleanup job will expire it): sessionId={}, error={}",
                                                s.getId().value(), err.getMessage()));
                    }
                })
                .subscribe().with(v -> {},
                        err -> logger.error("Failed to query active upload sessions: kbId={}, error={}", kbId, err.getMessage()));
    }

    /** 单会话作库作废（独立短事务 via self）：置 ABORTED + 临时文件移回收站（best-effort） */
    @WithTransaction
    public Uni<Void> abortUploadSessionInTx(com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession session) {
        return uploadSessionRepository.findById(session.getId())
                .flatMap(current -> {
                    // 会话可能在窗口内自行终态化（幂等守卫：仅进行中可作废）
                    if (current == null || !isSessionActive(current)) {
                        return Uni.createFrom().voidItem();
                    }
                    return uploadSessionRepository.update(current.abort())
                            .call(() -> fileStorageService.moveToTrash(current.getTempPath())
                                    .onFailure().recoverWithUni(err -> {
                                        logger.error("Failed to move aborted session temp file to trash: sessionId={}, path={}",
                                                current.getId().value(), current.getTempPath());
                                        return Uni.createFrom().voidItem();
                                    }))
                            .replaceWithVoid();
                });
    }

    /** 会话是否进行中（CREATED/UPLOADING/PROCESSING） */
    private static boolean isSessionActive(com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession session) {
        return switch (session.getStatus()) {
            case CREATED, UPLOADING, PROCESSING -> true;
            default -> false;
        };
    }

    // ========== 树形结构辅助方法 ==========

    /** 移动校验上下文：被移动类别 + 同 KB 全部未删除类别 */
    private record TreeContext(CategoryEntity entity, List<CategoryEntity> all) {
    }

    /**
     * 删除上下文：被删除类别 + 同 KB 全部未删除类别。
     * <p>包级私有（非 private）：作为 public 事务方法 {@link #deleteCategoryInTx} 的参数类型，
     * Quarkus Arc 生成的拦截代理类与本类同包，需可见。</p>
     */
    record DeleteContext(CategoryEntity root, List<CategoryEntity> all) {
    }

    /**
     * 逐层向上统计节点深度（根类别深度 = 1）
     * <p>用于创建接口的深度校验：父类别深度 + 1 不得超过最大深度。</p>
     */
    private Uni<Integer> countParentDepth(CategoryEntity node, int depth) {
        if (node.getParentId() == null || depth >= CategoryTreeService.MAX_CATEGORY_DEPTH) {
            return Uni.createFrom().item(depth);
        }
        return categoryRepository.findById(CategoryId.of(node.getParentId()))
                .flatMap(parent -> parent == null
                        ? Uni.createFrom().item(depth)
                        : countParentDepth(parent, depth + 1));
    }
}
