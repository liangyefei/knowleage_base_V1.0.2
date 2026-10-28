package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeId;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictItemRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.repository.DictTypeRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.exception.DictTypeCodeDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.dict.exception.DictTypeNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 字典类型命令服务（创建 / 编辑 / 删除 / 切换启用禁用）
 * <p>typeCode 创建后不可修改（预置与自定义类型统一规则，标识符不可变）；
 * 预置类型（isPreset=true）不可删除。
 * 类型变更（create/edit/delete）后调用 {@link DictMetaKeyCacheService#evictCache()}
 * 使元数据 key 白名单缓存失效（下次读取自动回源重建）。</p>
 */
@ApplicationScoped
public class DictTypeCommandService {

    private static final Logger logger = LoggerFactory.getLogger(DictTypeCommandService.class);

    /** typeCode 命名规范：小写字母开头的字母/数字/下划线，长度不超过 50 */
    private static final Pattern TYPE_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");

    private final DictTypeRepository dictTypeRepository;
    private final DictItemRepository dictItemRepository;
    private final DictMetaKeyCacheService dictMetaKeyCacheService;
    private final DictReferenceChecker dictReferenceChecker;

    public DictTypeCommandService(DictTypeRepository dictTypeRepository,
                                  DictItemRepository dictItemRepository,
                                  DictMetaKeyCacheService dictMetaKeyCacheService,
                                  DictReferenceChecker dictReferenceChecker) {
        this.dictTypeRepository = dictTypeRepository;
        this.dictItemRepository = dictItemRepository;
        this.dictMetaKeyCacheService = dictMetaKeyCacheService;
        this.dictReferenceChecker = dictReferenceChecker;
    }

    /**
     * 创建字典类型
     * <p>校验 typeCode 格式（小写下划线）与唯一性（status=1）→ 持久化 → 白名单缓存失效。</p>
     *
     * @param command 创建命令
     * @param locale  语言环境
     * @return 新创建的字典类型实体（含 ID）
     */
    @WithTransaction
    public Uni<DictTypeEntity> create(CreateDictTypeCommand command, String locale) {
        String typeCode = command.typeCode().trim();
        String typeName = command.typeName().trim();
        String controlledMetaKeys = normalizeControlledMetaKeys(command.controlledMetaKeys());
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Create dict type: typeCode={}, typeName={}", typeCode, typeName);

        validateTypeCode(typeCode, appMessages);

        return dictTypeRepository.findByCode(typeCode)
                .onItem().ifNotNull().failWith(() -> {
                    logger.error("Dict type code already exists: typeCode={}", typeCode);
                    return new DictTypeCodeDuplicateException(appMessages.dictTypeCodeAlreadyExists());
                })
                .flatMap(ignored -> {
                    DictTypeEntity entity = DictTypeEntity.create(typeCode, typeName, controlledMetaKeys);
                    return dictTypeRepository.persist(entity)
                            .call(v -> dictMetaKeyCacheService.evictCache())
                            .invoke(e -> logger.info("Dict type created: id={}, typeCode={}",
                                    e.getId().value(), typeCode))
                            .map(e -> e);
                });
    }

    /**
     * 编辑字典类型（全量替换）
     * <p>typeCode 创建后不可修改，编辑仅替换 typeName/controlledMetaKeys；
     * controlledMetaKeys 为 null/空 = 清空管控。</p>
     *
     * @param command 编辑命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> edit(EditDictTypeCommand command, String locale) {
        String typeId = command.id().trim();
        String newTypeName = command.typeName().trim();
        String newControlledMetaKeys = normalizeControlledMetaKeys(command.controlledMetaKeys());
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Edit dict type: id={}, newTypeName={}", typeId, newTypeName);

        return dictTypeRepository.findById(DictTypeId.of(typeId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict type not found: id={}", typeId);
                    return new DictTypeNotFoundException(appMessages.dictTypeNotFound());
                })
                .flatMap(entity -> {
                    if (DictTypeEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict type already deleted, cannot edit: id={}", typeId);
                        return Uni.createFrom().failure(
                                new DictTypeNotFoundException(appMessages.dictTypeNotFound()));
                    }
                    // 全量替换更新（typeCode 保持不变）+ 白名单缓存失效
                    DictTypeEntity updated = entity.update(newTypeName, newControlledMetaKeys);
                    return dictTypeRepository.update(updated)
                            .call(v -> dictMetaKeyCacheService.evictCache())
                            .invoke(e -> logger.info("Dict type edited: id={}, typeCode={}",
                                    typeId, e.getTypeCode()))
                            .replaceWithVoid();
                });
    }

    /**
     * 删除字典类型（软删，级联软删该类型下全部条目）
     * <p>预置类型（isPreset=true，系统初始化种子数据）不可删除，拒绝并返回 A0420。
     * 删除前校验该类型下任一未删除条目值是否被业务数据引用（元数据表 meta_value /
     * version 专用列）：有关联引用则拒绝（A0420），无引用方级联软删。引用检查委托
     * {@link DictReferenceChecker}（knowledge 侧实现，跨模块 SPI）。
     * 类型变更（create/edit/delete）后调用 {@link DictMetaKeyCacheService#evictCache()}
     * 使元数据 key 白名单缓存失效（下次读取自动回源重建）。</p>
     *
     * @param command 删除命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> delete(DeleteDictTypeCommand command, String locale) {
        String typeId = command.id().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Delete dict type: id={}", typeId);

        return dictTypeRepository.findById(DictTypeId.of(typeId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict type not found: id={}", typeId);
                    return new DictTypeNotFoundException(appMessages.dictTypeNotFound());
                })
                .flatMap(entity -> {
                    if (DictTypeEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict type already deleted: id={}", typeId);
                        return Uni.createFrom().failure(
                                new DictTypeNotFoundException(appMessages.dictTypeNotFound()));
                    }
                    if (Boolean.TRUE.equals(entity.getIsPreset())) {
                        logger.error("Preset dict type cannot be deleted: id={}, typeCode={}",
                                typeId, entity.getTypeCode());
                        return Uni.createFrom().failure(
                                new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                                        appMessages.dictPresetTypeCannotDelete()));
                    }
                    // 删除前引用检查：被业务数据引用则拒绝（fail-closed：检查失败亦拒绝）
                    return dictReferenceChecker.isTypeReferenced(entity.getTypeCode())
                            .flatMap(referenced -> {
                                if (Boolean.TRUE.equals(referenced)) {
                                    logger.warn("Dict type referenced by business data, deletion rejected: id={}, typeCode={}",
                                            typeId, entity.getTypeCode());
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.USER_ERROR_A0420,
                                            appMessages.dictTypeInUseCannotDelete(entity.getTypeCode())));
                                }
                                // 级联软删条目 + 软删类型 + 白名单缓存失效
                                return dictItemRepository.softDeleteByTypeCode(entity.getTypeCode())
                                        .flatMap(n -> dictTypeRepository.update(entity.delete()))
                                        .call(v -> dictMetaKeyCacheService.evictCache())
                                        .invoke(e -> logger.info("Dict type deleted: id={}, typeCode={}",
                                                typeId, e.getTypeCode()))
                                        .replaceWithVoid();
                            });
                });
    }

    /**
     * 切换字典类型启用/禁用状态（1↔2）
     * <p>仅在 {@link DictTypeEntity#STATUS_ACTIVE} 与 {@link DictTypeEntity#STATUS_DISABLED}
     * 间切换；软删除（0）视同不存在（A0430）。再启用（2→1）前预检 typeCode 唯一性——
     * 若禁用期间另建了同名活跃类型，拒绝（A0429）。类型状态变更影响 key 白名单，
     * 故调用 {@link DictMetaKeyCacheService#evictCache()} 失效 key 白名单 + 值缓存。</p>
     *
     * @param command 切换状态命令
     * @param locale  语言环境
     * @return Uni&lt;Void&gt;
     */
    @WithTransaction
    public Uni<Void> changeStatus(ChangeDictTypeStatusCommand command, String locale) {
        String typeId = command.id().trim();
        Short targetStatus = command.status();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        logger.info("Change dict type status: id={}, targetStatus={}", typeId, targetStatus);

        // 1) 目标状态合法性（仅 1/2，0 走 delete 通道）
        if (!DictTypeEntity.STATUS_ACTIVE.equals(targetStatus)
                && !DictTypeEntity.STATUS_DISABLED.equals(targetStatus)) {
            logger.error("Invalid dict type status parameter: targetStatus={}", targetStatus);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.dictStatusInvalid());
        }

        return dictTypeRepository.findById(DictTypeId.of(typeId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Dict type not found: id={}", typeId);
                    return new DictTypeNotFoundException(appMessages.dictTypeNotFound());
                })
                .flatMap(entity -> {
                    if (DictTypeEntity.STATUS_DELETED.equals(entity.getStatus())) {
                        logger.error("Dict type already deleted, cannot change status: id={}", typeId);
                        return Uni.createFrom().failure(
                                new DictTypeNotFoundException(appMessages.dictTypeNotFound()));
                    }
                    // 2) 幂等短路：已是目标状态，直接成功（不写库、不清缓存）
                    if (targetStatus.equals(entity.getStatus())) {
                        logger.info("Dict type already in target status, idempotent return: id={}, status={}",
                                typeId, targetStatus);
                        return Uni.createFrom().voidItem();
                    }
                    // 3) 再启用（2→1）预检 typeCode 唯一性（跳过自身靠 status=1 过滤实现），
                    //    通过后内联执行切换状态 + 缓存失效
                    Uni<DictTypeEntity> ready = DictTypeEntity.STATUS_ACTIVE.equals(targetStatus)
                            ? dictTypeRepository.findByCode(entity.getTypeCode())
                                .onItem().ifNotNull().failWith(() -> {
                                    logger.error("Dict type code already exists, cannot enable: id={}, typeCode={}",
                                            typeId, entity.getTypeCode());
                                    return new DictTypeCodeDuplicateException(
                                            appMessages.dictTypeCodeAlreadyExists());
                                })
                                .replaceWith(entity)
                            : Uni.createFrom().item(entity);
                    // 4) 切换状态 + key 白名单缓存失效
                    return ready.flatMap(e -> {
                        DictTypeEntity updated = e.changeStatus(targetStatus);
                        return dictTypeRepository.update(updated)
                                .call(v -> dictMetaKeyCacheService.evictCache())
                                .invoke(u -> logger.info("Dict type status changed: id={}, typeCode={}, status={}",
                                        typeId, u.getTypeCode(), targetStatus))
                                .replaceWithVoid();
                    });
                });
    }

    // ========== 内部校验 ==========

    /**
     * typeCode 格式校验（内容级，A0420 + 具体消息）
     */
    private void validateTypeCode(String typeCode, AppMessages appMessages) {
        if (!TYPE_CODE_PATTERN.matcher(typeCode).matches()) {
            logger.error("Invalid dict type code format: typeCode={}", typeCode);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420,
                    appMessages.dictTypeCodeInvalid());
        }
    }

    /**
     * controlledMetaKeys 归一化：null/空 → null；否则 trim 后返回
     * <p>单个 key 的格式校验由元数据写入端（metadata/set 白名单校验）负责；
     * 此处仅做归一化，不校验 key 本身（字典类型只是登记声明）。</p>
     */
    private String normalizeControlledMetaKeys(String controlledMetaKeys) {
        if (controlledMetaKeys == null || controlledMetaKeys.isBlank()) {
            return null;
        }
        return controlledMetaKeys.trim();
    }
}
