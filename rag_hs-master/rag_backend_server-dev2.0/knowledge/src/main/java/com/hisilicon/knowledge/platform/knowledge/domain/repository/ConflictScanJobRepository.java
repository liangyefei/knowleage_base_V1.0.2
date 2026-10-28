package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * 检测任务仓储（领域层）。
 */
public interface ConflictScanJobRepository {

    Uni<Optional<ConflictScanJob>> findById(ConflictScanJobId id);

    /** 兼容旧接口：同 scope 是否有 QUEUED/RUNNING 手动任务。新流程下全局串行，一般不再依赖此判重。 */
    Uni<Boolean> existsRunningManualByScopeFingerprint(String scopeFingerprint);

    /** 全局串行队列：当前是否有 RUNNING 任务。 */
    Uni<Optional<ConflictScanJob>> findRunning();

    /** 全局串行队列：取最早的 QUEUED 任务。 */
    Uni<Optional<ConflictScanJob>> findNextQueued();

    /**
     * 按 batch_no 前缀统计当日已有任务数（用于生成日内序号）。
     */
    Uni<Long> countByBatchNoPrefix(String prefix);

    Uni<ConflictScanJob> persist(ConflictScanJob entity);

    Uni<ConflictScanJob> merge(ConflictScanJob entity);
}
