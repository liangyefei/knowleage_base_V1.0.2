package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleId;
import com.hisilicon.knowledge.platform.system.domain.repository.RoleRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.mapper.RoleMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色仓储实现（{@code roles}）
 */
@ApplicationScoped
public class RoleRepositoryImpl implements RoleRepository {

    private final RolePanacheRepository panacheRepo;
    private final RoleMapper mapper = Mappers.getMapper(RoleMapper.class);

    public RoleRepositoryImpl(RolePanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Optional<RoleEntity>> findById(RoleId id) {
        return panacheRepo.findById(id.value())
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Optional<RoleEntity>> findByRoleKey(String roleKey) {
        return panacheRepo.find("roleKey", roleKey).firstResult()
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<Boolean> existsByRoleKey(String roleKey) {
        return panacheRepo.count("roleKey", roleKey).map(count -> count > 0);
    }

    @Override
    public Uni<List<RoleEntity>> findEnabledByIds(Collection<RoleId> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<String> values = ids.stream().map(RoleId::value).toList();
        Map<String, Object> params = Map.of(
                "ids", values,
                "status", RoleEntity.STATUS_ENABLED);
        return panacheRepo.find("id in :ids and status = :status", params)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<RoleEntity> persist(RoleEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity)).map(po -> entity);
    }

    @Override
    public Uni<RoleEntity> merge(RoleEntity entity) {
        // 实体通过 merge 将新 PO 合并到会话（Hibernate Reactive 不允许 remove/persist 游离 PO 的更新语义）
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<Void> deleteById(RoleId id) {
        return panacheRepo.deleteById(id.value()).replaceWithVoid();
    }
}
