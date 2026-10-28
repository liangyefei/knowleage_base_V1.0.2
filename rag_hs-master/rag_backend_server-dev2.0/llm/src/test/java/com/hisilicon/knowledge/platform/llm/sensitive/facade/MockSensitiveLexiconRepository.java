package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveLexiconRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手动 Mock — 替换 {@link SensitiveLexiconRepositoryImpl}
 * <p>词状态内存口径（word → status）支撑导入 新增/跳过/状态更新 分支。</p>
 */
public class MockSensitiveLexiconRepository extends SensitiveLexiconRepositoryImpl {

    private static volatile boolean wordExists;
    /** 累积全部 persistAll 的词（reset 清空） */
    private static final List<SensitiveLexiconEntity> persistedEntities = new ArrayList<>();
    /** word → status（findStatusByTagIdAndWord 内存口径，测试内词跨标签唯一） */
    private static final Map<String, Short> wordStatuses = new HashMap<>();
    private static volatile Long deleteCount = 0L;
    private static volatile List<SensitiveLexiconEntity> activeWords = List.of();
    private static volatile Long updateStatusCount = 0L;
    private static volatile List<String> updateStatusIds = List.of();
    private static volatile Short updateStatusTarget;
    private static volatile String updatedStatusWord;
    private static volatile Short updatedStatusValue;
    /** id → 词（findById 内存口径，编辑用） */
    private static final Map<String, SensitiveLexiconEntity> entitiesById = new HashMap<>();
    /** existsByTagIdAndWordExcluding 结果（null 视为 false） */
    private static volatile Boolean existsExcludingResult;
    private static volatile SensitiveTagId excludedTagId;
    private static volatile String excludedWord;
    private static volatile SensitiveLexiconId excludedSelfId;
    private static volatile SensitiveLexiconId updatedWordId;
    private static volatile SensitiveTagId updatedWordTagId;
    private static volatile String updatedWordText;
    private static volatile Short updatedWordStatus;
    private static volatile String updatedWordRemarks;

    public MockSensitiveLexiconRepository() {
        super(null);
    }

    public static void setWordExists(boolean exists) { wordExists = exists; }

    public static void setWordStatus(String word, Short status) { wordStatuses.put(word, status); }

    public static void setDeleteCount(long count) { deleteCount = count; }

    public static void setActiveWords(List<SensitiveLexiconEntity> words) { activeWords = words; }

    public static void setUpdateStatusCount(long count) { updateStatusCount = count; }

    public static List<SensitiveLexiconEntity> getPersistedEntities() { return persistedEntities; }

    public static List<String> getUpdateStatusIds() { return updateStatusIds; }

    public static Short getUpdateStatusTarget() { return updateStatusTarget; }

    public static String getUpdatedStatusWord() { return updatedStatusWord; }

    public static Short getUpdatedStatusValue() { return updatedStatusValue; }

    public static void putEntity(SensitiveLexiconEntity entity) {
        entitiesById.put(entity.getId().value(), entity);
    }

    public static void setExistsExcludingResult(Boolean result) { existsExcludingResult = result; }

    public static SensitiveTagId getExcludedTagId() { return excludedTagId; }

    public static String getExcludedWord() { return excludedWord; }

    public static SensitiveLexiconId getUpdatedWordId() { return updatedWordId; }

    public static SensitiveTagId getUpdatedWordTagId() { return updatedWordTagId; }

    public static String getUpdatedWordText() { return updatedWordText; }

    public static Short getUpdatedWordStatus() { return updatedWordStatus; }

    public static String getUpdatedWordRemarks() { return updatedWordRemarks; }

    public static void reset() {
        wordExists = false;
        persistedEntities.clear();
        wordStatuses.clear();
        deleteCount = 0L;
        activeWords = List.of();
        updateStatusCount = 0L;
        updateStatusIds = List.of();
        updateStatusTarget = null;
        updatedStatusWord = null;
        updatedStatusValue = null;
        entitiesById.clear();
        existsExcludingResult = null;
        excludedTagId = null;
        excludedWord = null;
        excludedSelfId = null;
        updatedWordId = null;
        updatedWordTagId = null;
        updatedWordText = null;
        updatedWordStatus = null;
        updatedWordRemarks = null;
    }

    @Override
    public Uni<Long> persistAll(List<SensitiveLexiconEntity> entities) {
        persistedEntities.addAll(entities);
        return Uni.createFrom().item((long) entities.size());
    }

    @Override
    public Uni<Long> deleteByIds(List<String> ids) {
        return Uni.createFrom().item(deleteCount);
    }

    @Override
    public Uni<Long> deleteByTagId(SensitiveTagId tagId) {
        return Uni.createFrom().item(deleteCount);
    }

    @Override
    public Uni<Boolean> existsByTagIdAndWord(SensitiveTagId tagId, String word) {
        return Uni.createFrom().item(wordExists);
    }

    @Override
    public Uni<Short> findStatusByTagIdAndWord(SensitiveTagId tagId, String word) {
        return Uni.createFrom().item(wordStatuses.get(word));
    }

    @Override
    public Uni<Long> updateStatusByTagIdAndWord(SensitiveTagId tagId, String word, Short status) {
        updatedStatusWord = word;
        updatedStatusValue = status;
        wordStatuses.put(word, status);
        return Uni.createFrom().item(1L);
    }

    @Override
    public Uni<Long> updateStatusByIds(List<String> ids, Short status) {
        updateStatusIds = List.copyOf(ids);
        updateStatusTarget = status;
        return Uni.createFrom().item(updateStatusCount);
    }

    @Override
    public Uni<List<SensitiveLexiconEntity>> listActiveWords() {
        return Uni.createFrom().item(activeWords);
    }

    @Override
    public Uni<SensitiveLexiconEntity> findById(SensitiveLexiconId id) {
        return Uni.createFrom().item(entitiesById.get(id.value()));
    }

    @Override
    public Uni<Boolean> existsByTagIdAndWordExcluding(SensitiveTagId tagId, String word, SensitiveLexiconId excludeId) {
        excludedTagId = tagId;
        excludedWord = word;
        excludedSelfId = excludeId;
        return Uni.createFrom().item(Boolean.TRUE.equals(existsExcludingResult));
    }

    @Override
    public Uni<Long> updateTagIdAndWordAndStatusAndRemarks(SensitiveLexiconId id, SensitiveTagId tagId,
                                                           String word, Short status, String remarks) {
        updatedWordId = id;
        updatedWordTagId = tagId;
        updatedWordText = word;
        updatedWordStatus = status;
        updatedWordRemarks = remarks;
        return Uni.createFrom().item(1L);
    }
}
