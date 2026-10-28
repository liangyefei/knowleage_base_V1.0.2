package com.hisilicon.knowledge.platform.llm.sensitive.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.exception.SensitiveTagNotFoundException;
import com.hisilicon.knowledge.platform.llm.sensitive.exception.SensitiveWordNotFoundException;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache.SensitiveWordCacheService;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordCsv;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExcel;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordImportRow;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 敏感词词库命令服务（新增 / 编辑 / 删除 / 状态切换 / 导入）
 * <p>词在标签内唯一（跨状态：停用词仍占用唯一性，只能重新启用）；任何变更后调用
 * {@link SensitiveWordCacheService#evictCache()} 失效缓存并重建内存匹配树。</p>
 */
@ApplicationScoped
public class SensitiveWordCommandService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordCommandService.class);

    /** 文件启用列取值 */
    private static final String ENABLE_TEXT = "启用";
    private static final String DISABLE_TEXT = "停用";

    private final SensitiveTagRepository tagRepository;
    private final SensitiveLexiconRepository lexiconRepository;
    private final SensitiveWordCacheService cacheService;

    public SensitiveWordCommandService(SensitiveTagRepository tagRepository,
                                       SensitiveLexiconRepository lexiconRepository,
                                       SensitiveWordCacheService cacheService) {
        this.tagRepository = tagRepository;
        this.lexiconRepository = lexiconRepository;
        this.cacheService = cacheService;
    }

    /**
     * 新增敏感词：状态合法性校验 → 标签存在性校验 → 词去重（已存在拒绝 A0429）→ 持久化（含创建人/备注）→ 缓存失效
     */
    @WithTransaction
    public Uni<SensitiveLexiconEntity> add(AddSensitiveWordCommand command, String operatorId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String tagId = command.tagId().trim();
        String word = command.word().trim();
        Short status = requireValidWordStatus(command.status(), appMessages);
        String remarks = trimToNull(command.remarks());

        logger.info("Add sensitive word: tagId={}, word={}, status={}, operatorId={}", tagId, word, status, operatorId);

        return requireActiveTag(tagId, appMessages)
                .flatMap(tag -> lexiconRepository.existsByTagIdAndWord(tag.getId(), word)
                        .flatMap(exists -> {
                            if (exists) {
                                logger.error("Sensitive word already exists: tagId={}, word={}", tagId, word);
                                return Uni.createFrom().failure(new BusinessException(
                                        ErrorCodeEnum.USER_ERROR_A0429, appMessages.sensitiveWordDuplicate()));
                            }
                            SensitiveLexiconEntity entity = SensitiveLexiconEntity
                                    .create(tag.getId(), word, status, operatorId, remarks);
                            return lexiconRepository.persistAll(List.of(entity))
                                    .map(n -> entity)
                                    .call(e -> cacheService.evictCache())
                                    .invoke(e -> logger.info("Sensitive word added: id={}, word={}", e.getId().value(), word));
                        }));
    }

    /**
     * 编辑敏感词（全量替换：分类 + 词文本 + 状态 + 备注；不动创建人/创建时间）：
     * 状态合法性校验 → 词存在性 → 目标标签存在且未删除 → 目标标签内词去重（排除自身，跨状态）→ 更新 → 缓存失效
     */
    @WithTransaction
    public Uni<Void> edit(EditSensitiveWordCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String id = command.id().trim();
        String tagId = command.tagId().trim();
        String word = command.word().trim();
        Short status = requireValidWordStatus(command.status(), appMessages);
        String remarks = trimToNull(command.remarks());

        logger.info("Edit sensitive word: id={}, tagId={}, word={}, status={}", id, tagId, word, status);

        return lexiconRepository.findById(SensitiveLexiconId.of(id))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Sensitive word not found: id={}", id);
                    return new SensitiveWordNotFoundException(appMessages.sensitiveWordNotFound());
                })
                .flatMap(current -> requireActiveTag(tagId, appMessages)
                        .flatMap(tag -> lexiconRepository
                                .existsByTagIdAndWordExcluding(tag.getId(), word, current.getId())
                                .flatMap(exists -> {
                                    if (exists) {
                                        logger.error("Sensitive word already exists: tagId={}, word={}", tagId, word);
                                        return Uni.createFrom().failure(new BusinessException(
                                                ErrorCodeEnum.USER_ERROR_A0429, appMessages.sensitiveWordDuplicate()));
                                    }
                                    return lexiconRepository
                                            .updateTagIdAndWordAndStatusAndRemarks(current.getId(), tag.getId(), word, status, remarks)
                                            .flatMap(n -> n > 0
                                                    ? Uni.createFrom().item(n)
                                                    : Uni.createFrom().failure(new SensitiveWordNotFoundException(
                                                            appMessages.sensitiveWordNotFound())))
                                            .call(n -> cacheService.evictCache())
                                            .invoke(n -> logger.info("Sensitive word edited: id={}, tagId={}, word={}, status={}",
                                                    id, tagId, word, status))
                                            .replaceWithVoid();
                                })));
    }

    /**
     * 删除敏感词（硬删）→ 缓存失效
     *
     * @return 删除条数
     */
    @WithTransaction
    public Uni<Long> delete(DeleteSensitiveWordCommand command, String locale) {
        logger.info("Delete sensitive words: count={}", command.ids().size());
        return lexiconRepository.deleteByIds(command.ids())
                .call(n -> n > 0 ? cacheService.evictCache() : Uni.createFrom().voidItem())
                .invoke(n -> logger.info("Sensitive words deleted: {} rows", n));
    }

    /**
     * 批量切换词状态（1↔2）；状态影响匹配范围 → 有变更时缓存失效
     *
     * @return 更新条数
     */
    @WithTransaction
    public Uni<Long> changeStatus(ChangeSensitiveWordStatusCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        Short targetStatus = command.status();

        logger.info("Change sensitive word status: count={}, targetStatus={}", command.ids().size(), targetStatus);

        if (!SensitiveLexiconEntity.STATUS_ENABLED.equals(targetStatus)
                && !SensitiveLexiconEntity.STATUS_DISABLED.equals(targetStatus)) {
            logger.error("Invalid sensitive word status parameter: targetStatus={}", targetStatus);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.sensitiveWordStatusInvalid());
        }

        return lexiconRepository.updateStatusByIds(command.ids(), targetStatus)
                .call(n -> n > 0 ? cacheService.evictCache() : Uni.createFrom().voidItem())
                .invoke(n -> logger.info("Sensitive word statuses changed: {} rows", n));
    }

    /**
     * 导入词库（xlsx / csv，5 列同构：标签名称|标签标识|标签是否启用|敏感词|词是否启用）：
     * 整文件校验（任一行无效整体拒绝）→ 按标签名分组（同名合并，启用列以最后一次出现为准）→
     * 标签复用/新建（状态按文件更新）→ 词 新增/跳过/状态更新 → 有变更时统一缓存失效
     *
     * @param fileName 上传文件名（决定解析器）
     * @param content  文件字节
     * @return 导入结果（总行数/新增词数/跳过词数/新建标签数/状态更新词数）
     */
    @WithTransaction
    public Uni<ImportResult> importWords(String fileName, byte[] content, String operatorId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        List<SensitiveWordImportRow> rows = parse(fileName, content, appMessages);
        validate(rows, appMessages);
        if (rows.isEmpty()) {
            logger.error("No valid data in word import: fileName={}", fileName);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.sensitiveImportInvalid());
        }

        Map<String, TagGroup> groups = groupByTag(rows, appMessages);
        logger.info("Word import started: fileName={}, tags={}, rows={}, operatorId={}",
                fileName, groups.size(), rows.size(), operatorId);

        Counters counters = new Counters();
        return processTagGroups(groups.entrySet().iterator(), counters, operatorId, appMessages)
                .flatMap(v -> counters.dirty()
                        ? cacheService.evictCache()
                        : Uni.createFrom().voidItem())
                .map(v -> {
                    ImportResult result = new ImportResult(rows.size(), counters.imported, counters.skipped,
                            counters.createdTags, counters.statusUpdated);
                    logger.info("Word import completed: total={}, imported={}, skipped={}, createdTags={}, statusUpdated={}",
                            result.total(), result.imported(), result.skipped(),
                            result.createdTags(), result.statusUpdated());
                    return result;
                });
    }

    // ========== 导入：解析与校验 ==========

    /**
     * 校验手动新增/编辑的状态取值：仅允许 1（启用）/ 2（停用），非法抛 A0420
     */
    private Short requireValidWordStatus(Short status, AppMessages appMessages) {
        if (!SensitiveLexiconEntity.STATUS_ENABLED.equals(status)
                && !SensitiveLexiconEntity.STATUS_DISABLED.equals(status)) {
            logger.error("Invalid sensitive word status parameter: status={}", status);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.sensitiveWordStatusInvalid());
        }
        return status;
    }

    /**
     * 校验标签存在且未删除（手动新增词用）
     */
    private Uni<SensitiveTagEntity> requireActiveTag(String tagId, AppMessages appMessages) {
        return tagRepository.findById(SensitiveTagId.of(tagId))
                .onItem().ifNull().failWith(() ->
                        new SensitiveTagNotFoundException(appMessages.sensitiveTagNotFound()))
                .flatMap(entity -> SensitiveTagEntity.STATUS_DELETED.equals(entity.getStatus())
                        ? Uni.createFrom().failure(new SensitiveTagNotFoundException(appMessages.sensitiveTagNotFound()))
                        : Uni.createFrom().item(entity));
    }

    /**
     * 按文件后缀分支解析（.xlsx → POI；.csv → commons-csv；其余拒绝）
     */
    private List<SensitiveWordImportRow> parse(String fileName, byte[] content, AppMessages appMessages) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx")) {
                return SensitiveWordExcel.parseRows(content);
            }
            if (name.endsWith(".csv")) {
                return SensitiveWordCsv.parseRows(content);
            }
            logger.error("Unsupported word import file format: fileName={}", fileName);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.sensitiveImportInvalid());
        } catch (IOException e) {
            logger.error("Failed to parse word import file: fileName={}", fileName, e);
            throw new BusinessException(ErrorCodeEnum.ERROR_EXCEL_FORMAT, appMessages.sensitiveImportInvalid());
        }
    }

    /**
     * 整文件校验：必填（标签名称/敏感词）、长度上限（词/标签名 ≤255，标识 ≤64）、
     * 启用列取值（启用/停用，空默认启用）；任一行无效整体拒绝
     */
    private void validate(List<SensitiveWordImportRow> rows, AppMessages appMessages) {
        for (SensitiveWordImportRow row : rows) {
            if (row.textLabel() == null || row.textLabel().isBlank() || row.textLabel().length() > 255) {
                throw invalidImport(appMessages, "标签名称", row);
            }
            if (row.identification() != null && row.identification().length() > 64) {
                throw invalidImport(appMessages, "标签标识", row);
            }
            if (row.word() == null || row.word().isBlank() || row.word().length() > 255) {
                throw invalidImport(appMessages, "敏感词", row);
            }
            parseEnable(row.tagEnableRaw(), appMessages);
            parseEnable(row.wordEnableRaw(), appMessages);
        }
    }

    private BusinessException invalidImport(AppMessages appMessages, String column, SensitiveWordImportRow row) {
        logger.error("Invalid row in word import: column={}, textLabel={}, word={}", column, row.textLabel(), row.word());
        return new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.sensitiveImportInvalid());
    }

    /**
     * 去除首尾空白，空串归一为 null（备注入库口径）
     */
    private String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 启用列取值解析：空默认启用；仅接受"启用"/"停用"
     */
    private Short parseEnable(String raw, AppMessages appMessages) {
        if (raw == null || raw.isBlank() || ENABLE_TEXT.equals(raw)) {
            return SensitiveTagEntity.STATUS_ACTIVE;
        }
        if (DISABLE_TEXT.equals(raw)) {
            return SensitiveTagEntity.STATUS_DISABLED;
        }
        throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.sensitiveImportInvalid());
    }

    // ========== 导入：分组与处理 ==========

    /**
     * 单标签分组（同名标签多行合并；标签标识/标签状态以最后一次出现为准，标识仅取非空值）
     */
    private static final class TagGroup {
        private String identification;
        private Short tagStatus;
        private final LinkedHashMap<String, Short> words = new LinkedHashMap<>();
    }

    private Map<String, TagGroup> groupByTag(List<SensitiveWordImportRow> rows, AppMessages appMessages) {
        Map<String, TagGroup> groups = new LinkedHashMap<>();
        for (SensitiveWordImportRow row : rows) {
            String textLabel = row.textLabel().trim();
            Short tagStatus = parseEnable(row.tagEnableRaw(), appMessages);
            String identification = row.identification() == null || row.identification().isBlank()
                    ? null : row.identification().trim();

            TagGroup group = groups.get(textLabel);
            if (group == null) {
                group = new TagGroup();
                groups.put(textLabel, group);
            }
            if (identification != null) {
                group.identification = identification;
            }
            group.tagStatus = tagStatus;
            group.words.put(row.word().trim(), parseEnable(row.wordEnableRaw(), appMessages));
        }
        return groups;
    }

    /**
     * 串行处理标签分组（避免并发占用 session）
     */
    private Uni<Void> processTagGroups(Iterator<Map.Entry<String, TagGroup>> iterator, Counters counters,
                                       String operatorId, AppMessages appMessages) {
        if (!iterator.hasNext()) {
            return Uni.createFrom().voidItem();
        }
        Map.Entry<String, TagGroup> entry = iterator.next();
        String textLabel = entry.getKey();
        TagGroup group = entry.getValue();
        return tagRepository.findActiveByLabel(textLabel)
                .flatMap(existing -> resolveTag(existing, textLabel, group, counters))
                .flatMap(tag -> processWords(tag.getId(),
                        new ArrayList<>(group.words.entrySet()).iterator(), counters, operatorId))
                .chain(() -> processTagGroups(iterator, counters, operatorId, appMessages));
    }

    /**
     * 标签复用（状态不同则更新）或新建（标识空默认取标签名称，状态按文件值）
     */
    private Uni<SensitiveTagEntity> resolveTag(SensitiveTagEntity existing, String textLabel,
                                               TagGroup group, Counters counters) {
        if (existing != null) {
            if (group.tagStatus.equals(existing.getStatus())) {
                return Uni.createFrom().item(existing);
            }
            counters.tagStatusUpdated = true;
            return tagRepository.update(existing.changeStatus(group.tagStatus));
        }
        counters.createdTags++;
        String identification = group.identification != null ? group.identification : textLabel;
        return tagRepository.persist(
                SensitiveTagEntity.create(textLabel, identification, null, group.tagStatus));
    }

    /**
     * 串行处理单标签下全部词：不存在→按文件状态入库；状态相同→跳过；状态不同→更新状态
     */
    private Uni<Void> processWords(SensitiveTagId tagId, Iterator<Map.Entry<String, Short>> iterator,
                                   Counters counters, String operatorId) {
        if (!iterator.hasNext()) {
            return Uni.createFrom().voidItem();
        }
        Map.Entry<String, Short> entry = iterator.next();
        String word = entry.getKey();
        Short target = entry.getValue();
        return lexiconRepository.findStatusByTagIdAndWord(tagId, word)
                .flatMap(current -> {
                    if (current == null) {
                        counters.imported++;
                        return lexiconRepository
                                .persistAll(List.of(SensitiveLexiconEntity
                                        .create(tagId, word, target, operatorId, null)))
                                .replaceWithVoid();
                    }
                    if (target.equals(current)) {
                        counters.skipped++;
                        return Uni.createFrom().voidItem();
                    }
                    counters.statusUpdated++;
                    return lexiconRepository.updateStatusByTagIdAndWord(tagId, word, target)
                            .replaceWithVoid();
                })
                .chain(() -> processWords(tagId, iterator, counters, operatorId));
    }

    /**
     * 导入计数器（dirty：是否有任何影响匹配范围的变更，决定缓存失效）
     */
    private static final class Counters {
        private int imported;
        private int skipped;
        private int createdTags;
        private int statusUpdated;
        private boolean tagStatusUpdated;

        boolean dirty() {
            return imported > 0 || statusUpdated > 0 || createdTags > 0 || tagStatusUpdated;
        }
    }

    /**
     * 词库导入结果
     *
     * @param total         文件内有效行数
     * @param imported      实际新增词数
     * @param skipped       跳过词数（库内已存在且状态相同）
     * @param createdTags   新建标签数
     * @param statusUpdated 状态被更新的已有词数
     */
    public record ImportResult(int total, int imported, int skipped, int createdTags, int statusUpdated) {
    }
}
