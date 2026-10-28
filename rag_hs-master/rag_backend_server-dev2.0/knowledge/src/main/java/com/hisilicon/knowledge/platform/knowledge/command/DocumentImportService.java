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
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DraftConflictPolicy;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.IdGenerator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ArchiveFormats;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ZipFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDocumentResponse;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadSummary;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档导入服务：上传（普通文件 + ZIP/RAR 压缩包混传 + 元数据）、tus 会话落地
 * （persistZipEntry/importCompletedFile）与上传期文件生命周期（快照/守卫回收/内容索引触发）。
 * <p>从 DocumentCommandService 拆出；存储路径解析经 {@link DocumentStoragePathResolver}；
 * 版本覆盖/编辑流见 DocumentVersionService。纯函数静态（扩展名判定/类型归类）仍留在
 * DocumentCommandService 供两域共用。</p>
 */
@ApplicationScoped
public class DocumentImportService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentImportService.class);

    /** 单个文件最大大小：100MB（默认，可通过 kb.upload.max-file-size 配置） */
    private final long maxFileSize;

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

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final FileStorageService fileStorageService;
    private final FileRecycleService fileRecycleService;
    private final DictValueValidator dictValueValidator;
    private final DocumentContentIndexService contentIndexService;
    private final Vertx vertx;
    private final DocumentStoragePathResolver storagePathResolver;

    public DocumentImportService(KnowledgeBaseRepository knowledgeBaseRepository,
                                 CategoryRepository categoryRepository,
                                 DocumentRepository documentRepository,
                                 DocumentMetadataRepository documentMetadataRepository,
                                 FileStorageService fileStorageService,
                                 FileRecycleService fileRecycleService,
                                 DictValueValidator dictValueValidator,
                                 DocumentContentIndexService contentIndexService,
                                 Vertx vertx,
                                 DocumentStoragePathResolver storagePathResolver,
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
        this.fileStorageService = fileStorageService;
        this.fileRecycleService = fileRecycleService;
        this.dictValueValidator = dictValueValidator;
        this.contentIndexService = contentIndexService;
        this.vertx = vertx;
        this.storagePathResolver = storagePathResolver;
        this.zipMaxEntries = zipMaxEntries;
        this.zipMaxTotalBytes = zipMaxTotalSize.asLongValue();
        this.maxFileSize = maxFileSize.asLongValue();
    }

    /**
     * 内容抽取触发（fire-and-forget）：文件内容落定（新路径已写入 documents.file_path）后调用。
     * 异步执行，不阻塞、不影响业务结果；详见 {@link DocumentContentIndexService#indexAsync}。
     * 包内共享：DocumentVersionService 版本覆盖/编辑落定后同源触发。
     */
    void indexContent(String documentId, String filePath) {
        // 未装配内容索引（如单测直喂构造器传 null）时视为功能关闭，不影响主流程
        if (contentIndexService == null) {
            return;
        }
        contentIndexService.indexAsync(documentId, filePath);
    }
    /**
     * 上传文档（支持普通文件 + ZIP 压缩包混传，支持元数据）
     *
     * @param operatorId 操作员 ID
     * @param kbId       所属知识库 ID
     * @param categoryId 目标类别 ID（可为空）
     * @param files      文件列表（fileName + content）
     * @param metadata   上传元数据（可 null；author/tags/product/chipModel/version/securityLevel/accessPolicy）
     * @param locale     语言环境
     * @return 上传响应（逐文件结果 + 汇总）
     */
    @WithTransaction
    public Uni<UploadDocumentResponse> upload(UploadRequest request) {
        String operatorId = request.operatorId();
        String kbId = request.kbId();
        String categoryId = request.categoryId();
        List<DocumentCommandService.FileInput> files = request.files();
        UploadDocumentCommand metadata = request.metadata();
        AppMessages appMessages = LocaleUtil.getAppMessage(request.locale());
        // 版本号必填（上传由元数据提供，存入 documents.current_version）
        String version = DocumentCommandService.extractRequiredVersion(metadata != null ? metadata.version() : null, appMessages);
        String trimmedKbId = kbId.trim();
        String baseCategoryId = categoryId == null ? null : categoryId.trim();

        logger.info("Upload documents: kbId={}, categoryId={}, fileCount={}, version={}, operatorId={}",
                trimmedKbId, baseCategoryId, files.size(), version, operatorId);

        // 1) 校验知识库存在且未删除——findByIdForUpdate 锁 KB 行（与删除事务互斥）：
        //    删除提交后本事务校验必然看到已删，杜绝文档插入已删目录产生新孤儿
        return knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of(trimmedKbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", trimmedKbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        logger.error("Knowledge base deleted, upload not allowed: id={}", trimmedKbId);
                        return Uni.createFrom().failure(
                                new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    return Uni.createFrom().item(kb);
                })
                // 2) categoryId 非空时校验类别存在且属于该知识库
                .flatMap(kb -> validateUploadCategory(trimmedKbId, baseCategoryId, appMessages)
                        .replaceWith(kb))
                // 3) 字典值校验（上传/编辑共用，fail-closed）——提前校验，避免文件落盘后才发现非法值
                .flatMap(kb -> dictValueValidator
                        .validateOrThrow(buildDocumentMetadata(metadata), invalid -> new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0420,
                                appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value())))
                        .replaceWith(kb))
                // 4) 展开文件（ZIP 解压 + 类别路径解析），然后逐个处理
                .flatMap(kb -> {
                    ImportRequest base = new ImportRequest(operatorId, trimmedKbId, baseCategoryId,
                            null, null, null, null, metadata, null, appMessages);
                    return expandFiles(base, files)
                            .flatMap(expandedFiles -> processFiles(base, expandedFiles));
                });
    }

    /**
     * 上传目标类别校验（categoryId 为空 = 根目录直通；非空时校验存在/活跃/归属该知识库）。
     */
    private Uni<Void> validateUploadCategory(String kbId, String baseCategoryId, AppMessages appMessages) {
        if (baseCategoryId == null || baseCategoryId.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return categoryRepository.findById(CategoryId.of(baseCategoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", baseCategoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(cat -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(cat.getStatus())
                            || !kbId.equals(cat.getKbId())) {
                        logger.error("Category not found or does not belong to this knowledge base: id={}, kbId={}",
                                baseCategoryId, kbId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }
    // ======================== 文件展开（ZIP/RAR 解压） ========================

    /**
     * 展开文件列表：普通文件直通，ZIP 文件在 worker 线程解压后展开为 FileUploadContext 列表
     * <p>ZIP 解压是 CPU/内存密集操作（实测 10944 条目阻塞 event loop 3.5s+），
     * 必须通过 {@code vertx.executeBlocking} 切到 worker 线程池；回调自动回到 event loop context，
     * 后续 Hibernate 调用不受影响。</p>
     */
    private Uni<List<FileUploadContext>> expandFiles(ImportRequest req,
                                                      List<DocumentCommandService.FileInput> files) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        String baseCategoryId = req.categoryId();
        AppMessages appMessages = req.appMessages();
        List<FileUploadContext> result = new ArrayList<>();
        List<Uni<Void>> tasks = new ArrayList<>();

        for (DocumentCommandService.FileInput file : files) {
            if (ArchiveFormats.isArchive(file.fileName())) {
                // ZIP/RAR 文件：先校验压缩包自身大小（与普通文件同一业务上限），超限不解压直接 FAILED
                if (file.content().length > maxFileSize) {
                    logger.warn("Archive file size exceeds limit: fileName={}, size={}", file.fileName(), file.content().length);
                    result.add(new FileUploadContext(file.fileName(), null,
                            null, baseCategoryId, null, true,
                            appMessages.fileSizeExceeded(formatBytes(maxFileSize)), ErrorCodeEnum.USER_ERROR_A0705.getCode()));
                    continue;
                }
                // ZIP 文件：worker 线程解压（解压失败按原逻辑作为单个 FAILED 结果）
                tasks.add(vertx.executeBlocking(() -> extractZipEntries(file.fileName(), file.content()))
                        .map(entries -> {
                            for (var entry : entries) {
                                String entryFileName = DocumentCommandService.extractFileName(entry.path());
                                // 目录路径 → 目标类别 ID（此处同步收集，后续在 processFiles 中逐文件异步确保类别存在）
                                String dirPath = DocumentCommandService.getDirectoryPath(entry.path()).orElse(null);
                                result.add(new FileUploadContext(entryFileName, entry.content(),
                                        null, baseCategoryId, dirPath));
                            }
                            logger.info("ZIP extracted: {} → {} files", file.fileName(), entries.size());
                            return DocumentCommandService.voidTaskResult();
                        })
                        .onFailure(ZipExtractException.class)
                        .recoverWithItem(t -> {
                            FileUploadContext failure = zipFailureContext(file.fileName(), baseCategoryId, t, appMessages);
                            if (failure != null) {
                                result.add(failure);
                            }
                            return null;
                        }));
            } else {
                // 普通文件：直通
                result.add(new FileUploadContext(file.fileName(), file.content(),
                        file.mimeType(), baseCategoryId, null));
            }
        }
        if (tasks.isEmpty()) {
            return Uni.createFrom().item(result);
        }
        // 多个 ZIP 并行解压；回调都在 event loop context 串行执行，result 收集安全
        return Uni.join().all(tasks).andFailFast().map(v -> result);
    }

    /** ZIP/RAR 解压失败 → 单条目 FAILED 占位（notAZipFile / 超限 / 其余 IO 三类原因） */
    private FileUploadContext zipFailureContext(String fileName, String baseCategoryId,
                                                ZipExtractException t, AppMessages appMessages) {
        if (t.notAZipFile) {
            logger.warn("Not an archive file: fileName={}, error={}", fileName, t.getMessage());
            return new FileUploadContext(fileName, null,
                    null, baseCategoryId, null, true,
                    t.rar ? appMessages.rarNotValid() : appMessages.zipNotValid(),
                    ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode());
        }
        if (t.limitExceeded != null) {
            // 超限（条目数/总量）：明确告知用户原因和上限
            ZipFileExtractor.ZipLimitExceededException le = t.limitExceeded;
            String reason = le.entryCountExceeded
                    ? appMessages.zipEntryCountExceeded((int) le.maxValue)
                    : appMessages.zipTotalSizeExceeded(formatBytes(le.maxValue));
            logger.warn("ZIP extraction exceeds limit: fileName={}, {}", fileName, reason);
            return new FileUploadContext(fileName, null,
                    null, baseCategoryId, null, true,
                    reason, ErrorCodeEnum.ERROR_READ_FILE.getCode());
        }
        logger.error("ZIP extraction failed: fileName={}, error={}", fileName, t.getMessage());
        return new FileUploadContext(fileName, null,
                null, baseCategoryId, null, true,
                appMessages.zipExtractFailed(), ErrorCodeEnum.ERROR_READ_FILE.getCode());
    }

    /** 解压压缩包 ZIP/RAR（worker 线程执行），异常统一包装为 {@link ZipExtractException} 便于分类恢复 */
    private List<ZipFileExtractor.ZipFileEntry> extractZipEntries(String fileName, byte[] content) {
        boolean rar = ArchiveFormats.isRar(fileName);
        try {
            return rar
                    ? RarFileExtractor.extract(content, zipMaxEntries, zipMaxTotalBytes,
                            archiveExternalTool.orElse(null))
                    : ZipFileExtractor.extract(content, zipMaxEntries, zipMaxTotalBytes);
        } catch (ZipFileExtractor.NotAZipFileException | RarFileExtractor.NotRarFileException e) {
            throw new ZipExtractException(e, true, rar);
        } catch (ZipFileExtractor.ZipLimitExceededException e) {
            throw new ZipExtractException(e);
        } catch (java.io.IOException e) {
            throw new ZipExtractException(e, false, rar);
        }
    }

    /** 解压异常包装：区分「非压缩包文件」（422）与超限/IO 失败 */
    private static final class ZipExtractException extends RuntimeException {
        final boolean notAZipFile;
        final boolean rar;
        final ZipFileExtractor.ZipLimitExceededException limitExceeded;

        ZipExtractException(Throwable cause, boolean notAZipFile, boolean rar) {
            super(cause.getMessage(), cause);
            this.notAZipFile = notAZipFile;
            this.rar = rar;
            this.limitExceeded = null;
        }

        ZipExtractException(ZipFileExtractor.ZipLimitExceededException limitExceeded) {
            super(limitExceeded.getMessage(), limitExceeded);
            this.notAZipFile = false;
            this.rar = false;
            this.limitExceeded = limitExceeded;
        }
    }

    /** 逐个处理展开后的文件（串行链），聚合为上传响应 */
    private Uni<UploadDocumentResponse> processFiles(ImportRequest req,
                                                      List<FileUploadContext> contexts) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        UploadDocumentCommand metadata = req.uploadMeta();
        AppMessages appMessages = req.appMessages();
        List<FileUploadResult> results = new ArrayList<>();

        // 串行处理（逐个文件），确保类别创建和文档创建有序
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (FileUploadContext ctx : contexts) {
            chain = chain.flatMap(v -> {
                // 如果是 ZIP 解压失败的占位条目
                if (ctx.zippedFailed) {
                    results.add(FileUploadResult.fail(ctx.fileName, ctx.failReason, ctx.failCode));
                    return Uni.createFrom().voidItem();
                }
                // 先确保目标类别路径存在
                return ensureCategoryPath(kbId, ctx.baseCategoryId, ctx.dirPath, appMessages)
                        .flatMap(targetCatId -> processSingleFile(new ImportRequest(
                                        operatorId, kbId, targetCatId, ctx.fileName, ctx.mimeType,
                                        null, null, metadata, null, appMessages),
                                ctx.content, contexts.size() == 1)
                                .invoke(results::add)
                                .replaceWithVoid());
            });
        }
        return chain.map(v -> aggregateResults(results));
    }

    /**
     * 确保目录路径对应的类别层级存在，返回目标叶子类别 ID
     */
    private Uni<String> ensureCategoryPath(String kbId, String baseCategoryId,
                                            String dirPath, AppMessages appMessages) {
        if (dirPath == null || dirPath.isEmpty()) {
            return Uni.createFrom().item(baseCategoryId);
        }
        String[] segments = dirPath.split("/");
        // 串行遍历目录段
        Uni<String> chain = Uni.createFrom().item(baseCategoryId);
        // 计算起始深度：baseCategoryId 为 null 时 depth=0（KB根），否则需要查
        final int[] depth = {baseCategoryId == null ? 0 : -1}; // -1 表示需要从 DB 查

        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            final String seg = segment;
            chain = chain.flatMap(parentId -> {
                int currentDepth = depth[0] + 1;
                if (currentDepth > 10) {
                    return Uni.createFrom().failure(new BusinessException(
                            ErrorCodeEnum.USER_ERROR_A0420,
                            appMessages.categoryDepthExceeded()));
                }
                depth[0] = currentDepth;
                return findOrCreateCategory(kbId, parentId, seg, appMessages);
            });
        }
        return chain;
    }

    /** 查找或创建同父同名类别（复用已存在，不存在则新建） */
    private Uni<String> findOrCreateCategory(String kbId, String parentId,
                                              String name, AppMessages appMessages) {
        return categoryRepository.findByName(kbId, parentId, name)
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(existing.getId().value());
                    }
                    // 新建类别：计算 sortOrder（首个从 1 开始，已有则接最大序号 +1）
                    return categoryRepository.maxSortOrder(kbId, parentId)
                            .flatMap(maxSort -> {
                                int sortOrder = (maxSort != null) ? maxSort + 1 : 1;
                                CategoryEntity newCat = CategoryEntity.create(
                                        kbId, parentId, name, sortOrder);
                                return categoryRepository.persist(newCat)
                                        .map(cat -> cat.getId().value());
                            });
                });
    }

    /**
     * 同名同类型过滤：仅保留与本次上传扩展名一致（{@link DocumentCommandService#extractExtension} 已统一小写）的
     * 已存在文档参与冲突分流。同名不同类型视为不同文档（走新建），不误报"文档已存在"。
     */
    private static List<DocumentEntity> filterSameExtension(List<DocumentEntity> docs, String extension) {
        return docs.stream()
                .filter(d -> DocumentCommandService.extractExtension(DocumentCommandService.extractFileName(d.getFilePath())).equals(extension))
                .toList();
    }
    // ======================== 单文件处理（multipart 上传链） ========================

    /**
     * 处理单个文件（校验 + 同名检测 + 冲突分流 + 存储 + 持久化 + 元数据写入）
     */
    private Uni<FileUploadResult> processSingleFile(ImportRequest req, byte[] content, boolean singleFile) {
        String fileName = req.fileName();
        AppMessages appMessages = req.appMessages();
        // 校验 1: 文件大小
        if (content == null || content.length == 0) {
            return Uni.createFrom().item(
                    FileUploadResult.fail(fileName, appMessages.fileCannotEmpty(),
                            ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME.getCode()));
        }
        if (content.length > maxFileSize) {
            logger.warn("File size exceeds limit: fileName={}, size={}", fileName, content.length);
            return Uni.createFrom().item(
                    FileUploadResult.fail(fileName, appMessages.fileSizeExceeded(formatBytes(maxFileSize)),
                            ErrorCodeEnum.USER_ERROR_A0705.getCode()));
        }

        // 校验 2: 文件类型（扩展名）——不支持类型跳过（警告非错误），与 persistZipEntry 一致；
        // 批量容忍部分跳过，整体仍成功（code=0），详见 results[].reason/code + summary.skipped
        String extension = DocumentCommandService.extractExtension(fileName);
        if (!DocumentCommandService.isExtensionAllowed(fileName, extension)) {
            logger.warn("Unsupported file type: fileName={}, ext={}", fileName, extension);
            return Uni.createFrom().item(
                    FileUploadResult.skipped(fileName, appMessages.fileTypeNotSupported(extension),
                            ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode()));
        }

        // 提取标题：单文件上传且前端指定了 title 时用前端值，否则文件名去扩展名推导
        // （ZIP 批量 singleFile=false，强制用各自文件名避免批量同名）
        String title = (singleFile && req.uploadMeta() != null && req.uploadMeta().title() != null && !req.uploadMeta().title().isBlank())
                ? req.uploadMeta().title().trim() : DocumentCommandService.stripExtension(fileName);
        String effectiveMimeType = (req.mimeType() != null) ? req.mimeType() : "application/octet-stream";
        ImportRequest effective = new ImportRequest(req.operatorId(), req.kbId(), req.categoryId(),
                fileName, effectiveMimeType, title, extension, req.uploadMeta(), null, appMessages);

        // 同名检测 + 冲突处理
        return documentRepository.findByKbIdAndTitle(req.kbId(), req.categoryId(), title)
                .flatMap(existingDocs ->
                        // 同名检测语义为「同名 + 同扩展名」：同名不同类型视为不同文档走新建，不误报文档已存在
                        routeSingleFileConflict(effective, content,
                                filterSameExtension(existingDocs, extension)));
    }

    /** 单文件冲突分流：DRAFT 覆盖 → REVIEWING/PUBLISHED/ARCHIVED 跳过 → 新建 */
    private Uni<FileUploadResult> routeSingleFileConflict(ImportRequest req, byte[] content,
                                                          List<DocumentEntity> conflicts) {
        var draftDoc = conflicts.stream()
                .filter(d -> DocumentEntity.STATUS_DRAFT.equals(d.getStatus()))
                .findFirst();
        if (draftDoc.isPresent()) {
            return overrideDraftFile(req, draftDoc.get(), content);
        }
        Uni<FileUploadResult> skip = conflictSkipResult(conflicts, req.fileName(), req.appMessages());
        if (skip != null) {
            return skip;
        }
        // 无冲突 → 新建
        return createNewFile(req, content);
    }

    // ======================== 被替换旧文件的两阶段回收 ========================

    /**
     * 快照被替换文档的旧物理文件身份（两阶段回收第一步，须在 DB 更新前调用）。
     * <p>身份快照防同名重传误回收：快照后旧路径若被他人重传覆盖，回收时身份不一致自动跳过
     * （见 {@link FileRecycleService#recycleGuarded}）。旧路径为空 → 空快照（后续直达无回收）。</p>
     */
    Uni<Map<String, FileStorageService.FileSnapshot>> snapshotReplacedFile(String oldFilePath) {
        if (oldFilePath == null || oldFilePath.isBlank()) {
            return Uni.createFrom().item(Map.of());
        }
        return fileRecycleService.snapshot(List.of(oldFilePath));
    }

    /**
     * 守卫回收被替换的旧物理文件（两阶段回收第二步：DB 更新成功后调用）——严格复用删除流程的
     * {@link FileRecycleService#recycleGuarded} 模式（快照已于更新前经 {@link #snapshotReplacedFile} 采集）。
     * <p>回收守卫（命中任一即跳过，仅记 info 日志）：</p>
     * <ol>
     *   <li>旧路径 == 实际落盘新路径：物理文件仍归当前文档使用，无孤儿可回收；</li>
     *   <li>旧路径仍被 document_versions 引用：归档历史版本（版本覆盖等）仍需可下载；</li>
     *   <li>旧路径仍被其他 documents 行引用：同名清洗碰撞等极端场景，防误删他文档文件。</li>
     * </ol>
     * <p>回收失败仅告警（文件留原地由清理任务兜底），不影响上传/编辑业务结果。</p>
     */
    Uni<Void> recycleReplacedFile(String documentId, String oldFilePath, String newFilePath,
                                  Map<String, FileStorageService.FileSnapshot> snapshots) {
        if (snapshots.isEmpty() || oldFilePath == null || oldFilePath.isBlank()
                || oldFilePath.equals(newFilePath)) {
            return Uni.createFrom().voidItem();
        }
        return documentRepository.findVersionFilePaths(documentId)
                .flatMap(versionPaths -> {
                    if (versionPaths != null && versionPaths.contains(oldFilePath)) {
                        logger.info("Skip old file recycling (still referenced by a historical version): documentId={}, filePath={}",
                                documentId, oldFilePath);
                        return Uni.createFrom().voidItem();
                    }
                    return documentRepository.findIdsByFilePaths(List.of(oldFilePath))
                            .flatMap(pathOwners -> {
                                String ownerId = pathOwners.get(oldFilePath);
                                if (ownerId != null && !documentId.equals(ownerId)) {
                                    logger.info("Skip old file recycling (still referenced by another document): documentId={}, filePath={}, owner={}",
                                            documentId, oldFilePath, ownerId);
                                    return Uni.createFrom().voidItem();
                                }
                                return fileRecycleService
                                        .recycleGuarded("documentId=" + documentId + " (replaced-file)", snapshots)
                                        .onFailure().invoke(err -> logger.warn(
                                                "Failed to recycle the replaced old file (file left in place, cleanup task will handle it): documentId={}, filePath={}, error={}",
                                                documentId, oldFilePath, err.getMessage()))
                                        .onFailure().recoverWithNull()
                                        .replaceWithVoid();
                            });
                });
    }

    /** DRAFT 同名覆盖（multipart 链：store 覆盖旧文件 + 全量替换元数据 + 守卫回收旧 + 索引触发） */
    private Uni<FileUploadResult> overrideDraftFile(ImportRequest req, DocumentEntity draftDoc,
                                                     byte[] content) {
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        UploadDocumentCommand metadata = req.uploadMeta();
        String documentId = draftDoc.getId().value();
        String oldFilePath = draftDoc.getFilePath();
        logger.info("DRAFT overwritten by same name: documentId={}, title={}", documentId, title);

        return storagePathResolver.resolveStoragePath(draftDoc.getKbId(), draftDoc.getCategoryId(),
                        documentId, fileName, extension)
                // 两阶段回收第一步：DB 更新前快照旧文件身份（同名重传覆盖防护）
                .flatMap(storagePath -> snapshotReplacedFile(oldFilePath)
                .flatMap(snapshots -> fileStorageService.store(storagePath, content)
                .flatMap(actualPath -> {
                    DocumentEntity updated = draftOverrideEntity(draftDoc, req, actualPath, (long) content.length);
                    return documentRepository.update(updated)
                            .flatMap(e -> {
                                // 全量替换 document_metadata（product/chip_model/version/sensitivity/access_policy）
                                java.util.Map<String, String> meta = buildDocumentMetadata(metadata);
                                return meta.isEmpty()
                                        ? Uni.createFrom().item(e)
                                        : documentMetadataRepository.replaceAll(documentId, meta)
                                                .replaceWith(e);
                            })
                            // DB 更新成功后：旧文件守卫回收（失败仅告警，不影响上传结果）
                            .flatMap(e -> recycleReplacedFile(documentId, oldFilePath, actualPath, snapshots)
                                    .replaceWith(e))
                            // 文件内容已落定：异步更新内容索引（不影响上传结果）
                            .invoke(e -> indexContent(documentId, actualPath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, (long) content.length,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                })));
    }

    /** 创建新文档（含元数据持久化） */
    private Uni<FileUploadResult> createNewFile(ImportRequest req, byte[] content) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        String categoryId = req.categoryId();
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        String documentId = IdGenerator.doc();

        return storagePathResolver.resolveStoragePath(kbId, categoryId, documentId, fileName, extension)
                .flatMap(storagePath -> fileStorageService.store(storagePath, content)
                .flatMap(actualPath -> {
                    DocumentEntity entity = DocumentEntity.create(documentId, kbId, categoryId,
                            new DocumentFileSpec(title, actualPath, content.length, mimeType,
                                    DocumentCommandService.classify(fileName),
                                    metaAuthor(req), metaTagsJoined(req),
                                    metaRemark(req), metaVersion(req)),
                            operatorId);
                    return documentRepository.persist(entity)
                            .invoke(() -> logger.info("Document uploaded: id={}, title={}, size={}, path={}",
                                    documentId, title, content.length, actualPath))
                            .flatMap(e -> {
                                // 全量替换 document_metadata（product/chip_model/version/sensitivity/access_policy）
                                // documentId 即实体 ID（create 重载传入），存储路径与 DB 主键一致
                                java.util.Map<String, String> meta = buildDocumentMetadata(req.uploadMeta());
                                return meta.isEmpty()
                                        ? Uni.createFrom().item(e)
                                        : documentMetadataRepository.replaceAll(e.getId().value(), meta)
                                                .replaceWith(e);
                            })
                            // 文件内容已落定：异步更新内容索引（不影响上传结果）
                            .invoke(e -> indexContent(documentId, actualPath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, (long) content.length,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                }));
    }
    // ======================== tus complete：批量上传落库 ========================

    /**
     * 批量上传 complete 落库变体：文件已在磁盘（.uploads/{sessionId}），移动而非重 store。
     * <p>复用同名检测、{@link DocumentStoragePathResolver#resolveStoragePath}、
     * {@link DocumentCommandService#classify}、{@link DocumentCommandService#deduceFileType}。
     * 元数据取 {@link ImportFileMetadata}（create 时已解析并校验，complete 直接消费，不重解析/重校验）。</p>
     * <p>草稿态同名按 {@code draftPolicy} 处理：OVERRIDE 覆盖（旧文件移 trash + move 临时覆盖）；
     * SKIP 跳过（临时文件移 trash）。REVIEWING/PUBLISHED/ARCHIVED 一律 SKIP。DEPRECATED 视同未命中 → 新建。</p>
     */
    public Uni<FileUploadResult> importCompletedFile(ImportRequest req, String tempPath, long fileSize,
                                                      DraftConflictPolicy draftPolicy) {
        String effectiveMimeType = (req.mimeType() != null) ? req.mimeType() : "application/octet-stream";
        ImportRequest effective = new ImportRequest(req.operatorId(), req.kbId(), req.categoryId(),
                req.fileName(), effectiveMimeType, req.title(), req.extension(),
                null, req.importMeta(), req.appMessages());

        // 同名检测 + 冲突处理（与 processSingleFile 一致的分流，仅落库路径不同）
        return documentRepository.findByKbIdAndTitle(req.kbId(), req.categoryId(), req.title())
                .flatMap(existingDocs -> {
                    // 同名检测语义为「同名 + 同扩展名」：同名不同类型视为不同文档走新建，不误报文档已存在
                    List<DocumentEntity> conflicts = filterSameExtension(existingDocs, req.extension());
                    var draftDoc = conflicts.stream()
                            .filter(d -> DocumentEntity.STATUS_DRAFT.equals(d.getStatus()))
                            .findFirst();
                    if (draftDoc.isPresent()) {
                        if (draftPolicy == DraftConflictPolicy.SKIP) {
                            return skipImported(tempPath, req.fileName(), req.appMessages().documentAlreadyExists(),
                                    ErrorCodeEnum.USER_ERROR_A0508.getCode());
                        }
                        return overrideImportDraft(effective, draftDoc.get(), tempPath, fileSize);
                    }
                    Uni<FileUploadResult> skip = conflictSkipResult(conflicts, req.fileName(), req.appMessages());
                    if (skip != null) {
                        return skip;
                    }
                    // 无冲突 / DEPRECATED（不参与同名匹配）→ 新建
                    return importNewFile(effective, tempPath, fileSize);
                });
    }

    /** complete 新建文档（文件已在位；persist 先于 move，落库失败回滚保留临时文件） */
    private Uni<FileUploadResult> importNewFile(ImportRequest req, String tempPath, long fileSize) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        String categoryId = req.categoryId();
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        ImportFileMetadata metadata = req.importMeta();
        String documentId = IdGenerator.doc();
        logger.info("complete: new document created: documentId={}, title={}, size={}", documentId, title, fileSize);

        // resolve 路径（快照取名）→ persist 先于 move：persist 失败（DB 冲突）回滚保留临时文件，用户可重传；
        // move 失败回滚 persist（DB 不留残行）
        return storagePathResolver.resolveStoragePath(kbId, categoryId, documentId, fileName, extension)
                .flatMap(storagePath -> {
                    DocumentEntity entity = DocumentEntity.create(documentId, kbId, categoryId,
                            new DocumentFileSpec(title, storagePath, fileSize, mimeType,
                                    DocumentCommandService.classify(fileName),
                                    metaAuthor(req), metaTagsJoined(req),
                                    metaRemark(req), metaVersion(req)),
                            operatorId);
                    return documentRepository.persist(entity)
                            .flatMap(e -> fileStorageService.move(tempPath, storagePath).replaceWith(e))
                            .flatMap(e -> replaceImportMetadata(e.getId().value(), metadata, e))
                            // 文件已 move 入位：异步更新内容索引（不影响 complete 结果）
                            .invoke(e -> indexContent(documentId, storagePath))
                            .invoke(() -> logger.info("complete: document persisted: id={}, path={}", documentId, storagePath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, fileSize,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                });
    }

    /** complete 覆盖草稿态同名（update 先于 trash 旧+move 临时；任一步失败回滚 DB，临时文件保留） */
    private Uni<FileUploadResult> overrideImportDraft(ImportRequest req, DocumentEntity draftDoc,
                                                     String tempPath, long fileSize) {
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        ImportFileMetadata metadata = req.importMeta();
        String documentId = draftDoc.getId().value();
        String oldFilePath = draftDoc.getFilePath();
        logger.info("complete: DRAFT overwritten by same name: documentId={}, title={}", documentId, title);

        // resolve 路径（快照取名）→ update 先：失败回滚保留旧文件+临时文件；
        // 旧文件守卫回收（两阶段）→ move 临时入位；move 失败回滚 update（DB 不留残行），旧已回收（幂等可重试）
        return storagePathResolver.resolveStoragePath(draftDoc.getKbId(), draftDoc.getCategoryId(),
                        documentId, fileName, extension)
                .flatMap(storagePath -> snapshotReplacedFile(oldFilePath)
                .flatMap(snapshots -> {
                    DocumentEntity updated = draftOverrideEntity(draftDoc, req, storagePath, fileSize);
                    return documentRepository.update(updated)
                            .flatMap(e -> recycleReplacedFile(documentId, oldFilePath, storagePath, snapshots)
                                    .replaceWith(e))
                            .flatMap(e -> fileStorageService.move(tempPath, storagePath).replaceWith(e))
                            .flatMap(e -> replaceImportMetadata(documentId, metadata, e))
                            // 文件已 move 入位：异步更新内容索引（不影响 complete 结果）
                            .invoke(e -> indexContent(documentId, storagePath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, fileSize,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                }));
    }

    /** complete 跳过：临时文件移 trash，返回 SKIPPED */
    private Uni<FileUploadResult> skipImported(String tempPath, String fileName, String reason, String code) {
        return fileStorageService.moveToTrash(tempPath)
                .onFailure().invoke(err -> logger.warn("complete: failed to move temp file to trash while skipping (cleanup job will handle it): tempPath={}, error={}",
                        tempPath, err.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWith(FileUploadResult.skipped(fileName, reason, code));
    }

    /** 元数据全量替换到 document_metadata（仅写文档层；空 Map 跳过）。包内共享：DVS 版本覆盖同源调用 */
    Uni<DocumentEntity> replaceImportMetadata(String documentId, ImportFileMetadata metadata,
                                              DocumentEntity entity) {
        if (metadata == null || metadata.documentMetadata() == null || metadata.documentMetadata().isEmpty()) {
            return Uni.createFrom().item(entity);
        }
        return documentMetadataRepository.replaceAll(documentId, metadata.documentMetadata())
                .replaceWith(entity);
    }

    // ======================== zip 批量 complete：逐条目落库 ========================
    // 编排（流式解压 + fire-and-forget + 进度推进 + 终态）在 UploadSessionCommandService.runZipExtraction；
    // 本类仅暴露 persistZipEntry（单条目落库），由编排方 @WithTransaction 提供事务，与其共一事务。

    /**
     * 单个 zip 条目落库（在编排方 persistZipEntryAndBump 的 @WithTransaction 事务内执行——
     * 本方法不加 @WithTransaction 以与其共一事务：doc 落库 + 进度推进原子；
     * 每条目各开一独立事务，单条目失败回滚自身不影响其余）。按 entryPath 的目录路径映射子类目；
     * 扩展名不在白名单 → SKIPPED（跳过，不计失败）；空文件 → SKIPPED；同名检测 →
     * DRAFT+OVERRIDE 覆盖 / DRAFT+SKIP·REVIEWING·PUBLISHED·ARCHIVED 跳过 / 新建。
     */
    public Uni<FileUploadResult> persistZipEntry(ImportRequest req, String entryPath, byte[] entryBytes,
                                                 DraftConflictPolicy policy) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        String categoryId = req.categoryId();
        AppMessages appMessages = req.appMessages();
        String entryFileName = DocumentCommandService.extractFileName(entryPath);
        String extension = DocumentCommandService.extractExtension(entryFileName);
        if (!DocumentCommandService.isExtensionAllowed(entryFileName, extension)) {
            logger.warn("zip entry type unsupported, skipped: entry={}, ext={}", entryPath, extension);
            return Uni.createFrom().item(FileUploadResult.skipped(entryFileName,
                    appMessages.fileTypeNotSupported(extension), ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode()));
        }
        // 空文件按跳过处理（不记失败）：零字节占位文件在源码包中常见，不应拖垮整包终态（编排侧分桶聚合）
        if (entryBytes == null || entryBytes.length == 0) {
            return Uni.createFrom().item(FileUploadResult.skipped(entryFileName,
                    appMessages.fileCannotEmpty(), ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME.getCode()));
        }
        String title = DocumentCommandService.stripExtension(entryFileName);
        String mimeType = "application/octet-stream";
        // 与普通上传 zip（expandFiles→ensureCategoryPath）统一：按 zip 内目录路径映射子类目
        String dirPath = DocumentCommandService.getDirectoryPath(entryPath).orElse(null);
        return ensureCategoryPath(kbId, categoryId, dirPath, appMessages)
                .flatMap(targetCatId -> documentRepository.findByKbIdAndTitle(kbId, targetCatId, title)
                .flatMap(existing -> {
                    List<DocumentEntity> conflicts = filterSameExtension(existing, extension);
                    var draftDoc = conflicts.stream()
                            .filter(d -> DocumentEntity.STATUS_DRAFT.equals(d.getStatus()))
                            .findFirst();
                    ImportRequest entry = new ImportRequest(operatorId, kbId, targetCatId,
                            entryFileName, mimeType, title, extension, null, req.importMeta(), appMessages);
                    if (draftDoc.isPresent()) {
                        if (policy == DraftConflictPolicy.SKIP) {
                            return Uni.createFrom().item(FileUploadResult.skipped(entryFileName,
                                    appMessages.documentAlreadyExists(), ErrorCodeEnum.USER_ERROR_A0508.getCode()));
                        }
                        return overrideImportDraftBytes(entry, draftDoc.get(), entryBytes);
                    }
                    Uni<FileUploadResult> skip = conflictSkipResult(conflicts, entryFileName, appMessages);
                    if (skip != null) {
                        return skip;
                    }
                    return importNewFileBytes(entry, entryBytes);
                }))
                // 业务类异常（子类目映射超深度/重名等）转该条目 SKIPPED 并记因
                .onFailure(BusinessException.class).recoverWithItem(err -> {
                    logger.warn("zip entry rejected by business rule, skipped: entry={}, code={}, reason={}",
                            entryPath, err.getErrorCodeEnum().getCode(), err.getMessage());
                    return FileUploadResult.skipped(entryFileName, err.getMessage(),
                            err.getErrorCodeEnum().getCode());
                });
    }

    /** zip 条目新建文档（字节存储，store 先于 persist——与普通上传 createNewFile 一致；actualPath 入实体） */
    private Uni<FileUploadResult> importNewFileBytes(ImportRequest req, byte[] content) {
        String operatorId = req.operatorId();
        String kbId = req.kbId();
        String categoryId = req.categoryId();
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        ImportFileMetadata metadata = req.importMeta();
        String documentId = IdGenerator.doc();
        return storagePathResolver.resolveStoragePath(kbId, categoryId, documentId, fileName, extension)
                .flatMap(storagePath -> fileStorageService.store(storagePath, content)
                .flatMap(actualPath -> {
                    DocumentEntity entity = DocumentEntity.create(documentId, kbId, categoryId,
                            new DocumentFileSpec(title, actualPath, content.length, mimeType,
                                    DocumentCommandService.classify(fileName),
                                    metaAuthor(req), metaTagsJoined(req),
                                    metaRemark(req), metaVersion(req)),
                            operatorId);
                    return documentRepository.persist(entity)
                            .invoke(() -> logger.info("zip entry persisted: id={}, title={}, path={}", documentId, title, actualPath))
                            .flatMap(e -> replaceImportMetadata(e.getId().value(), metadata, e))
                            // 文件内容已落定：异步更新内容索引（不影响 zip 条目结果）
                            .invoke(e -> indexContent(documentId, actualPath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, (long) content.length,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                }));
    }

    /** zip 条目 DRAFT 同名覆盖（snapshot 旧 → store 新 → update(filePath=actualPath) → 守卫回收旧 → replaceAll） */
    private Uni<FileUploadResult> overrideImportDraftBytes(ImportRequest req, DocumentEntity draftDoc,
                                                           byte[] content) {
        String fileName = req.fileName();
        String mimeType = req.mimeType();
        String title = req.title();
        String extension = req.extension();
        ImportFileMetadata metadata = req.importMeta();
        String documentId = draftDoc.getId().value();
        String oldFilePath = draftDoc.getFilePath();
        logger.info("zip entry DRAFT overwritten: documentId={}, title={}", documentId, title);
        return storagePathResolver.resolveStoragePath(draftDoc.getKbId(), draftDoc.getCategoryId(),
                        documentId, fileName, extension)
                // 两阶段回收第一步：DB 更新前快照旧文件身份（同名重传覆盖防护）
                .flatMap(storagePath -> snapshotReplacedFile(oldFilePath)
                .flatMap(snapshots -> fileStorageService.store(storagePath, content)
                .flatMap(actualPath -> {
                    DocumentEntity updated = draftOverrideEntity(draftDoc, req, actualPath, (long) content.length);
                    return documentRepository.update(updated)
                            // DB 更新成功后：旧文件守卫回收（失败仅告警，不影响该条目落库结果）
                            .flatMap(e -> recycleReplacedFile(documentId, oldFilePath, actualPath, snapshots)
                                    .replaceWith(e))
                            .flatMap(e -> replaceImportMetadata(documentId, metadata, e))
                            // 文件内容已落定：异步更新内容索引（不影响 zip 条目结果）
                            .invoke(e -> indexContent(documentId, actualPath))
                            .map(e -> FileUploadResult.success(new FileUploadResult.SuccessPayload(
                                    documentId, title, (long) content.length,
                                    DocumentCommandService.deduceFileType(mimeType, extension),
                                    DocumentCommandService.classify(fileName), e.getStatus())));
                })));
    }

    // ======================== 参数对象与共享助手 ========================

    /**
     * 上传/导入公共请求上下文（参数对象）。
     * <p>multipart 上传链填充 {@code uploadMeta}（UploadDocumentCommand），
     * tus complete/zip 条目链填充 {@code importMeta}（ImportFileMetadata）；
     * 两组元数据同链互斥。title/extension/mimeType 由调用方按各自规则推导后填充。</p>
     */
    record ImportRequest(String operatorId, String kbId, String categoryId, String fileName,
                         String mimeType, String title, String extension,
                         UploadDocumentCommand uploadMeta, ImportFileMetadata importMeta,
                         AppMessages appMessages) {
    }

    /** multipart 上传入参（参数对象：操作者 + 目标域 + 文件列表 + 元数据）。公开：facade 构造后调 upload */
    public record UploadRequest(String operatorId, String kbId, String categoryId,
                                List<DocumentCommandService.FileInput> files,
                                UploadDocumentCommand metadata, String locale) {
    }

    /** 双源元数据作者（multipart uploadMeta 优先，tus importMeta 兜底，均空 = null） */
    private static String metaAuthor(ImportRequest r) {
        if (r.uploadMeta() != null) {
            return r.uploadMeta().author();
        }
        return r.importMeta() != null ? r.importMeta().author() : null;
    }

    /** 双源元数据标签（逗号连接；均空 = null） */
    private static String metaTagsJoined(ImportRequest r) {
        List<String> tags = null;
        if (r.uploadMeta() != null) {
            tags = r.uploadMeta().tags();
        } else if (r.importMeta() != null) {
            tags = r.importMeta().tags();
        }
        return (tags != null && !tags.isEmpty()) ? String.join(",", tags) : null;
    }

    /** 双源元数据备注（均空 = null） */
    private static String metaRemark(ImportRequest r) {
        if (r.uploadMeta() != null) {
            return r.uploadMeta().remark();
        }
        return r.importMeta() != null ? r.importMeta().remark() : null;
    }

    /** 双源元数据版本号（均空 = null；调用方保证非空白） */
    private static String metaVersion(ImportRequest r) {
        if (r.uploadMeta() != null) {
            return r.uploadMeta().version().trim();
        }
        return r.importMeta() != null ? r.importMeta().version().trim() : null;
    }

    /**
     * 目标状态冲突的统一跳过判定（普通上传/complete/zip 条目共用口径）：
     * REVIEWING → 审核中跳过；PUBLISHED → 已存在跳过；ARCHIVED → 已归档跳过；
     * 其余（含 DEPRECATED，不参与同名匹配）返回 null（可新建/覆盖）。
     */
    private Uni<FileUploadResult> conflictSkipResult(List<DocumentEntity> conflicts, String fileName,
                                                     AppMessages appMessages) {
        if (conflicts.stream().anyMatch(d -> DocumentEntity.STATUS_REVIEWING.equals(d.getStatus()))) {
            logger.warn("Document with same name is under review: fileName={}", fileName);
            return Uni.createFrom().item(FileUploadResult.skipped(fileName,
                    appMessages.documentUnderReview(),
                    ErrorCodeEnum.USER_ERROR_A0508.getCode()));
        }
        if (conflicts.stream().anyMatch(d -> DocumentEntity.STATUS_PUBLISHED.equals(d.getStatus()))) {
            logger.warn("Document with same name already published: fileName={}", fileName);
            return Uni.createFrom().item(FileUploadResult.skipped(fileName,
                    appMessages.documentAlreadyExists(),
                    ErrorCodeEnum.USER_ERROR_A0508.getCode()));
        }
        if (conflicts.stream().anyMatch(d -> DocumentEntity.STATUS_ARCHIVED.equals(d.getStatus()))) {
            logger.warn("Document with same name already archived: fileName={}", fileName);
            return Uni.createFrom().item(FileUploadResult.skipped(fileName,
                    appMessages.documentArchived(),
                    ErrorCodeEnum.USER_ERROR_A0508.getCode()));
        }
        return null;
    }

    /**
     * DRAFT 覆盖实体构建（multipart 覆盖 / complete 覆盖 / zip 条目覆盖三链共用；
     * 覆盖=新内容周期：createdAt/updatedAt 按最新上传计）。
     */
    private static DocumentEntity draftOverrideEntity(DocumentEntity draftDoc, ImportRequest req,
                                                      String filePath, long fileSize) {
        return DocumentEntity.builder()
                .id(draftDoc.getId())
                .kbId(draftDoc.getKbId())
                .categoryId(draftDoc.getCategoryId())
                .title(req.title())
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .processError(null)
                .currentVersion(metaVersion(req) != null ? metaVersion(req) : draftDoc.getCurrentVersion())
                .publishedVersion(draftDoc.getPublishedVersion())
                .hasPublishedVersion(draftDoc.getHasPublishedVersion())
                .filePath(filePath)
                .fileSize(fileSize)
                .mimeType(req.mimeType())
                .docType(DocumentCommandService.classify(req.fileName()))
                .author(metaAuthor(req))
                .tags(metaTagsJoined(req))
                .remark(metaRemark(req))
                .createdBy(draftDoc.getCreatedBy())
                .inheritParent(draftDoc.getInheritParent())
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    /** 从上传元数据构建 document_metadata 键值集合（空 = 不写）；委托 UploadDocumentCommand 复用单一逻辑源 */
    static java.util.Map<String, String> buildDocumentMetadata(UploadDocumentCommand metadata) {
        return metadata == null ? java.util.Map.of() : metadata.toDocumentMetadata();
    }

    /** 字节数格式化（日志/提示用）。包内共享：DVS 大小校验提示同源 */
    static String formatBytes(long bytes) {
        if (bytes >= 1073741824) {
            return String.format("%.1fGB", bytes / 1073741824.0);
        }
        if (bytes >= 1048576) {
            return String.format("%.1fMB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    /** 聚合结果 + 汇总统计 */
    private UploadDocumentResponse aggregateResults(List<FileUploadResult> results) {
        int total = results.size();
        int success = (int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
        int skipped = (int) results.stream().filter(r -> "SKIPPED".equals(r.getStatus())).count();
        int failed = (int) results.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
        return new UploadDocumentResponse(results, new UploadSummary(total, success, skipped, failed));
    }

    /**
     * 文件上传内部上下文
     *
     * @param dirPath      ZIP 条目目录路径（null = 普通文件）
     * @param zippedFailed ZIP 解压失败占位
     */
    private record FileUploadContext(String fileName, byte[] content, String mimeType, String baseCategoryId,
                                     String dirPath, boolean zippedFailed, String failReason, String failCode) {
        FileUploadContext(String fileName, byte[] content, String mimeType,
                          String baseCategoryId, String dirPath) {
            this(fileName, content, mimeType, baseCategoryId, dirPath, false, null, null);
        }
    }
}