package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentFileSpec;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DraftConflictPolicy;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.IdGenerator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KbCallbackProcessedRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryEntry;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ReviewHistoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.CategoryTreeService;
import com.hisilicon.knowledge.platform.knowledge.domain.service.ConflictAutoScanHook;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ArchiveFormats;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentStateException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ZipFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentUploadResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchReviewItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchReviewResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchReviewSummary;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchDeleteSummary;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchSubmitReviewItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchSubmitReviewResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BatchSubmitReviewSummary;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDocumentResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadSummary;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionOverrideResult;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档命令服务
 */
@ApplicationScoped
public class DocumentCommandService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentCommandService.class);

    /** 单个文件最大大小：100MB（默认，可通过 kb.upload.max-file-size 配置） */
    private final long maxFileSize;

    /** 允许的文件扩展名（小写） */
    // 支持的文件类型清单见 docs/supported_file_formats.json，此处为其代码镜像（小写，去重）。
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 文档：pdf/word/excel/ppt/markdown/txt/html/csv
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "txt", "html", "htm", "csv",
            // 代码：c/c++/python/typescript/arkts/java/javascript
            "c", "cc", "cpp", "cxx", "h", "hpp", "py", "ts", "tsx", "ets", "java", "js", "mjs", "cjs",
            // 配置：ini/dtsi/xml/sh/makefile(.cmake)
            "ini", "dts", "dtsi", "dtso", "xml", "sh", "cmake",
            // 图片：png/jpg/jpeg/bmp
            "png", "jpg", "jpeg", "bmp"
    );

    /** 允许的无扩展名文件名（小写精确匹配） */
    private static final Set<String> ALLOWED_FILENAMES = Set.of("makefile");

    /** MIME 类型 → 文件扩展名映射（用于从 MIME 推断 fileType） */
    // 支持的文件类型清单见 docs/supported_file_formats.json；yaml 不在清单内，不再映射。
    private static final java.util.Map<String, String> MIME_TO_EXT = java.util.Map.ofEntries(
            // 文档
            java.util.Map.entry("application/pdf", "pdf"),
            java.util.Map.entry("application/msword", "doc"),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            java.util.Map.entry("application/vnd.ms-excel", "xls"),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            java.util.Map.entry("application/vnd.ms-powerpoint", "ppt"),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            java.util.Map.entry("text/plain", "txt"),
            java.util.Map.entry("text/markdown", "md"),
            java.util.Map.entry("text/html", "html"),
            java.util.Map.entry("text/csv", "csv"),
            // 代码（c/c++/python/java/js/ts 无标准独立 MIME 的靠扩展名判定）
            java.util.Map.entry("text/x-c", "c"),
            java.util.Map.entry("text/x-csrc", "c"),
            java.util.Map.entry("text/x-c++src", "cpp"),
            java.util.Map.entry("text/x-python", "py"),
            java.util.Map.entry("text/x-java-source", "java"),
            java.util.Map.entry("text/javascript", "js"),
            java.util.Map.entry("application/javascript", "js"),
            java.util.Map.entry("application/x-javascript", "js"),
            // 配置
            java.util.Map.entry("application/xml", "xml"),
            java.util.Map.entry("text/xml", "xml"),
            java.util.Map.entry("text/x-sh", "sh"),
            java.util.Map.entry("application/x-sh", "sh"),
            // 图片
            java.util.Map.entry("image/png", "png"),
            java.util.Map.entry("image/jpeg", "jpg"),
            java.util.Map.entry("image/jpg", "jpg"),
            java.util.Map.entry("image/bmp", "bmp"),
            java.util.Map.entry("image/x-ms-bmp", "bmp")
    );

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final DocumentOutboxRepository outboxRepository;
    private final KbCallbackProcessedRepository callbackProcessedRepository;
    private final ReviewHistoryRepository reviewHistoryRepository;
    private final FileStorageService fileStorageService;
    private final FileRecycleService fileRecycleService;
    private final DictValueValidator dictValueValidator;
    private final Vertx vertx;

    /** 类别树领域服务（纯逻辑，无 CDI 依赖，直接实例化，对齐 CategoryCommandService 范式） */
    private final CategoryTreeService categoryTreeService = new CategoryTreeService();

    /**
     * 文档切片就绪后的自动冲突检测钩子（可选注入；模块未启用时为 null，主链路不受影响）。
     * 在 processCallback COMPLETED 事务提交后旁路调用，实现内部已兜底异常。
     */
    @Inject
    ConflictAutoScanHook conflictAutoScanHook;

    /** ZIP 压缩包单包最大文件条目数（防超长处理链） */
    private final int zipMaxEntries;

    /** ZIP 压缩包解压总字节上限（防高压缩比 zip 炸弹内存溢出） */
    private final long zipMaxTotalBytes;

    /**
     * RAR5 外部解压工具路径（7zz/7z）。native image 模式下 7-Zip-JBinding 的 JNI 不可用，
     * 须配置本项走外部进程；JVM 模式可留空走内嵌 JNI。Optional 语义：缺省 = 未配置。
     */
    @ConfigProperty(name = "kb.archive.external-tool")
    java.util.Optional<String> archiveExternalTool;

    /**
     * 自注入：非事务编排方法调用 *InTx() 事务方法的桥接。
     * 直接 this.*InTx() 会绕过 {@code @WithTransaction} 拦截器（CDI 代理不生效）。
     */
    @jakarta.inject.Inject
    DocumentCommandService self;

    /** 文档内容抽取索引服务（fire-and-forget，详见 {@link DocumentContentIndexService}） */
    private final DocumentContentIndexService contentIndexService;

    /** 存储路径解析（快照语义 + move 前唯一化，从本类迁出的存储路径域） */
    private final DocumentStoragePathResolver storagePathResolver;

    public DocumentCommandService(KnowledgeBaseRepository knowledgeBaseRepository,
                                  CategoryRepository categoryRepository,
                                  DocumentRepository documentRepository,
                                  DocumentMetadataRepository documentMetadataRepository,
                                  DocumentOutboxRepository outboxRepository,
                                  KbCallbackProcessedRepository callbackProcessedRepository,
                                  ReviewHistoryRepository reviewHistoryRepository,
                                  DocumentStoragePathResolver storagePathResolver,
                                  FileStorageService fileStorageService,
                                  FileRecycleService fileRecycleService,
                                  DictValueValidator dictValueValidator,
                                  DocumentContentIndexService contentIndexService,
                                  Vertx vertx,
                                  @ConfigProperty(name = "kb.upload.zip-max-entries", defaultValue = "2000")
                                  int zipMaxEntries,
                                  @ConfigProperty(name = "kb.upload.zip-max-total-size", defaultValue = "1G")
                                  MemorySize zipMaxTotalSize,
                                  @ConfigProperty(name = "kb.upload.max-file-size", defaultValue = "100M")
                                  MemorySize maxFileSize) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.documentMetadataRepository = documentMetadataRepository;
        this.outboxRepository = outboxRepository;
        this.callbackProcessedRepository = callbackProcessedRepository;
        this.reviewHistoryRepository = reviewHistoryRepository;
        this.storagePathResolver = storagePathResolver;
        this.fileStorageService = fileStorageService;
        this.fileRecycleService = fileRecycleService;
        this.dictValueValidator = dictValueValidator;
        this.contentIndexService = contentIndexService;
        this.vertx = vertx;
        this.zipMaxEntries = zipMaxEntries;
        this.zipMaxTotalBytes = zipMaxTotalSize.asLongValue();
        this.maxFileSize = maxFileSize.asLongValue();
    }




    /** 从 ZIP 条目路径提取文件名（最后一段） */
    static String extractFileName(String entryPath) {
        if (entryPath == null || entryPath.isEmpty()) return "";
        int lastSlash = Math.max(entryPath.lastIndexOf('/'), entryPath.lastIndexOf('\\'));
        return lastSlash >= 0 ? entryPath.substring(lastSlash + 1) : entryPath;
    }

    /** 从 ZIP 条目路径提取目录路径（不含文件名），如 "hardware/board/doc.pdf" → "hardware/board"；根目录文件为空 {@code Optional} */
    static java.util.Optional<String> getDirectoryPath(String entryPath) {
        if (entryPath == null || entryPath.isEmpty()) return java.util.Optional.empty();
        int lastSlash = Math.max(entryPath.lastIndexOf('/'), entryPath.lastIndexOf('\\'));
        if (lastSlash <= 0) return java.util.Optional.empty(); // 根目录文件，无子目录
        return java.util.Optional.of(entryPath.substring(0, lastSlash));
    }



    /**
     * 提取并校验必填版本号（上传/版本覆盖用）：空 → A0420 + versionRequired。
     * version 由元数据提供，存入 documents.current_version（不进 document_metadata）。
     */
    static String extractRequiredVersion(String version, AppMessages appMessages) {
        if (version == null || version.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.versionRequired());
        }
        return version.trim();
    }


    /**
     * 提交审核：DRAFT → REVIEWING
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> submitReview(String operatorId, String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Submit for review: documentId={}, operatorId={}", documentId, operatorId);

        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    // 状态机校验（非法转换抛 IllegalStateException）
                    DocumentEntity updated = entity.submitReview();
                    return documentRepository.update(updated)
                            .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                                    documentId, operatorId, HISTORY_SUBMIT_REVIEW,
                                    DocumentEntity.STATUS_DRAFT, DocumentEntity.STATUS_REVIEWING,
                                    entity.getCurrentVersion(), null)));
                })
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Submit for review failed, invalid status: documentId={}, error={}", documentId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                })
                .invoke(() -> logger.info("Submitted for review: documentId={}", documentId))
                .replaceWithVoid();
    }

    /**
     * 编辑者撤回审核：REVIEWING → DRAFT
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> cancelReview(String operatorId, String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Withdraw review: documentId={}, operatorId={}", documentId, operatorId);

        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    DocumentEntity updated = entity.cancelReview();
                    return documentRepository.update(updated)
                            .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                                    documentId, operatorId, HISTORY_CANCEL_REVIEW,
                                    DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_DRAFT,
                                    entity.getCurrentVersion(), null)));
                })
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Withdraw review failed, invalid status: documentId={}, error={}", documentId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                })
                .invoke(() -> logger.info("Review withdrawn: documentId={}", documentId))
                .replaceWithVoid();
    }

    /**
     * 审核通过：REVIEWING → PUBLISHED，发布 DOCUMENT_PUBLISHED 事件通知模型服务处理
     *
     * @param operatorId 操作员 ID（审核人）
     * @param documentId 文档 ID
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> approve(String operatorId, String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Approve review: documentId={}, reviewer={}", documentId, operatorId);

        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    DocumentEntity updated = entity.approve(operatorId);
                    // 可观测性：approve 是 has_published_version 的隐式改写点（覆盖后重审通过保持 TRUE），
                    // 留 debug 日志避免下次排查时序靠猜
                    if (logger.isDebugEnabled()) {
                        logger.debug("approve writes has_published_version: documentId={}, {} -> {}",
                                documentId, entity.getHasPublishedVersion(), updated.getHasPublishedVersion());
                    }
                    return documentRepository.update(updated)
                            // 保存审核历史（同事务，状态变更与审计记录原子提交）
                            .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                                    documentId, operatorId, HISTORY_APPROVE,
                                    DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_PUBLISHED,
                                    entity.getCurrentVersion(), null)))
                            // 通知模型服务：切片+向量化（payload 取 approve 前的实体：
                            // publishedVersion/hasPublishedVersion 是"上一版"信息，approve 后会被覆盖）
                            .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(KnowledgeEvent.EventType.DOCUMENT_PUBLISHED, new KnowledgeEvent.Subject(entity.getKbId(), entity.getCategoryId(), documentId, operatorId), buildPublishedPayload(entity))));
                })
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Approve review failed, invalid status: documentId={}, error={}", documentId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                })
                .invoke(() -> logger.info("Review approved: documentId={}", documentId))
                .replaceWithVoid();
    }

    /**
     * 审核驳回：REVIEWING → DRAFT
     *
     * @param operatorId 操作员 ID（审核人）
     * @param documentId 文档 ID
     * @param reason     驳回原因（Facade 层已校验非空）
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> reject(String operatorId, String documentId, String reason, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Reject review: documentId={}, reviewer={}, reason={}", documentId, operatorId, reason);

        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    DocumentEntity updated = entity.reject(operatorId, reason);
                    return documentRepository.update(updated)
                            .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                                    documentId, operatorId, HISTORY_REJECT,
                                    DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_DRAFT,
                                    entity.getCurrentVersion(), reason)));
                })
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Reject review failed, invalid status: documentId={}, error={}", documentId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                })
                .invoke(() -> logger.info("Review rejected: documentId={}", documentId))
                .replaceWithVoid();
    }

    /**
     * 归档（仅版本级）：将 document_versions 中指定版本行置 ARCHIVED，文档生命周期不变，
     * 发布 DOCUMENT_ARCHIVED 事件（payload 携带 version/filePath/scope=VERSION，模型按
     * filePath 移除该版本向量化数据）。version 必填——不允许归档整个文档（所有版本）。
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param version    版本号（必填，指向历史版本行；空白视为参数错误）
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> archive(String operatorId, String documentId, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (version == null || version.isBlank()) {
            logger.error("Version-level archive: version must not be empty: documentId={}", documentId);
            return Uni.createFrom().failure(
                    new DocumentNotFoundException(appMessages.documentVersionNotFound()));
        }
        return archiveOrDeprecateVersion(operatorId, new VersionLifecycle(documentId.trim(), version.trim(),
                DocumentVersion.STATUS_ARCHIVED, KnowledgeEvent.EventType.DOCUMENT_ARCHIVED,
                HISTORY_ARCHIVE), locale);
    }

    /** 版本级生命周期操作入参（参数对象：目标状态/事件/历史动作三元组收敛） */
    private record VersionLifecycle(String documentId, String version, String targetStatus,
                                    KnowledgeEvent.EventType eventType, String historyAction) {
    }

    /**
     * 版本级归档/废弃公共流程：仅更新 document_versions 版本行状态（文档生命周期不变），
     * 写一条带 version 的审核历史（fromStatus=toStatus=文档当前状态），发布带
     * version/filePath/scope=VERSION payload 的事件供模型按路径清理向量化数据。
     * <p>版本不存在（含 version 指向当前版——当前版不在归档表）→ A0430 documentVersionNotFound；
     * 版本已下线（status != PUBLISHED）→ 幂等静默成功；并发下 update 守卫未命中亦幂等。</p>
     */
    private Uni<Void> archiveOrDeprecateVersion(String operatorId, VersionLifecycle ctx, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Version-level {}: documentId={}, version={}, operatorId={}, targetStatus={}",
                HISTORY_ARCHIVE.equals(ctx.historyAction()) ? "archive" : "deprecate",
                ctx.documentId(), ctx.version(), operatorId, ctx.targetStatus());
        return documentRepository.findById(DocumentId.of(ctx.documentId()))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", ctx.documentId());
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> ctx.version().equals(entity.getCurrentVersion())
                        // 当前版：版本级操作 + documents 生命周期联动（ARCHIVED/DEPRECATED）
                        ? applyCurrentVersionLifecycle(operatorId, entity, ctx, appMessages)
                        : offlineHistoricalVersion(operatorId, entity, ctx, appMessages))
                .invoke(() -> logger.info("Version-level operation succeeded: documentId={}, version={}, status={}",
                        ctx.documentId(), ctx.version(), ctx.targetStatus()));
    }

    /**
     * 历史版本行下线：仅更新 document_versions 单行状态，文档生命周期不变。
     * <p>版本不存在（含 version 指向当前版——当前版不在归档表）→ A0430 documentVersionNotFound；
     * 版本已下线（status != PUBLISHED）→ 幂等静默成功；并发下 update 守卫未命中亦幂等。
     * 成功路径写带 version 的审核历史（fromStatus=toStatus=文档当前状态）+ scope=VERSION 事件
     * 供模型按 filePath 清理该版本向量化数据。</p>
     */
    private Uni<Void> offlineHistoricalVersion(String operatorId,
                                               com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                               VersionLifecycle ctx, AppMessages appMessages) {
        String documentId = ctx.documentId();
        String version = ctx.version();
        String targetStatus = ctx.targetStatus();
        return documentRepository.findVersion(documentId, version)
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document version not found (or it points to the current version): documentId={}, version={}", documentId, version);
                    return new DocumentNotFoundException(appMessages.documentVersionNotFound());
                })
                .flatMap(v -> {
                    if (!DocumentVersion.STATUS_PUBLISHED.equals(v.status())) {
                        // 已下线版本重复操作：幂等静默成功
                        logger.info("Version already offline, idempotent skip: documentId={}, version={}, status={}",
                                documentId, version, v.status());
                        return Uni.createFrom().voidItem();
                    }
                    String filePath = v.filePath() != null ? v.filePath() : entity.getFilePath();
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("reason", targetStatus);
                    payload.put("filePath", filePath);
                    payload.put("version", version);
                    payload.put("scope", "VERSION");
                    return documentRepository.updateVersionStatus(documentId, version, targetStatus)
                            .flatMap(updated -> {
                                if (!updated) {
                                    // 并发窗口内已被其它请求下线：幂等
                                    logger.info("Version status update matched nothing (concurrent offline), idempotent: documentId={}, version={}",
                                            documentId, version);
                                    return Uni.createFrom().voidItem();
                                }
                                return reviewHistoryRepository.save(new ReviewHistoryEntry(
                                        documentId, operatorId, ctx.historyAction(),
                                        entity.getStatus(), entity.getStatus(), version, null))
                                        .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(ctx.eventType(), new KnowledgeEvent.Subject(entity.getKbId(), entity.getCategoryId(), documentId, operatorId), payload)));
                            });
                });
    }
    /**
     * 当前版归档/废弃：版本行按需物化并置目标状态 + documents 生命周期联动。
     * <p>守卫：目标态重复操作幂等静默成功；仅 PUBLISHED 可归档/废弃（复用
     * {@link DocumentEntity#archive()}/{@link DocumentEntity#deprecate()} 状态机，
     * 其余状态 → DocumentStateException）。物化：document_versions 无当前版行时从
     * documents 列物化（wasPublished=TRUE，status=目标状态）；已有 PUBLISHED 行则原位更新状态。
     * 联动：documents.status → 目标状态；废弃发 DOCUMENT_DELETED（不带 scope，文档级清向量），
     * 归档发 eventType（filePath+version，无 scope=VERSION）。</p>
     */
    private Uni<Void> applyCurrentVersionLifecycle(String operatorId,
                                                   com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                                   VersionLifecycle ctx, AppMessages appMessages) {
        String version = ctx.version();
        String targetStatus = ctx.targetStatus();
        KnowledgeEvent.EventType eventType = ctx.eventType();
        String historyAction = ctx.historyAction();
        String documentId = entity.getId().value();
        String fromStatus = entity.getStatus();
        if (targetStatus.equals(fromStatus)) {
            // 文档已处于目标生命周期：幂等静默成功（与版本级重复下线口径一致）
            logger.info("Current version {} idempotent skip: documentId={}, version={}, status={}",
                    HISTORY_ARCHIVE.equals(historyAction) ? "archive" : "deprecate", documentId, version, fromStatus);
            return Uni.createFrom().voidItem();
        }
        DocumentEntity updated;
        try {
            updated = DocumentVersion.STATUS_ARCHIVED.equals(targetStatus)
                    ? entity.archive() : entity.deprecate();
        } catch (IllegalStateException e) {
            logger.error("Current version {} has an invalid status: documentId={}, version={}, status={}",
                    HISTORY_ARCHIVE.equals(historyAction) ? "archive" : "deprecate", documentId, version, fromStatus);
            return Uni.createFrom().failure(new DocumentStateException(appMessages.documentStateInvalid()));
        }
        return documentRepository.findVersion(documentId, version)
                .flatMap(existing -> existing == null
                        // 物化当前版行：documents 列 → document_versions（status=目标状态）
                        ? documentRepository.archiveVersion(new DocumentVersion(
                                documentId, version, entity.getFilePath(), entity.getFileSize(),
                                entity.getMimeType(), Boolean.TRUE, targetStatus,
                                entity.getCreatedBy(), entity.getCreatedAt()))
                        : documentRepository.updateVersionStatus(documentId, version, targetStatus)
                                .replaceWithVoid())
                .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                        documentId, operatorId, historyAction,
                        fromStatus, targetStatus, version, null)))
                .call(() -> documentRepository.update(updated))
                .call(() -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("reason", targetStatus);
                    if (entity.getFilePath() != null) {
                        payload.put("filePath", entity.getFilePath());
                    }
                    payload.put("version", version);
                    return outboxRepository.insert(KnowledgeEvent.withPayload(eventType, new KnowledgeEvent.Subject(entity.getKbId(), entity.getCategoryId(), documentId, operatorId), payload));
                });
    }

    /**
     * 废弃（仅版本级）：将 document_versions 中指定版本行置 DEPRECATED，文档生命周期不变，
     * 发布 DOCUMENT_DELETED 事件（payload 携带 version/filePath/scope=VERSION）供模型按路径
     * 清理向量化数据。version 必填——不允许废弃整个文档（所有版本）。
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param version    版本号（必填，指向历史版本行；空白视为参数错误）
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> deprecate(String operatorId, String documentId, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (version == null || version.isBlank()) {
            logger.error("Version-level deprecate: version must not be empty: documentId={}", documentId);
            return Uni.createFrom().failure(
                    new DocumentNotFoundException(appMessages.documentVersionNotFound()));
        }
        return archiveOrDeprecateVersion(operatorId, new VersionLifecycle(documentId.trim(), version.trim(),
                DocumentVersion.STATUS_DEPRECATED, KnowledgeEvent.EventType.DOCUMENT_DELETED,
                HISTORY_DEPRECATE), locale);
    }

    // ========== 批量审核 ==========

    private static final String SCOPE_KB = "KB";
    private static final String SCOPE_CATEGORY = "CATEGORY";
    private static final String SCOPE_DOCUMENT = "DOCUMENT";
    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT = "REJECT";

    // ========== 审核历史动作常量 ==========
    private static final String HISTORY_SUBMIT_REVIEW = "SUBMIT_REVIEW";
    private static final String HISTORY_CANCEL_REVIEW = "CANCEL_REVIEW";
    private static final String HISTORY_APPROVE = "APPROVE";
    private static final String HISTORY_REJECT = "REJECT";
    private static final String HISTORY_ARCHIVE = "ARCHIVE";
    private static final String HISTORY_DEPRECATE = "DEPRECATE";

    /** 批量审核/提交审核单次处理上限：KB/CATEGORY/DOCUMENT 范围超此数只审前 1000 条（按 createdAt），remainingCount 告知剩余 */
    private static final int BATCH_REVIEW_MAX_PER_RUN = 1000;

    /**
     * 批量审核：对 KB / CATEGORY（递归子分类）/ DOCUMENT 三种范围之一的审核中文档批量通过或驳回。
     * <p>处理逻辑同单个 {@link #approve}/{@link #reject}：
     * <ul>
     *   <li>仅 REVIEWING 文档可流转；非 REVIEWING 文档记为 SKIPPED（KB/CATEGORY 模式 DB 已过滤不计入，
     *       DOCUMENT 模式显式列举故报告跳过）</li>
     *   <li>approve → PUBLISHED + 写 outbox DOCUMENT_PUBLISHED 事件（payload 用 approve 前的实体，同单个）</li>
     *   <li>reject → DRAFT + 记录驳回原因到 review_comment</li>
     * </ul>
     * 整批在单一 {@code @WithTransaction} 内完成（状态变更 + outbox 同事务提交，原子性）；
     * 顺序处理（Multi concatenate 逐条等待前一条完成）避免同一 Hibernate reactive session 并发破坏 LoadContexts。
     *
     * @param operatorId 操作员 ID（审核人）
     * @param command    批量审核命令
     * @param locale     语言环境
     * @return 逐条结果 + 汇总统计
     */
    @WithTransaction
    public Uni<BatchReviewResponse> batchReview(String operatorId, BatchReviewCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String scope = command.scope() == null ? null : command.scope().trim();
        String action = command.action() == null ? null : command.action().trim();
        // 1) scope / action 合法性 + 条件必填校验 + 规整
        String kbId = command.kbId() == null ? null : command.kbId().trim();
        if (!SCOPE_KB.equals(scope) && !SCOPE_CATEGORY.equals(scope) && !SCOPE_DOCUMENT.equals(scope)) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewScopeInvalid());
        }
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action)) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewActionInvalid());
        }
        String reason = command.reason() == null ? null : command.reason().trim();
        if (ACTION_REJECT.equals(action) && (reason == null || reason.isBlank())) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewReasonRequired());
        }
        BatchScope s = normalizeBatchScope(scope, kbId, command.categoryIds(), command.documentIds(), appMessages);
        logger.info("Batch review: scope={}, action={}, kbId={}, categoryCount={}, documentCount={}, operatorId={}",
                s.scope(), action, s.kbId(),
                s.categoryIds() != null ? s.categoryIds().size() : 0,
                s.documentIds() != null ? s.documentIds().size() : 0,
                operatorId);

        // 2) 按范围解析目标文档 → 3) 上限分流 → 4) 顺序处理 + 汇总
        return resolveReviewTargets(s, appMessages, false)
                .flatMap(docs -> {
                    Partition p = partitionDocs(s, docs);
                    List<BatchReviewItem> results = buildReviewSkips(p, action, appMessages);
                    return processAllSequentially(p.scopedDocs(), operatorId, action, reason, appMessages)
                            .map(processed -> summarizeBatchReview(results, processed, p.remaining()));
                })
                .invoke(resp -> logger.info("Batch review completed: action={}, total={}, success={}, skipped={}, operatorId={}",
                        action, resp.getSummary().getTotal(), resp.getSummary().getSuccess(),
                        resp.getSummary().getSkipped(), operatorId));
    }

    /** DOCUMENT/跨库两类前置 SKIPPED 条目（未命中 id + 跨库文档） */
    private List<BatchReviewItem> buildReviewSkips(Partition p, String action, AppMessages appMessages) {
        List<BatchReviewItem> results = new ArrayList<>();
        for (String did : p.missingIds()) {
            results.add(BatchReviewItem.skipped(did, null,
                    appMessages.documentNotFound(),
                    ErrorCodeEnum.USER_ERROR_A0430.getCode(), action));
        }
        for (DocumentEntity d : p.crossKbDocs()) {
            results.add(BatchReviewItem.skipped(d.getId().value(), d.getTitle(),
                    appMessages.documentKbMismatch(),
                    ErrorCodeEnum.USER_ERROR_A0430.getCode(), action));
        }
        return results;
    }

    /** 汇总批量审核响应：前置 SKIPPED + 逐条处理结果 + 汇总统计 */
    private BatchReviewResponse summarizeBatchReview(List<BatchReviewItem> skips,
                                                     List<BatchReviewItem> processed, int remaining) {
        List<BatchReviewItem> results = new ArrayList<>(skips);
        results.addAll(processed);
        int total = results.size();
        int success = (int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
        int skipped = (int) results.stream().filter(r -> "SKIPPED".equals(r.getStatus())).count();
        return new BatchReviewResponse(results, new BatchReviewSummary(total, success, skipped, remaining));
    }    /**
     * 顺序处理文档列表（Multi concatenate 串行逐条处理）。
     * <p>逐个等待前一条完成才处理下一条，保证同一 Hibernate reactive session 串行。
     * <b>不用递归 flatMap 折叠</b>：每层 flatMap 嵌套一个算子，调用栈深度随文档数线性增长，
     * 批量上限 1000 时实际 StackOverflowError（Mutiny 无 trampoline，原 javadoc 的说法错误）；
     * {@code concatenate()} 是单个算子内部迭代 drain，栈深恒定，结果按输入顺序输出。</p>
     */
    private Uni<List<BatchReviewItem>> processAllSequentially(List<DocumentEntity> docs,
                                                              String operatorId, String action,
                                                              String reason, AppMessages appMessages) {
        return Multi.createFrom().iterable(docs)
                .onItem().transformToUni(doc -> processOne(doc, operatorId, action, reason, appMessages))
                .concatenate()
                .collect().asList();
    }

    /**
     * 单条审核处理：复用领域状态机 {@link DocumentEntity#approve}/{@link DocumentEntity#reject}
     * + outbox 事件（approve），与单个审核逻辑完全一致。非 REVIEWING 记为 SKIPPED。
     */
    private Uni<BatchReviewItem> processOne(DocumentEntity entity, String operatorId,
                                            String action, String reason, AppMessages appMessages) {
        String docId = entity.getId().value();
        String title = entity.getTitle();
        // 非 REVIEWING → 跳过（KB/CATEGORY 模式 DB 已过滤，此处兜底；DOCUMENT 模式主要靠此判定）
        if (!DocumentEntity.STATUS_REVIEWING.equals(entity.getStatus())) {
            return Uni.createFrom().item(BatchReviewItem.skipped(docId, title,
                    appMessages.documentNotReviewing(),
                    ErrorCodeEnum.USER_ERROR_A0508.getCode(), action));
        }
        if (ACTION_APPROVE.equals(action)) {
            // 复用领域状态机 + outbox（与单个 approve 一致：payload 用 approve 前的 entity）
            DocumentEntity updated = entity.approve(operatorId);
            // 同单个 approve：隐式改写点留 debug 日志
            if (logger.isDebugEnabled()) {
                logger.debug("batch approve writes has_published_version: documentId={}, {} -> {}",
                        docId, entity.getHasPublishedVersion(), updated.getHasPublishedVersion());
            }
            return documentRepository.update(updated)
                    .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                            docId, operatorId, HISTORY_APPROVE,
                            DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_PUBLISHED,
                            entity.getCurrentVersion(), null)))
                    .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(KnowledgeEvent.EventType.DOCUMENT_PUBLISHED, new KnowledgeEvent.Subject(entity.getKbId(), entity.getCategoryId(), docId, operatorId), buildPublishedPayload(entity))))
                    .map(v -> BatchReviewItem.success(docId, title,
                            DocumentEntity.STATUS_PUBLISHED, action));
        }
        // REJECT
        DocumentEntity updated = entity.reject(operatorId, reason);
        return documentRepository.update(updated)
                .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                        docId, operatorId, HISTORY_REJECT,
                        DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_DRAFT,
                        entity.getCurrentVersion(), reason)))
                .map(v -> BatchReviewItem.success(docId, title,
                        DocumentEntity.STATUS_DRAFT, action));
    }

    // ========== 批量提交审核 ==========

    /**
     * 批量提交审核：对 KB / CATEGORY（递归子分类）/ DOCUMENT 三种范围之一的草稿文档批量提交审核（DRAFT → REVIEWING）。
     * <p>处理逻辑同单个 {@link #submitReview}：
     * <ul>
     *   <li>仅 DRAFT 文档可提交；非 DRAFT 文档记为 SKIPPED（KB/CATEGORY 模式 DB 已过滤不计入，
     *       DOCUMENT 模式显式列举故报告跳过）</li>
     *   <li>提交审核 → REVIEWING + 写 reviewHistory（SUBMIT_REVIEW 动作）；不发 outbox（提交审核不触发模型处理）</li>
     * </ul>
     * 整批在单一 {@code @WithTransaction} 内完成；顺序处理（Multi concatenate 逐条等待前一条完成）避免同一 Hibernate reactive session 并发破坏 LoadContexts。
     *
     * @param operatorId 操作员 ID（提交人）
     * @param command    批量提交审核命令
     * @param locale     语言环境
     * @return 逐条结果 + 汇总统计
     */
    @WithTransaction
    public Uni<BatchSubmitReviewResponse> batchSubmitReview(String operatorId, BatchSubmitReviewCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String scope = command.scope() == null ? null : command.scope().trim();
        // 1) scope 合法性 + 条件必填校验 + 规整
        String kbId = command.kbId() == null ? null : command.kbId().trim();
        List<String> categoryIds = command.categoryIds();
        List<String> documentIds = command.documentIds();
        if (!SCOPE_KB.equals(scope) && !SCOPE_CATEGORY.equals(scope) && !SCOPE_DOCUMENT.equals(scope)) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchSubmitReviewScopeInvalid());
        }
        BatchScope s = normalizeBatchScope(scope, kbId, categoryIds, documentIds, appMessages);
        logger.info("Batch submit for review: scope={}, kbId={}, categoryCount={}, documentCount={}, operatorId={}",
                s.scope(), s.kbId(),
                s.categoryIds() != null ? s.categoryIds().size() : 0,
                s.documentIds() != null ? s.documentIds().size() : 0,
                operatorId);

        // 2) 按范围解析目标文档 → 3) 上限分流 → 4) 顺序处理 + 汇总
        return resolveReviewTargets(s, appMessages, true)
                .flatMap(docs -> {
                    Partition p = partitionDocs(s, docs);
                    List<BatchSubmitReviewItem> results = new ArrayList<>();
                    for (String did : p.missingIds()) {
                        results.add(BatchSubmitReviewItem.skipped(did, null,
                                appMessages.documentNotFound(),
                                ErrorCodeEnum.USER_ERROR_A0430.getCode()));
                    }
                    for (DocumentEntity d : p.crossKbDocs()) {
                        results.add(BatchSubmitReviewItem.skipped(d.getId().value(), d.getTitle(),
                                appMessages.documentKbMismatch(),
                                ErrorCodeEnum.USER_ERROR_A0430.getCode()));
                    }
                    return processAllSequentiallyForSubmit(p.scopedDocs(), operatorId, appMessages)
                            .map(processed -> {
                                results.addAll(processed);
                                int total = results.size();
                                int success = (int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
                                int skipped = (int) results.stream().filter(r -> "SKIPPED".equals(r.getStatus())).count();
                                return new BatchSubmitReviewResponse(results, new BatchSubmitReviewSummary(total, success, skipped, p.remaining()));
                            });
                })
                .invoke(resp -> logger.info("Batch submit for review completed: total={}, success={}, skipped={}, operatorId={}",
                        resp.getSummary().getTotal(), resp.getSummary().getSuccess(),
                        resp.getSummary().getSkipped(), operatorId));
    }

    /** 批量操作范围（参数对象：scope + kb + 类别/文档列举） */
    private record BatchScope(String scope, String kbId, List<String> categoryIds, List<String> documentIds) {
    }

    /** 范围分流结果：未命中 id / 跨库文档 / 本轮处理文档 / 留待下一批数量 */
    private record Partition(List<String> missingIds, List<DocumentEntity> crossKbDocs,
                             List<DocumentEntity> scopedDocs, int remaining) {
    }

    /**
     * 批量操作条件必填校验 + 规整（scope 已验合法）：kbId 必填；CATEGORY 需非空 categoryIds；
     * DOCUMENT 需非空 documentIds。返回携带规整后列表的 BatchScope（不改入参列表）。
     */
    private BatchScope normalizeBatchScope(String scope, String kbId, List<String> categoryIds,
                                           List<String> documentIds, AppMessages appMessages) {
        if (kbId == null || kbId.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewKbIdRequired());
        }
        List<String> normalizedCategories = categoryIds;
        List<String> normalizedDocuments = documentIds;
        if (SCOPE_CATEGORY.equals(scope)) {
            normalizedCategories = normalizeIdList(categoryIds);
            if (normalizedCategories.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewCategoryIdsRequired());
            }
        }
        if (SCOPE_DOCUMENT.equals(scope)) {
            normalizedDocuments = normalizeIdList(documentIds);
            if (normalizedDocuments.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.batchReviewDocumentIdsRequired());
            }
        }
        return new BatchScope(scope, kbId, normalizedCategories, normalizedDocuments);
    }

    /**
     * 按范围解析批量目标文档：KB（KB 存在且活跃 → reviewing/draft 全量）、
     * CATEGORY（校验选中分类属于该 KB → 子树收集 → reviewing/draft 全量）、
     * DOCUMENT（KB 校验 → 按 id 全状态加载，跳过判定交由处理分支）。
     *
     * @param draftsOnly true=批量提交审核（查 DRAFT）；false=批量审核（查 REVIEWING）
     */
    private Uni<List<DocumentEntity>> resolveReviewTargets(BatchScope s, AppMessages appMessages, boolean draftsOnly) {
        String scope = s.scope();
        String kbId = s.kbId();
        Uni<KnowledgeBaseEntity> kbUni = knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
                .flatMap(kb -> KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())
                        ? Uni.createFrom().item(kb)
                        : Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound())));
        if (SCOPE_KB.equals(scope)) {
            return kbUni.flatMap(kb -> draftsOnly
                    ? documentRepository.findDraftByKbId(kbId)
                    : documentRepository.findReviewingByKbId(kbId));
        }
        if (SCOPE_CATEGORY.equals(scope)) {
            return kbUni.flatMap(kb -> categoryRepository.findAllByKbId(kbId)
                    .map(all -> {
                        Set<String> allIds = all.stream()
                                .map(cat -> cat.getId().value()).collect(Collectors.toSet());
                        // 校验每个 selected categoryId 存在且属于该 KB
                        for (String cid : s.categoryIds()) {
                            if (!allIds.contains(cid)) {
                                throw new CategoryNotFoundException(appMessages.categoryNotFound());
                            }
                        }
                        // 收集子树：选中分类 + 各自全部后代
                        Set<String> target = new HashSet<>(s.categoryIds());
                        for (String cid : s.categoryIds()) {
                            target.addAll(categoryTreeService.collectSubtreeIds(all, cid));
                        }
                        return target;
                    })
                    .flatMap(target -> draftsOnly
                            ? documentRepository.findDraftByCategoryIds(kbId, target)
                            : documentRepository.findReviewingByCategoryIds(kbId, target)));
        }
        // DOCUMENT 模式：全状态加载（含非目标状态 + 跨库，由处理分支判定跳过）
        return kbUni.flatMap(kb -> documentRepository.findByIds(s.documentIds()));
    }

    /**
     * 批量上限分流：DOCUMENT 模式截断到前 {@link #BATCH_REVIEW_MAX_PER_RUN} 个 id
     * （未命中 id 与跨库文档记入 missing/crossKb 由调用方生成 SKIPPED；同库超出上限留待下一批）；
     * KB/CATEGORY 模式按 SQL 序截断前 N 条。
     */
    private Partition partitionDocs(BatchScope s, List<DocumentEntity> docs) {
        List<String> missing = new ArrayList<>();
        List<DocumentEntity> crossKb = new ArrayList<>();
        List<DocumentEntity> scopedDocs;
        int remaining;
        if (SCOPE_DOCUMENT.equals(s.scope())) {
            List<String> ids = s.documentIds();
            List<String> limitedIds = (ids.size() > BATCH_REVIEW_MAX_PER_RUN)
                    ? new ArrayList<>(ids.subList(0, BATCH_REVIEW_MAX_PER_RUN)) : ids;
            remaining = ids.size() - limitedIds.size();
            Set<String> foundIds = docs.stream()
                    .map(d -> d.getId().value()).collect(Collectors.toSet());
            // 未命中：文档不存在 → SKIPPED
            for (String did : limitedIds) {
                if (!foundIds.contains(did)) {
                    missing.add(did);
                }
            }
            // 跨库文档：不属于该 KB → SKIPPED；仅处理同库且在截断内的文档
            scopedDocs = new ArrayList<>();
            for (DocumentEntity d : docs) {
                if (!s.kbId().equals(d.getKbId())) {
                    crossKb.add(d);
                } else if (limitedIds.contains(d.getId().value())) {
                    scopedDocs.add(d);
                } else {
                    // 同库但超出本次截断上限：留待下一批，不计入本轮结果
                    logger.debug("Batch review deferred by run limit: documentId={}", d.getId().value());
                }
            }
        } else {
            // KB/CATEGORY：截断到前 BATCH_REVIEW_MAX_PER_RUN 条（按 createdAt 升序，由 SQL 保证）
            if (docs.size() > BATCH_REVIEW_MAX_PER_RUN) {
                scopedDocs = new ArrayList<>(docs.subList(0, BATCH_REVIEW_MAX_PER_RUN));
                remaining = docs.size() - BATCH_REVIEW_MAX_PER_RUN;
                logger.info("Batch scope {} resolved {} documents, exceeding the limit {}; only the first {} will be processed, {} remaining",
                        s.scope(), docs.size(), BATCH_REVIEW_MAX_PER_RUN, BATCH_REVIEW_MAX_PER_RUN, remaining);
            } else {
                scopedDocs = docs;
                remaining = 0;
            }
        }
        return new Partition(missing, crossKb, scopedDocs, remaining);
    }
    /**
     * 顺序处理文档列表（Multi concatenate 串行逐条处理）——批量提交审核专用。
     * <p>与 {@link #processAllSequentially} 同构，仅返回类型不同（{@link BatchSubmitReviewItem}）。
     * 不用递归 flatMap（栈深随文档数线性增长，上限 1000 时 StackOverflowError）。</p>
     */
    private Uni<List<BatchSubmitReviewItem>> processAllSequentiallyForSubmit(List<DocumentEntity> docs,
                                                                             String operatorId, AppMessages appMessages) {
        return Multi.createFrom().iterable(docs)
                .onItem().transformToUni(doc -> processOneSubmitReview(doc, operatorId, appMessages))
                .concatenate()
                .collect().asList();
    }

    /**
     * 单条提交审核处理：复用领域状态机 {@link DocumentEntity#submitReview} + reviewHistory，
     * 与单个 {@link #submitReview} 逻辑完全一致。非 DRAFT 记为 SKIPPED。不发 outbox（提交审核不触发模型处理）。
     */
    private Uni<BatchSubmitReviewItem> processOneSubmitReview(DocumentEntity entity, String operatorId,
                                                              AppMessages appMessages) {
        String docId = entity.getId().value();
        String title = entity.getTitle();
        // 非 DRAFT → 跳过（KB/CATEGORY 模式 DB 已过滤，此处兜底；DOCUMENT 模式主要靠此判定）
        if (!DocumentEntity.STATUS_DRAFT.equals(entity.getStatus())) {
            return Uni.createFrom().item(BatchSubmitReviewItem.skipped(docId, title,
                    appMessages.documentNotDraft(),
                    ErrorCodeEnum.USER_ERROR_A0508.getCode()));
        }
        // 复用领域状态机（与单个 submitReview 一致）
        DocumentEntity updated = entity.submitReview();
        return documentRepository.update(updated)
                .call(() -> reviewHistoryRepository.save(new ReviewHistoryEntry(
                        docId, operatorId, HISTORY_SUBMIT_REVIEW,
                        DocumentEntity.STATUS_DRAFT, DocumentEntity.STATUS_REVIEWING,
                        entity.getCurrentVersion(), null)))
                .map(v -> BatchSubmitReviewItem.success(docId, title,
                        DocumentEntity.STATUS_REVIEWING));
    }

    /** 规整 ID 列表：trim + 过滤空白 + 去重 */
    private List<String> normalizeIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 物理删除（<b>仅版本级</b>，version 必传）：移除 document_versions 指定版本行 + 回收文件
     * + 发 DOCUMENT_DELETED(scope=VERSION) 通知模型清该版本向量。
     * <p>version 指向<b>当前版</b>时：删除当前版文件并级联删除 documents 行
     * （document_versions 历史行保留，current_version 随 documents 行消失）。
     * version 缺失 → A0420（文档级删除路径已移除，前端必须传版本号）。
     * 生命周期守卫：仅 DRAFT 可删，其余状态 → A0508「仅草稿状态的文档可删除」。</p>
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param version    版本号（必填；当前版=删 documents 行+当前版文件；历史版=仅删版本行）
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @io.quarkus.hibernate.reactive.panache.common.WithSession
    public Uni<Void> delete(String operatorId, String documentId, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (version == null || version.isBlank()) {
            logger.error("Hard delete requires version (only version-level delete is supported): documentId={}", documentId);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.versionRequired()));
        }
        return deleteVersion(operatorId, documentId.trim(), version.trim(), locale);
    }

    /**
     * 事务内执行：删除 documents 行 + document_metadata（保留 document_versions 历史行，
     * 当前版本级删除路径调用，无 outbox 写入——由调用方负责事件与文件回收）
     * <p>通过 {@code self} 自注入调用，保证 {@code @WithTransaction} 拦截器生效。</p>
     *
     * @param documentId 文档 ID
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> deleteCurrentVersionInTx(String documentId) {
        return documentRepository.deleteDocumentKeepVersions(DocumentId.of(documentId));
    }

    /**
     * 版本级物理删除：移除 document_versions 指定版本行 + 回收文件 + 通知模型清该版本向量。
     * <p>传<b>当前版</b>时：删除当前版文件并级联删除 documents 行（document_versions 历史行保留，
     * current_version 随 documents 行消失），事件按文档级口径清理当前版向量。
     * <p>历史版流程：findVersion（缺失 → A0430）→ 快照版本文件 → 事务内
     * {@link #deleteVersionInTx}（删版本行 + 审核历史 + outbox DOCUMENT_DELETED scope=VERSION）
     * → 事务后身份校验回收文件。</p>
     */
    private Uni<Void> deleteVersion(String operatorId, String documentId, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Version-level delete: documentId={}, version={}, operatorId={}", documentId, version, operatorId);
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    // 生命周期守卫：仅 DRAFT 可删除（当前版与历史版本行同等约束；
                    // REVIEWING/PUBLISHED/ARCHIVED/DEPRECATED 一律拒绝，请走 deprecate/版本级下线）
                    if (!DocumentEntity.STATUS_DRAFT.equals(entity.getStatus())) {
                        logger.error("Only documents in DRAFT status can be deleted: documentId={}, status={}, version={}",
                                documentId, entity.getStatus(), version);
                        return Uni.createFrom().failure(
                                new DocumentStateException(appMessages.documentOnlyDraftCanDelete()));
                    }
                    if (version.equals(entity.getCurrentVersion())) {
                        return deleteCurrentVersion(operatorId, entity, version, locale);
                    }
                    return documentRepository.findVersion(documentId, version)
                            .onItem().ifNull().failWith(() -> {
                                logger.error("Document version not found: documentId={}, version={}", documentId, version);
                                return new DocumentNotFoundException(appMessages.documentVersionNotFound());
                            })
                            .flatMap(v -> {
                                if (v.filePath() == null || v.filePath().isBlank()) {
                                    // 版本行无文件（历史脏数据）：仅删行 + 事件，跳过文件回收
                                    return self.deleteVersionInTx(operatorId, new VersionTarget(
                                            documentId, version, entity.getKbId(), entity.getCategoryId(),
                                            v.filePath()), locale)
                                            .map(x -> null);
                                }
                                return fileRecycleService.snapshot(List.of(v.filePath()))
                                        .flatMap(snapshots -> self.deleteVersionInTx(
                                                operatorId, new VersionTarget(
                                                        documentId, version, entity.getKbId(),
                                                        entity.getCategoryId(), v.filePath()), locale)
                                                .call(() -> fileRecycleService.recycleGuarded(
                                                        "documentId=" + documentId + ",version=" + version, snapshots))
                                                .map(x -> null));
                            });
                })
                .invoke(() -> logger.info("Version-level delete succeeded: documentId={}, version={}, operatorId={}",
                        documentId, version, operatorId))
                .replaceWithVoid();
    }

    /**
     * 当前版本级删除：删除当前版文件 + documents 行（保留 document_versions 历史行）。
     * <p>流程：快照当前版文件 → 事务内 {@link #deleteCurrentVersionInTx}（删 documents +
     * document_metadata，历史版本行保留）+ 审核历史 + outbox DOCUMENT_DELETED
     * （payload {reason:CURRENT_VERSION_DELETED, filePath, version}，模型清当前版向量）
     * → 事务后身份校验回收当前版文件。文档删除后 current_version 随 documents 行消失。</p>
     */
    private Uni<Void> deleteCurrentVersion(String operatorId,
                                           com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                           String version, String locale) {
        String documentId = entity.getId().value();
        String filePath = entity.getFilePath();
        logger.info("Delete current version: documentId={}, version={}, operatorId={}", documentId, version, operatorId);
        return fileRecycleService.snapshot(
                        filePath == null || filePath.isBlank() ? List.of() : List.of(filePath))
                .flatMap(snapshots -> self.deleteCurrentVersionInTx(documentId)
                        .flatMap(v -> {
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("reason", "CURRENT_VERSION_DELETED");
                            if (filePath != null) {
                                payload.put("filePath", filePath);
                            }
                            payload.put("version", version);
                            return reviewHistoryRepository.save(new ReviewHistoryEntry(
                                            documentId, operatorId, "DELETE",
                                            entity.getStatus(), "DELETED", version, null))
                                    .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(KnowledgeEvent.EventType.DOCUMENT_DELETED, new KnowledgeEvent.Subject(entity.getKbId(), entity.getCategoryId(), documentId, operatorId), payload)));
                        })
                        // 事务提交后：身份校验回收当前版文件（历史版本文件保留）
                        .call(() -> snapshots.isEmpty()
                                ? Uni.createFrom().voidItem()
                                : fileRecycleService.recycleGuarded(
                                        "documentId=" + documentId + ",version=" + version, snapshots)));
    }

    /** 版本级删除入参（参数对象：目标版本五元组收敛） */
    public record VersionTarget(String documentId, String version, String kbId,
                                String categoryId, String filePath) {
    }

    /**
     * 事务内版本级删除：删 document_versions 单行 + 写带 version 的审核历史（action=DELETE）
     * + outbox DOCUMENT_DELETED（payload {reason:VERSION_DELETED, filePath, version, scope:VERSION}，
     * 模型按 filePath 清该版本向量）。文件回收在事务后由调用方完成。
     */
    @WithTransaction
    public Uni<Void> deleteVersionInTx(String operatorId, VersionTarget target, String locale) {
        String documentId = target.documentId();
        String version = target.version();
        String kbId = target.kbId();
        String categoryId = target.categoryId();
        String filePath = target.filePath();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return documentRepository.deleteVersion(documentId, version)
                .flatMap(deleted -> {
                    if (!deleted) {
                        // 并发/重复删除：幂等
                        logger.info("Version row not found, idempotent skip: documentId={}, version={}", documentId, version);
                        return Uni.createFrom().voidItem();
                    }
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("reason", "VERSION_DELETED");
                    if (filePath != null) {
                        payload.put("filePath", filePath);
                    }
                    payload.put("version", version);
                    payload.put("scope", "VERSION");
                    return reviewHistoryRepository.save(new ReviewHistoryEntry(
                                    documentId, operatorId, "DELETE_VERSION",
                                    "VERSION", "DELETED", version, null))
                            .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(KnowledgeEvent.EventType.DOCUMENT_DELETED, new KnowledgeEvent.Subject(kbId, categoryId, documentId, operatorId), payload)));
                });
    }

    // ========== 批量物理删除 ==========

    /** 批量删除单次请求上限（去重后）：防一次删除文件过多导致请求超时/网络错误 */
    private static final int BATCH_DELETE_MAX = 200;

    /**
     * 批量物理删除（仅版本级）：逐条复用 {@link #delete} 版本级语义（移除指定版本行 + 文件回收
     * + 通知模型清该版本向量），文档本体不变。
     * <p>防误操作护栏：不支持文档级批量删除；每个条目必须携带 version（缺 version → A0420
     * fail-closed 整批拒绝，避免部分执行）。逐条独立执行——每条各自事务，单条失败
     * （版本不存在 A0430 / 非 DRAFT 状态不可删 A0508 / 执行异常 B0001）只记入该条结果，不影响其余。
     * 顺序处理（Multi concatenate 逐条等待前一条完成）。</p>
     * <ul>
     *   <li>{@code DELETED} —— 版本行删除成功</li>
     *   <li>{@code SKIPPED} —— 版本不存在（A0430）/ 非 DRAFT 状态不可删（A0508「仅草稿状态的文档可删除」）</li>
     *   <li>{@code FAILED} —— 单条执行异常（系统/IO 失败，B0001），不影响其余</li>
     * </ul>
     * 整体恒为成功（code=0，HTTP 200）：部分失败也成功返回，与批量审核的容忍语义一致。
     *
     * @param operatorId 操作员 ID
     * @param items      版本级删除目标列表（每条 {documentId, version}，version 必填；
     *                   规整后要求 1~{@value #BATCH_DELETE_MAX} 条）
     * @param locale     语言环境
     * @return 逐条结果 + 汇总统计
     */
    public Uni<BatchDeleteResponse> batchDelete(String operatorId,
                                                List<com.hisilicon.knowledge.platform.knowledge.command.BatchDeleteCommand.DeleteItem> items,
                                                String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 1) 规整 + fail-closed 校验：仅版本级；每条必须带 version；1~BATCH_DELETE_MAX 条
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.batchDeleteDocumentIdsRequired());
        }
        List<DeleteTarget> targets = new java.util.ArrayList<>();
        for (com.hisilicon.knowledge.platform.knowledge.command.BatchDeleteCommand.DeleteItem item : items) {
            String id = item.documentId() == null ? "" : item.documentId().trim();
            String ver = item.version() == null ? "" : item.version().trim();
            if (id.isEmpty() || ver.isEmpty()) {
                // 防误操作：任一条目缺版本号 → 整批拒绝（不静默部分执行）
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.batchDeleteVersionRequired());
            }
            targets.add(new DeleteTarget(id, ver));
        }
        if (targets.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.batchDeleteCountExceeded(BATCH_DELETE_MAX));
        }

        logger.info("Batch delete (version level): count={}, operatorId={}", targets.size(), operatorId);

        // 2) 顺序逐条删除（独立事务，失败互不影响）
        return Multi.createFrom().iterable(targets)
                .onItem().transformToUni(target -> deleteOneForBatch(target, operatorId, locale, appMessages))
                .concatenate()
                .collect().asList()
                .map(results -> {
                    int deleted = (int) results.stream()
                            .filter(r -> BatchDeleteItem.STATUS_DELETED.equals(r.getStatus())).count();
                    int skipped = (int) results.stream()
                            .filter(r -> BatchDeleteItem.STATUS_SKIPPED.equals(r.getStatus())).count();
                    int failed = (int) results.stream()
                            .filter(r -> BatchDeleteItem.STATUS_FAILED.equals(r.getStatus())).count();
                    return new BatchDeleteResponse(results,
                            new BatchDeleteSummary(targets.size(), deleted, skipped, failed));
                })
                .invoke(resp -> logger.info("Batch delete completed: total={}, deleted={}, skipped={}, failed={}, operatorId={}",
                        resp.getSummary().getTotal(), resp.getSummary().getDeleted(),
                        resp.getSummary().getSkipped(), resp.getSummary().getFailed(), operatorId));
    }

    /** 批量删除目标：{@param documentId} 文档 ID，{@param version} 版本号（null=文档级；非 null=版本级） */
    /** 任务型响应占位（避免裸 {@code (Void) null} 强转，G.TYP.13） */
    static Void voidTaskResult() {
        return null;
    }

    private record DeleteTarget(String documentId, String version) {
    }

    /**
     * 批量删除单条：复用 {@link #delete} 全部语义，异常归一为逐条结果（不向上传播，不中断整批）。
     * <p>不存在 → SKIPPED(A0430)；非 DRAFT 状态不可删 → SKIPPED(A0508「仅草稿状态的文档可删除」)；
     * 版本不存在/指向当前版（版本级）→ SKIPPED(A0430)；其余执行异常 → FAILED(B0001)。</p>
     */
    private Uni<BatchDeleteItem> deleteOneForBatch(DeleteTarget target, String operatorId,
                                                   String locale, AppMessages appMessages) {
        String documentId = target.documentId();
        String version = target.version();
        // batch-delete 已 fail-closed 保证 version 非空；delete 仅版本级语义
        Uni<Void> op = self.delete(operatorId, documentId, version, locale);
        return op
                .onItem().transform(v -> BatchDeleteItem.deleted(documentId, version))
                .onFailure(DocumentNotFoundException.class).recoverWithItem(e ->
                        BatchDeleteItem.skipped(documentId, version,
                                e.getMessage() != null ? e.getMessage() : appMessages.documentNotFound(),
                                ErrorCodeEnum.USER_ERROR_A0430.getCode()))
                .onFailure(DocumentStateException.class).recoverWithItem(e ->
                        BatchDeleteItem.skipped(documentId, version,
                                appMessages.documentOnlyDraftCanDelete(),
                                ErrorCodeEnum.USER_ERROR_A0508.getCode()))
                .onFailure().recoverWithItem(e -> {
                    logger.error("Batch delete failed for one item: documentId={}, version={}, operatorId={}, error={}",
                            documentId, version, operatorId, e.getMessage());
                    return BatchDeleteItem.failed(documentId, version, appMessages.systemInternalError(),
                            ErrorCodeEnum.SYSTEM_ERROR_B0001.getCode());
                });
    }

    /**
     * 模型服务处理结果回调（内部接口）
     * <p>修改 processStatus / processError / processErrorCode / chunkCount / processedAt；
     * FAILED（向量化失败）时同时将生命周期 status 由 PUBLISHED 打回 DRAFT（失败文档不可检索，
     * 回草稿待修复重审，见 {@link #resolveLifecycleStatus}）——ARCHIVED/DEPRECATED（人工终态）
     * 与 REVIEWING（审核流）不被异步回调打回；COMPLETED 不触碰 lifecycle status（重试成功
     * 停留在 DRAFT + COMPLETED，重新发布必须经人工重审，不自动恢复）。状态机守卫：仅
     * PENDING/PROCESSING 可迁移到终态；当前已是终态时按"最新回调胜"——processedAt 可比较
     * 且更晚则覆盖；无法比较（模型未回传 processedAt）时仅 COMPLETED→FAILED 兜底覆盖
     * （成功清失败，见方法内 3.b），其余视为重复/过期回调，ACK 跳过（幂等）。DRAFT+PENDING
     * 窗口（生命周期回退已重置处理周期）的回调视为旧内容周期陈旧回调，WARN + record + ACK
     * 跳过（见方法内 3.5 守卫）。</p>
     * <p>幂等：以 messageId 为去重主键，同事务查重 + 落库；文档不存在（已物理删除）
     * 也 ACK 跳过并记录 messageId，避免重投。</p>
     *
     * @param cmd    回调命令（messageId/documentId/eventType/error/errorCode/chunkCount/processedAt）
     * @param locale 语言环境
     * @return Uni&lt;Void&gt;；成功完成（含幂等跳过）→ ACK，瞬时故障 → nack 重试
     */
    @WithTransaction
    public Uni<Void> processCallback(ProcessCallbackCommand cmd, String locale) {
        String documentId = cmd.documentId();
        String status = cmd.completed() ? DocumentEntity.PROCESS_COMPLETED : DocumentEntity.PROCESS_FAILED;
        logger.info("Model processing callback: messageId={}, documentId={}, status={}", cmd.messageId(), documentId, status);

        // 1) 幂等去重：messageId 已处理过 → ACK 跳过
        return callbackProcessedRepository.existsByMessageId(cmd.messageId())
                .flatMap(already -> Boolean.TRUE.equals(already)
                        ? Uni.createFrom().voidItem()
                        : applyCallback(cmd, documentId, status))
                .invoke(() -> logger.info("Model processing callback handled: documentId={}, status={}", documentId, status))
                .replaceWithVoid();
    }

    /**
     * 应用单条模型回调：文档缺失 ACK 跳过 + 防重放；终态/回退守卫拦截陈旧回调；
     * 其余更新处理状态并记录 messageId。
     */
    private Uni<Void> applyCallback(ProcessCallbackCommand cmd, String documentId, String status) {
        return documentRepository.findById(DocumentId.of(documentId))
                .flatMap(entity -> {
                    // 2) 文档不存在（可能已物理删除）→ ACK 跳过 + 记录 messageId 防重放
                    if (entity == null) {
                        logger.warn("Document for callback not found (possibly hard-deleted), ACK and skip: documentId={}", documentId);
                        return callbackProcessedRepository.record(
                                cmd.messageId(), documentId, cmd.kbId(), cmd.eventType(), status);
                    }
                    Uni<Void> stale = guardStaleCallback(cmd, entity, status);
                    if (stale != null) {
                        return stale;
                    }
                    // 4) 应用回调（PENDING/PROCESSING → 终态；或终态被更新回调覆盖）
                    return documentRepository.update(withProcessStatus(entity, status, cmd))
                            .flatMap(v -> callbackProcessedRepository.record(
                                    cmd.messageId(), documentId, entity.getKbId(), cmd.eventType(), status));
                });
    }

    /**
     * 陈旧回调守卫：命中则返回记录 messageId 的 ACK Uni（调用方直接采用），否则返回 null。
     * <p>3) 终态守卫（最新回调胜）：新回调 processedAt 严格晚于当前终态 → 覆盖（返回 null 走应用）；
     * 无法比较时兜底仅 COMPLETED 覆盖 FAILED（同文档回调按 documentId 作 Kafka key 分区保序，
     * 后消费的必然产生更晚）——防止旧周期 FAILED 挡掉新周期成功回调，文档冻结在"已发布+向量化失败"；
     * 其余 → ACK 跳过（幂等），避免旧 FAILED 冲掉已落的 COMPLETED。</p>
     * <p>3.5) DRAFT+PENDING 窗口守卫：生命周期回退（版本覆盖/草稿重传）已重置处理周期，
     * 迟到的旧内容周期回调不落库——否则草稿被盖章为终态，产生"草稿但已向量化"
     * 状态污染（出站侧已作废 PENDING 事件，此守卫兜底已发出/在途回调）。</p>
     */
    private Uni<Void> guardStaleCallback(ProcessCallbackCommand cmd,
                                         com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity entity,
                                         String status) {
        String documentId = entity.getId().value();
        String current = entity.getProcessStatus();
        boolean alreadyTerminal = DocumentEntity.PROCESS_COMPLETED.equals(current)
                || DocumentEntity.PROCESS_FAILED.equals(current);
        if (alreadyTerminal) {
            boolean comparable = isLaterCallback(cmd.processedAt(), entity.getProcessedAt());
            boolean successClearsFailure = DocumentEntity.PROCESS_COMPLETED.equals(status)
                    && DocumentEntity.PROCESS_FAILED.equals(current);
            if (!comparable && !successClearsFailure) {
                logger.info("Document processing status already terminal and this is not an update when the callback arrived, treated as duplicate/stale, ACK and skip: documentId={}, current={}",
                        documentId, current);
                return callbackProcessedRepository.record(
                        cmd.messageId(), documentId, entity.getKbId(), cmd.eventType(), status);
            }
            if (!comparable) {
                logger.info("Callback processedAt is missing so no comparison is possible, fallback rule overwrites FAILED with COMPLETED: documentId={}, current={}",
                        documentId, current);
            }
        }
        if (DocumentEntity.STATUS_DRAFT.equals(entity.getStatus())
                && DocumentEntity.PROCESS_PENDING.equals(current)) {
            logger.warn("Stale callback arrived on a reverted draft, ignored: documentId={}, lifecycle=DRAFT, "
                    + "processStatus=PENDING, callback={}, callbackProcessedAt={}",
                    documentId, cmd.eventType(), cmd.processedAt());
            return callbackProcessedRepository.record(
                    cmd.messageId(), documentId, entity.getKbId(), cmd.eventType(), status);
        }
        return null;
    }
    /**
     * 构建带新处理结果的文档实体（不可变，复制其余字段）。
     * <p>COMPLETED：写 chunkCount/processedAt，清空 error/errorCode，lifecycle status 不变；
     * FAILED：写 error/errorCode/processedAt，chunkCount 置空（本周期未产出切片），
     * lifecycle status 按 {@link #resolveLifecycleStatus} 决策（PUBLISHED 打回 DRAFT）。</p>
     */
    private static DocumentEntity withProcessStatus(DocumentEntity entity, String processStatus, ProcessCallbackCommand cmd) {
        boolean completed = DocumentEntity.PROCESS_COMPLETED.equals(processStatus);
        String processError = completed
                ? null
                : (cmd.error() != null && !cmd.error().isBlank() ? cmd.error() : "（模型未提供错误描述）");
        String processErrorCode = completed ? null : cmd.errorCode();
        Integer chunkCount = completed ? cmd.chunkCount() : null;
        LocalDateTime processedAt = cmd.processedAt() != null ? cmd.processedAt() : LocalDateTime.now();
        return DocumentEntity.builder()
                .id(entity.getId())
                .kbId(entity.getKbId())
                .categoryId(entity.getCategoryId())
                .title(entity.getTitle())
                .status(resolveLifecycleStatus(entity, processStatus))
                .processStatus(processStatus)
                .processError(processError)
                .chunkCount(chunkCount)
                .processErrorCode(processErrorCode)
                .processedAt(processedAt)
                .currentVersion(entity.getCurrentVersion())
                .publishedVersion(entity.getPublishedVersion())
                .hasPublishedVersion(entity.getHasPublishedVersion())
                .filePath(entity.getFilePath())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .docType(entity.getDocType())
                .reviewedBy(entity.getReviewedBy())
                .reviewComment(entity.getReviewComment())
                .reviewedAt(entity.getReviewedAt())
                .author(entity.getAuthor())
                .tags(entity.getTags())
                .remark(entity.getRemark())
                .createdBy(entity.getCreatedBy())
                .inheritParent(entity.getInheritParent())
                .createdAt(entity.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * FAILED 回调的生命周期打回规则（向量化失败 → 回草稿）。
     * <p>仅 PUBLISHED 打回 DRAFT：文档真正可检索 = PUBLISHED + COMPLETED，向量化失败的
     * "已发布"文档不可检索却停留在发布列表，打回草稿让编辑者感知失败并修复后重新走审核流。
     * DRAFT 已是目标态（幂等不变）；ARCHIVED/DEPRECATED 是人工终态（归档下线/软删除）、
     * REVIEWING 属人工审核流，均不被异步回调打回——处理期间被归档/废弃的竞态下只落
     * processStatus，warn 提示便于排查。</p>
     * <p>后续 COMPLETED 回调不自动恢复 PUBLISHED——打回是单向门，重新发布必须经人工重审
     * （重试成功停留在 DRAFT + COMPLETED）。</p>
     *
     * @param entity        回调前文档实体
     * @param processStatus 本次回调写入的处理状态
     * @return 打回决策后的生命周期状态
     */
    private static String resolveLifecycleStatus(DocumentEntity entity, String processStatus) {
        if (!DocumentEntity.PROCESS_FAILED.equals(processStatus)) {
            return entity.getStatus();
        }
        if (DocumentEntity.STATUS_PUBLISHED.equals(entity.getStatus())) {
            return DocumentEntity.STATUS_DRAFT;
        }
        if (!DocumentEntity.STATUS_DRAFT.equals(entity.getStatus())) {
            logger.warn("Document is in an unexpected lifecycle status when the FAILED callback arrived, left unchanged: documentId={}, status={}, processStatus={}",
                    entity.getId(), entity.getStatus(), entity.getProcessStatus());
        }
        return entity.getStatus();
    }

    /**
     * 判断新回调是否比当前终态更新（"最新回调胜"覆盖判断）。
     * <p>仅当新回调 processedAt 严格晚于当前终态 processedAt（且两者非空）时返回 true；
     * 任一为空 → 无法可靠判断 → 返回 false（保守跳过，走幂等）。
     * 用于重试场景：模型先发 FAILED、重试成功后发 COMPLETED，COMPLETED 的 processedAt 更晚 → 覆盖。</p>
     */
    private static boolean isLaterCallback(LocalDateTime cmdProcessedAt, LocalDateTime currentProcessedAt) {
        return cmdProcessedAt != null && currentProcessedAt != null
                && cmdProcessedAt.isAfter(currentProcessedAt);
    }

    /**
     * 文件类型分类（按扩展名归类；default → OTHER，不判断 makefile）
     */
    static String classify(String fileName) {
        String ext = extractExtension(fileName);
        if (ext.isEmpty()) {
            // 无扩展名构建脚本（Makefile）→ CONFIG；CMakeLists.txt 走 .txt→DOCUMENT；其余无扩展名 → OTHER
            String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
            if ("makefile".equals(lower)) {
                return DocumentEntity.DOC_TYPE_CONFIG;
            }
            return DocumentEntity.DOC_TYPE_OTHER;
        }
        // 分组对齐 docs/supported_file_formats.json
        return switch (ext) {
            case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "txt", "html", "htm", "csv"
                    -> DocumentEntity.DOC_TYPE_DOCUMENT;
            case "c", "cc", "cpp", "cxx", "h", "hpp", "py", "ts", "tsx", "ets", "java", "js", "mjs", "cjs"
                    -> DocumentEntity.DOC_TYPE_CODE;
            case "ini", "dts", "dtsi", "dtso", "xml", "sh", "cmake"
                    -> DocumentEntity.DOC_TYPE_CONFIG;
            default -> DocumentEntity.DOC_TYPE_OTHER;
        };
    }

    /** 从文件名提取扩展名（小写） */
    static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ""; // 无扩展名
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 检查文件扩展名/文件名是否在允许列表中 */
    static boolean isExtensionAllowed(String fileName, String extension) {
        if (extension.isEmpty()) {
            // 无扩展名：检查完整文件名
            return ALLOWED_FILENAMES.contains(fileName.toLowerCase(java.util.Locale.ROOT));
        }
        return ALLOWED_EXTENSIONS.contains(extension);
    }

    /** 从文件名去掉扩展名 */
    static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    /**
     * 推断文件类型短名（fileType）。
     * <p><b>权威源为文件名扩展名</b>（上传时已 {@link #extractExtension} 算出），
     * 优先返回 extension；仅当扩展名缺失（如 {@code makefile} 无扩展名）时，
     * 才退回 {@link #MIME_TO_EXT} 按客户端 MIME 兜底，再否则返回 {@code unknown}。
     * 不再让客户端 MIME 覆盖扩展名——避免 {@code .h} 被报成 {@code text/plain}
     * 后误显为 {@code txt}。</p>
     */
    static String deduceFileType(String mimeType, String extension) {
        if (extension != null && !extension.isEmpty()) {
            return extension;
        }
        if (mimeType != null && MIME_TO_EXT.containsKey(mimeType)) {
            return MIME_TO_EXT.get(mimeType);
        }
        return "unknown";
    }

    /**
     * 构建 DOCUMENT_PUBLISHED 消息 payload（须用 approve 前的实体：
     * hasPublishedVersion/publishedVersion 记录"上一版"信息，approve 后会被覆盖；
     * 附带 file_version_label（取 current_version）与 tags（逗号串拆列表，空则省略））
     */
    private Map<String, Object> buildPublishedPayload(DocumentEntity entity) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", entity.getTitle());
        payload.put("filePath", entity.getFilePath());
        payload.put("fileSize", entity.getFileSize());
        payload.put("mimeType", entity.getMimeType());
        payload.put("version", entity.getCurrentVersion());
        // file_version_label：与 version 同源（current_version），模型展示用的人类可读版本标签
        payload.put("file_version_label", entity.getCurrentVersion());
        // tags：逗号分隔串拆为列表（去空白、去空项）；未打标签时省略该字段
        if (entity.getTags() != null && !entity.getTags().isBlank()) {
            List<String> tags = Arrays.stream(entity.getTags().split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.toList());
            if (!tags.isEmpty()) {
                payload.put("tags", tags);
            }
        }
        if (entity.getCategoryId() != null) {
            payload.put("categoryId", entity.getCategoryId());
        }
        payload.put("isVersionUpdate", entity.getHasPublishedVersion());
        if (entity.getPublishedVersion() != null) {
            payload.put("previousVersion", entity.getPublishedVersion());
        }
        return payload;
    }

    /**
     * 文件输入（非 Command record，仅用于服务内部传递）
     */
    public record FileInput(String fileName, byte[] content, String mimeType) {
    }
}