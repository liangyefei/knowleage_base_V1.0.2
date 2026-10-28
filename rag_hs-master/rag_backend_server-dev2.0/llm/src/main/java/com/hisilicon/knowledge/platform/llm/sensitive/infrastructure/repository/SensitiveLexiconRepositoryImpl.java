package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.mapper.SensitiveLexiconMapper;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveLexiconPO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词词库仓储实现
 */
@ApplicationScoped
public class SensitiveLexiconRepositoryImpl implements SensitiveLexiconRepository {

    private final SensitiveLexiconPanacheRepository panacheRepo;
    private final SensitiveLexiconMapper mapper = Mappers.getMapper(SensitiveLexiconMapper.class);

    public SensitiveLexiconRepositoryImpl(SensitiveLexiconPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Long> persistAll(List<SensitiveLexiconEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        List<SensitiveLexiconPO> pos = entities.stream().map(mapper::toPO).toList();
        return panacheRepo.persist(pos).map(p -> (long) pos.size());
    }

    @Override
    public Uni<Long> deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return panacheRepo.delete("id in ?1", ids);
    }

    @Override
    public Uni<Long> deleteByTagId(SensitiveTagId tagId) {
        return panacheRepo.delete("tagId = ?1", tagId.value());
    }

    @Override
    public Uni<Boolean> existsByTagIdAndWord(SensitiveTagId tagId, String word) {
        return panacheRepo.count("tagId = :tagId and word = :word",
                        Map.of("tagId", tagId.value(), "word", word))
                .map(count -> count > 0);
    }

    @Override
    public Uni<Short> findStatusByTagIdAndWord(SensitiveTagId tagId, String word) {
        return panacheRepo.find("tagId = :tagId and word = :word",
                        Map.of("tagId", tagId.value(), "word", word))
                .firstResult()
                .map(po -> po == null ? null : po.getStatus());
    }

    @Override
    public Uni<Long> updateStatusByTagIdAndWord(SensitiveTagId tagId, String word, Short status) {
        return panacheRepo.update("status = :status where tagId = :tagId and word = :word",
                        Map.of("status", status, "tagId", tagId.value(), "word", word))
                .map(Integer::longValue);
    }

    /**
     * 查询参与匹配的全量词库记录（仅"启用标签（status=1）且词自身启用（status=1）"的词）。
     * <p>{@code @WithSession} 必须落在本方法：调用方是敏感词缓存服务的回源路径（HTTP 提问
     * 校验/管理端事务链，有安全上下文但无 Panache 会话），跨 bean 调用经代理进入，此处显式
     * 开只读会话（事务链内加入既有会话；同 knowledge 模块 ChatDocsSourceResolverImpl 先例，
     * 本类内自调用会绕过注解，故不放缓存侧）。</p>
     */
    @Override
    @WithSession
    public Uni<List<SensitiveLexiconEntity>> listActiveWords() {
        // 仅"启用标签（status=1）且词自身启用（status=1）"的词参与匹配
        return panacheRepo.find(
                        "tagId in (select t.id from SensitiveTagPO t where t.status = 1) and status = 1")
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Long> updateStatusByIds(List<String> ids, Short status) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return panacheRepo.update("status = ?1 where id in ?2", status, ids)
                .map(Integer::longValue);
    }

    @Override
    public Uni<SensitiveLexiconEntity> findById(SensitiveLexiconId id) {
        return panacheRepo.findById(id.value())
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<Boolean> existsByTagIdAndWordExcluding(SensitiveTagId tagId, String word, SensitiveLexiconId excludeId) {
        return panacheRepo.count("tagId = :tagId and word = :word and id <> :id",
                        Map.of("tagId", tagId.value(), "word", word, "id", excludeId.value()))
                .map(count -> count > 0);
    }

    @Override
    public Uni<Long> updateTagIdAndWordAndStatusAndRemarks(SensitiveLexiconId id, SensitiveTagId tagId,
                                                           String word, Short status, String remarks) {
        // remarks 可为 null（清空备注），Map.of 不接受 null，故用允许 null 的 HashMap
        Map<String, Object> params = new HashMap<>();
        params.put("tagId", tagId.value());
        params.put("word", word);
        params.put("status", status);
        params.put("remarks", remarks);
        params.put("id", id.value());
        return panacheRepo.update("tagId = :tagId, word = :word, status = :status, remarks = :remarks where id = :id",
                        params)
                .map(Integer::longValue);
    }
}
