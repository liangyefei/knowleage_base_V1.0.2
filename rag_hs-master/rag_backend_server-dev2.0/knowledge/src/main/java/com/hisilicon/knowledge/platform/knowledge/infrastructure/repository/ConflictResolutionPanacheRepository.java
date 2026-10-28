package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictResolutionPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（冲突处理记录）。
 */
@ApplicationScoped
public class ConflictResolutionPanacheRepository implements PanacheRepositoryBase<ConflictResolutionPO, String> {
}
