package com.hisilicon.knowledge.platform.llm.sensitive.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.exception.SensitiveTagNotFoundException;
import com.hisilicon.knowledge.platform.llm.sensitive.exception.SensitiveTagNameDuplicateException;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache.SensitiveWordCacheService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 敏感词标签命令服务（创建 / 编辑 / 删除 / 切换启用禁用）
 * <p>文本标签在未删除记录（status != 0）内唯一；删除为软删除，级联硬删该标签下全部词库。
 * 任何变更后调用 {@link SensitiveWordCacheService#evictCache()} 失效缓存并重建内存匹配树。</p>
 */
@ApplicationScoped
public class SensitiveTagCommandService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveTagCommandService.class);

    private final SensitiveTagRepository tagRepository;
    private final SensitiveLexiconRepository lexiconRepository;
    private final SensitiveWordCacheService cacheService;

    public SensitiveTagCommandService(SensitiveTagRepository tagRepository,
                                      SensitiveLexiconRepository lexiconRepository,
                                      SensitiveWordCacheService cacheService) {
        this.tagRepository = tagRepository;
        this.lexiconRepository = lexiconRepository;
        this.cacheService = cacheService;
    }

    /**
     * 创建标签：状态合法性校验（仅 1/2）→ 文本标签唯一性校验（未删除记录内）→ 持久化 → 缓存失效
     */
    @WithTransaction
    public Uni<SensitiveTagEntity> create(CreateSensitiveTagCommand command, String locale) {
        String textLabel = command.textLabel().trim();
        String labelIdentification = command.labelIdentification().trim();
        String remarks = normalizeRemarks(command.remarks());
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        Short status = requireValidTagStatus(command.status(), appMessages);

        logger.info("Create sensitive tag: textLabel={}, labelIdentification={}, status={}",
                textLabel, labelIdentification, status);

        return checkLabelUnique(textLabel, null, appMessages)
                .flatMap(v -> {
                    SensitiveTagEntity entity = SensitiveTagEntity.create(textLabel, labelIdentification, remarks, status);
                    return tagRepository.persist(entity)
                            .call(e -> cacheService.evictCache())
                            .invoke(e -> logger.info("Sensitive tag created: id={}, textLabel={}",
                                    e.getId().value(), textLabel));
                });
    }

    /**
     * 编辑标签（全量替换，含状态）：状态合法性校验（仅 1/2）→ 存在性 + 唯一性（排除自身）校验 → 更新 → 缓存失效
     */
    @WithTransaction
    public Uni<SensitiveTagEntity> edit(EditSensitiveTagCommand command, String locale) {
        String id = command.id().trim();
        String textLabel = command.textLabel().trim();
        String labelIdentification = command.labelIdentification().trim();
        String remarks = normalizeRemarks(command.remarks());
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        Short status = requireValidTagStatus(command.status(), appMessages);

        logger.info("Edit sensitive tag: id={}, textLabel={}, status={}", id, textLabel, status);

        return tagRepository.findById(SensitiveTagId.of(id))
                .onItem().ifNull().failWith(() -> notFound(appMessages))
                .flatMap(entity -> {
                    if (SensitiveTagEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Sensitive tag already deleted, cannot edit: id={}", id);
                        return Uni.createFrom().failure(notFound(appMessages));
                    }
                    return checkLabelUnique(textLabel, id, appMessages)
                            .flatMap(v -> {
                                SensitiveTagEntity updated = entity.update(textLabel, labelIdentification, remarks, status);
                                return tagRepository.update(updated)
                                        .call(e -> cacheService.evictCache())
                                        .invoke(e -> logger.info("Sensitive tag edited: id={}, textLabel={}, status={}",
                                                id, e.getTextLabel(), e.getStatus()));
                            });
                });
    }

    /**
     * 删除标签（软删，级联硬删该标签下全部词库）→ 缓存失效
     */
    @WithTransaction
    public Uni<Void> delete(String id, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        logger.info("Delete sensitive tag: id={}", id);

        return tagRepository.findById(SensitiveTagId.of(id))
                .onItem().ifNull().failWith(() -> notFound(appMessages))
                .flatMap(entity -> {
                    if (SensitiveTagEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Sensitive tag already deleted: id={}", id);
                        return Uni.createFrom().failure(notFound(appMessages));
                    }
                    return lexiconRepository.deleteByTagId(entity.getId())
                            .flatMap(deleted -> tagRepository.update(entity.delete())
                                    .call(v -> cacheService.evictCache())
                                    .invoke(u -> logger.info("Sensitive tag deleted: id={}, cascade deleted {} words",
                                            id, deleted)))
                            .replaceWithVoid();
                });
    }

    /**
     * 切换启用/禁用（1↔2）：幂等短路；软删记录 A0430。状态变更影响匹配范围 → 缓存失效
     */
    @WithTransaction
    public Uni<Void> changeStatus(ChangeSensitiveTagStatusCommand command, String locale) {
        String id = command.id().trim();
        Short targetStatus = command.status();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Change sensitive tag status: id={}, targetStatus={}", id, targetStatus);

        if (!SensitiveTagEntity.STATUS_ACTIVE.equals(targetStatus)
                && !SensitiveTagEntity.STATUS_DISABLED.equals(targetStatus)) {
            logger.error("Invalid sensitive tag status parameter: targetStatus={}", targetStatus);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.sensitiveTagStatusInvalid());
        }

        return tagRepository.findById(SensitiveTagId.of(id))
                .onItem().ifNull().failWith(() -> notFound(appMessages))
                .flatMap(entity -> {
                    if (SensitiveTagEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Sensitive tag already deleted, cannot change status: id={}", id);
                        return Uni.createFrom().failure(notFound(appMessages));
                    }
                    if (targetStatus.equals(entity.getStatus())) {
                        logger.info("Sensitive tag already in target status, idempotent return: id={}, status={}", id, targetStatus);
                        return Uni.createFrom().voidItem();
                    }
                    return tagRepository.update(entity.changeStatus(targetStatus))
                            .call(v -> cacheService.evictCache())
                            .invoke(u -> logger.info("Sensitive tag status changed: id={}, status={}",
                                    id, u.getStatus()))
                            .replaceWithVoid();
                });
    }

    // ========== 内部校验 ==========

    /**
     * 校验手动创建/编辑的状态取值：仅允许 1（启用）/ 2（禁用），非法抛 A0420
     */
    private Short requireValidTagStatus(Short status, AppMessages appMessages) {
        if (!SensitiveTagEntity.STATUS_ACTIVE.equals(status) && !SensitiveTagEntity.STATUS_DISABLED.equals(status)) {
            logger.error("Invalid sensitive tag status parameter: status={}", status);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.sensitiveTagStatusInvalid());
        }
        return status;
    }

    private Uni<Void> checkLabelUnique(String textLabel, String excludeId, AppMessages appMessages) {
        return tagRepository.findActiveByLabel(textLabel)
                .onItem().transformToUni(existing -> {
                    boolean duplicate = existing != null && !existing.getId().value().equals(excludeId);
                    if (duplicate) {
                        logger.error("Sensitive tag already exists: textLabel={}", textLabel);
                        return Uni.createFrom().failure(
                                new SensitiveTagNameDuplicateException(appMessages.sensitiveTagNameDuplicate()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    private SensitiveTagNotFoundException notFound(AppMessages appMessages) {
        return new SensitiveTagNotFoundException(appMessages.sensitiveTagNotFound());
    }

    private String normalizeRemarks(String remarks) {
        return (remarks == null || remarks.isBlank()) ? null : remarks.trim();
    }
}
