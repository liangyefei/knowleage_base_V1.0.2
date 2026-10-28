package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ImportBatchPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 导入批次 Panache 仓储
 */
@ApplicationScoped
public class ImportBatchPanacheRepository implements PanacheRepositoryBase<ImportBatchPO, String> {
}
