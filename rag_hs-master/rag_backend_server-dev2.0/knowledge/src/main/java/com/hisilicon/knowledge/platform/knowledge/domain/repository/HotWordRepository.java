package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordId;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * 热门词仓储接口（领域层）
 */
public interface HotWordRepository {

    Uni<Optional<HotWordEntity>> findById(HotWordId id);

    Uni<Boolean> existsByKbIdAndContent(String kbId, String content);

    Uni<Long> countEnabledByKbId(String kbId);

    Uni<HotWordEntity> persist(HotWordEntity entity);

    Uni<HotWordEntity> merge(HotWordEntity entity);

    Uni<Void> delete(HotWordEntity entity);
}
