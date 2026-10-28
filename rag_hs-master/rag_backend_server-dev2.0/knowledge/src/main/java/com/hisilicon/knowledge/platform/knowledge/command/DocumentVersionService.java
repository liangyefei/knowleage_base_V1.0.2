package com.hisilicon.knowledge.platform.knowledge.command;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentFileSpec;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentStateException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionOverrideResult;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;

/**
 * 文档版本/编辑服务：版本覆盖（multipart + tus 临时文件两条路径）与文档编辑
 * （元数据/标签/版本号变更，含 tus edit 前置校验）。从 DocumentCommandService 拆出；
 * 文件落定/快照回收/内容索引经 {@link DocumentImportService}，存储路径经
 * {@link DocumentStoragePathResolver}；纯函数静态仍留在 DocumentCommandService 共用。
 */
@ApplicationScoped
public class DocumentVersionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentVersionService.class);

    /** 单个文件最大大小：100MB（默认，可通过 kb.upload.max-file-size 配置） */
    private final long maxFileSize;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final DocumentOutboxRepository outboxRepository;
    private final FileStorageService fileStorageService;
    private final DictValueValidator dictValueValidator;
    private final DocumentImportService documentImportService;
    private final DocumentStoragePathResolver storagePathResolver;

    public DocumentVersionService(KnowledgeBaseRepository knowledgeBaseRepository,
                                  DocumentRepository documentRepository,
                                  DocumentMetadataRepository documentMetadataRepository,
                                  DocumentOutboxRepository outboxRepository,
                                  FileStorageService fileStorageService,
                                  DictValueValidator dictValueValidator,
                                  DocumentImportService documentImportService,
                                  DocumentStoragePathResolver storagePathResolver,
                                  @ConfigProperty(name = "kb.upload.max-file-size", defaultValue = "100M")
                                  MemorySize maxFileSize) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentMetadataRepository = documentMetadataRepository;
        this.outboxRepository = outboxRepository;
        this.fileStorageService = fileStorageService;
        this.dictValueValidator = dictValueValidator;
        this.documentImportService = documentImportService;
        this.storagePathResolver = storagePathResolver;
        this.maxFileSize = maxFileSize.asLongValue();
    }
    /**
     * 版本覆盖：已发布文档上传新版本（仅 PUBLISHED 允许）
     * <p>
     * 流程：校验 KB 存在 → 校验文档存在且属于该 KB 且 PUBLISHED → 文件校验（大小/类型）
     * → 存储新文件 → 归档旧版到 document_versions（was_published=TRUE，旧版保持可检索）
     * → 文档更新（currentVersion+1，status→DRAFT，processStatus→PENDING）。
     * 不通知模型服务（approve 时通过 DOCUMENT_PUBLISHED 通知）。
     * </p>
     *
     * @param operatorId 操作员 ID
     * @param kbId       知识库 ID
     * @param documentId 文档 ID
     * @param file       新版本文件
     * @param locale     语言环境
     * @return 版本覆盖结果
     */
    @WithTransaction
    public Uni<VersionOverrideResult> overrideVersion(OverrideRequest request) {
        String operatorId = request.operatorId();
        String kbId = request.kbId();
        String documentId = request.documentId();
        DocumentCommandService.FileInput file = request.file();
        UploadDocumentCommand metadata = request.metadata();
        AppMessages appMessages = LocaleUtil.getAppMessage(request.locale());
        String trimmedKbId = kbId.trim();
        String trimmedDocId = documentId.trim();

        // 版本号必填 + tags 校验 + 字典值校验（与上传一致，fail-fast）
        String newVersion = DocumentCommandService.extractRequiredVersion(metadata != null ? metadata.version() : null, appMessages);
        try {
            validateTags(metadata != null ? metadata.tags() : null, appMessages);
        } catch (BusinessException e) {
            return Uni.createFrom().failure(e);
        }
        java.util.Map<String, String> metaMap = DocumentImportService.buildDocumentMetadata(metadata);

        logger.info("Version override: kbId={}, documentId={}, fileName={}, version={}, operatorId={}",
                trimmedKbId, trimmedDocId, file.fileName(), newVersion, operatorId);

        // 1-2) KB 存在且活跃 + 文档存在/归属/已发布/处理周期就绪
        // 3) 字典值校验 + 版本查重；4) 文件校验；5) 存储新文件；6) 归档旧版 + 更新文档
        return resolveOverrideTarget(trimmedKbId, trimmedDocId, appMessages)
                .flatMap(doc -> dictValueValidator.validateOrThrow(metaMap, invalid -> new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0420,
                                appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value())))
                        .flatMap(v -> checkVersionNotPublished(doc, newVersion, appMessages))
                        .replaceWith(doc))
                .flatMap(doc -> validateOverrideFile(doc, file, appMessages).replaceWith(doc))
                .flatMap(doc -> storeOverrideNewFile(doc, file, metadata))
                .flatMap(ctx -> applyVersionOverride(ctx, operatorId, newVersion, metaMap, appMessages))
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Version override failed: documentId={}, error={}", trimmedDocId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                });
    }

    /** 版本覆盖入参（参数对象：multipart 端点 → 服务） */
    public record OverrideRequest(String operatorId, String kbId, String documentId,
                                  DocumentCommandService.FileInput file,
                                  UploadDocumentCommand metadata, String locale) {
    }

    /** 1-2) 版本覆盖前置解析：KB 存在且活跃 + 文档存在/归属该 KB/仅 PUBLISHED/处理周期 COMPLETED */
    private Uni<DocumentEntity> resolveOverrideTarget(String trimmedKbId, String trimmedDocId,
                                                      AppMessages appMessages) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(trimmedKbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", trimmedKbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        logger.error("Knowledge base deleted, version override not allowed: id={}", trimmedKbId);
                        return Uni.createFrom().failure(
                                new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    return Uni.createFrom().item(kb);
                })
                .flatMap(kb -> documentRepository.findById(DocumentId.of(trimmedDocId))
                        .onItem().ifNull().failWith(() -> {
                            logger.error("Document not found: id={}", trimmedDocId);
                            return new DocumentNotFoundException(appMessages.documentNotFound());
                        })
                        .flatMap(doc -> {
                            if (!trimmedKbId.equals(doc.getKbId())) {
                                logger.error("Document does not belong to this knowledge base: docId={}, kbId={}", trimmedDocId, trimmedKbId);
                                return Uni.createFrom().failure(
                                        new DocumentNotFoundException(appMessages.documentNotFound()));
                            }
                            return checkOverridableState(doc, appMessages);
                        }));
    }

    /** 覆盖资格：仅 PUBLISHED 且处理周期 COMPLETED（旧版在处理时覆盖会错位/残缺） */
    private Uni<DocumentEntity> checkOverridableState(DocumentEntity doc, AppMessages appMessages) {
        if (!DocumentEntity.STATUS_PUBLISHED.equals(doc.getStatus())) {
            logger.error("Only published documents can be version-overridden: docId={}, status={}",
                    doc.getId().value(), doc.getStatus());
            return Uni.createFrom().failure(
                    new DocumentStateException(appMessages.documentStateInvalid()));
        }
        if (!DocumentEntity.PROCESS_COMPLETED.equals(doc.getProcessStatus())) {
            logger.warn("Document processing status not ready before override: docId={}, processStatus={}",
                    doc.getId().value(), doc.getProcessStatus());
            String message = DocumentEntity.PROCESS_FAILED.equals(doc.getProcessStatus())
                    ? appMessages.versionOverrideFailed()
                    : appMessages.versionOverrideProcessing();
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.USER_ERROR_A0508, message));
        }
        return Uni.createFrom().item(doc);
    }

    /** 4) 文件校验：大小 / 扩展名白名单 / 同类型强校验（新扩展名 == 源文档扩展名） */
    private Uni<Void> validateOverrideFile(DocumentEntity doc, DocumentCommandService.FileInput file,
                                           AppMessages appMessages) {
        if (file.content().length > maxFileSize) {
            logger.warn("File size exceeds limit: fileName={}, size={}", file.fileName(), file.content().length);
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                            appMessages.fileSizeExceeded(DocumentImportService.formatBytes(maxFileSize))));
        }
        String extension = DocumentCommandService.extractExtension(file.fileName());
        if (!DocumentCommandService.isExtensionAllowed(file.fileName(), extension)) {
            logger.warn("Unsupported file type: fileName={}, ext={}", file.fileName(), extension);
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                            appMessages.fileTypeNotSupported(extension)));
        }
        String existingExt = DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(doc.getFilePath()));
        if (!extension.equalsIgnoreCase(existingExt)) {
            logger.warn("Version override rejected, file type differs from the source file: docId={}, expected={}, received={}",
                    doc.getId().value(), existingExt, extension);
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                            appMessages.fileTypeChanged(existingExt, extension)));
        }
        return Uni.createFrom().voidItem();
    }

    /** 5) 存储新文件（title：前端指定优先，否则文件名推导；版本覆盖为单文件场景） */
    private Uni<OverrideContext> storeOverrideNewFile(DocumentEntity doc, DocumentCommandService.FileInput file,
                                                      UploadDocumentCommand metadata) {
        String title = (metadata != null && metadata.title() != null && !metadata.title().isBlank())
                ? metadata.title().trim() : DocumentCommandService.stripExtension(file.fileName());
        String extension = DocumentCommandService.extractExtension(file.fileName());
        String mimeType = file.mimeType() != null ? file.mimeType() : "application/octet-stream";
        return storagePathResolver.resolveStoragePath(doc.getKbId(), doc.getCategoryId(),
                doc.getId().value(), file.fileName(), extension)
                .flatMap(storagePath -> fileStorageService.store(storagePath, file.content())
                .map(actualPath -> new OverrideContext(doc, title, actualPath,
                        (long) file.content().length, mimeType, extension, metadata, file.fileName())));
    }

    /** 6) 归档旧版 + 更新文档（新版本号/文件/属性）+ 全量替换元数据 + 作废旧内容周期未发事件 */
    private Uni<VersionOverrideResult> applyVersionOverride(OverrideContext ctx, String operatorId,
                                                            String newVersion,
                                                            java.util.Map<String, String> metaMap,
                                                            AppMessages appMessages) {
        DocumentEntity old = ctx.doc();
        DocumentVersion archived = DocumentVersion.published(new DocumentVersion.PublishedSeed(
                old.getId().value(), old.getCurrentVersion(), old.getFilePath(), old.getFileSize(),
                old.getMimeType(), Boolean.TRUE, operatorId, LocalDateTime.now()));
        String newAuthor = ctx.metadata() != null ? ctx.metadata().author() : null;
        String newTags = (ctx.metadata() != null && ctx.metadata().tags() != null && !ctx.metadata().tags().isEmpty())
                ? String.join(",", ctx.metadata().tags()) : null;
        String newRemark = ctx.metadata() != null ? ctx.metadata().remark() : null;
        DocumentEntity updated = old.overrideWithNewVersion(new DocumentFileSpec(
                ctx.title(), ctx.filePath(), ctx.fileSize(), ctx.mimeType(),
                DocumentCommandService.classify(ctx.fileName()), newAuthor, newTags, newRemark, newVersion));
        return documentRepository.archiveVersion(archived)
                .flatMap(v -> documentRepository.update(updated))
                // 作废旧内容周期未发事件：覆盖后模型不应再处理/回调旧文件（与 update 同事务）
                .call(() -> outboxRepository.cancelPendingByDocument(old.getId().value()))
                .flatMap(e -> metaMap.isEmpty()
                        ? Uni.createFrom().item(e)
                        : documentMetadataRepository.replaceAll(e.getId().value(), metaMap).replaceWith(e))
                // 新文件内容已落定：异步更新内容索引（状态回 DRAFT，重审通过前不可搜）
                .invoke(e -> documentImportService.indexContent(e.getId().value(), ctx.filePath()))
                .invoke(() -> logger.info("Version override succeeded: documentId={}, newVersion={}, title={}",
                        old.getId().value(), updated.getCurrentVersion(), ctx.title()))
                .map(e -> VersionOverrideResult.of(new VersionOverrideResult.Payload(
                        e.getId().value(), e.getTitle(), e.getFileSize(),
                        DocumentCommandService.deduceFileType(e.getMimeType(), ctx.extension()),
                        e.getCurrentVersion(), e.getStatus())));
    }

    /** 版本覆盖内部上下文（metadata/fileName 供落库属性推导复用） */
    private record OverrideContext(DocumentEntity doc, String title, String filePath,
                                   long fileSize, String mimeType, String extension,
                                   UploadDocumentCommand metadata, String fileName) {
    }

    /** 标签数量上限 */
    private static final int MAX_TAGS_COUNT = 20;
    /** 单个标签长度上限 */
    private static final int MAX_TAG_LENGTH = 30;

    /**
     * 文档编辑（title/author/tags/remark + 元数据全量替换 + 可选文件替换）
     * <p>生命周期约束：仅 DRAFT/PUBLISHED 允许编辑属性/元数据，REVIEWING/ARCHIVED/DEPRECATED 拒绝（A0508）。
     * title 为 {@code null} = 不改（特殊例外）；author/tags/remark 为 {@code null} = 清空。
     * 元数据（product/chip_model/version/sensitivity/official）全量替换，不传的 key 清空。
     * 文件替换仅 DRAFT 允许（同格式：新文件扩展名 == 现有扩展名，否则 A0701 + fileTypeChanged），
     * processStatus 重置为 PENDING，版本号不变；PUBLISHED + 文件 → 拒绝（A0508 + documentPublishedUseVersion），
     * 已发布文档换文件走版本覆盖端点。编辑不发任何消息事件。</p>
     *
     * @param operatorId 操作员 ID
     * @param documentId 文档 ID
     * @param command    编辑命令（title/author/tags/remark + metadata；可 null = 仅换文件）
     * @param file       新文件（可 null = 不替换文件；非 null 时需 DRAFT 且同格式）
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> edit(String operatorId, String documentId, EditDocumentCommand command,
                      DocumentCommandService.FileInput file, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Edit document: documentId={}, operatorId={}, hasFile={}", documentId, operatorId, file != null);

        // 至少传一个字段或一个文件
        if ((command == null || isAllNull(command)) && file == null) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidParameter()));
        }

        // tags 校验（command 可 null → 跳过）
        try {
            validateTags(command != null ? command.tags() : null, appMessages);
        } catch (BusinessException e) {
            return Uni.createFrom().failure(e);
        }

        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> guardEditState(entity, file, appMessages))
                .flatMap(entity -> {
                    java.util.Map<String, String> metaMap = command != null ? buildEditMetadata(command)
                            : java.util.Map.of();
                    // 编辑改版本号（可选）：非空则查重（不得为已发布/已归档版本）
                    String newVersion = (command != null && command.version() != null
                            && !command.version().isBlank()) ? command.version().trim() : null;
                    // PUBLISHED 禁改版本号：已发布文档的版本号由版本覆盖流程推进
                    if (DocumentEntity.STATUS_PUBLISHED.equals(entity.getStatus()) && newVersion != null) {
                        logger.error("Published documents are not allowed to change the version number: id={}, version={}", documentId, newVersion);
                        return Uni.createFrom().failure(
                                new DocumentStateException(appMessages.documentPublishedVersionLocked()));
                    }
                    return dictValueValidator.validateOrThrow(metaMap, invalid -> new BusinessException(
                                    ErrorCodeEnum.USER_ERROR_A0420,
                                    appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value())))
                            .flatMap(v -> newVersion != null
                                    ? checkVersionNotPublished(entity, newVersion, appMessages)
                                    : Uni.createFrom().voidItem())
                            .flatMap(v -> file != null
                                    ? replaceDraftFile(new DraftFileEdit(entity, file, command, metaMap,
                                            newVersion, appMessages))
                                    : applyPropertyEdit(entity, command, metaMap, documentId, newVersion));
                })
                .onFailure(IllegalStateException.class).transform(e -> {
                    logger.error("Edit failed: documentId={}, error={}", documentId, e.getMessage());
                    return new DocumentStateException(appMessages.documentStateInvalid());
                })
                .invoke(() -> logger.info("Edit succeeded: documentId={}", documentId))
                .replaceWithVoid();
    }

    /** 编辑状态守卫：REVIEWING/ARCHIVED/DEPRECATED 拒绝；PUBLISHED+文件须走版本覆盖端点 */
    private Uni<DocumentEntity> guardEditState(DocumentEntity entity, DocumentCommandService.FileInput file,
                                               AppMessages appMessages) {
        if (DocumentEntity.STATUS_REVIEWING.equals(entity.getStatus())) {
            return Uni.createFrom().failure(
                    new DocumentStateException(appMessages.documentUnderReview()));
        }
        if (DocumentEntity.STATUS_ARCHIVED.equals(entity.getStatus())
                || DocumentEntity.STATUS_DEPRECATED.equals(entity.getStatus())) {
            return Uni.createFrom().failure(
                    new DocumentStateException(appMessages.documentStateInvalid()));
        }
        if (file != null && DocumentEntity.STATUS_PUBLISHED.equals(entity.getStatus())) {
            logger.error("Replacing the file of a published document must use the version override endpoint: id={}",
                    entity.getId().value());
            return Uni.createFrom().failure(
                    new DocumentStateException(appMessages.documentPublishedUseVersion()));
        }
        return Uni.createFrom().item(entity);
    }

    /** 仅编辑属性（无文件）：editProperties + 全量替换元数据（此分支 command 必非 null，file==null 守卫已保证） */
    private Uni<DocumentEntity> applyPropertyEdit(DocumentEntity entity, EditDocumentCommand command,
                                                  java.util.Map<String, String> metaMap,
                                                  String documentId, String newVersion) {
        String newTitle = command.title() != null ? command.title().trim() : null;
        String newAuthor = command.author();
        String newTags = (command.tags() != null && !command.tags().isEmpty())
                ? String.join(",", command.tags()) : null;
        String newRemark = command.remark();
        DocumentEntity updated = entity.editProperties(newTitle, newAuthor, newTags, newRemark, newVersion);
        return documentRepository.update(updated)
                .flatMap(e -> metaMap.isEmpty()
                        ? Uni.createFrom().item(e)
                        : documentMetadataRepository.replaceAll(documentId, metaMap).replaceWith(e));
    }

    /**
     * DRAFT 同格式文件替换 + 属性/元数据编辑（edit 携带 file 时调用）
     * <p>校验：文件大小 ≤ maxFileSize；扩展名在允许列表；新扩展名 == 现有扩展名（同格式，否则 A0701）。
     * 存储新文件 → {@link DocumentEntity#editWithNewFile}（status 保持 DRAFT，processStatus→PENDING，currentVersion=newVersion 或不变）
     * → 元数据全量替换。返回更新后的实体（与无文件分支返回类型一致）。</p>
     */
    private Uni<DocumentEntity> replaceDraftFile(DraftFileEdit req) {
        DocumentEntity doc = req.doc();
        DocumentCommandService.FileInput file = req.file();
        EditDocumentCommand command = req.command();
        java.util.Map<String, String> metaMap = req.metaMap();
        String newVersion = req.newVersion();
        AppMessages appMessages = req.appMessages();
        String documentId = doc.getId().value();
        return validateDraftFileReplace(doc, file, appMessages)
                // 4) 存储新文件（title 遵循 edit 语义：null=不改，沿用现 title）
                .flatMap(v -> {
                    String newTitle = (command != null && command.title() != null) ? command.title().trim() : doc.getTitle();
                    String newAuthor = command != null ? command.author() : null;
                    String newTags = (command != null && command.tags() != null && !command.tags().isEmpty())
                            ? String.join(",", command.tags()) : null;
                    String newRemark = command != null ? command.remark() : null;
                    String mimeType = file.mimeType() != null ? file.mimeType() : "application/octet-stream";
                    String oldFilePath = doc.getFilePath();
                    String newExt = DocumentCommandService.extractExtension(file.fileName());
                    return storagePathResolver.resolveStoragePath(doc.getKbId(), doc.getCategoryId(),
                                    documentId, file.fileName(), newExt)
                            // 两阶段回收第一步：DB 更新前快照旧文件身份（同名重传覆盖防护）
                            .flatMap(storagePath -> documentImportService.snapshotReplacedFile(oldFilePath)
                            .flatMap(snapshots -> fileStorageService.store(storagePath, file.content())
                            .flatMap(actualPath -> {
                                DocumentEntity updated = doc.editWithNewFile(new DocumentFileSpec(newTitle,
                                        actualPath, file.content().length, mimeType, DocumentCommandService.classify(file.fileName()),
                                        newAuthor, newTags, newRemark, newVersion));
                                return documentRepository.update(updated)
                                        // 重置 PENDING = 新内容周期：作废旧周期未发事件（如重试在途），防陈旧回调落库（同事务）
                                        .call(() -> outboxRepository.cancelPendingByDocument(documentId))
                                        .flatMap(e -> metaMap.isEmpty()
                                                ? Uni.createFrom().item(e)
                                                : documentMetadataRepository.replaceAll(documentId, metaMap).replaceWith(e))
                                        // DB 更新成功后：旧文件守卫回收（失败仅告警，不影响编辑结果）
                                        .flatMap(e -> documentImportService.recycleReplacedFile(documentId, oldFilePath, actualPath, snapshots)
                                                .replaceWith(e))
                                        // 文件内容已落定：异步更新内容索引（不影响编辑结果）
                                        .invoke(e -> documentImportService.indexContent(documentId, actualPath));
                            })
                            .invoke(e -> logger.info("Edited file replaced: documentId={}, fileName={}, path={}",
                                    documentId, file.fileName(), storagePath))));
                });
    }

    /** DRAFT 文件替换编辑入参（参数对象：编辑实体 + 新文件 + 属性命令 + 元数据 + 版本号） */
    private record DraftFileEdit(DocumentEntity doc, DocumentCommandService.FileInput file,
                                 EditDocumentCommand command, java.util.Map<String, String> metaMap,
                                 String newVersion, AppMessages appMessages) {
    }

    /** DRAFT 文件替换三重校验：大小 ≤ 上限 / 扩展名白名单 / 同格式（新扩展名 == 现有扩展名） */
    private Uni<Void> validateDraftFileReplace(DocumentEntity doc, DocumentCommandService.FileInput file,
                                               AppMessages appMessages) {
        if (file.content().length > maxFileSize) {
            logger.warn("Edited file size exceeds limit: fileName={}, size={}", file.fileName(), file.content().length);
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                    appMessages.fileSizeExceeded(DocumentImportService.formatBytes(maxFileSize))));
        }
        String newExt = DocumentCommandService.extractExtension(file.fileName());
        if (!DocumentCommandService.isExtensionAllowed(file.fileName(), newExt)) {
            logger.warn("Edited file type unsupported: fileName={}, ext={}", file.fileName(), newExt);
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported(newExt)));
        }
        String existingExt = DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(doc.getFilePath()));
        if (!newExt.equalsIgnoreCase(existingExt)) {
            logger.warn("Edit rejected, file type change is not allowed: docId={}, expected={}, received={}",
                    doc.getId().value(), existingExt, newExt);
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeChanged(existingExt, newExt)));
        }
        return Uni.createFrom().voidItem();
    }

    /** 判断 EditDocumentCommand 是否所有字段都为 null/空 */
    private static boolean isAllNull(EditDocumentCommand command) {
        return command.title() == null
                && command.author() == null
                && (command.tags() == null || command.tags().isEmpty())
                && command.remark() == null
                && command.product() == null
                && command.chipModel() == null
                && command.version() == null
                && command.securityLevel() == null
                && command.official() == null;
    }

    // ======================== tus 文档用途会话（DOC_EDIT / VERSION_OVERRIDE） ========================
    // create 校验与 complete 落库的文档侧业务规则归口（编排与会话状态机在 UploadSessionCommandService）。

    /**
     * tus 文档用途会话 create 校验（fail-closed，与 multipart edit/overrideVersion 同规则）：
     * <ul>
     *   <li>文档存在（A0430）；</li>
     *   <li>DOC_EDIT：状态机校验（REVIEWING→审核中 / ARCHIVED·DEPRECATED→状态非法 /
     *       PUBLISHED→须走版本覆盖，均 A0508；PUBLISHED 整体拒绝，不可能经此路径修改版本号）；</li>
     *   <li>VERSION_OVERRIDE：KB 活跃 + 仅 PUBLISHED（A0508/A0430）；</li>
     *   <li>同类型强校验：新扩展名 == 源文档 file_path 末段扩展名（A0701 fileTypeChanged，
     *       两种用途一致；zip 在调用方已被显式拒绝）；</li>
     *   <li>tags 数量/长度校验（与 multipart 一致）；</li>
     *   <li>携带版本号（edit 可选 / override 必填——必填性由调用方校验）时查重
     *       （不得为已发布/已归档版本）。</li>
     * </ul>
     *
     * @param documentId 目标文档 ID
     * @param fileName   新文件名（已清洗）
     * @param purpose    DOC_EDIT / VERSION_OVERRIDE
     * @param metadata   已解析会话元数据（可为 null=仅换文件）
     * @param appMessages 国际化消息
     * @return 校验通过的目标文档（含 kbId/categoryId 供会话归属）
     */
    public Uni<DocumentEntity> validateDocumentUploadTarget(String documentId, String fileName,
                                                        UploadSessionPurpose purpose,
                                                        ImportFileMetadata metadata,
                                                        AppMessages appMessages) {
    String newExt = DocumentCommandService.extractExtension(fileName);
    return documentRepository.findById(DocumentId.of(documentId))
            .onItem().ifNull().failWith(() -> {
                logger.error("Target document not found: id={}", documentId);
                return new DocumentNotFoundException(appMessages.documentNotFound());
            })
            .flatMap(doc -> purpose == UploadSessionPurpose.VERSION_OVERRIDE
                    ? checkTusOverrideTarget(doc, appMessages)
                    : checkTusEditTarget(doc, appMessages))
            .flatMap(doc -> {
                // 同类型强校验（source of truth：documents.file_path 末段扩展名）
                String existingExt = DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(doc.getFilePath()));
                if (!newExt.equalsIgnoreCase(existingExt)) {
                    logger.warn("tus edit/override rejected, file type differs from the source file: docId={}, expected={}, received={}",
                            documentId, existingExt, newExt);
                    return Uni.createFrom().failure(new BusinessException(
                            ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                            appMessages.fileTypeChanged(existingExt, newExt)));
                }
                try {
                    validateTags(metadata != null ? metadata.tags() : null, appMessages);
                } catch (BusinessException e) {
                    return Uni.createFrom().failure(e);
                }
                String version = metadata != null ? metadata.version() : null;
                String newVersion = version != null && !version.isBlank() ? version.trim() : null;
                return checkVersionNotPublished(doc, newVersion, appMessages).replaceWith(doc);
            });
}

