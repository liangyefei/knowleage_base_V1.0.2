package com.hisilicon.knowledge.platform.knowledge.infrastructure.job;

import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回收目录清理任务 — 物理删除超过保留期的 trash 文件（两阶段删除的第二步）
 * <p>
 * 多实例安全：物理删除本身幂等（deleteIfExists 语义），多个实例同时删同一文件无害；
 * 时间判断基于文件 mtime，不依赖实例时钟。
 * </p>
 */
@ApplicationScoped
public class TrashCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(TrashCleanupJob.class);

    private final FileStorageService fileStorageService;
    private final int retentionDays;

    public TrashCleanupJob(FileStorageService fileStorageService,
                           @ConfigProperty(name = "kb.storage.trash-retention-days", defaultValue = "7")
                           int retentionDays) {
        this.fileStorageService = fileStorageService;
        this.retentionDays = retentionDays;
    }

    /**
     * 每日凌晨清理过期回收文件
     */
    @Scheduled(cron = "0 30 3 * * ?", concurrentExecution = ConcurrentExecution.SKIP)
    public Uni<Void> cleanup() {
        return fileStorageService.cleanTrash(retentionDays)
                .invoke(deleted -> {
                    if (deleted > 0) {
                        logger.info("Trash directory cleanup job completed: deleted={}", deleted);
                    }
                })
                .onFailure().invoke(err -> logger.error("Trash directory cleanup job execution failed", err))
                .replaceWithVoid();
    }
}
