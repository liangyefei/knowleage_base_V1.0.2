package com.hisilicon.knowledge.platform.llm.sensitive.domain.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 敏感词词库仓储接口
 */
public interface SensitiveLexiconRepository {

    /**
     * 批量持久化词库记录
     *
     * @return 实际持久化条数
     */
    Uni<Long> persistAll(List<SensitiveLexiconEntity> entities);

    /**
     * 按 ID 批量删除词库记录（硬删）
     *
     * @return 删除条数
     */
    Uni<Long> deleteByIds(List<String> ids);

    /**
     * 删除某标签下全部词库记录（硬删，标签软删时级联）
     *
     * @return 删除条数
     */
    Uni<Long> deleteByTagId(SensitiveTagId tagId);

    /**
     * 词是否已存在于该标签下（去重校验，跨状态：停用词仍占用唯一性）
     */
    Uni<Boolean> existsByTagIdAndWord(SensitiveTagId tagId, String word);

    /**
     * 查询词在标签下的当前状态（导入用：决定新词入库/跳过/更新状态）
     *
     * @return 词不存在返回 null；存在返回状态（1/2）
     */
    Uni<Short> findStatusByTagIdAndWord(SensitiveTagId tagId, String word);

    /**
     * 按标签+词更新词状态（导入时已有词状态变更）
     *
     * @return 更新条数
     */
    Uni<Long> updateStatusByTagIdAndWord(SensitiveTagId tagId, String word, Short status);

    /**
     * 查询参与匹配的全量词库记录（仅启用标签下且词自身启用的词），供缓存重建
     */
    Uni<List<SensitiveLexiconEntity>> listActiveWords();

    /**
     * 按 ID 批量更新词状态（1=启用 / 2=停用）
     *
     * @return 更新条数
     */
    Uni<Long> updateStatusByIds(List<String> ids, Short status);

    /**
     * 按 ID 查询词库记录（编辑用）
     *
     * @return 词不存在返回 null
     */
    Uni<SensitiveLexiconEntity> findById(SensitiveLexiconId id);

    /**
     * 词是否已存在于该标签下（排除指定记录自身；编辑去重校验，跨状态：停用词仍占用唯一性）
     */
    Uni<Boolean> existsByTagIdAndWordExcluding(SensitiveTagId tagId, String word, SensitiveLexiconId excludeId);

    /**
     * 按 ID 更新词的归属标签、词文本、状态与备注（全量替换，不动创建时间/创建人）
     *
     * @return 更新条数
     */
    Uni<Long> updateTagIdAndWordAndStatusAndRemarks(SensitiveLexiconId id, SensitiveTagId tagId,
                                                    String word, Short status, String remarks);
}
