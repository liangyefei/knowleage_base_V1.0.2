package com.hisilicon.knowledge.platform.knowledge.infrastructure.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskSnapshot;
import com.hisilicon.knowledge.platform.knowledge.command.ConflictResultPersistService;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictScanJobRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 冲突检测任务串行调度 + 轮询 Job。
 *
 * <p>每 tick（默认 15s）：
 * <ol>
 *   <li>若当前有 RUNNING 任务：轮询模型端 {@code GET /tasks/{modelTaskId}}：
 *     <ul>
 *       <li>RUNNING/QUEUED：仅刷新 chunk 进度；</li>
 *       <li>SUCCEEDED：拉取 results，落库冲突明细，job 置 COMPLETED；</li>
 *       <li>FAILED：job 置 FAILED 并记录 error。</li>
 *     </ul>
 *   </li>
 *   <li>若当前无 RUNNING 任务：取最早的 QUEUED job，调用模型端 {@code POST /tasks} 或 {@code /batch_tasks}，
 *       成功置 RUNNING；失败置 FAILED 不阻塞队列。</li>
 * </ol>
 * </p>
 *
 * <p>{@code concurrentExecution = SKIP}：单实例内串行；多实例并发时通过数据库行的状态跃迁
 * 天然互斥（多个实例同时查到的同一 RUNNING job 轮询是幂等的，副作用仅在 SUCCEEDED 时落库，
 * 由 {@link ConflictResultPersistService} 内的「job 已终态则跳过」守卫兜底）。</p>
 */
@ApplicationScoped
public class ConflictScanQueueJob {

    private static final Logger logger = LoggerFactory.getLogger(ConflictScanQueueJob.class);

    @Inject
    ConflictScanJobRepository jobRepository;

    @Inject
    ConflictDetectionClient detectionClient;

    @Inject
    ConflictResultPersistService resultPersistService;

    @Inject
    ObjectMapper objectMapper;

    @Scheduled(every = "${kb.conflict.poll-interval:15s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @WithTransaction
    public Uni<Void> tick() {
        return jobRepository.findRunning()
                .chain(opt -> {
                    if (opt.isPresent()) {
                        return pollRunning(opt.get());
                    }
                    return dispatchNextQueued();
                })
                .onFailure().recoverWithUni(e -> {
                    logger.warn("ConflictScanQueueJob tick failed: {}", e.getMessage());
                    return Uni.createFrom().voidItem();
                })
                .replaceWithVoid();
    }

    private Uni<Void> pollRunning(ConflictScanJob job) {
        if (job.getModelTaskId() == null || job.getModelTaskId().isBlank()) {
            // 防御：RUNNING 但没有 model_task_id，说明提交时异常，直接置 FAILED
            logger.warn("Running job missing modelTaskId, mark FAILED: jobId={}", job.getId().value());
            return jobRepository.merge(job.markFailed("missing model_task_id")).replaceWithVoid();
        }
        return detectionClient.pollTask(job.getModelTaskId())
                .chain(snapshot -> {
                    if (!snapshot.isTerminal()) {
                        ConflictScanJob refreshed = job.refreshProgress(
                                snapshot.doneChunks(), snapshot.totalChunks(), snapshot.conflictCount());
                        return jobRepository.merge(refreshed).replaceWithVoid();
                    }
                    if ("FAILED".equals(snapshot.status())) {
                        String err = snapshot.error() == null ? "model task failed" : snapshot.error();
                        return jobRepository.merge(job.markModelFailed(err)).replaceWithVoid();
                    }
                    // SUCCEEDED：拉完整结果并落库
                    return detectionClient.fetchResults(job.getModelTaskId())
                            .chain(result -> resultPersistService.persist(job, result))
                            .chain(v -> {
                                int conflictCount = resultSummaryCount(job);
                                ConflictScanJob finished = job.markSucceeded(
                                        snapshot.doneChunks() == null ? 0 : snapshot.doneChunks(),
                                        0, 0, 0, conflictCount);
                                return jobRepository.merge(finished).replaceWithVoid();
                            })
                            .onFailure().invoke(e -> logger.error(
                                    "Persist conflict result failed, job stuck RUNNING: jobId={}, err={}",
                                    job.getId().value(), e.toString()));
                })
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Poll running job failed, keep RUNNING for retry: jobId={}, err={}",
                            job.getId().value(), e.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }

    private static int resultSummaryCount(ConflictScanJob job) {
        return job.getConflictCount() == null ? 0 : job.getConflictCount();
    }

    private Uni<Void> dispatchNextQueued() {
        return jobRepository.findNextQueued()
                .chain(opt -> {
                    if (opt.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    ConflictScanJob job = opt.get();
                    List<String> filePaths = parseFilePaths(job.getFilePathsJson());
                    if (filePaths.isEmpty()) {
                        logger.warn("Queued job has no filePaths, mark FAILED: jobId={}", job.getId().value());
                        return jobRepository.merge(job.markFailed("empty file_paths")).replaceWithVoid();
                    }
                    Uni<String> submitUni = filePaths.size() == 1
                            ? detectionClient.submitTask(job.getKbId(), filePaths.get(0))
                            : detectionClient.submitBatch(job.getKbId(), filePaths);
                    return submitUni
                            .chain(modelTaskId -> {
                                ConflictScanJob running = job.markRunning(modelTaskId, job.getFilePathsJson());
                                return jobRepository.merge(running).replaceWithVoid();
                            })
                            .onFailure().recoverWithUni(e -> {
                                logger.warn("Dispatch queued job failed, mark FAILED: jobId={}, err={}",
                                        job.getId().value(), e.getMessage());
                                return jobRepository.merge(job.markFailed(e.getMessage())).replaceWithVoid();
                            });
                });
    }

    private List<String> parseFilePaths(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            logger.warn("Parse filePathsJson failed: json={}, err={}", json, e.getMessage());
            return List.of();
        }
    }
}
