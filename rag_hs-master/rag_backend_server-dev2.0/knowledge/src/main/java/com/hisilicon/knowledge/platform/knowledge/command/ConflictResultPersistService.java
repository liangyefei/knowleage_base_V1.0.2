package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecord;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordItem;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictType;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictRecordRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ConflictRecordItemPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordItemPO;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 冲突结果落库服务（由 {@code ConflictScanQueueJob} 在模型端 SUCCEEDED 后调用）。
 *
 * <p>逐条冲突按指纹去重（同 kb 同指纹已存在则 reseen，否则新建）→ 落证据快照 →
 * 不修改 job 本身的状态（调用方负责）。</p>
 */
@ApplicationScoped
public class ConflictResultPersistService {

    private static final Logger logger = LoggerFactory.getLogger(ConflictResultPersistService.class);

    private final ConflictRecordRepository recordRepository;
    private final ConflictRecordItemPanacheRepository itemPanacheRepo;

    public ConflictResultPersistService(ConflictRecordRepository recordRepository,
                                        ConflictRecordItemPanacheRepository itemPanacheRepo) {
        this.recordRepository = recordRepository;
        this.itemPanacheRepo = itemPanacheRepo;
    }

    @WithTransaction
    public Uni<Void> persist(ConflictScanJob job, ConflictTaskResult result) {
        List<ConflictTaskResult.Conflict> conflicts = result.conflicts() == null
                ? List.of() : result.conflicts();
        if (job.getKbId() == null) {
            logger.warn("Cross-KB job conflict persist skipped: jobId={}, conflictCount={}",
                    job.getId().value(), conflicts.size());
            return Uni.createFrom().voidItem();
        }
        AtomicInteger newCount = new AtomicInteger();
        AtomicInteger hitCount = new AtomicInteger();
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (ConflictTaskResult.Conflict c : conflicts) {
            chain = chain.chain(v -> persistOne(job, c, newCount, hitCount));
        }
        return chain.replaceWithVoid()
                .invoke(() -> logger.info("Conflict result persisted: jobId={}, new={}, hit={}",
                        job.getId().value(), newCount.get(), hitCount.get()));
    }

    private Uni<Void> persistOne(ConflictScanJob job, ConflictTaskResult.Conflict c,
                                 AtomicInteger newCount, AtomicInteger hitCount) {
        String kbId = job.getKbId();
        String fingerprint = computeFingerprint(c);
        ConflictType type = ConflictType.fromModelValue(c.type());
        // job 此时仍是 RUNNING（finishedAt 在 persist 之后才赋值），用当前时间作为冲突检出完成时间
        LocalDateTime detectedAt = LocalDateTime.now();

        return recordRepository.findByKbAndFingerprint(kbId, fingerprint)
                .chain(existing -> {
                    if (existing.isPresent()) {
                        hitCount.incrementAndGet();
                        ConflictRecord updated = existing.get().reseen(job.getId());
                        return recordRepository.merge(updated).replaceWithVoid();
                    }
                    ConflictRecord record = ConflictRecord.builder()
                            .kbId(kbId)
                            .firstJobId(job.getId())
                            .lastSeenJobId(job.getId())
                            .fingerprint(fingerprint)
                            .topic(c.conflictPoint() == null ? "" : c.conflictPoint())
                            .type(type)
                            .rawType(c.type())
                            .summary(c.conflictPoint())
                            .locationCount(2)
                            .scopeLabel(c.filePath())
                            .detectedStartedAt(job.getStartedAt())
                            .detectedFinishedAt(detectedAt)
                            .confidence(c.confidence())
                            .suggestion(c.suggestion())
                            .reason(c.reason())
                            .conflictPoint(c.conflictPoint())
                            .oldSourceFile(c.oldSourceFile())
                            .similarity(c.similarity())
                            .build();
                    return recordRepository.persist(record)
                            .chain(persisted -> persistItems(persisted.getId(), c))
                            .replaceWithVoid()
                            .invoke(newCount::incrementAndGet);
                });
    }

    private Uni<Void> persistItems(ConflictRecordId recordId, ConflictTaskResult.Conflict c) {
        // 模型端一条冲突 = 两条证据（新文件侧 + 知识库侧）
        Uni<Void> chain = Uni.createFrom().voidItem();
        chain = chain.chain(v -> saveItem(recordId, extractFileName(c.filePath()), c.newTextExcerpt(), null,
                c.newChunkIndex(), true, 1));
        chain = chain.chain(v -> saveItem(recordId, c.oldSourceFile(), c.oldTextExcerpt(),
                c.oldSourcePath(), null, false, 2));
        return chain;
    }

    /** 从 /kb-files/files/uart-manual.pdf 提取最后一段作为展示文件名。 */
    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        String trimmed = path.replace('\\', '/');
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    private Uni<Void> saveItem(ConflictRecordId recordId, String docName, String excerpt, String oldSourcePath,
                               Integer chunkIndex, boolean baseline, int sortNo) {
        ConflictRecordItemPO po = new ConflictRecordItemPO();
        po.id = com.hisilicon.knowledge.platform.knowledge.domain.entity.IdGenerator.conflictItem();
        po.recordId = recordId.value();
        po.documentId = null;
        po.docNameSnapshot = docName == null ? "unknown" : docName;
        po.publishStatusSnapshot = "PUBLISHED";
        po.chunkIndex = chunkIndex;
        po.excerpt = excerpt == null ? "" : excerpt;
        po.newTextExcerpt = baseline ? excerpt : null;
        po.oldTextExcerpt = baseline ? null : excerpt;
        po.oldSourcePath = oldSourcePath;
        po.baseline = baseline;
        po.sortNo = sortNo;
        po.createdAt = java.time.LocalDateTime.now();
        return itemPanacheRepo.persist(po).replaceWithVoid();
    }

    private String computeFingerprint(ConflictTaskResult.Conflict c) {
        String raw = (c.type() == null ? "" : c.type()) + "|"
                + (c.filePath() == null ? "" : c.filePath()) + "|"
                + (c.oldSourceFile() == null ? "" : c.oldSourceFile()) + "|"
                + (c.conflictPoint() == null ? "" : c.conflictPoint().trim().toLowerCase().replaceAll("\\s+", " "));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }
}
