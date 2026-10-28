package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ImportBatchRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.UploadSessionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.UploadSessionPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ImportBatchProgress;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入批次查询服务（读）：批次进度（upload_sessions 聚合）+ 用户批次列表。
 * <p>批次进度不落列（§5.1），由 upload_sessions 聚合得出，避免双写不一致。
 * 查询用 Panache 直接读（与 NavTreeQueryService 等一致）。</p>
 */
@ApplicationScoped
public class ImportBatchQueryService {

    /** 结果摘要封顶条数：防超大会话数把响应撑爆 */
    private static final int RESULT_SUMMARY_CAP = 20;

    private final ImportBatchRepository batchRepository;
    private final UploadSessionPanacheRepository sessionPanacheRepository;

    public ImportBatchQueryService(ImportBatchRepository batchRepository,
                                   UploadSessionPanacheRepository sessionPanacheRepository) {
        this.batchRepository = batchRepository;
        this.sessionPanacheRepository = sessionPanacheRepository;
    }

    /** 批次进度：聚合 upload_sessions 各状态计数 + 未完成会话 ID 清单（断点恢复用） */
    @WithSession
    public Uni<ImportBatchProgress> getProgress(String batchId) {
        return batchRepository.findById(ImportBatchId.of(batchId))
                .onItem().ifNull().failWith(() -> new WebApplicationException(Response.Status.NOT_FOUND))
                .flatMap(batch -> sessionPanacheRepository.find("batchId", batchId).list()
                        .map(sessions -> toProgress(batch, sessions)));
    }

    /** 用户批次列表（按创建时间倒序），批次状态由 sessions 聚合推导（DB 状态滞后） */
    @WithSession
    public Uni<List<ImportBatchProgress>> listBatches(String operatorId) {
        return batchRepository.findByCreatedBy(operatorId)
                .flatMap(batches -> {
                    if (batches.isEmpty()) {
                        return Uni.createFrom().item(java.util.Collections.emptyList());
                    }
                    List<Uni<ImportBatchProgress>> unis = new ArrayList<>(batches.size());
                    for (ImportBatch batch : batches) {
                        unis.add(sessionPanacheRepository.find("batchId", batch.getId().value()).list()
                                .map(sessions -> toProgress(batch, sessions)));
                    }
                    return Uni.combine().all().unis(unis)
                            .with(results -> {
                                @SuppressWarnings("unchecked")
                                List<ImportBatchProgress> typed = (List<ImportBatchProgress>) results;
                                return new ArrayList<>(typed);
                            });
                });
    }

    private ImportBatchProgress toProgress(ImportBatch batch, List<UploadSessionPO> sessions) {
        ImportBatchProgress p = new ImportBatchProgress();
        p.setBatchId(batch.getId().value());
        p.setBatchName(batch.getBatchName());
        p.setKbId(batch.getKbId());
        p.setTotalFiles(batch.getTotalFiles());
        p.setTotalSize(batch.getTotalSize());
        p.setCreatedAt(batch.getCreatedAt());

        long completed = 0;
        long skipped = 0;
        long failed = 0;
        long aborted = 0;
        long expired = 0;
        long ignored = 0;
        List<String> uploading = new ArrayList<>();
        List<String> created = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (UploadSessionPO s : sessions) {
            String st = s.getStatus();
            switch (st) {
                case "COMPLETED": completed++; break;
                case "SKIPPED": skipped++; break;
                case "FAILED": failed++; break;
                case "ABORTED": aborted++; break;
                case "EXPIRED": expired++; break;
                case "UPLOADING": uploading.add(s.getId()); break;
                case "CREATED": created.add(s.getId()); break;
                default: break;
            }
            // zip 解压跳过的条目数（不支持类型等，COMPLETED 终态携带）
            ignored += (s.getSkippedCount() != null ? s.getSkippedCount() : 0);
            // 结果摘要透出（error 列已扩展为通用结果摘要：zip 分桶统计/失败原因；终态与否均回传）
            if (summaries.size() < RESULT_SUMMARY_CAP && s.getError() != null && !s.getError().isBlank()) {
                summaries.add(s.getFileName() + ": " + s.getError());
            }
        }
        p.setResultSummaries(summaries.isEmpty() ? null : summaries);
        p.setCompleted(completed);
        p.setSkipped(skipped);
        p.setFailed(failed);
        p.setAborted(aborted);
        p.setExpired(expired);
        p.setIgnored(ignored);
        p.setUploading(uploading);
        p.setCreated(created);

        // 批次状态推导（DB 状态滞后：session 终态后批次 DB 状态不自动更新）。
        // ABORTED 是用户操作（DB 已准确）；否则由 sessions 聚合推导：
        // 有未完成 session（UPLOADING/CREATED）→ UPLOADING；全终态有 FAILED → PARTIAL；否则 COMPLETED
        ImportBatchStatus status;
        if (batch.getStatus() == ImportBatchStatus.ABORTED) {
            status = ImportBatchStatus.ABORTED;
        } else if (!uploading.isEmpty() || !created.isEmpty()) {
            status = ImportBatchStatus.UPLOADING;
        } else if (failed > 0) {
            status = ImportBatchStatus.PARTIAL;
        } else {
            status = ImportBatchStatus.COMPLETED;
        }
        p.setStatus(status.name());
        return p;
    }
}
