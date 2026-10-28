package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.HotWordRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.HotWordMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.Optional;

/**
 * 热门词仓储实现
 */
@ApplicationScoped
public class HotWordRepositoryImpl implements HotWordRepository {

    private final HotWordPanacheRepository panacheRepo;
    private final HotWordMapper mapper = Mappers.getMapper(HotWordMapper.class);

    public HotWordRepositoryImpl(HotWordPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Optional<HotWordEntity>> findById(HotWordId id) {
        return panacheRepo.findById(id.value())
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Boolean> existsByKbIdAndContent(String kbId, String content) {
        return panacheRepo.count("kbId = ?1 and content = ?2", kbId, content)
                .map(count -> count > 0);
    }

    @Override
    public Uni<Long> countEnabledByKbId(String kbId) {
        return panacheRepo.count("kbId = ?1 and status = ?2", kbId, HotWordEntity.STATUS_ENABLED);
    }

    @Override
    public Uni<HotWordEntity> persist(HotWordEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<HotWordEntity> merge(HotWordEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<Void> delete(HotWordEntity entity) {
        // 按 id 删除：Hibernate Reactive remove() 要求受管实体，不能传新建的游离 PO
        return panacheRepo.deleteById(entity.getId().value())
                .replaceWithVoid();
    }
}
