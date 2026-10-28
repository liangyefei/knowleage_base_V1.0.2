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
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ImportBatchRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 导入批次命令服务（写）：创建批次 / 中止批次。
 * <p>批次进度查询、列表查询属读，归 {@code ImportBatchQueryService}。
 * 元数据校验复用 {@link MetadataCommandService#validateFormat}（同包）+ 字典 key 白名单 + 字典值（fail-closed）。</p>
 */
@ApplicationScoped
public class ImportBatchCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ImportBatchCommandService.class);

    private final ImportBatchRepository batchRepository;
    private final UploadSessionRepository sessionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final FileStorageService fileStorageService;
    private final DictMetaKeyCacheService dictMetaKeyCacheService;
    private final DictValueValidator dictValueValidator;

    public ImportBatchCommandService(ImportBatchRepository batchRepository,
                                     UploadSessionRepository sessionRepository,
                                     KnowledgeBaseRepository knowledgeBaseRepository,
                                     CategoryRepository categoryRepository,
                                     FileStorageService fileStorageService,
                                     DictMetaKeyCacheService dictMetaKeyCacheService,
                                     DictValueValidator dictValueValidator) {
        this.batchRepository = batchRepository;
        this.sessionRepository = sessionRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.fileStorageService = fileStorageService;
        this.dictMetaKeyCacheService = dictMetaKeyCacheService;
        this.dictValueValidator = dictValueValidator;
    }

    /**
     * 创建导入批次。
     * <p>校验 kb 存在且活跃、category 属于 kb、default_metadata 经字典校验（fail-closed），失败 A0420 不建批次。</p>
     */
    @WithTransaction
    public Uni<String> createBatch(String operatorId, CreateImportBatchCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (command == null || command.kbId() == null || command.kbId().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        if (command.batchName() == null || command.batchName().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue());
        }
        DraftConflictPolicy policy = command.draftConflictPolicy() != null
                ? command.draftConflictPolicy() : DraftConflictPolicy.OVERRIDE;
        // 前端 metadata JSON 形态（UploadDocumentCommand）→ 持久化形态 ImportFileMetadata（复用普通上传映射）
        ImportFileMetadata defaultMetadata = command.defaultMetadata() != null
                ? command.defaultMetadata().toImportMetadata() : ImportFileMetadata.empty();

        // 1) 校验知识库存在且未删除
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(command.kbId().trim()))
                .onItem().ifNull().failWith(() -> new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        return Uni.createFrom().failure(new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    // 2) categoryId 非空时校验类别存在且属于该 kb
                    return validateCategory(command.categoryId(), kb, appMessages)
                            .flatMap(v -> validateBatchMetadata(defaultMetadata, appMessages))
                            .flatMap(v -> {
                                ImportBatch batch = ImportBatch.create(new ImportBatch.BatchSeed(
                                        command.kbId().trim(),
                                        command.categoryId() == null ? null : command.categoryId().trim(),
                                        command.batchName().trim(),
                                        defaultMetadata, policy, operatorId));
                                return batchRepository.persist(batch)
                                        .map(ImportBatch::getId)
                                        .map(ImportBatchId::value);
                            });
                });
    }

    /**
     * 中止批次：未完成会话置 ABORTED + 临时文件 moveToTrash，批次置 ABORTED。幂等。
     */
    @WithTransaction
    public Uni<Void> abort(String operatorId, String batchId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return batchRepository.findById(ImportBatchId.of(batchId))
                .onItem().ifNull().failWith(() -> new KnowledgeBaseNotFoundException(appMessages.kbNotFound()))
                .flatMap(batch -> {
                    if (!operatorId.equals(batch.getCreatedBy())) {
                        logger.warn("Batch aborted by a non-creator: batchId={}, operator={}, creator={}",
                                batchId, operatorId, batch.getCreatedBy());
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0301, appMessages.unauthorizedOrTokenExpired()));
                    }
                    return sessionRepository.findIncompleteByBatch(batchId)
                            .flatMap(incomplete -> {
                                Uni<Void> chain = Uni.createFrom().voidItem();
                                for (var session : incomplete) {
                                    chain = chain.flatMap(v -> fileStorageService.moveToTrash(session.getTempPath())
                                            .onFailure().recoverWithNull()
                                            .flatMap(v2 -> sessionRepository.update(session.abort()).replaceWithVoid()));
                                }
                                return chain.flatMap(v -> batchRepository.update(batch.abort())).replaceWithVoid();
                            });
                });
    }

    // ========== 校验 ==========

    private Uni<Void> validateCategory(String categoryId, KnowledgeBaseEntity kb, AppMessages appMessages) {
        if (categoryId == null || categoryId.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return categoryRepository.findById(CategoryId.of(categoryId.trim()))
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
     * 校验批次 default_metadata：同步格式（复用 MetadataCommandService.validateFormat）→ key 白名单 → 字典值。
     * fail-closed。version 非空校验（与单文件上传一致）。
     */
    private Uni<Void> validateBatchMetadata(ImportFileMetadata metadata, AppMessages appMessages) {
        Map<String, String> docMeta = metadata.documentMetadata() != null ? metadata.documentMetadata() : Map.of();
        try {
            MetadataCommandService.validateFormat(docMeta, appMessages);
        } catch (BusinessException e) {
            return Uni.createFrom().failure(e);
        }
        if (metadata.version() == null || metadata.version().isBlank()) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.versionRequired()));
        }
        return validateMetaKeys(docMeta, appMessages)
                .flatMap(v -> dictValueValidator.validateOrThrow(docMeta, invalid -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0420, appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value()))))
                .replaceWithVoid();
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
}
