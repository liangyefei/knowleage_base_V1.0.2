package com.hisilicon.knowledge.platform.knowledge.infrastructure.job;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * tus 上传会话超期清理任务 — 将超期未完成会话置 EXPIRED + 临时文件移 trash。
 * <p>扫 {@code status in (CREATED,UPLOADING) and expires_at < now}：临时文件 {@link FileStorageService#moveToTrash}
 * （幂等，两阶段删除第一步），session {@code expire()} 落库。物理删复用 {@link TrashCleanupJob}。
 * 多实例安全：moveToTrash 幂等（文件不存在视为成功），expire 状态机守卫（终态不可再过期）。</p>
 * <p>另扫卡死的 PROCESSING 会话（zip 后台解压崩溃未达 onFailure）：updated_at 早于阈值即标 FAILED +
 * 临时文件移 trash。防误杀双重依据：① 后台阶段2逐条目 bumpEntriesDone 更新 updated_at；
 * ② 大包阶段1（流式写盘）期间 DB 无心跳，靠 {@link FileStorageService#hasRecentWrites} 查
 * 解压目录近阈值窗口内是否有文件写入，有则本轮跳过。</p>
 */
@ApplicationScoped
public class UploadSessionCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(UploadSessionCleanupJob.class);

    /** zip 解压临时目录（与 UploadSessionCommandService.ZIP_EXTRACT_DIR 一致；跨类引用则互相耦合，复制字面量并注释约定） */
    static final String ZIP_EXTRACT_DIR = ".zip-extract";

    private final UploadSessionRepository sessionRepository;
    private final FileStorageService fileStorageService;

    /** PROCESSING 会话判定卡死的无推进时长（分钟），超此未 bumpEntriesDone 视为崩溃 */
    @ConfigProperty(name = "kb.import.tus.stale-processing-minutes", defaultValue = "30")
    int staleProcessingMinutes;

    public UploadSessionCleanupJob(UploadSessionRepository sessionRepository,
                                   FileStorageService fileStorageService) {
        this.sessionRepository = sessionRepository;
        this.fileStorageService = fileStorageService;
    }

    /** 每日凌晨清理超期未完成会话 */
    @Scheduled(cron = "0 0 4 * * ?", concurrentExecution = ConcurrentExecution.SKIP)
    @WithTransaction
    public Uni<Void> cleanup() {
        return sessionRepository.findExpiredIncomplete(LocalDateTime.now())
                .flatMap(this::expireAll)
                .invoke(count -> {
                    if (count > 0) {
                        logger.info("tus expired session cleanup completed: expired={}", count);
                    }
                })
                .onFailure().invoke(err -> logger.error("tus expired session cleanup failed", err))
                .replaceWithVoid();
    }

    /** 每 30 分钟扫描卡死的 PROCESSING 会话（后台解压崩溃兜底） */
    @Scheduled(every = "30m", concurrentExecution = ConcurrentExecution.SKIP)
    @WithTransaction
    public Uni<Void> cleanupStaleProcessing() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleProcessingMinutes);
        return sessionRepository.findStaleProcessing(threshold)
                .flatMap(this::failStaleProcessing)
                .invoke(count -> {
                    if (count > 0) {
                        logger.info("tus stuck PROCESSING cleanup completed: failed={}", count);
                    }
                })
                .onFailure().invoke(err -> logger.error("tus stuck PROCESSING cleanup failed", err))
                .replaceWithVoid();
    }

    private Uni<Long> expireAll(List<UploadSession> sessions) {
        Uni<Long> chain = Uni.createFrom().item(0L);
        long[] count = {0};
        for (UploadSession session : sessions) {
            chain = chain.flatMap(c -> fileStorageService.moveToTrash(session.getTempPath())
                    .onFailure().recoverWithNull()
                    .flatMap(v -> sessionRepository.update(session.expire()))
                    .map(v -> ++count[0]));
        }
        return chain;
    }

    private Uni<Long> failStaleProcessing(List<UploadSession> sessions) {
        Uni<Long> chain = Uni.createFrom().item(0L);
        long[] count = {0};
        for (UploadSession session : sessions) {
            chain = chain.flatMap(c -> fileStorageService.hasRecentWrites(ZIP_EXTRACT_DIR + "/" + session.getId().value(), threshold())
                    .flatMap(active -> {
                        if (Boolean.TRUE.equals(active)) {
                            // 解压目录仍在持续写盘（大包阶段1）：不判崩溃，下轮再看
                            logger.info("Extraction directory of PROCESSING session still active, skip stuck check: sessionId={}", session.getId().value());
                            return Uni.createFrom().item(c);
                        }
                        return fileStorageService.moveToTrash(session.getTempPath())
                                .onFailure().recoverWithNull()
                                .flatMap(v -> sessionRepository.update(session.markFailed("zip 解压超时未推进，判定崩溃")))
                                .map(v -> ++count[0]);
                    }));
        }
        return chain;
    }

    private LocalDateTime threshold() {
        return LocalDateTime.now().minusMinutes(staleProcessingMinutes);
    }
}
