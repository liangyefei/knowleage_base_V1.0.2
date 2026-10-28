package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictScanJobPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Panache 数据访问层（冲突检测任务）。
 */
@ApplicationScoped
public class ConflictScanJobPanacheRepository implements PanacheRepositoryBase<ConflictScanJobPO, String> {

    /** 查当前 RUNNING 的任务（全局串行队列：最多一条）。 */
    public Uni<ConflictScanJobPO> findRunning() {
        return find("status = ?1", "RUNNING").firstResult();
    }

    /** 找最早的 QUEUED 任务（FIFO）。HQL 用实体属性名 createdAt（驼峰）。 */
    public Uni<ConflictScanJobPO> findNextQueued() {
        return find("status = ?1 order by createdAt asc", "QUEUED").firstResult();
    }
}
