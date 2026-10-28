package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentStateException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一元数据命令服务（POST /kb/metadata/set）
 * <p>以 {@code entityType}（KB / CATEGORY / DOCUMENT）区分操作对象，对指定实体的
 * 元数据执行全量替换。三张元数据表（knowledge_base_metadata / category_metadata /
 * document_metadata）结构一致，共享同一套 key-value 校验与替换语义。</p>
 * <p>校验规则（防直接调用攻击）：key 必须为字母开头的字母/数字/下划线且长度≤50
 * （对齐 {@code meta_key varchar(50)}）；value trim 后长度 1~200（对齐
 * {@code meta_value varchar(200)}）且不含控制字符；条目数≤50（Bean Validation 层）。</p>
 * <p>状态约束：KB/CATEGORY 仅活跃（status=1）可编辑，软删除视同不存在（A0430）；
 * DOCUMENT 仅 DRAFT/PUBLISHED 可编辑，其余状态 A0508。编辑元数据不发布任何消息事件。</p>
 */
@ApplicationScoped
public class MetadataCommandService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataCommandService.class);

    /** 实体类型：知识库 */
    public static final String ENTITY_TYPE_KB = NavTreeItem.TYPE_KB;
    /** 实体类型：类别 */
    public static final String ENTITY_TYPE_CATEGORY = NavTreeItem.TYPE_CATEGORY;
    /** 实体类型：文档 */
    public static final String ENTITY_TYPE_DOCUMENT = "DOCUMENT";

    /** 元数据 key 格式：字母开头，仅字母/数字/下划线，长度 1~50（对齐 meta_key varchar(50)） */
    private static final Pattern META_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,49}$");
    /** 元数据 value 最大长度（对齐 meta_value varchar(200)） */
    private static final int META_VALUE_MAX_LENGTH = 200;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseMetadataRepository kbMetadataRepository;
    private final CategoryMetadataRepository categoryMetadataRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final DictMetaKeyCacheService dictMetaKeyCacheService;
    private final DictValueValidator dictValueValidator;

    public MetadataCommandService(KnowledgeBaseRepository knowledgeBaseRepository,
                                  CategoryRepository categoryRepository,
                                  DocumentRepository documentRepository,
                                  KnowledgeBaseMetadataRepository kbMetadataRepository,
                                  CategoryMetadataRepository categoryMetadataRepository,
                                  DocumentMetadataRepository documentMetadataRepository,
                                  DictMetaKeyCacheService dictMetaKeyCacheService,
                                  DictValueValidator dictValueValidator) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.kbMetadataRepository = kbMetadataRepository;
        this.categoryMetadataRepository = categoryMetadataRepository;
        this.documentMetadataRepository = documentMetadataRepository;
        this.dictMetaKeyCacheService = dictMetaKeyCacheService;
        this.dictValueValidator = dictValueValidator;
    }

    /**
     * 设置元数据（全量替换）
     *
     * @param operatorId 操作者用户 ID
     * @param command    元数据命令
     * @param locale     语言环境
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> set(String operatorId, SetMetadataCommand command, String locale) {
        String entityType = command.entityType().trim();
        String entityId = command.entityId().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (!ENTITY_TYPE_KB.equals(entityType)
                && !ENTITY_TYPE_CATEGORY.equals(entityType)
                && !ENTITY_TYPE_DOCUMENT.equals(entityType)) {
            logger.error("Invalid metadata entity type: entityType={}", entityType);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidEntityTypeValue()));
        }

        // 同步格式校验（防直接调用攻击；上传/覆盖路径不受影响），失败短路不查字典
        try {
            validateFormat(command.metadata(), appMessages);
        } catch (BusinessException e) {
            return Uni.createFrom().failure(e);
        }

        // 字典白名单校验 + 值校验：key 必须存在于生效字典类型，value 必须在字典条目中
        return validateMetaKeys(command.metadata(), appMessages)
                .flatMap(v -> validateMetaValues(command.metadata(), appMessages))
                .flatMap(v -> {
                    if (ENTITY_TYPE_KB.equals(entityType)) {
                        return setForKb(entityId, command.metadata(), appMessages);
                    }
                    if (ENTITY_TYPE_CATEGORY.equals(entityType)) {
                        return setForCategory(entityId, command.metadata(), appMessages);
                    }
                    return setForDocument(entityId, command.metadata(), appMessages);
                });
    }

    // ========== 各实体类型实现 ==========

    private Uni<Void> setForKb(String kbId, Map<String, String> metadata, AppMessages appMessages) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", kbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(entity -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Knowledge base already deleted, cannot set metadata: id={}", kbId);
                        return Uni.createFrom().failure(
                                new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    return kbMetadataRepository.replaceAll(kbId, metadata);
                });
    }

    private Uni<Void> setForCategory(String categoryId, Map<String, String> metadata, AppMessages appMessages) {
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", categoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(entity -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Category already deleted, cannot set metadata: id={}", categoryId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    return categoryMetadataRepository.replaceAll(categoryId, metadata);
                });
    }

    private Uni<Void> setForDocument(String documentId, Map<String, String> metadata, AppMessages appMessages) {
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    String status = entity.getStatus();
                    if (!DocumentEntity.STATUS_DRAFT.equals(status)
                            && !DocumentEntity.STATUS_PUBLISHED.equals(status)) {
                        logger.error("Document status does not allow metadata edit: id={}, status={}", documentId, status);
                        return Uni.createFrom().failure(
                                new DocumentStateException(appMessages.documentStateInvalid()));
                    }
                    return documentMetadataRepository.replaceAll(documentId, metadata);
                });
    }

    // ========== key/value 校验 ==========

    /**
     * 同步逐项校验元数据 key/value 格式，非法时直接抛出第一条非法条目对应的业务异常。
     * <p>不涉及 IO，先于字典白名单校验执行（格式非法的请求短路，不查缓存/DB）。
     * 包级可见以供同包的批量上传 {@link ImportBatchCommandService}/{@link UploadSessionCommandService}
     * 复用，避免校验逻辑漂移。</p>
     * @throws BusinessException 格式非法
     */
    static void validateFormat(Map<String, String> metadata, AppMessages appMessages) {
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !META_KEY_PATTERN.matcher(key).matches()) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.metadataKeyInvalid(key));
            }
            if (value == null || value.isBlank() || value.trim().length() > META_VALUE_MAX_LENGTH
                    || containsControlChar(value)) {
                throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                        appMessages.metadataValueInvalid(key));
            }
        }
    }

    /**
     * 异步校验元数据 key 白名单：每个 key 必须存在于生效字典类型（status=1）的
     * {@code controlled_meta_keys} 并集中（经 {@link DictMetaKeyCacheService} 缓存获取）。
     * 任何 key 不在白名单 → A0420 + metadataKeyNotInDict。
     */
    private Uni<Void> validateMetaKeys(Map<String, String> metadata, AppMessages appMessages) {
        return dictMetaKeyCacheService.getActiveMetaKeys()
                .flatMap(validKeys -> {
                    for (String key : metadata.keySet()) {
                        if (!validKeys.contains(key)) {
                            logger.error("Metadata key not in dictionary controlled keys: key={}, validKeys={}", key, validKeys);
                            return Uni.createFrom().failure(new BusinessException(
                                    ErrorCodeEnum.USER_ERROR_A0420,
                                    appMessages.metadataKeyNotInDict(key)));
                        }
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * 异步校验元数据值合法性（上传/编辑/元数据写入共用 {@link DictValueValidator#validateOrThrow}）：
     * 每个 key 的 value（逗号分隔多值逐个校验）必须在对应字典类型的活跃条目中。
     * 任何 value 不在字典条目中 → A0420 + metadataValueNotInDict；缓存故障 fail-closed。
     */
    private Uni<Void> validateMetaValues(Map<String, String> metadata, AppMessages appMessages) {
        return dictValueValidator.validateOrThrow(metadata, invalid -> new BusinessException(
                ErrorCodeEnum.USER_ERROR_A0420,
                appMessages.metadataValueNotInDict(invalid.metaKey(), invalid.value())));
    }

    /**
     * 是否包含控制字符（ASCII 0x00-0x1F 及 DEL 0x7F）
     */
    private static boolean containsControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
