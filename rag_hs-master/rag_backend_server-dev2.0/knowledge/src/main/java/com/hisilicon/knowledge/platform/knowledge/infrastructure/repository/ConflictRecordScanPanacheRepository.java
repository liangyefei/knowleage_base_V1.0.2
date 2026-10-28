package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordScanPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（冲突-批次多对多）。
 */
@ApplicationScoped
public class ConflictRecordScanPanacheRepository implements PanacheRepositoryBase<ConflictRecordScanPO, String> {
}
