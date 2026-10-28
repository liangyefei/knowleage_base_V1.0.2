package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（冲突记录）。
 */
@ApplicationScoped
public class ConflictRecordPanacheRepository implements PanacheRepositoryBase<ConflictRecordPO, String> {
}
