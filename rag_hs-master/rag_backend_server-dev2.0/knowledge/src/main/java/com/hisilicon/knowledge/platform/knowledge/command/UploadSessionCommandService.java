package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DraftConflictPolicy;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ImportBatchRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.TusProtocolException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ArchiveFormats;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarArchiveInspector;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ZipArchiveInspector;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.ZipFileExtractor;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * tus 上传会话命令服务：create / head / patch（含 complete）/ delete。
 * <p>offset 以临时文件实际大小为准（FS 事实源，§12）：PATCH 校验 clientOffset == FileStorageService.size，
 * 不等 → 409；传完（offset==length）触发 complete 即落库（§4.6），落库失败回滚保留临时文件（§13）。
 * 元数据 create 时解析+校验（fail-closed），complete 直接消费，不重解析/重校验（§4.6）。</p>
 */
@ApplicationScoped
public class UploadSessionCommandService {

    private static final Logger logger = LoggerFactory.getLogger(UploadSessionCommandService.class);

    /** k8s 环境变量 HOSTNAME 即 pod 名：多副本分诊日志用（本机为 unknown-pod） */
    private static final String POD_NAME =
            System.getenv().getOrDefault("HOSTNAME", "unknown-pod");

    private final UploadSessionRepository sessionRepository;
    private final ImportBatchRepository batchRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final FileStorageService fileStorageService;
    private final DocumentCommandService documentCommandService;
    private final DocumentImportService documentImportService;
    private final DocumentVersionService documentVersionService;
    private final DictMetaKeyCacheService dictMetaKeyCacheService;
    private final DictValueValidator dictValueValidator;
    private final long tusMaxFileSize;
    /** 文档编辑/版本覆盖 tus 上传上限：与 multipart edit/versions 同源（kb.upload.max-file-size） */
    private final long docTusMaxFileSize;
    private final long batchMaxTotalSize;
    private final int expireHours;
    private final int batchMaxFiles;

    /**
     * 自注入：patch 非事务编排（读 session + FS append）与事务写方法（updateOffset/completeSingleFile/
     * markSessionTerminal）的桥接。直接 this.xxx() 会绕过 {@code @WithTransaction} 拦截器（CDI 代理不生效）。
     */
    @jakarta.inject.Inject
    UploadSessionCommandService self;

    /** executeBlocking：zip 后台流式解压在 worker 池跑（阻塞读 + 逐条目 await 落库） */
    @jakarta.inject.Inject
    io.vertx.mutiny.core.Vertx vertx;

    /** 批量 zip 解压条目数上限（独立于普通上传，普通上传仍受 kb.upload.zip-max-entries 约束） */
    @ConfigProperty(name = "kb.import.tus.zip-max-entries", defaultValue = "2000")
    int batchZipMaxEntries;

    /** 批量 zip 解压总量上限（流式逐条目，不受普通上传 List OOM 约束；默认 10G 放行 5GB zip） */
    @ConfigProperty(name = "kb.import.tus.zip-max-total-size", defaultValue = "10G")
    io.quarkus.runtime.configuration.MemorySize batchZipMaxTotalSize;

    /** 批量 zip 目录层级上限（complete 时 central directory 前置预检用；相对路径段数，含文件名段） */
    private final int batchZipMaxDepth;

    /**
     * RAR5 外部解压工具路径（7zz/7z）。native image 模式下 7-Zip-JBinding 的 JNI 不可用，
     * 须配置本项走外部进程；JVM 模式可留空走内嵌 JNI。Optional 语义：缺省 = 未配置。
     */
    @ConfigProperty(name = "kb.archive.external-tool")
    java.util.Optional<String> archiveExternalTool;

    public UploadSessionCommandService(UploadSessionRepository sessionRepository,
                                       ImportBatchRepository batchRepository,
                                       KnowledgeBaseRepository knowledgeBaseRepository,
                                       CategoryRepository categoryRepository,
                                       FileStorageService fileStorageService,
                                       DocumentCommandService documentCommandService,
                                       DocumentImportService documentImportService,
                                       DocumentVersionService documentVersionService,
                                       DictMetaKeyCacheService dictMetaKeyCacheService,
                                       DictValueValidator dictValueValidator,
                                       @ConfigProperty(name = "kb.import.tus.max-file-size", defaultValue = "1G")
                                       MemorySize tusMaxFileSize,
                                       @ConfigProperty(name = "kb.upload.max-file-size", defaultValue = "100M")
                                       MemorySize docTusMaxFileSize,
                                       @ConfigProperty(name = "kb.import.batch.max-total-size", defaultValue = "20G")
                                       MemorySize batchMaxTotalSize,
                                       @ConfigProperty(name = "kb.import.tus.expire-hours", defaultValue = "168")
                                       int expireHours,
                                       @ConfigProperty(name = "kb.import.batch.max-files", defaultValue = "10000")
                                       int batchMaxFiles,
                                       @ConfigProperty(name = "kb.import.tus.zip-max-depth", defaultValue = "10")
                                       int batchZipMaxDepth) {
        this.sessionRepository = sessionRepository;
        this.batchRepository = batchRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.fileStorageService = fileStorageService;
        this.documentCommandService = documentCommandService;
        this.documentImportService = documentImportService;
        this.documentVersionService = documentVersionService;
        this.dictMetaKeyCacheService = dictMetaKeyCacheService;
        this.dictValueValidator = dictValueValidator;
        this.tusMaxFileSize = tusMaxFileSize.asLongValue();
        this.docTusMaxFileSize = docTusMaxFileSize.asLongValue();
        this.batchMaxTotalSize = batchMaxTotalSize.asLongValue();
        this.expireHours = expireHours;
        this.batchMaxFiles = batchMaxFiles;
        this.batchZipMaxDepth = batchZipMaxDepth;
    }

    // ========== create ==========

