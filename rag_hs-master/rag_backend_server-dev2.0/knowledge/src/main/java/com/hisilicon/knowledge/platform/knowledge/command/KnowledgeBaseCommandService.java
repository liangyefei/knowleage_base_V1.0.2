package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KbCallbackProcessedRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNameDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 知识库命令服务
 */
@ApplicationScoped
public class KnowledgeBaseCommandService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseCommandService.class);

    /** 删除阻塞状态：审核中/已发布/已归档的文档不允许随知识库删除（须先撤回或废弃） */
    private static final List<String> DELETE_BLOCKING_STATUSES = List.of(
            DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_PUBLISHED, DocumentEntity.STATUS_ARCHIVED);

    /** 随知识库级联物理删除的状态：草稿（未处理）+ 已废弃（向量已在废弃时清理） */
    private static final List<String> DELETE_CASCADE_STATUSES = List.of(
            DocumentEntity.STATUS_DRAFT, DocumentEntity.STATUS_DEPRECATED);

    /** 删除知识库单次级联物理删除文档上限：防一次删除文件过多导致请求超时/网络错误 */
    private static final int DELETE_MAX_DOCS = 200;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentOutboxRepository outboxRepository;
    private final CategoryRepository categoryRepository;
    private final KbCallbackProcessedRepository callbackProcessedRepository;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService fileRecycleService;
    private final com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository uploadSessionRepository;

    /**
     * 自注入：delete() 非事务编排 + deleteKbInTx() 事务方法的桥接。
     * 直接 this.deleteKbInTx() 会绕过 {@code @WithTransaction} 拦截器（CDI 代理不生效）。
     */
    @jakarta.inject.Inject
    KnowledgeBaseCommandService self;

    public KnowledgeBaseCommandService(KnowledgeBaseRepository knowledgeBaseRepository,
                                       DocumentOutboxRepository outboxRepository,
                                       CategoryRepository categoryRepository,
                                       KbCallbackProcessedRepository callbackProcessedRepository,
                                       DocumentRepository documentRepository,
                                       FileStorageService fileStorageService,
                                       com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService fileRecycleService,
                                       com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository uploadSessionRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.outboxRepository = outboxRepository;
        this.categoryRepository = categoryRepository;
        this.callbackProcessedRepository = callbackProcessedRepository;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.fileRecycleService = fileRecycleService;
        this.uploadSessionRepository = uploadSessionRepository;
    }

    /**
     * 创建知识库
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param command    创建命令
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> create(String operatorId, CreateKbCommand command, String locale) {
        String name = command.kbName().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Create knowledge base: name={}, operatorId={}", name, operatorId);

        // 1) 校验名称唯一性（findByName 只查未删除记录，软删除后允许重建同名）
        return knowledgeBaseRepository.findByName(name)
                .onItem().ifNotNull().failWith(() -> {
                    logger.error("Knowledge base name already exists: {}", name);
                    return new KnowledgeBaseNameDuplicateException(appMessages.kbNameAlreadyExists());
                })
                // 2) 名称不存在，领域工厂创建实体并入库
                .onItem().ifNull().switchTo(() -> {
                    KnowledgeBaseEntity entity = KnowledgeBaseEntity.create(
                            name, command.description(), operatorId);

                    // 3) 物理目录段唯一化：kb/ 下候选 {seg}/{seg}_1… 取第一个空闲段
                    //    （旧 KB 改名后仍占旧名目录，新 KB 同名须跳号），实际段持久化到 dirName
                    String baseSegment = DocumentStoragePathResolver.dirSegment(name, entity.getId().value());
                    return DocumentStoragePathResolver
                            .uniqueStorageDirSegment(fileStorageService, "kb", baseSegment)
                            .flatMap(dirName -> knowledgeBaseRepository.persist(entity.withDirName(dirName))
                                    .invoke(() -> logger.info("Knowledge base created: id={}, name={}, dirName={}",
                                            entity.getId().value(), name, dirName))
                                    // 4) 预建知识库物理目录（kb/{dirName}，快照语义：改名不搬目录）。
                                    //    best-effort：失败仅告警不阻塞创建
                                    .call(() -> fileStorageService.ensureDirectory("kb/" + dirName)
                                            .onFailure().invoke(err -> logger.warn(
                                                    "KB directory pre-creation failed (creation not affected): dir=kb/{}, err={}",
                                                    dirName, err.getMessage()))
                                            .onFailure().recoverWithNull())
                                    // 5) 发布知识库创建事件（通知模型服务初始化向量库等）
                                    .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(
                                            KnowledgeEvent.EventType.KB_CREATED,
                                            new KnowledgeEvent.Subject(
                                                    entity.getId().value(), null, null, operatorId),
                                            buildKbPayload(name, command.description())))));
                })
                .replaceWithVoid();
    }

    /**
     * 更新知识库基础信息
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param command    更新命令
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> update(String operatorId, UpdateKbCommand command, String locale) {
        String kbId = command.kbId().trim();
        String newName = command.kbName().trim();
        String newDescription = command.description();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Update knowledge base: id={}, operatorId={}", kbId, operatorId);

        // 1) 校验知识库存在且未删除
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", kbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(entity -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Knowledge base already deleted, cannot edit: id={}", kbId);
                        return Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    return Uni.createFrom().item(entity);
                })
                // 2) 名称变更时校验唯一性（名称相同则跳过，避免自身冲突）
                .flatMap(entity -> {
                    if (!newName.equals(entity.getName())) {
                        return knowledgeBaseRepository.findByName(newName)
                                .onItem().ifNotNull().failWith(() -> {
                                    logger.error("Knowledge base name already exists: {}", newName);
                                    return new KnowledgeBaseNameDuplicateException(appMessages.kbNameAlreadyExists());
                                })
                                .map(ignored -> entity);
                    }
                    return Uni.createFrom().item(entity);
                })
                // 3) 领域实体更新并持久化（全量替换：未传的 description 会置空）
                .flatMap(entity -> {
                    KnowledgeBaseEntity updated = entity.update(newName, newDescription);
                    return knowledgeBaseRepository.update(updated)
                            .invoke(() -> logger.info("Knowledge base updated: id={}", kbId))
                            // 4) 发布知识库编辑事件
                            .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(KnowledgeEvent.EventType.KB_UPDATED, new KnowledgeEvent.Subject(kbId, null, null, operatorId), buildKbPayload(newName, newDescription))));
                })
                .replaceWithVoid();
    }

    /**
     * 软删除知识库（级联软删全部类别 + 级联物理删除可删文档）
     * <p>两阶段：</p>
     * <ol>
     *   <li>编排（无事务，仅只读）：校验存在且未删除 → 阻塞校验：全 KB 范围（含根目录）存在
     *       REVIEWING/PUBLISHED/ARCHIVED 文档 → 拒绝（A0436，提示先撤回或废弃）；</li>
     *   <li>事务（{@link #deleteKbInTx}）：开头锁 KB 行（与上传落库事务互斥，锁序一致无死锁）→
     *       防御性复查阻塞计数 → 批量物理删除 DRAFT/DEPRECATED 文档（3 条语句级联
     *       document_metadata/document_versions，状态守卫防并发半删）→ 级联软删全部类别 →
     *       KB 软删 → 发布 KB_DELETED 事件（通知模型服务清理向量索引）。</li>
     * </ol>
     * <p>事务提交后（不阻塞响应）：① 文档文件（当前版本 + 历史版本）异步移入回收站
     * （快照身份校验，防止同名重传覆盖后误删新文件，见 {@link com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService}）；
     * ② 该 KB 全部在传上传会话异步作废（ABORTED + 临时文件移回收站）。
     * 级联删除 DRAFT/DEPRECATED 保证删除后无孤儿文档可被查询，且重建同名知识库后可重新上传。</p>
     *
     * @param operatorId 操作员 ID（从 JWT Token 解析）
     * @param kbId       知识库 ID
     * @param locale     语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithSession
    public Uni<Void> delete(String operatorId, String kbId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Delete knowledge base: id={}, operatorId={}", kbId, operatorId);

        // 1) 校验知识库存在且未删除（已删除视为不存在，删除幂等）
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", kbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(entity -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Knowledge base already deleted: id={}", kbId);
                        return Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    // 2) 阻塞校验：全 KB 范围（含根目录）存在审核中/已发布/已归档文档 → 拒绝删除
                    return documentRepository.countByKbIdAndStatusIn(kbId, DELETE_BLOCKING_STATUSES)
                            .flatMap(blocking -> {
                                if (blocking > 0) {
                                    logger.error("Knowledge base contains non-deletable documents (REVIEWING/PUBLISHED/ARCHIVED): kbId={}, count={}",
                                            kbId, blocking);
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.DELETE_BLOCKED_BY_DOCUMENTS,
                                            appMessages.kbDeleteBlockedByDocuments()));
                                }
                                // 2.1) 数量上限：待级联物理删除的 DRAFT/DEPRECATED 文档过多 →
                                //      单次删除耗时超 60s APIG 时限，直接拒绝，避免超时/网络错误
                                return documentRepository.countByKbIdAndStatusIn(kbId, DELETE_CASCADE_STATUSES)
                                        .flatMap(cascadeCount -> {
                                            if (cascadeCount > DELETE_MAX_DOCS) {
                                                logger.error("Too many files pending deletion in knowledge base: kbId={}, count={}, max={}",
                                                        kbId, cascadeCount, DELETE_MAX_DOCS);
                                                return Uni.createFrom().failure(new BusinessException(
                                                        ErrorCodeEnum.USER_ERROR_A0420,
                                                        appMessages.kbDeleteCountExceeded(DELETE_MAX_DOCS)));
                                            }
                                            // 3) 事务：锁 KB 行 + 物理删除可删文档 + 级联软删类别 + KB 软删 + KB_DELETED 事件；
                                            //    提交后（事务 Uni 完成）文件回收 + 在传会话作废异步执行，不阻塞响应
                                            return self.deleteKbInTx(appMessages, operatorId, kbId)
                                                    .invoke(snapshots -> fileRecycleService
                                                            .recycleAsync("kbId=" + kbId, snapshots))
                                                    .invoke(() -> abortActiveSessionsAsync(kbId));
                                        });
                            });
                })
                .replaceWithVoid();
    }

    /**
     * 事务内执行知识库删除：锁 KB 行 + 批量物理删除 DRAFT/DEPRECATED 文档 + 级联软删类别 + KB 软删 + KB_DELETED 事件。
     * <p>通过 {@code self} 自注入调用保证 {@code @WithTransaction} 拦截器生效。
     * 返回待回收路径 → 删除时快照（供提交后身份校验回收，防同名重传误删）。</p>
     * <p>开头 {@code findByIdForUpdate} 锁 KB 行：与上传落库事务互斥（同 {@code deleteCategoryInTx}）。</p>
     */
    @WithTransaction
    public Uni<Map<String, FileStorageService.FileSnapshot>> deleteKbInTx(AppMessages appMessages, String operatorId, String kbId) {
        // 锁 KB 行（与上传落库互斥，锁序一致：均先 KB 行，无死锁）
        return knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of(kbId))
                // 防御性复查：编排（只读校验）与事务之间的并发窗口内文档状态可能变化
                .flatMap(kb -> documentRepository.countByKbIdAndStatusIn(kbId, DELETE_BLOCKING_STATUSES))
                .flatMap(blocking -> {
                    if (blocking > 0) {
                        logger.error("Re-check inside KB delete transaction still found blocking documents: kbId={}, count={}", kbId, blocking);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.DELETE_BLOCKED_BY_DOCUMENTS,
                                appMessages.kbDeleteBlockedByDocuments()));
                    }
                    return documentRepository.findByKbIdAndStatusIn(kbId, DELETE_CASCADE_STATUSES);
                })
                // 收集待删除文档的全部文件路径并快照（须在级联删除行之前完成）
                .flatMap(docs -> collectDocFilePaths(docs)
                        .flatMap(snapshots -> documentRepository
                                .deleteAllByIds(docIds(docs), DELETE_CASCADE_STATUSES)
                                .invoke(() -> logger.info("KB cascade physically deleted documents: kbId={}, count={}", kbId, docs.size()))
                                .map(v -> snapshots)))
                // 级联软删全部类别
                .chain(snapshots -> cascadeDeleteCategories(kbId).map(v -> snapshots))
                // KB 软删 + KB_DELETED 事件（通知模型服务清理向量索引等）
                .chain(snapshots -> knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                        .flatMap(entity -> {
                            KnowledgeBaseEntity deleted = entity.delete();
                            return knowledgeBaseRepository.update(deleted)
                                    .invoke(() -> logger.info("Knowledge base deleted: id={}", kbId))
                                    .call(() -> outboxRepository.insert(KnowledgeEvent.of(
                                            KnowledgeEvent.EventType.KB_DELETED,
                                            kbId, null, null, operatorId)))
                                    .map(v -> snapshots);
                        }));
    }

    /** 文档 ID 集合 */
    private static List<String> docIds(List<DocumentEntity> docs) {
        return docs.stream().map(d -> d.getId().value()).toList();
    }

    /** 收集文档全部文件路径并快照身份（当前版本取内存实体 + 历史版本一条批量查询，去重保序） */
    private Uni<Map<String, FileStorageService.FileSnapshot>> collectDocFilePaths(List<DocumentEntity> docs) {
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

    /**
     * 作废该 KB 全部在传上传会话——fire-and-forget（提交后调用，不阻塞删除响应）。
     * <p>删除提交后其后续落库校验必然失败；显式作废比让会话解压完成后逐条目报错更及时。</p>
     */
    private void abortActiveSessionsAsync(String kbId) {
        uploadSessionRepository.findActiveByKbId(kbId)
                .invoke(sessions -> {
                    if (sessions.isEmpty()) {
                        return;
                    }
                    logger.info("Abort upload sessions in knowledge base: kbId={}, count={}", kbId, sessions.size());
                    for (com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession s : sessions) {
                        self.abortUploadSessionInTx(s)
                                .subscribe().with(v -> {},
                                        err -> logger.error("Failed to abort upload session (session cleanup job will expire it): sessionId={}, error={}",
                                                s.getId().value(), err.getMessage()));
                    }
                })
                .subscribe().with(v -> {},
                        err -> logger.error("Failed to query active upload sessions: kbId={}, error={}", kbId, err.getMessage()));
    }

    /** 单会话作废（独立短事务 via self）：置 ABORTED + 临时文件移回收站（best-effort），幂等守卫 */
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

    /**
     * 知识库级模型回调处理（KB_CREATED / KB_DELETED 的 PROCESSING_COMPLETED / PROCESSING_FAILED）
     * <p>仅修改 KB 的 processStatus / processError / processErrorCode / processedAt，
     * 不动生命周期 status（active / deleted）。状态机守卫：仅 PENDING 可迁移到终态；
     * 已是终态视为重复/过期，ACK 跳过。幂等：messageId 去重；KB 不存在也 ACK 跳过。</p>
     *
     * @param cmd    回调命令
     * @param locale 语言环境
     * @return Uni&lt;Void&gt;；成功完成（含幂等跳过）→ ACK，瞬时故障 → nack 重试
     */
    @WithTransaction
    public Uni<Void> processCallback(ProcessCallbackCommand cmd, String locale) {
        String kbId = cmd.kbId();
        String status = cmd.completed()
                ? KnowledgeBaseEntity.PROCESS_COMPLETED
                : KnowledgeBaseEntity.PROCESS_FAILED;
        logger.info("KB model callback: messageId={}, kbId={}, status={}", cmd.messageId(), kbId, status);

        // 1) 幂等去重：messageId 已处理过 → ACK 跳过
        return callbackProcessedRepository.existsByMessageId(cmd.messageId())
                .flatMap(already -> {
                    if (Boolean.TRUE.equals(already)) {
                        logger.info("KB callback already processed, idempotent skip: messageId={}", cmd.messageId());
                        return Uni.createFrom().voidItem();
                    }
                    return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                            .flatMap(entity -> {
                                // 2) KB 不存在 → ACK 跳过 + 记录 messageId 防重放
                                if (entity == null) {
                                    logger.warn("Knowledge base for callback not found, ACK and skip: kbId={}", kbId);
                                    return callbackProcessedRepository.record(
                                            cmd.messageId(), null, kbId, cmd.eventType(), status);
                                }
                                String current = entity.getProcessStatus();
                                boolean alreadyTerminal = KnowledgeBaseEntity.PROCESS_COMPLETED.equals(current)
                                        || KnowledgeBaseEntity.PROCESS_FAILED.equals(current);
                                // 3) 状态机守卫：已是终态时按"最新回调胜"判断——
                                //    新回调 processedAt 严格晚于当前终态 processedAt → 视为重试新结果，覆盖（走到 4）；
                                //    否则（过期/同序/无法比较）→ ACK 跳过（幂等）。
                                if (alreadyTerminal && !isLaterCallback(cmd.processedAt(), entity.getProcessedAt())) {
                                    logger.info("Process status already terminal and callback is not newer, treated as duplicate/expired, ACK and skip: kbId={}, current={}",
                                            kbId, current);
                                    return callbackProcessedRepository.record(
                                            cmd.messageId(), null, kbId, cmd.eventType(), status);
                                }
                                // 4) 应用回调（PENDING → 终态；或终态被更新回调覆盖）
                                boolean completed = cmd.completed();
                                String processError = completed
                                        ? null
                                        : (cmd.error() != null && !cmd.error().isBlank()
                                                ? cmd.error() : "（模型未提供错误描述）");
                                String errorCode = completed ? null : cmd.errorCode();
                                LocalDateTime processedAt = cmd.processedAt() != null
                                        ? cmd.processedAt() : LocalDateTime.now();
                                KnowledgeBaseEntity updated = entity.withProcessResult(
                                        status, processError, errorCode, processedAt);
                                return knowledgeBaseRepository.update(updated)
                                        .flatMap(v -> callbackProcessedRepository.record(
                                                cmd.messageId(), null, kbId, cmd.eventType(), status));
                            });
                })
                .invoke(() -> logger.info("KB model callback processed: kbId={}, status={}", kbId, status))
                .replaceWithVoid();
    }

    /**
     * 级联软删除知识库下所有未删除类别
     */
    private Uni<Void> cascadeDeleteCategories(String kbId) {
        return categoryRepository.findAllByKbId(kbId)
                .flatMap(categories -> {
                    if (categories.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    List<Uni<CategoryEntity>> updates = categories.stream()
                            .map(c -> categoryRepository.update(c.delete()))
                            .toList();
                    return Uni.combine().all().unis(updates).discardItems();
                });
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
     * 构建 KB_CREATED / KB_UPDATED 消息 payload
     * <p>description 可空：null 时省略该字段（模型侧按缺省处理）。</p>
     */
    private Map<String, Object> buildKbPayload(String kbName, String description) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kbName", kbName);
        if (description != null) {
            payload.put("description", description);
        }
        return payload;
    }
}
