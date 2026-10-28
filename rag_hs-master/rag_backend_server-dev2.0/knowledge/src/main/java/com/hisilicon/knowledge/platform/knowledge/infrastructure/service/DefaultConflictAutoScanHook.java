package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanMode;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanTriggerType;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictScanJobRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictSettingsRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.ConflictAutoScanHook;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@link ConflictAutoScanHook} 实现：文档切片发布后，按「全局开关 AND KB 级开关」判定是否触发自动检测。
 *
 * <p>触发时直接落一个 QUEUED job（单文件），由 {@code ConflictScanQueueJob} 串行调度提交模型端。
 * 所有异常吞掉只记日志，不影响文档发布主链路。</p>
 */
@ApplicationScoped
public class DefaultConflictAutoScanHook implements ConflictAutoScanHook {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConflictAutoScanHook.class);
    private static final DateTimeFormatter BATCH_DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String MODEL_FILE_PATH_PREFIX = "/kb-files/";

    private final ConflictSettingsRepository settingsRepository;
    private final ConflictScanJobRepository scanJobRepository;
    private final ConflictDetectionClient detectionClient;

    public DefaultConflictAutoScanHook(ConflictSettingsRepository settingsRepository,
                                       ConflictScanJobRepository scanJobRepository,
                                       ConflictDetectionClient detectionClient) {
        this.settingsRepository = settingsRepository;
        this.scanJobRepository = scanJobRepository;
        this.detectionClient = detectionClient;
    }

    @Override
    public Uni<Void> afterChunkPublished(String kbId, String documentId) {
        return settingsRepository.findGlobal()
                .chain(globalOpt -> {
                    if (globalOpt.isEmpty() || !globalOpt.get().isAutoEnabled()) {
                        return Uni.createFrom().voidItem();
                    }
                    return settingsRepository.findByKbId(kbId)
                            .chain(kbOpt -> {
                                boolean kbEnabled = kbOpt.isPresent() && kbOpt.get().isAutoEnabled();
                                if (!kbEnabled) {
                                    return Uni.createFrom().voidItem();
                                }
                                // file_path 通过 documentId 查太绕；这里只落 job，文件路径由 job 的 filePathsJson 字段在 dispatch 时补全
                                // 简化：AUTO 任务在 dispatch 时按 documentId 查 filePath
                                String scopeNodesJson = "{\"documentId\":\"" + documentId + "\"}";
                                return nextBatchNo()
                                        .map(batchNo -> ConflictScanJob.queue(
                                                batchNo, kbId, ScanTriggerType.AUTO, ScanMode.INCREMENTAL,
                                                scopeNodesJson, null, 1, "SYSTEM"))
                                        .chain(scanJobRepository::persist)
                                        .replaceWithVoid();
                            });
                })
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Auto conflict scan hook error swallowed: kbId={}, docId={}, err={}",
                            kbId, documentId, e.getMessage());
                    return Uni.createFrom().voidItem();
                })
                .replaceWithVoid();
    }

    private Uni<String> nextBatchNo() {
        String day = LocalDateTime.now().format(BATCH_DAY_FMT);
        return scanJobRepository.countByBatchNoPrefix("CHK-" + day + "-")
                .map(count -> String.format("CHK-%s-A%02d", day, count + 1));
    }
}
