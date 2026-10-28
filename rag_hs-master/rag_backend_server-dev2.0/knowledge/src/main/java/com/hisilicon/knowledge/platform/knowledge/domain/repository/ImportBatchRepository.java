package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 导入批次仓储接口（import_batches 表）
 */
public interface ImportBatchRepository {

    /** 按 ID 查询批次 */
    Uni<ImportBatch> findById(ImportBatchId id);

    /** 持久化新批次 */
    Uni<ImportBatch> persist(ImportBatch entity);

    /** 更新批次（merge 持久化，用于 totalFiles/totalSize/status） */
    Uni<ImportBatch> update(ImportBatch entity);

    /** 查询用户创建的批次列表（按创建时间倒序） */
    Uni<List<ImportBatch>> findByCreatedBy(String createdBy);
}