    /**
     * 创建上传会话（§4.2），按用途分流。元数据解析+校验 fail-closed，失败不建会话：
     * <ul>
     *   <li>BATCH_IMPORT（缺省）：批次归属/配额校验 → {@link #createInBatch}；上限 kb.import.tus.max-file-size</li>
     *   <li>DOC_EDIT / VERSION_OVERRIDE：目标文档校验（状态/同类型/版本查重，见
     *       {@link DocumentCommandService#validateDocumentUploadTarget}）→ 会话不隶属批次；上限
     *       kb.upload.max-file-size（与 multipart edit/versions 同源）</li>
     * </ul>
     */
    @WithTransaction
    public Uni<UploadSession> create(String operatorId, CreateUploadSessionCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (command == null || command.fileName() == null || command.fileName().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        if (command.uploadLength() <= 0) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        UploadSessionPurpose purpose = resolvePurpose(command.purpose(), appMessages);
        long maxFileSize = purpose == UploadSessionPurpose.BATCH_IMPORT ? tusMaxFileSize : docTusMaxFileSize;
        if (command.uploadLength() > maxFileSize) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                    appMessages.fileSizeExceeded(formatBytes(maxFileSize)));
        }
        return switch (purpose) {
            case BATCH_IMPORT -> createForBatchImport(operatorId, command, appMessages);
            case DOC_EDIT, VERSION_OVERRIDE -> createForDocument(operatorId, command, purpose, appMessages);
        };
    }

    /** 解析用途字符串（fail-closed：未知显式值拒绝，不静默降级） */
    private static UploadSessionPurpose resolvePurpose(String purpose, AppMessages appMessages) {
        try {
            return UploadSessionPurpose.fromString(purpose);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
    }

    /**
     * BATCH_IMPORT 会话创建（原 create 主体，逻辑不变）：批次归属/未过期/配额 → KB/类目 →
     * 元数据 merge+校验 → 扩展名白名单（zip 特判放行）→ 落库。
     */
    private Uni<UploadSession> createForBatchImport(String operatorId, CreateUploadSessionCommand command,
                                                    AppMessages appMessages) {
        if (command.batchId() == null || command.batchId().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        // 取批次（校验属当前用户 + 未过期）→ 解析 kbId/categoryId/defaultMetadata/draftConflictPolicy
        return batchRepository.findById(ImportBatchId.of(command.batchId().trim()))
                .onItem().ifNull().failWith(() -> new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.invalidEntityTypeValue()))
                .flatMap(batch -> {
                    if (!operatorId.equals(batch.getCreatedBy())) {
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0301,
                                appMessages.unauthorizedOrTokenExpired()));
                    }
                    if (ImportBatchStatus.ABORTED == batch.getStatus()) {
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                                appMessages.invalidEntityTypeValue()));
                    }
                    // 批次触顶校验
                    if (batch.getTotalFiles() >= batchMaxFiles) {
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                                appMessages.fileSizeExceeded(String.valueOf(batchMaxFiles))));
                    }
                    if (batch.getTotalSize() + command.uploadLength() > batchMaxTotalSize) {
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0705,
                                appMessages.fileSizeExceeded(formatBytes(batchMaxTotalSize))));
                    }
                    return createInBatch(operatorId, command, batch, appMessages);
                });
    }

    /**
     * DOC_EDIT / VERSION_OVERRIDE 会话创建：目标文档必填 → zip 显式拒绝 → 扩展名白名单 →
     * 元数据解析校验（fail-closed，无批次默认可继承）→ 文档侧校验（状态/同类型/版本查重，
     * {@link DocumentCommandService#validateDocumentUploadTarget}）→ 落会话（batch_id=NULL）。
     */
    private Uni<UploadSession> createForDocument(String operatorId, CreateUploadSessionCommand command,
                                                 UploadSessionPurpose purpose, AppMessages appMessages) {
        if (command.targetDocumentId() == null || command.targetDocumentId().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        String fileName = DocumentStoragePathResolver.sanitizeFileName(command.fileName());
        // 压缩包显式拒绝（doc 用途不允许 zip/rar——文案清晰；扩展名白名单本就不含二者，双保险）
        if (ArchiveFormats.isZip(fileName)) {
            throw new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported("zip"));
        }
        if (ArchiveFormats.isRar(fileName)) {
            throw new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported("rar"));
        }
        // 扩展名白名单（同普通上传 ALLOWED_EXTENSIONS；无 zip 特判——仅批量导入放行 zip）
        String extension = DocumentCommandService.extractExtension(fileName);
        if (!DocumentCommandService.isExtensionAllowed(fileName, extension)) {
            throw new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported(extension));
        }
        String fileTitle = DocumentCommandService.stripExtension(command.fileName());
        return resolveDocumentMetadata(command, purpose, appMessages)
                .flatMap(metadata -> documentVersionService.validateDocumentUploadTarget(
                        command.targetDocumentId().trim(), fileName, purpose, metadata, appMessages)
                        .flatMap(doc -> {
                            UploadSession session = UploadSession.createForDocument(new UploadSession.Seed(
                                    purpose, null, doc.getId().value(),
                                    doc.getKbId(), doc.getCategoryId(),
                                    fileName, fileTitle, command.mimeType(),
                                    command.uploadLength(), null, metadata, operatorId,
                                    LocalDateTime.now().plusHours(expireHours)));
                            return sessionRepository.persist(session);
                        }));
    }

    /**
     * doc 用途元数据校验（fail-closed）：格式 → 字典 key 白名单 → 字典值；VERSION_OVERRIDE 版本号必填。
     * metadata 为 null（tus metadata 键缺省）= 仅换文件不改属性（DOC_EDIT 合法；VERSION_OVERRIDE 因版本号
     * 必填而在下方拒绝）。
     */
    private Uni<ImportFileMetadata> resolveDocumentMetadata(CreateUploadSessionCommand command,
                                                            UploadSessionPurpose purpose,
                                                            AppMessages appMessages) {
        ImportFileMetadata metadata = command.perFileMetadata();
        Map<String, String> docMeta = metadata != null && metadata.documentMetadata() != null
                ? metadata.documentMetadata() : Map.of();
        MetadataCommandService.validateFormat(docMeta, appMessages);
        if (purpose == UploadSessionPurpose.VERSION_OVERRIDE
                && (metadata == null || metadata.version() == null || metadata.version().isBlank())) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.versionRequired());
        }
        return validateMetaKeys(docMeta, appMessages)
                .flatMap(v -> dictValueValidator.validateOrThrow(docMeta, invalid -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value()))))
                .replaceWith(metadata);
    }

    private Uni<UploadSession> createInBatch(String operatorId, CreateUploadSessionCommand command,
                                            ImportBatch batch, AppMessages appMessages) {
        // 校验 kb 存在且活跃
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(batch.getKbId()))
                .onItem().ifNull().failWith(() -> new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        return Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    String categoryId = batch.getCategoryId();
                    return validateCategory(categoryId, kb, appMessages)
                            .flatMap(v -> resolveAndValidateMetadata(command, batch, appMessages))
                            .flatMap(resolved -> {
                                DraftConflictPolicy policy = DraftConflictPolicy.fromString(
                                        command.draftPolicy(), batch.getDraftConflictPolicy());
                                String fileName = DocumentStoragePathResolver.sanitizeFileName(command.fileName());
                                // 扩展名白名单（与普通上传 ALLOWED_EXTENSIONS 一致）：
                                // .zip/.rar 特判放行（complete 时解压成 N 文档）；其余扩展名不在白名单 → 422
                                String extension = DocumentCommandService.extractExtension(fileName);
                                if (!ArchiveFormats.isArchive(fileName)
                                        && !DocumentCommandService.isExtensionAllowed(fileName, extension)) {
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                                            appMessages.fileTypeNotSupported(extension)));
                                }
                                String fileTitle = DocumentCommandService.stripExtension(command.fileName());
                                UploadSession session = UploadSession.create(new UploadSession.Seed(
                                        UploadSessionPurpose.BATCH_IMPORT, batch.getId().value(), null,
                                        batch.getKbId(), categoryId,
                                        fileName, fileTitle, command.mimeType(),
                                        command.uploadLength(), policy, resolved, operatorId,
                                        LocalDateTime.now().plusHours(expireHours)));
                                return sessionRepository.persist(session)
                                        .flatMap(s -> batchRepository.update(batch.addFile(command.uploadLength()))
                                                .replaceWith(s));
                            });
                });
    }

    // ========== head ==========

    /** 查断点（§4.3）：offset = 临时文件实际大小（FS 事实源）；仅会话创建人可查 */
    @WithSession
    public Uni<UploadSession> head(String operatorId, String sessionId, String locale) {
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .onItem().ifNull().failWith(() ->
                        new TusProtocolException(404, TusProtocolException.Reason.SESSION_NOT_FOUND))
                .flatMap(session -> requireOwned(session, operatorId,
                        LocaleUtil.getAppMessage(locale)))
                .flatMap(session -> {
                    if (session.getStatus() == UploadSessionStatus.ABORTED) {
                        return Uni.createFrom().failure(new TusProtocolException(
                                410, TusProtocolException.Reason.SESSION_ABORTED));
                    }
                    if (session.getStatus() == UploadSessionStatus.EXPIRED) {
                        return Uni.createFrom().failure(new TusProtocolException(
                                410, TusProtocolException.Reason.SESSION_EXPIRED));
                    }
                    return Uni.createFrom().item(session);
                });
    }

    // ========== patch ==========

    /**
     * 上传分片（§4.4）。clientOffset != FS.size → 409；传完触发 complete（同响应）。
     * <p>非事务编排（读 session + FS append）：{@code findById} 读需 Mutiny.Session，本方法无
     * {@code @WithTransaction}，且 PATCH 经自定义 {@code @HttpMethod("PATCH")}（JAX-RS 无标准 @PATCH）
     * 不享 JAX-RS 自动 session，故显式 {@code @WithSession} 开只读 session——否则抛
     * "No current Mutiny.Session found"。写仍走 self.* 的 {@code @WithTransaction}（独立 tx 提交，
     * 复用本 session，不受 @WithSession 影响）。</p>
     * @return 新 offset（响应 Upload-Offset）
     */
    @WithSession
    public Uni<Long> patch(String operatorId, String sessionId, long clientOffset, byte[] chunk, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .onItem().ifNull().failWith(() ->
                        new TusProtocolException(404, TusProtocolException.Reason.SESSION_NOT_FOUND))
                .flatMap(session -> requireOwned(session, operatorId, appMessages))
                .flatMap(session -> rejectPatchState(session))
                .flatMap(session -> {
                    // 幂等短路：已终态/空分片 → 返当前 offset（不写不触发 complete）
                    Long early = patchEarlyOffset(session, chunk);
                    if (early != null) {
                        return Uni.createFrom().item(early);
                    }
                    return appendChunkWithArbitration(session, operatorId, chunk, clientOffset, appMessages);
                });
    }

    /** patch 状态守卫：ABORTED/EXPIRED→410；FAILED→409(带原因)；PROCESSING→409（防二次拉起解压） */
    private Uni<UploadSession> rejectPatchState(UploadSession session) {
        if (session.getStatus() == UploadSessionStatus.ABORTED) {
            return Uni.createFrom().failure(new TusProtocolException(
                    410, TusProtocolException.Reason.SESSION_ABORTED));
        }
        if (session.getStatus() == UploadSessionStatus.EXPIRED) {
            return Uni.createFrom().failure(new TusProtocolException(
                    410, TusProtocolException.Reason.SESSION_EXPIRED));
        }
        if (session.getStatus() == UploadSessionStatus.FAILED) {
            String reason = session.getError() != null ? session.getError() : "详见批次进度";
            return Uni.createFrom().failure(new TusProtocolException(
                    409, TusProtocolException.Reason.SESSION_FAILED, List.of(reason)));
        }
        if (session.getStatus() == UploadSessionStatus.PROCESSING) {
            return Uni.createFrom().failure(new TusProtocolException(
                    409, TusProtocolException.Reason.SESSION_PROCESSING));
        }
        return Uni.createFrom().item(session);
    }

    /**
     * patch 幂等短路（null=继续写）：已 COMPLETED/SKIPPED → 返当前 offset（不重落）；
     * 空分片 → 返当前 offset（无字节可追加，幂等成功）。
     */
    private Long patchEarlyOffset(UploadSession session, byte[] chunk) {
        if (session.getStatus() == UploadSessionStatus.COMPLETED
                || session.getStatus() == UploadSessionStatus.SKIPPED) {
            return session.getUploadOffset();
        }
        if (chunk == null || chunk.length == 0) {
            return session.getUploadOffset();
        }
        return null;
    }

    /**
     * 分片落盘 + 双源仲裁 + 完包触发 complete / 未完推进 offset。
     * <p>双源仲裁自愈：写序"先落盘→后提交 offset"保证稳态 dbOffset ≤ 文件真实长度，故
     * clientOffset==dbOffset 且大于当前读到的 size，只可能是共享存储（NFS/SFS）另一副本刚追加、
     * 本 pod 属性缓存滞后的陈旧视图——视为合法续传点按 clientOffset 落盘（同位置覆盖写顺带刷新元数据）。</p>
     */
    private Uni<Long> appendChunkWithArbitration(UploadSession session, String operatorId, byte[] chunk,
                                                 long clientOffset, AppMessages appMessages) {
        String sessionId = session.getId().value();
        String tempPath = session.getTempPath();
        long dbOffset = session.getUploadOffset();
        return fileStorageService.size(tempPath)
                .flatMap(currentSize -> {
                    if (clientOffset != currentSize) {
                        if (clientOffset == dbOffset && dbOffset > currentSize) {
                            logger.warn("TUS_ATTRCACHE_HEAL: sessionId={}, resumeAt={}, staleSize={}, pod={}",
                                    sessionId, clientOffset, currentSize, POD_NAME);
                        } else {
                            logger.warn("TUS_OFFSET_CONFLICT: sessionId={}, requested={}, fsSize={}, "
                                            + "dbOffset={}, status={}, diffBytes={}, pod={}",
                                    sessionId, clientOffset, currentSize, dbOffset,
                                    session.getStatus(), clientOffset - currentSize, POD_NAME);
                            return Uni.createFrom().failure(
                                    TusProtocolException.offsetConflict(currentSize, clientOffset));
                        }
                    }
                    return fileStorageService.appendChunk(tempPath, clientOffset, chunk)
                            .flatMap(newSize -> {
                                UploadSession advanced = session.appendChunkLen(chunk.length);
                                if (advanced.isComplete()) {
                                    return complete(operatorId, advanced, appMessages)
                                            .replaceWith(newSize);
                                }
                                return self.updateOffsetSession(advanced).replaceWith(newSize);
                            });
                });
    }

    // ========== complete ==========

    /**
     * complete 落库编排（§4.6，非事务——读 size + 分流，写走独立 tx via self）：
     * 先校验 FS 实际大小 == uploadLength，再按用途分流：
     * <ul>
     *   <li>BATCH_IMPORT：zip→后台解压 / 单文件→importCompletedFile（{@link #completeBatchImport}）；</li>
     *   <li>DOC_EDIT：DRAFT 同类型文件替换 + 可选属性编辑（{@link #completeDocEdit}）；</li>
     *   <li>VERSION_OVERRIDE：PUBLISHED 新版本覆盖 + 旧版归档（{@link #completeVersionOverride}）。</li>
     * </ul>
     * doc 用途的永久性业务拒绝（上传期间文档状态/类型变更、版本冲突、文档已删）→ 临时文件移 trash +
     * 会话 FAILED + 协议化 409（客户端停止重试）；瞬时故障事务回滚、会话留 UPLOADING@满 offset 可重试。
     */
    private Uni<Void> complete(String operatorId, UploadSession session, AppMessages appMessages) {
        return fileStorageService.size(session.getTempPath())
                .flatMap(actualSize -> {
                    if (actualSize != session.getUploadLength()) {
                        return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                                appMessages.fileSizeExceeded(formatBytes(session.getUploadLength()))));
                    }
                    return switch (session.getPurpose()) {
                        case BATCH_IMPORT -> completeBatchImport(operatorId, session, appMessages);
                        case DOC_EDIT -> self.completeDocEdit(operatorId, session, appMessages)
                                .onFailure(BusinessException.class).recoverWithUni(err ->
                                        failDocumentSession(session, err instanceof BusinessException be ? be : null));
                        case VERSION_OVERRIDE -> self.completeVersionOverride(operatorId, session, appMessages)
                                .onFailure(BusinessException.class).recoverWithUni(err ->
                                        failDocumentSession(session, err instanceof BusinessException be ? be : null));
                    };
                });
    }

    /**
     * doc 用途永久性业务拒绝处置（仿 {@link #failZipPrecheck}）：临时文件移 trash（幂等）→
     * 会话终态 FAILED（错误原因可见）→ 协议化 409 + 具体业务码/文案 + Upload-Offset=全长纠偏头。
     */
    private Uni<Void> failDocumentSession(UploadSession session, BusinessException be) {
        logger.warn("tus document purpose complete rejected by business rule (session set to FAILED): sessionId={}, purpose={}, code={}, reason={}",
                session.getId().value(), session.getPurpose(), be.getErrorCodeEnum().getCode(), be.getMessage());
        return fileStorageService.moveToTrash(session.getTempPath())
                .onFailure().recoverWithNull()
                .flatMap(x -> self.markSessionTerminal(session.getId().value(), FileUploadResult.fail(
                        session.getFileName(), be.getMessage(), be.getErrorCodeEnum().getCode())))
                .flatMap(x -> Uni.createFrom().failure(TusProtocolException.completeFailed(
                        be.getMessage(), be.getErrorCodeEnum(), session.getUploadLength())));
    }

    /**
     * DOC_EDIT complete 落库（独立 tx via self）：文件替换/属性编辑 + 会话终态更新，原子。
     * 永久性拒绝（BusinessException，如文档已转 REVIEWING/类型不一致/版本冲突）由编排方
     * {@link #complete} 捕获转 {@link #failDocumentSession}；瞬时异常回滚保留临时文件可重试。
     */
    @WithTransaction
    public Uni<Void> completeDocEdit(String operatorId, UploadSession session, AppMessages appMessages) {
        return documentVersionService.editWithFileFromTemp(new DocumentVersionService.TusEditRequest(
                        operatorId, session.getTargetDocumentId(), session.getFileName(),
                        session.getMimeType(), session.getMetadata(), session.getTempPath(),
                        session.getUploadLength(), appMessages))
                .flatMap(result -> sessionRepository
                        .update(session.markComplete(result.getDocumentId()))
                        .replaceWithVoid());
    }

    /**
     * VERSION_OVERRIDE complete 落库（独立 tx via self）：旧版归档 + 文档更新 + 会话终态更新，原子。
     * 失败语义同 {@link #completeDocEdit}。
     */
    @WithTransaction
    public Uni<Void> completeVersionOverride(String operatorId, UploadSession session, AppMessages appMessages) {
        return documentVersionService.overrideVersionFromTemp(new DocumentVersionService.TusOverrideRequest(
                        operatorId, session.getTargetDocumentId(), session.getFileName(),
                        session.getMimeType(), session.getMetadata(), session.getTempPath(),
                        session.getUploadLength(), appMessages))
                .flatMap(result -> sessionRepository
                        .update(session.markComplete(result.getDocumentId()))
                        .replaceWithVoid());
    }

    /**
     * BATCH_IMPORT complete 落库（原 complete 主体）：单文件→原子落库（completeSingleFile，PATCH 等到
     * 落库完）；zip→markProcessing 提交后 fire-and-forget 后台流式解压（runZipExtraction），PATCH 立即返 204
     * （避免千条目同步落库致 HTTP 超时）。zip 分支仅限本用途（doc 用途在 create 已拒绝 zip）。
     */
    private Uni<Void> completeBatchImport(String operatorId, UploadSession session, AppMessages appMessages) {
        // 压缩包：先前置预检（毫秒级元数据扫描，不写解压临时文件）——
        // 条目数/总量/层级/单文件大小（rar 另含分卷/加密）超限 → 清理临时文件 + 会话 FAILED + 同步报错
        // （协议化 409 + A0705 + Upload-Offset 头，最后一片的响应即报错原因，无需轮询；
        // 不用 200 envelope：tus 客户端把非 204 视为可重试错误静默循环，会吞掉根因）；
        // 通过 → 转 PROCESSING 提交后，后台异步流式解压（PATCH 立即返 204）
        boolean isZip = ArchiveFormats.isZip(session.getFileName());
        if (isZip || ArchiveFormats.isRar(session.getFileName())) {
            return fileStorageService.openReadChannel(session.getTempPath())
                    .flatMap(channel -> vertx.executeBlocking(() -> isZip
                            ? inspectZipAndClose(channel, batchZipMaxEntries,
                                    batchZipMaxTotalSize.asLongValue(), batchZipMaxDepth)
                            : inspectRarAndClose(channel, batchZipMaxEntries,
                                    batchZipMaxTotalSize.asLongValue(), batchZipMaxDepth)))
                    .flatMap(violation -> violation != null
                            ? failZipPrecheck(session, violation, appMessages)
                            : self.markProcessing(session.getId().value())
                                    .invoke(() -> runZipExtractionInBackground(session, appMessages))
                                    .replaceWithVoid());
        }
        // 单文件：1 会话→1 文档，原子 tx（PATCH 等到落库完）
        return self.completeSingleFile(operatorId, session, appMessages).replaceWithVoid();
    }

    /** zip 预检：channel 由 inspect 内负责关闭 */
    private static ZipArchiveInspector.Violation inspectZipAndClose(
            java.nio.channels.SeekableByteChannel channel, int maxEntries, long maxTotalBytes, int maxDepth) {
        return ZipArchiveInspector.inspect(channel, maxEntries, maxTotalBytes, maxDepth)
                .orElse(null);
    }

    /** rar 预检：channel → 流（junrar 不吃 nio channel），流由 inspect 内负责关闭 */
    private static ZipArchiveInspector.Violation inspectRarAndClose(
            java.nio.channels.SeekableByteChannel channel, int maxEntries, long maxTotalBytes, int maxDepth) {
        return RarArchiveInspector.inspect(java.nio.channels.Channels.newInputStream(channel),
                maxEntries, maxTotalBytes, maxDepth)
                .orElse(null);
    }

    /**
     * 预检违规处置：临时文件移 trash（幂等）→ 会话终态 FAILED（批次进度可见）
     * → 同步协议化失败（{@link TusProtocolException} COMPLETE_FAILED：
     * HTTP 409 + 业务码 A0705 + 具体超限文案 + Upload-Offset=全长纠偏头）。
     * 非 2xx 让 tus 客户端立即终止重试并把原因直达 UI。
     */
    private Uni<Void> failZipPrecheck(UploadSession session, ZipArchiveInspector.Violation violation,
                                      AppMessages appMessages) {
        String reason = switch (violation.type()) {
            case ENTRY_COUNT_EXCEEDED -> appMessages.zipEntryCountExceeded((int) violation.maxValue());
            case TOTAL_SIZE_EXCEEDED -> appMessages.zipTotalSizeExceeded(formatBytes(violation.maxValue()));
            case SINGLE_ENTRY_TOO_LARGE -> appMessages.zipSingleEntryTooLarge(formatBytes(violation.maxValue()));
            case DEPTH_EXCEEDED -> appMessages.zipEntryDepthExceeded((int) violation.maxValue());
            case ENCRYPTED_ARCHIVE -> appMessages.rarEncrypted();
            case MULTI_VOLUME -> appMessages.rarMultiVolume();
        };
        logger.warn("zip pre-check failed (blocked before extraction): sessionId={}, file={}, {}:{}",
                session.getId().value(), session.getFileName(), violation.type(), reason);
        return fileStorageService.moveToTrash(session.getTempPath())
                .onFailure().recoverWithNull()
                .flatMap(x -> self.markSessionTerminal(session.getId().value(), FileUploadResult.fail(
                        session.getFileName(), reason, ErrorCodeEnum.USER_ERROR_A0705.getCode())))
                .flatMap(x -> Uni.createFrom().failure(TusProtocolException.completeFailed(
                        reason, ErrorCodeEnum.USER_ERROR_A0705, session.getUploadLength())));
    }

    /** 会话归属校验：仅创建人可 head/patch/delete 本人会话（越权 A0301 经 mapper 出 422） */
    private static Uni<UploadSession> requireOwned(UploadSession session, String operatorId,
                                                   AppMessages appMessages) {
        if (operatorId == null || !operatorId.equals(session.getCreatedBy())) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0301, appMessages.unauthorizedOrTokenExpired()));
        }
        return Uni.createFrom().item(session);
    }

    /** zip 会话转 PROCESSING（独立 tx via self），后台解压前置；幂等（已 PROCESSING 不重复触发） */
    @WithTransaction
    public Uni<Void> markProcessing(String sessionId) {
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .flatMap(session -> {
                    if (session == null || UploadSessionStatus.PROCESSING.equals(session.getStatus())
                            || session.getStatus().isTerminal()) {
                        return Uni.createFrom().voidItem();
                    }
                    return sessionRepository.update(session.markProcessing()).replaceWithVoid();
                });
    }

    /**
     * 后台流式解压 zip（fire-and-forget）：worker 线程逐条目读出 → persistZipEntryAndBump（独立 tx，
     * doc 落库 + 进度推进原子）→ 终态 markSessionTerminal。异常兜底标 FAILED。
     * <p>一个 executeBlocking 持有 worker 至解压完成（分钟级）；并发大包 worker 池压力列为未来优化。</p>
     */
    private void runZipExtractionInBackground(UploadSession session, AppMessages appMessages) {
        String sessionId = session.getId().value();
        runZipExtraction(session, appMessages)
                .subscribe().with(
                        ok -> logger.info("zip background extraction completed: sessionId={}", sessionId),
                        err -> {
                            logger.error("zip background extraction error: sessionId={}", sessionId, err);
                            self.markSessionTerminal(sessionId, FileUploadResult.fail(
                                            session.getFileName(), "解压异常: " + err.getMessage(),
                                            ErrorCodeEnum.ERROR_READ_FILE.getCode()))
                                    .subscribe().with(a -> {}, e -> logger.error("Marking FAILED also failed: {}", sessionId, e));
                        });
    }

    /** zip 条目解压临时目录（相对存储根），解压后移 trash 由清理任务回收 */
    private static final String ZIP_EXTRACT_DIR = ".zip-extract";

    /** 单个 zip 条目解压元数据：entryPath=zip 内相对路径（用于子分类），tempRelativePath=临时文件落盘路径 */
    private record ZipEntryInfo(String entryPath, String tempRelativePath) {}

    /** zip 解压阶段1 结果：成功时 entries 非空、fatal=null；致命失败时 entries=空、fatal=fail */
    private record ZipExtractResult(List<ZipEntryInfo> entries, FileUploadResult fatal) {
        boolean isFatal() { return fatal != null; }
    }

    /**
     * 流式解压 zip 落库（两阶段，规避 hibernate-reactive HR000068：DB 操作必须在 Vert.x event loop）：
     * <p>阶段1（worker executeBlocking）：同步解压所有条目到临时文件 .zip-extract/{sessionId}/{idx}.bin
     * （storeBlocking 同步写，worker 安全），收集元数据——不碰 DB（worker 线程不能开 reactive session）。
     * <p>阶段2（event loop reactive 链）：逐条 read 临时文件 → persistZipEntryAndBump（@WithTransaction，
     * event loop DB）→ moveToTrash 临时文件；条目级失败不中断（onFailure 记 tally + recoverWithNull）。
     * 完成后聚合 agg → moveToTrash zip + markSessionTerminal。
     */
    private Uni<Void> runZipExtraction(UploadSession session, AppMessages appMessages) {
        String operatorId = session.getCreatedBy();
        String sessionId = session.getId().value();
        String kbId = session.getKbId();
        String categoryId = session.getCategoryId();
        ImportFileMetadata metadata = session.getMetadata();
        DraftConflictPolicy policy = session.getDraftPolicy();
        int maxEntries = batchZipMaxEntries;
        long maxTotalBytes = batchZipMaxTotalSize.asLongValue();
        String zipExtractDir = ZIP_EXTRACT_DIR + "/" + sessionId;

        // 阶段1：worker 解压写临时文件，收集元数据（不碰 DB）
        // 条目名编码自适应（ZipFileExtractor.decodeEntryName）：EFS→UTF-8；非 EFS→UTF-8 优先 GBK fallback，
        // 一次解压兼容 mac（UTF-8 无 EFS）与 Windows（GBK 无 EFS）zip。
        return vertx.<ZipExtractResult>executeBlocking(() -> {
            List<ZipEntryInfo> entries = new ArrayList<>();
            int[] idx = {0};
            ZipFileExtractor.EntryConsumer consumer = (entryPath, content) -> {
                // 同步写临时文件（序号命名，避免 zip 内路径遍历/重名）；entryPath 原样保留用于子分类
                String tempRel = zipExtractDir + "/" + (idx[0]++) + ".bin";
                String actualTemp = fileStorageService.storeBlocking(tempRel, content);
                entries.add(new ZipEntryInfo(entryPath, actualTemp));
            };
            try {
                boolean zip = ArchiveFormats.isZip(session.getFileName());
                try (java.io.InputStream is = fileStorageService.openInputStreamBlocking(session.getTempPath())) {
                    if (zip) {
                        // 条目名编码自适应（ZipFileExtractor.decodeEntryName）：EFS→UTF-8；非 EFS→UTF-8 优先 GBK fallback，
                        // 一次解压兼容 mac（UTF-8 无 EFS）与 Windows（GBK 无 EFS）zip。
                        ZipFileExtractor.extract(is, maxEntries, maxTotalBytes, consumer);
                    } else {
                        RarFileExtractor.extract(is, maxEntries, maxTotalBytes, consumer,
                                archiveExternalTool.orElse(null));
                    }
                }
                return new ZipExtractResult(entries, null);
            } catch (ZipFileExtractor.NotAZipFileException e) {
                return new ZipExtractResult(List.of(), FileUploadResult.fail(session.getFileName(),
                        appMessages.zipNotValid(), ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode()));
            } catch (RarFileExtractor.NotRarFileException e) {
                return new ZipExtractResult(List.of(), FileUploadResult.fail(session.getFileName(),
                        appMessages.rarNotValid(), ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode()));
            } catch (ZipFileExtractor.ZipLimitExceededException e) {
                String reason = e.entryCountExceeded
                        ? appMessages.zipEntryCountExceeded((int) e.maxValue)
                        : appMessages.zipTotalSizeExceeded(formatBytes(e.maxValue));
                logger.warn("zip extraction exceeds limit (whole batch FAILED): sessionId={}, reason={}", sessionId, reason);
                return new ZipExtractResult(List.of(), FileUploadResult.fail(session.getFileName(),
                        reason, ErrorCodeEnum.ERROR_READ_FILE.getCode()));
            } catch (RuntimeException | java.io.IOException e) {
                logger.error("zip/rar extraction or temp file write failed: sessionId={}", sessionId, e);
                // 分卷/加密等语义失败透出具体原因（预检降级为 empty 后，在此兜底拦截）
                String msg = e.getMessage();
                boolean knownSemantic = msg != null && (msg.contains("加密") || msg.contains("分卷"));
                return new ZipExtractResult(List.of(), FileUploadResult.fail(session.getFileName(),
                        knownSemantic ? msg : appMessages.zipExtractFailed(),
                        ErrorCodeEnum.ERROR_READ_FILE.getCode()));
            }
        }).flatMap(extractResult -> {
            // 致命失败：直接清理 zip + 标终态
            if (extractResult.isFatal()) {
                return fileStorageService.moveToTrash(session.getTempPath())
                        .onFailure().recoverWithNull()
                        .flatMap(v -> self.markSessionTerminal(sessionId, extractResult.fatal()).replaceWithVoid());
            }
            // 阶段2：event loop 逐条 read 临时文件 + persistZipEntryAndBump（@WithTransaction DB）+ 删临时文件。
            // 分桶统计：业务性跳过（不支持类型/重名/空文件）不再拖垮整包状态，只有系统性失败才判 FAILED
            ZipAggregateStats stats = new ZipAggregateStats();
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (ZipEntryInfo e : extractResult.entries()) {
                chain = chain.flatMap(v -> fileStorageService.read(e.tempRelativePath())
                        .flatMap(content -> self.persistZipEntryAndBump(new ZipEntryPersist(
                                sessionId, operatorId, kbId, categoryId,
                                e.entryPath(), content, metadata, policy, appMessages)))
                        .onItem().invoke(stats::record)
                        .onFailure().invoke(err -> {
                            stats.recordSystemic(err.getMessage());
                            logger.error("zip entry persistence error: sessionId={}, entry={}, error={}",
                                    sessionId, e.entryPath(), err.getMessage(), err);
                        })
                        .onFailure().recoverWithNull()
                        .flatMap(r -> fileStorageService.moveToTrash(e.tempRelativePath())
                                .onFailure().recoverWithNull())
                        .replaceWithVoid());
            }
            return chain.flatMap(v -> fileStorageService.moveToTrash(session.getTempPath())
                    .onFailure().recoverWithNull()
                    .flatMap(vv -> self.markZipTerminal(sessionId, stats, appMessages)));
        });
    }

    /** zip 条目结果分桶（事件循环串行推进，无需线程安全）。桶识别依据条目结果的业务码。 */
    static final class ZipAggregateStats {
        static final String CODE_UNSUPPORTED = ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode();
        static final String CODE_DUPLICATE = ErrorCodeEnum.USER_ERROR_A0508.getCode();
        static final String CODE_EMPTY = ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME.getCode();

        int success;
        int unsupported;
        int duplicate;
        int empty;
        int systemic;
        String firstSystemic;

        /** 条目总数 = 各桶之和（派生值，避免双计） */
        int total() {
            return success + unsupported + duplicate + empty + systemic;
        }

        void record(FileUploadResult r) {
            if (r == null) {
                return;
            }
            switch (r.getStatus()) {
                case "SUCCESS" -> success++;
                case "SKIPPED" -> {
                    String code = r.getCode();
                    if (CODE_UNSUPPORTED.equals(code)) unsupported++;
                    else if (CODE_DUPLICATE.equals(code)) duplicate++;
                    else if (CODE_EMPTY.equals(code)) empty++;
                    else recordSystemic(r.getReason()); // 未知跳过码按系统性处理（防御）
                }
                default -> recordSystemic(r.getReason());
            }
        }

        void recordSystemic(String reason) {
            systemic++;
            if (firstSystemic == null) firstSystemic = reason;
        }

        int skippedTotal() {
            return unsupported + duplicate + empty;
        }
    }

    /**
     * zip 编排终态（独立 tx via self）：分级判死——仅系统性失败才 FAILED；
     * 业务性跳过保持 COMPLETED，分桶摘要写入 error 列（批次进度 resultSummaries 透出），
     * 纯全成功时摘要置 null 清列。改用本方法后 markSessionTerminal 仅服务 fatal 分支与单文件路径。
     */
    @WithTransaction
    public Uni<Void> markZipTerminal(String sessionId, ZipAggregateStats stats, AppMessages appMessages) {
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .flatMap(session -> {
                    if (session == null) {
                        return Uni.createFrom().voidItem();
                    }
                    if (stats.systemic > 0) {
                        String reason = appMessages.zipOutcomeSummary(stats.total(), stats.success,
                                stats.unsupported, stats.duplicate, stats.empty)
                                + "；系统性失败 " + stats.systemic
                                + (stats.firstSystemic != null ? "：" + stats.firstSystemic : "");
                        logger.warn("zip import contains systemic failures (whole batch FAILED): sessionId={}, {}", sessionId, reason);
                        return sessionRepository.update(session.markFailed(reason)).replaceWithVoid();
                    }
                    String summary = stats.skippedTotal() > 0
                            ? appMessages.zipOutcomeSummary(stats.total(), stats.success,
                                    stats.unsupported, stats.duplicate, stats.empty)
                            : null;
                    if (summary != null) {
                        logger.info("zip import completed (partially skipped): sessionId={}, {}", sessionId, summary);
                    }
                    return sessionRepository
                            .update(session.markComplete(null, stats.skippedTotal(), summary))
                            .replaceWithVoid();
                });
    }

    /**
     * 单 zip 条目落库 + 进度推进（独立 tx via self）：{@link DocumentCommandService#persistZipEntry}
     * 共一事务——doc 落库 + entries_done+1 原子；单条目失败回滚自身，不影响其余。
     * 进度推进（bumpEntriesDone）失败不阻断解压（recoverWithItem(0)）。
     * <p>开头锁 KB 行（findByIdForUpdate，与删除事务互斥）：删除提交后本条目校验必然看到
     * 目录已删而失败；并发多 zip 同 KB 时条目落库全局串行化，顺带消除同名子分类/同名文档
     * 的 TOCTOU 重复创建。持锁仅本条目事务时长（毫秒级），解压重活不受影响。</p>
     */
    @WithTransaction
    public Uni<FileUploadResult> persistZipEntryAndBump(ZipEntryPersist cmd) {
        return knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of(cmd.kbId()))
                .flatMap(kb -> documentImportService.persistZipEntry(new DocumentImportService.ImportRequest(
                                cmd.operatorId(), cmd.kbId(), cmd.categoryId(), null, null, null, null,
                                null, cmd.metadata(), cmd.appMessages()),
                        cmd.entryPath(), cmd.content(), cmd.policy()))
                .flatMap(r -> sessionRepository.bumpEntriesDone(cmd.sessionId())
                        .onFailure().recoverWithItem(0)
                        .replaceWith(r));
    }

    /** 单 zip 条目落库入参（参数对象：编排循环 → 条目事务方法） */
    record ZipEntryPersist(String sessionId, String operatorId, String kbId, String categoryId,
                           String entryPath, byte[] content, ImportFileMetadata metadata,
                           DraftConflictPolicy policy, AppMessages appMessages) {
    }

    /** 非终态 offset 写库（独立 tx via self） */
    @WithTransaction
    public Uni<Void> updateOffsetSession(UploadSession advanced) {
        return sessionRepository.update(advanced).replaceWithVoid();
    }

    /**
     * 单文件 complete 落库（独立 tx via self）：importCompletedFile（persist+move+replaceAll）+
     * session 终态更新，原子。落库失败抛出 → tx 回滚 → session 留 UPLOADING（offset==length）→ 上层 422。
     * <p>开头锁 KB 行（findByIdForUpdate，与删除事务互斥）：删除提交后落库校验必然看到目录已删。</p>
     */
    @WithTransaction
    public Uni<Void> completeSingleFile(String operatorId, UploadSession session, AppMessages appMessages) {
        String extension = DocumentCommandService.extractExtension(session.getFileName());
        return knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of(session.getKbId()))
                .flatMap(kb -> documentImportService.importCompletedFile(
                        new DocumentImportService.ImportRequest(
                                operatorId, session.getKbId(), session.getCategoryId(),
                                session.getFileName(), session.getMimeType(), session.getFileTitle(),
                                extension, null, session.getMetadata(), appMessages),
                        session.getTempPath(), session.getUploadLength(),
                        session.getDraftPolicy()))
                .flatMap(result -> {
                    UploadSession terminal = "SKIPPED".equals(result.getStatus())
                            ? session.markSkipped() : session.markComplete(result.getDocumentId());
                    return sessionRepository.update(terminal).replaceWithVoid();
                });
    }

    /**
     * zip complete 后终态 session 更新（独立 tx via self）：
     * SUCCESS→COMPLETED（documentId=null，多文档无单一 id）；SKIPPED→SKIPPED；FAILED→FAILED(聚合原因)。
     */
    @WithTransaction
    public Uni<Void> markSessionTerminal(String sessionId, FileUploadResult result) {
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .flatMap(session -> {
                    if (session == null) {
                        return Uni.createFrom().voidItem();
                    }
                    UploadSession terminal;
                    switch (result.getStatus()) {
                        case "SKIPPED":
                            terminal = session.markSkipped();
                            break;
                        case "FAILED":
                            terminal = session.markFailed(result.getReason());
                            break;
                        default:
                            terminal = session.markComplete(result.getDocumentId(), result.getSkippedCount());
                            break;
                    }
                    return sessionRepository.update(terminal).replaceWithVoid();
                });
    }

    // ========== delete ==========

    /** 终止会话（§4.5）：临时文件移 trash，session ABORTED，幂等；仅会话创建人可终止 */
    @WithTransaction
    public Uni<Void> delete(String operatorId, String sessionId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return sessionRepository.findById(UploadSessionId.of(sessionId))
                .onItem().ifNull().failWith(() ->
                        new TusProtocolException(404, TusProtocolException.Reason.SESSION_NOT_FOUND))
                .flatMap(session -> requireOwned(session, operatorId, appMessages))
                .flatMap(session -> {
                    if (session == null || session.getStatus() == UploadSessionStatus.ABORTED
                            || session.getStatus() == UploadSessionStatus.EXPIRED
                            || session.getStatus() == UploadSessionStatus.COMPLETED
                            || session.getStatus() == UploadSessionStatus.SKIPPED) {
                        return Uni.createFrom().voidItem();
                    }
                    return fileStorageService.moveToTrash(session.getTempPath())
                            .onFailure().recoverWithNull()
                            .flatMap(v -> sessionRepository.update(session.abort()).replaceWithVoid());
                });
    }

    // ========== 校验 ==========

    private Uni<Void> validateCategory(String categoryId, KnowledgeBaseEntity kb, AppMessages appMessages) {
        if (categoryId == null || categoryId.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0420, appMessages.categoryNotFound()))
                .flatMap(cat -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(cat.getStatus())
                            || !kb.getId().value().equals(cat.getKbId())) {
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0420, appMessages.categoryNotFound()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * 解析+校验元数据（§4.2 步骤6）：resolvedMetadata = 批次 default ⊕ 逐文件覆盖；
     * 复用 MetadataCommandService.validateFormat + 字典 key 白名单 + 字典值（fail-closed）。
     * version 非空校验（与单文件上传一致）。
     */
    private Uni<ImportFileMetadata> resolveAndValidateMetadata(CreateUploadSessionCommand command,
                                                              ImportBatch batch, AppMessages appMessages) {
        ImportFileMetadata perFile = command.perFileMetadata() != null
                ? command.perFileMetadata() : ImportFileMetadata.empty();
        ImportFileMetadata resolved = ImportFileMetadata.merge(batch.getDefaultMetadata(), perFile);
        Map<String, String> docMeta = resolved.documentMetadata() != null ? resolved.documentMetadata() : Map.of();
        try {
            MetadataCommandService.validateFormat(docMeta, appMessages);
        } catch (BusinessException e) {
            return Uni.createFrom().failure(e);
        }
        if (resolved.version() == null || resolved.version().isBlank()) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.versionRequired()));
        }
        return validateMetaKeys(docMeta, appMessages)
                .flatMap(v -> dictValueValidator.validateOrThrow(docMeta, invalid -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0420, appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value()))))
                .replaceWith(resolved);
    }

    private Uni<Void> validateMetaKeys(Map<String, String> metadata, AppMessages appMessages) {
        if (metadata == null || metadata.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return dictMetaKeyCacheService.getActiveMetaKeys()
                .flatMap(validKeys -> {
                    for (String key : metadata.keySet()) {
                        if (!validKeys.contains(key)) {
                            return Uni.createFrom().failure(new BusinessException(
                                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.metadataKeyNotInDict(key)));
                        }
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024 * 1024)) + "G";
        }
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + "M";
        }
        return String.valueOf(bytes);
    }
}