/** tus VERSION_OVERRIDE 目标校验：KB 活跃 + 仅 PUBLISHED（与 multipart overrideVersion 对齐） */
private Uni<DocumentEntity> checkTusOverrideTarget(DocumentEntity doc, AppMessages appMessages) {
    return knowledgeBaseRepository.findById(KnowledgeBaseId.of(doc.getKbId()))
            .onItem().ifNull().failWith(() ->
                    new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
            .flatMap(kb -> {
                if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                    return Uni.createFrom().failure(
                            new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                }
                if (!DocumentEntity.STATUS_PUBLISHED.equals(doc.getStatus())) {
                    logger.error("Only published documents can be version-overridden: docId={}, status={}",
                            doc.getId().value(), doc.getStatus());
                    return Uni.createFrom().failure(
                            new DocumentStateException(appMessages.documentStateInvalid()));
                }
                return Uni.createFrom().item(doc);
            });
}

/** tus DOC_EDIT 目标校验：状态机（与 multipart edit 一致：REVIEWING/终态/PUBLISHED 拒绝） */
private Uni<DocumentEntity> checkTusEditTarget(DocumentEntity doc, AppMessages appMessages) {
    if (DocumentEntity.STATUS_REVIEWING.equals(doc.getStatus())) {
        return Uni.createFrom().failure(
                new DocumentStateException(appMessages.documentUnderReview()));
    }
    if (DocumentEntity.STATUS_ARCHIVED.equals(doc.getStatus())
            || DocumentEntity.STATUS_DEPRECATED.equals(doc.getStatus())) {
        return Uni.createFrom().failure(
                new DocumentStateException(appMessages.documentStateInvalid()));
    }
    if (DocumentEntity.STATUS_PUBLISHED.equals(doc.getStatus())) {
        return Uni.createFrom().failure(
                new DocumentStateException(appMessages.documentPublishedUseVersion()));
    }
    return Uni.createFrom().item(doc);
}

    /**
     * tus DOC_EDIT complete：DRAFT 同类型文件替换 + 可选属性/元数据编辑（文件已在磁盘，move 而非 store，
     * 不整文件进内存）。complete 时复查状态与同类型——上传期间文档可能被他人变更，永久性拒绝抛
     * BusinessException（编排方协议化 409 + 会话 FAILED）。
     * <p>metadata null=仅换文件不改任何属性（对齐 multipart edit command=null：author/tags/remark 置空、
     * dict 元数据不替换）；非 null=全量替换语义（author/tags/remark null=清空）。落库次序仿
     * importCompletedFile：DB 先、move 后——move 失败异常回滚事务，临时文件保留可重试。</p>
     *
     * @param operatorId 操作者
     * @param documentId 目标文档 ID
     * @param fileName   新文件名（create 时已清洗，create 校验过白名单/同类型）
     * @param mimeType   MIME（可空，null 用 application/octet-stream）
     * @param metadata   会话元数据（null=仅换文件）
     * @param tempPath   临时文件相对路径 .uploads/{sessionId}
     * @param fileSize   文件大小（= uploadLength，编排方已校验 FS 实际大小一致）
     * @param appMessages 国际化消息
     * @return 单文件落库结果（恒 SUCCESS；业务拒绝走异常）
     */
    public Uni<FileUploadResult> editWithFileFromTemp(TusEditRequest req) {
        String documentId = req.documentId();
        String fileName = req.fileName();
        AppMessages appMessages = req.appMessages();
        String newExt = DocumentCommandService.extractExtension(fileName);
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Target document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(doc -> validateEditableForComplete(doc, fileName, req.fileSize(), appMessages))
                .flatMap(doc -> {
                    ImportFileMetadata metadata = req.metadata();
                    String newVersion = metadata != null && metadata.version() != null && !metadata.version().isBlank()
                            ? metadata.version().trim() : null;
                    return checkVersionNotPublished(doc, newVersion, appMessages)
                            .flatMap(v -> applyTusEdit(doc, req, newExt, newVersion));
                });
    }

    /** tus DOC_EDIT 编辑入参（参数对象：complete 编排 → 服务；文件已在磁盘，move 而非 store） */
    record TusEditRequest(String operatorId, String documentId, String fileName, String mimeType,
                          ImportFileMetadata metadata, String tempPath, long fileSize,
                          AppMessages appMessages) {
    }

    /** tus 编辑落库链：唯一化 move 目标 → 快照 → update+作废旧事件+元数据 → 守卫回收 → move → 索引 */
    private Uni<FileUploadResult> applyTusEdit(DocumentEntity doc, TusEditRequest req,
                                               String newExt, String newVersion) {
        String documentId = req.documentId();
        String fileName = req.fileName();
        ImportFileMetadata metadata = req.metadata();
        return storagePathResolver.resolveStoragePath(doc.getKbId(), doc.getCategoryId(),
                documentId, fileName, newExt)
                // tus 编辑 move 走 REPLACE_EXISTING：目标路径若与磁盘现存文件或 DB 占用路径
                // 相同（编辑换同名文件时常态），会物理覆盖在用/归档文件。move 前跳号唯一化。
                .flatMap(desired -> storagePathResolver.uniqueStoragePathForMove(doc, desired))
                // 两阶段回收第一步：DB 更新前快照旧文件身份（同名重传覆盖防护）
                .flatMap(storagePath -> documentImportService.snapshotReplacedFile(doc.getFilePath())
                .flatMap(snapshots -> {
                    String newTitle = metadata != null && metadata.title() != null && !metadata.title().isBlank()
                            ? metadata.title().trim() : doc.getTitle();
                    String newAuthor = metadata != null ? metadata.author() : null;
                    String newTags = metadata != null && metadata.tags() != null && !metadata.tags().isEmpty()
                            ? String.join(",", metadata.tags()) : null;
                    String newRemark = metadata != null ? metadata.remark() : null;
                    String effectiveMime = req.mimeType() != null ? req.mimeType() : "application/octet-stream";
                    DocumentEntity updated = doc.editWithNewFile(new DocumentFileSpec(newTitle,
                            storagePath, req.fileSize(), effectiveMime, DocumentCommandService.classify(fileName),
                            newAuthor, newTags, newRemark, newVersion));
                    return documentRepository.update(updated)
                            // 重置 PENDING = 新内容周期：作废旧周期未发事件（与 multipart replaceDraftFile 一致）
                            .call(() -> outboxRepository.cancelPendingByDocument(documentId))
                            .flatMap(e -> documentImportService.replaceImportMetadata(documentId, metadata, e))
                            // DB 更新成功后：旧文件守卫回收（失败仅告警，不影响编辑结果）
                            .flatMap(e -> documentImportService.recycleReplacedFile(documentId, doc.getFilePath(),
                                    storagePath, snapshots).replaceWith(e))
                            .flatMap(e -> fileStorageService.move(req.tempPath(), storagePath).replaceWith(e))
                            // 文件已 move 入位：异步更新内容索引（不影响 complete 结果）
                            .invoke(e -> documentImportService.indexContent(documentId, storagePath))
                            .map(e -> {
                                logger.info("tus edited file replaced: documentId={}, path={}, size={}",
                                        documentId, storagePath, req.fileSize());
                                return FileUploadResult.success(new FileUploadResult.SuccessPayload(documentId, e.getTitle(), req.fileSize(),
                                        DocumentCommandService.deduceFileType(effectiveMime, newExt),
                                        DocumentCommandService.classify(fileName), e.getStatus()));
                            });
                }));
    }

    /**
     * tus VERSION_OVERRIDE complete：PUBLISHED 同类型新版本覆盖（文件已在磁盘，move 而非 store）。
     * <p>复查 PUBLISHED + KB 活跃 + 同类型 + 版本查重（create 后状态可能变更），拒绝抛 BusinessException。
     * 归档旧版 document_versions(was_published=TRUE) → 文档更新（currentVersion=新版本、status→DRAFT、
     * processStatus→PENDING）→ 元数据全量替换 → move 临时文件入位。落库次序：DB 先、move 后。</p>
     *
     * @return 版本覆盖结果（与 multipart overrideVersion 返回一致）
     */
    public Uni<VersionOverrideResult> overrideVersionFromTemp(TusOverrideRequest req) {
        String documentId = req.documentId();
        String fileName = req.fileName();
        AppMessages appMessages = req.appMessages();
        String newExt = DocumentCommandService.extractExtension(fileName);
        // 版本号必填（create 已校验，complete 复查兜底）
        String newVersion = DocumentCommandService.extractRequiredVersion(req.metadata() != null ? req.metadata().version() : null, appMessages);
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Target document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(doc -> recheckTusOverrideTarget(doc, req, newVersion, newExt))
                .flatMap(doc -> {
                    // 复查通过 → resolve 路径 → 唯一化 move 目标 → 归档+更新
                    return storagePathResolver.resolveStoragePath(doc.getKbId(), doc.getCategoryId(),
                            documentId, fileName, newExt)
                            // tus 路径 move 走 REPLACE_EXISTING：目标路径若与磁盘现存文件或 DB
                            // 归档路径相同，会物理覆盖归档旧版文件（数据损坏级）。move 前跳号唯一化。
                            .flatMap(desired -> storagePathResolver.uniqueStoragePathForMove(doc, desired))
                            .flatMap(storagePath -> applyTusOverride(doc, req, newVersion, newExt, storagePath));
                });
    }

    /** tus VERSION_OVERRIDE 覆盖入参（参数对象：complete 编排 → 服务；文件已在磁盘，move 而非 store） */
    record TusOverrideRequest(String operatorId, String documentId, String fileName, String mimeType,
                              ImportFileMetadata metadata, String tempPath, long fileSize,
                              AppMessages appMessages) {
    }

    /** tus 覆盖 complete 复查：仅 PUBLISHED + 处理周期 COMPLETED + KB 活跃 + 同类型 + 大小 + 版本查重 */
    private Uni<DocumentEntity> recheckTusOverrideTarget(DocumentEntity doc, TusOverrideRequest req,
                                                         String newVersion, String newExt) {
        AppMessages appMessages = req.appMessages();
        String documentId = req.documentId();
        // 状态复查：仅 PUBLISHED（与 multipart overrideVersion 一致）
        if (!DocumentEntity.STATUS_PUBLISHED.equals(doc.getStatus())) {
            logger.error("Only published documents can be version-overridden: docId={}, status={}", documentId, doc.getStatus());
            return Uni.createFrom().failure(new DocumentStateException(appMessages.documentStateInvalid()));
        }
        // 处理周期守卫：与 multipart overrideVersion 一致，仅 COMPLETED 可覆盖
        if (!DocumentEntity.PROCESS_COMPLETED.equals(doc.getProcessStatus())) {
            logger.warn("Document processing status not ready before override: docId={}, processStatus={}",
                    documentId, doc.getProcessStatus());
            String message = DocumentEntity.PROCESS_FAILED.equals(doc.getProcessStatus())
                    ? appMessages.versionOverrideFailed()
                    : appMessages.versionOverrideProcessing();
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.USER_ERROR_A0508, message));
        }
        // KB 活跃复查
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(doc.getKbId()))
                .onItem().ifNull().failWith(() ->
                        new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        return Uni.createFrom().failure(
                                new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    // 同类型 + 大小复查（create 后文档/配置可能已变更）
                    String existingExt = DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(doc.getFilePath()));
                    if (!newExt.equalsIgnoreCase(existingExt)) {
                        logger.warn("tus version override rejected, file type differs from the source file: docId={}, expected={}, received={}",
                                documentId, existingExt, newExt);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                                appMessages.fileTypeChanged(existingExt, newExt)));
                    }
                    if (req.fileSize() > maxFileSize) {
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0705,
                                appMessages.fileSizeExceeded(DocumentImportService.formatBytes(maxFileSize))));
                    }
                    return checkVersionNotPublished(doc, newVersion, appMessages).replaceWith(doc);
                });
    }

    /** tus 覆盖落库链：归档旧版 → 更新文档 → 作废旧事件 → 元数据 → move 入位 → 索引（DB 先、move 后） */
    private Uni<VersionOverrideResult> applyTusOverride(DocumentEntity doc, TusOverrideRequest req,
                                                        String newVersion, String newExt, String storagePath) {
        String documentId = req.documentId();
        String fileName = req.fileName();
        ImportFileMetadata metadata = req.metadata();
        String title = metadata != null && metadata.title() != null && !metadata.title().isBlank()
                ? metadata.title().trim() : DocumentCommandService.stripExtension(fileName);
        String effectiveMime = req.mimeType() != null ? req.mimeType() : "application/octet-stream";
        DocumentVersion archived = DocumentVersion.published(new DocumentVersion.PublishedSeed(
                doc.getId().value(), doc.getCurrentVersion(), doc.getFilePath(), doc.getFileSize(),
                doc.getMimeType(), Boolean.TRUE, req.operatorId(), LocalDateTime.now()));
        DocumentEntity updated = doc.overrideWithNewVersion(new DocumentFileSpec(
                title, storagePath, req.fileSize(), effectiveMime,
                DocumentCommandService.classify(fileName),
                metadata != null ? metadata.author() : null,
                metadata != null && metadata.tags() != null && !metadata.tags().isEmpty()
                        ? String.join(",", metadata.tags()) : null,
                metadata != null ? metadata.remark() : null,
                newVersion));
        return documentRepository.archiveVersion(archived)
                .flatMap(v2 -> documentRepository.update(updated))
                // 覆盖后模型不应再处理/回调旧文件：作废旧周期未发事件（与 multipart overrideVersion 同事务一致）
                .call(() -> outboxRepository.cancelPendingByDocument(documentId))
                .flatMap(e -> documentImportService.replaceImportMetadata(documentId, metadata, e))
                .flatMap(e -> fileStorageService.move(req.tempPath(), storagePath).replaceWith(e))
                // 文件已 move 入位：异步更新内容索引（状态回 DRAFT，重审通过前不可搜）
                .invoke(e -> documentImportService.indexContent(documentId, storagePath))
                .map(e -> {
                    logger.info("tus version override succeeded: documentId={}, newVersion={}, path={}",
                            documentId, e.getCurrentVersion(), storagePath);
                    return VersionOverrideResult.of(new VersionOverrideResult.Payload(
                            documentId, title, req.fileSize(),
                            DocumentCommandService.deduceFileType(effectiveMime, newExt),
                            newVersion, e.getStatus()));
                });
    }

    /**
     * DOC_EDIT complete 状态/同类型/大小复查（create 后文档可能已变更）。
     * 与 {@link #validateDocumentUploadTarget} 的 DOC_EDIT 分支同规则，reject 走 BusinessException。
     */
    private Uni<DocumentEntity> validateEditableForComplete(DocumentEntity doc, String fileName,
                                                            long fileSize, AppMessages appMessages) {
        if (DocumentEntity.STATUS_REVIEWING.equals(doc.getStatus())) {
            return Uni.createFrom().failure(new DocumentStateException(appMessages.documentUnderReview()));
        }
        if (DocumentEntity.STATUS_ARCHIVED.equals(doc.getStatus())
                || DocumentEntity.STATUS_DEPRECATED.equals(doc.getStatus())) {
            return Uni.createFrom().failure(new DocumentStateException(appMessages.documentStateInvalid()));
        }
        if (DocumentEntity.STATUS_PUBLISHED.equals(doc.getStatus())) {
            return Uni.createFrom().failure(new DocumentStateException(appMessages.documentPublishedUseVersion()));
        }
        String newExt = DocumentCommandService.extractExtension(fileName);
        String existingExt = DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(doc.getFilePath()));
        if (!newExt.equalsIgnoreCase(existingExt)) {
            logger.warn("tus edit rejected, file type differs from the source file: docId={}, expected={}, received={}",
                    doc.getId().value(), existingExt, newExt);
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeChanged(existingExt, newExt)));
        }
        if (fileSize > maxFileSize) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                    appMessages.fileSizeExceeded(DocumentImportService.formatBytes(maxFileSize))));
        }
        return Uni.createFrom().item(doc);
    }

    /**
     * 版本查重（版本覆盖 / 编辑改版本号用）：新版本号不得等于当前已发布版（publishedVersion）
     * 或已归档历史版（document_versions）。命中 → A0701 + versionAlreadyPublished。
     * newVersion 为 null 时直接放行（编辑省略 version 的场景）。
     */
    private Uni<Void> checkVersionNotPublished(DocumentEntity doc, String newVersion, AppMessages appMessages) {
        if (newVersion == null) {
            return Uni.createFrom().voidItem();
        }
        String documentId = doc.getId().value();
        if (newVersion.equals(doc.getPublishedVersion())) {
            logger.warn("Version number duplicates the current published version: docId={}, version={}", documentId, newVersion);
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.versionAlreadyPublished(newVersion)));
        }
        return documentRepository.findVersion(documentId, newVersion)
                .flatMap(existing -> {
                    if (existing != null) {
                        logger.warn("Version number duplicates an archived historical version: docId={}, version={}", documentId, newVersion);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                                appMessages.versionAlreadyPublished(newVersion)));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * 从 EditDocumentCommand 构建 document_metadata key→value 映射。
     * null/blank = 清空该 key（不写入 Map → replaceAll 时先删后插，不在 Map 中的 key 被删）。
     */
    private static java.util.Map<String, String> buildEditMetadata(EditDocumentCommand command) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (command.product() != null && !command.product().isBlank()) {
            map.put("product", command.product().trim());
        }
        if (command.chipModel() != null && !command.chipModel().isBlank()) {
            map.put("chip_model", command.chipModel().trim());
        }
        // version 不写入 document_metadata：由 documents.current_version 承载
        if (command.securityLevel() != null && !command.securityLevel().isBlank()) {
            map.put("sensitivity", command.securityLevel().trim());
        }
        if (command.official() != null && !command.official().isBlank()) {
            map.put("official", command.official().trim());
        }
        return map;
    }

    /**
     * 校验标签列表：数量 ≤ {@value #MAX_TAGS_COUNT}，单个标签长度 ≤ {@value #MAX_TAG_LENGTH}。
     * @throws BusinessException 非法时直接抛出
     */
    private void validateTags(List<String> tags, AppMessages appMessages) {
        if (tags == null || tags.isEmpty()) {
            return; // null/空 = 清空，合法
        }
        if (tags.size() > MAX_TAGS_COUNT) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.tagsCountExceeded(MAX_TAGS_COUNT));
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String trimmed = tag.trim();
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.tagLengthExceeded(MAX_TAG_LENGTH));
            }
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                            appMessages.tagLengthExceeded(MAX_TAG_LENGTH));
                }
            }
        }
    }

}