package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictSettingsRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictSettingsPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * 冲突检测设置仓储实现。
 */
@ApplicationScoped
public class ConflictSettingsRepositoryImpl implements ConflictSettingsRepository {

    private final ConflictSettingsPanacheRepository panacheRepo;

    public ConflictSettingsRepositoryImpl(ConflictSettingsPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Optional<ConflictSettings>> findGlobal() {
        return panacheRepo.findById(ConflictSettings.GLOBAL_ID)
                .map(po -> Optional.ofNullable(po).map(this::toDomain));
    }

    @Override
    public Uni<Optional<ConflictSettings>> findByKbId(String kbId) {
        return panacheRepo.findByKbId(kbId)
                .map(po -> Optional.ofNullable(po).map(this::toDomain));
    }

    @Override
    public Uni<ConflictSettings> save(ConflictSettings entity) {
        ConflictSettingsPO po = toPO(entity);
        // merge 同时处理新增和更新（Hibernate Reactive 无 persistOrUpdate）
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(po))
                .map(p -> entity);
    }

    private ConflictSettings toDomain(ConflictSettingsPO po) {
        return ConflictSettings.builder()
                .id(po.id).kbId(po.kbId)
                .autoEnabled(Boolean.TRUE.equals(po.autoEnabled))
                .updatedBy(po.updatedBy).updatedAt(po.updatedAt)
                .build();
    }

    private ConflictSettingsPO toPO(ConflictSettings entity) {
        ConflictSettingsPO po = new ConflictSettingsPO();
        po.id = entity.getId();
        po.kbId = entity.getKbId();
        po.autoEnabled = entity.isAutoEnabled();
        po.updatedBy = entity.getUpdatedBy();
        po.updatedAt = entity.getUpdatedAt();
        return po;
    }
}
