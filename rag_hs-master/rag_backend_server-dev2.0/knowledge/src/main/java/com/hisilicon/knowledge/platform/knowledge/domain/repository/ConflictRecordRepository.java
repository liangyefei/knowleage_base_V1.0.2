package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecord;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * 冲突记录仓储（领域层）。
 */
public interface ConflictRecordRepository {

    Uni<Optional<ConflictRecord>> findById(ConflictRecordId id);

    /**
     * 按 (kbId, fingerprint) 查已有记录，用于指纹去重（需求简化：无 PENDING 状态，全量去重）。
     */
    Uni<Optional<ConflictRecord>> findByKbAndFingerprint(String kbId, String fingerprint);

    Uni<ConflictRecord> persist(ConflictRecord entity);

    Uni<ConflictRecord> merge(ConflictRecord entity);
}
