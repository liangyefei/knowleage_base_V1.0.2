package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordItemPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（冲突证据条目）。
 */
@ApplicationScoped
public class ConflictRecordItemPanacheRepository implements PanacheRepositoryBase<ConflictRecordItemPO, String> {
}
