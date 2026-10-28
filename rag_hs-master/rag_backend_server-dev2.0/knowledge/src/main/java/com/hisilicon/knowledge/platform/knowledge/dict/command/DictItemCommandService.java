package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictItemRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictTypeRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.exception.DictItemNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.dict.exception.DictItemValueDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.dict.exception.DictTypeNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字典条目命令服务（创建 / 编辑 / 删除 / 切换启用禁用）
 * <p>条目变更不影响 key 白名单（白名单只由类型的 controlledMetaKeys 决定），
 * 故条目操作不触发白名单缓存（{@code dict:active-meta-keys}）失效。
 * 但条目 value 变更会改变值缓存（{@code dict:active-meta-key-values}），
 * 故条目操作后调用 {@link DictValueValidator#evictCache()} 仅失效值缓存。</p>
 */
@ApplicationScoped
public class DictItemCommandService {

    private static final Logger logger = LoggerFactory.getLogger(DictItemCommandService.class);

    private final DictTypeRepository dictTypeRepository;
    private final DictItemRepository dictItemRepository;
    private final DictValueValidator dictValueValidator;
    private final DictReferenceChecker dictReferenceChecker;

    public DictItemCommandService(DictTypeRepository dictTypeRepository,
                                  DictItemRepository dictItemRepository,
                                  DictValueValidator dictValueValidator,
                                  DictReferenceChecker dictReferenceChecker) {
        this.dictTypeRepository = dictTypeRepository;
        this.dictItemRepository = dictItemRepository;
        this.dictValueValidator = dictValueValidator;
        this.dictReferenceChecker = dictReferenceChecker;
    }

    /**
     * 创建字典条目
     * <p>校验所属类型存在且未删除 → 同类型下 value 唯一 → sortOrder 自动
     * （不传 = 同级最大 + 1，首个从 1 开始）→ 持久化。</p>
     *
     * @param command 创建命令
     * @param locale  语言环境
     * @return 新创建的字典条目实体（含 ID）
     */
    @WithTransaction
    public Uni<DictItemEntity> create(CreateDictItemCommand command, String locale) {
        String typeCode = command.typeCode().trim();
        String label = command.label().trim();
        String value = command.value().trim();
        Integer sortOrder = command.sortOrder();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Create dict item: typeCode={}, value={}", typeCode, value);

        // 1) 类型必须存在且未删除（已删除视同不存在）
        return dictTypeRepository.findByCode(typeCode)
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict type not found or already deleted: typeCode={}", typeCode);
                    return new DictTypeNotFoundException(appMessages.dictTypeNotFound());
                })
                // 2) 同类型下 value 唯一性
                .flatMap(type -> dictItemRepository.findByTypeAndValue(typeCode, value))
                .onItem().ifNotNull().failWith(() -> {
                    logger.error("Dict item value already exists: typeCode={}, value={}", typeCode, value);
                    return new DictItemValueDuplicateException(appMessages.dictItemValueAlreadyExists());
                })
                // 3) sortOrder 自动：同级最大 + 1（首个从 1 开始）
                .onItem().ifNull().switchTo(() ->
                        dictItemRepository.maxSortOrder(typeCode)
                                .map(max -> sortOrder != null ? sortOrder : (max != null ? max + 1 : 1))
                                .flatMap(calculatedSort -> {
                                    DictItemEntity entity = DictItemEntity.create(
                                            typeCode, label, value, calculatedSort);
                                    return dictItemRepository.persist(entity)
                                            .call(v -> dictValueValidator.evictCache())
                                            .invoke(e -> logger.info("Dict item created: id={}, value={}, sortOrder={}",
                                                    e.getId().value(), value, calculatedSort))
                                            .map(e -> e);
                                }));
    }

    /**
     * 编辑字典条目
     * <p>label/value 全量替换；<b>sortOrder 为 null 时保持原值</b>（用户确认的例外）。
     * value 变更时校验同类型下唯一性（跳过自身）。
     * 修改 value 不影响已引用该值的历史元数据——元数据是历史事实，字典变更仅影响未来选项。</p>
     *
     * @param command 编辑命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> edit(EditDictItemCommand command, String locale) {
        String itemId = command.id().trim();
        String newLabel = command.label().trim();
        String newValue = command.value().trim();
        Integer newSortOrder = command.sortOrder();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Edit dict item: id={}, newValue={}", itemId, newValue);

        // 1) 条目必须存在且未删除（已删除视同不存在）
        return dictItemRepository.findById(DictItemId.of(itemId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict item not found: id={}", itemId);
                    return new DictItemNotFoundException(appMessages.dictItemNotFound());
                })
                .flatMap(entity -> {
                    if (DictItemEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict item already deleted, cannot edit: id={}", itemId);
                        return Uni.createFrom().failure(
                                new DictItemNotFoundException(appMessages.dictItemNotFound()));
                    }
                    // 2) value 变更时校验唯一性（跳过自身）
                    if (!newValue.equals(entity.getValue())) {
                        return dictItemRepository.findByTypeAndValue(entity.getTypeCode(), newValue)
                                .onItem().ifNotNull().failWith(() -> {
                                    logger.error("Dict item value already exists: typeCode={}, value={}",
                                            entity.getTypeCode(), newValue);
                                    return new DictItemValueDuplicateException(
                                            appMessages.dictItemValueAlreadyExists());
                                })
                                .map(ignored -> entity);
                    }
                    return Uni.createFrom().item(entity);
                })
                // 3) 全量替换更新（sortOrder null = 保持原值）
                .flatMap(entity -> {
                    DictItemEntity updated = entity.update(newLabel, newValue, newSortOrder);
                    return dictItemRepository.update(updated)
                            .call(v -> dictValueValidator.evictCache())
                            .invoke(e -> logger.info("Dict item edited: id={}, value={}", itemId, newValue))
                            .replaceWithVoid();
                });
    }

    /**
     * 删除字典条目（软删）
     * <p>删除前校验该值是否被业务数据引用（元数据表 meta_value / version 专用列）：
     * 有关联引用则拒绝（A0420），无引用方软删。引用检查委托
     * {@link DictReferenceChecker}（knowledge 侧实现，跨模块 SPI）。</p>
     *
     * @param command 删除命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> delete(DeleteDictItemCommand command, String locale) {
        String itemId = command.id().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Delete dict item: id={}", itemId);

        return dictItemRepository.findById(DictItemId.of(itemId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict item not found: id={}", itemId);
                    return new DictItemNotFoundException(appMessages.dictItemNotFound());
                })
                .flatMap(entity -> {
                    if (DictItemEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict item already deleted: id={}", itemId);
                        return Uni.createFrom().failure(
                                new DictItemNotFoundException(appMessages.dictItemNotFound()));
                    }
                    // 删除前引用检查：被业务数据引用则拒绝（fail-closed：检查失败亦拒绝）
                    return dictReferenceChecker.isItemValueReferenced(entity.getTypeCode(), entity.getValue())
                            .flatMap(referenced -> {
                                if (Boolean.TRUE.equals(referenced)) {
                                    logger.warn("Dict item referenced by business data, deletion rejected: id={}, typeCode={}, value={}",
                                            itemId, entity.getTypeCode(), entity.getValue());
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.USER_ERROR_A0420,
                                            appMessages.dictItemInUseCannotDelete(entity.getValue())));
                                }
                                return dictItemRepository.update(entity.delete())
                                        .call(v -> dictValueValidator.evictCache())
                                        .invoke(e -> logger.info("Dict item deleted: id={}, value={}",
                                                itemId, e.getValue()))
                                        .replaceWithVoid();
                            });
                });
    }

    /**
     * 切换字典条目启用/禁用状态（1↔2）
     * <p>仅在 {@link DictItemEntity#STATUS_ACTIVE} 与 {@link DictItemEntity#STATUS_DISABLED}
     * 间切换；软删除（0）视同不存在（A0430）。再启用（2→1）前预检 (typeCode, value) 唯一性——
     * 若禁用期间另建了同值活跃条目，拒绝（A0429）。条目状态变更不影响 key 白名单，
     * 仅调用 {@link DictValueValidator#evictCache()} 失效值缓存。</p>
     *
     * @param command 切换状态命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> changeStatus(ChangeDictItemStatusCommand command, String locale) {
        String itemId = command.id().trim();
        Short targetStatus = command.status();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Change dict item status: id={}, targetStatus={}", itemId, targetStatus);

        // 1) 目标状态合法性（仅 1/2，0 走 delete 通道）
        if (!DictItemEntity.STATUS_ACTIVE.equals(targetStatus)
                && !DictItemEntity.STATUS_DISABLED.equals(targetStatus)) {
            logger.error("Invalid dict item status parameter: targetStatus={}", targetStatus);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.dictStatusInvalid());
        }

        return dictItemRepository.findById(DictItemId.of(itemId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict item not found: id={}", itemId);
                    return new DictItemNotFoundException(appMessages.dictItemNotFound());
                })
                .flatMap(entity -> {
                    if (DictItemEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict item already deleted, cannot change status: id={}", itemId);
                        return Uni.createFrom().failure(
                                new DictItemNotFoundException(appMessages.dictItemNotFound()));
                    }
                    // 2) 幂等短路：已是目标状态，直接成功（不写库、不清缓存）
                    if (targetStatus.equals(entity.getStatus())) {
                        logger.info("Dict item already in target status, idempotent return: id={}, status={}",
                                itemId, targetStatus);
                        return Uni.createFrom().voidItem();
                    }
                    // 3) 再启用（2→1）预检 (typeCode, value) 唯一性（跳过自身靠 status=1 过滤实现），
                    //    通过后内联执行切换状态 + 值缓存失效
                    Uni<DictItemEntity> ready = DictItemEntity.STATUS_ACTIVE.equals(targetStatus)
                            ? dictItemRepository.findByTypeAndValue(entity.getTypeCode(), entity.getValue())
                                .onItem().ifNotNull().failWith(() -> {
                                    logger.error("Dict item value already exists, cannot enable: id={}, typeCode={}, value={}",
                                            itemId, entity.getTypeCode(), entity.getValue());
                                    return new DictItemValueDuplicateException(
                                            appMessages.dictItemValueAlreadyExists());
                                })
                                .replaceWith(entity)
                            : Uni.createFrom().item(entity);
                    // 4) 切换状态 + 值缓存失效
                    return ready.flatMap(e -> {
                        DictItemEntity updated = e.changeStatus(targetStatus);
                        return dictItemRepository.update(updated)
                                .call(v -> dictValueValidator.evictCache())
                                .invoke(u -> logger.info("Dict item status changed: id={}, value={}, status={}",
                                        itemId, u.getValue(), targetStatus))
                                .replaceWithVoid();
                    });
                });
    }
}
