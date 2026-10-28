package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagId;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.mapper.SensitiveTagMapper;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

/**
 * 敏感词标签仓储实现
 */
@ApplicationScoped
public class SensitiveTagRepositoryImpl implements SensitiveTagRepository {

    private final SensitiveTagPanacheRepository panacheRepo;
    private final SensitiveTagMapper mapper = Mappers.getMapper(SensitiveTagMapper.class);

    public SensitiveTagRepositoryImpl(SensitiveTagPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<SensitiveTagEntity> findById(SensitiveTagId id) {
        return panacheRepo.findById(id.value())
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<SensitiveTagEntity> findActiveByLabel(String textLabel) {
        return panacheRepo.find("textLabel = ?1 and status != 0", textLabel)
                .firstResult()
                .map(po -> po == null ? null : mapper.toDomain(po));
    }

    /**
     * 查询全部启用标签（status=1）。
     * <p>{@code @WithSession}：调用方为敏感词缓存服务的回源路径（HTTP 提问校验/管理端事务链，
     * 有安全上下文但无 Panache 会话），跨 bean 调用经代理进入，此处显式开只读会话（事务链内
     * 加入既有会话）。</p>
     */
    @Override
    @WithSession
    public Uni<java.util.List<SensitiveTagEntity>> listActive() {
        return panacheRepo.find("status = ?1", SensitiveTagEntity.STATUS_ACTIVE)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<SensitiveTagEntity> persist(SensitiveTagEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<SensitiveTagEntity> update(SensitiveTagEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }
}
