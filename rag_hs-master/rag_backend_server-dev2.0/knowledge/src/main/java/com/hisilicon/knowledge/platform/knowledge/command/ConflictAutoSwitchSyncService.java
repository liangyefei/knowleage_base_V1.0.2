package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictSettingsRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上传时同步模型端 auto_switch 的服务。
 *
 * <p>流程（best-effort，失败只记日志不影响上传主流程）：
 * <ol>
 *   <li>若 {@code autoConflictDetection != null}：upsert 平台侧该 KB 的覆盖开关；</li>
 *   <li>读全局开关 AND KB 级开关，计算最终 enabled；</li>
 *   <li>调模型端 {@code POST /kw/conflict_detection/auto_switch}。</li>
 * </ol>
 * </p>
 */
@ApplicationScoped
public class ConflictAutoSwitchSyncService {

    private static final Logger logger = LoggerFactory.getLogger(ConflictAutoSwitchSyncService.class);

    private final ConflictSettingsRepository settingsRepository;
    private final ConflictDetectionClient detectionClient;
    private final ConflictRecordCommandService recordCommandService;

    public ConflictAutoSwitchSyncService(ConflictSettingsRepository settingsRepository,
                                         ConflictDetectionClient detectionClient,
                                         ConflictRecordCommandService recordCommandService) {
        this.settingsRepository = settingsRepository;
        this.detectionClient = detectionClient;
        this.recordCommandService = recordCommandService;
    }

    /**
     * 上传后旁路调用：同步模型端 KB auto_switch。
     *
     * @param kbId                  知识库 ID
     * @param operatorId            操作员
     * @param autoConflictDetection 前端本次上传是否要求自动检测（null=不修改 KB 记忆值）
     */
    public Uni<Void> syncOnUpload(String kbId, String operatorId, Boolean autoConflictDetection) {
        // 1. 可选：upsert KB 级开关
        Uni<Void> upsertStep = autoConflictDetection == null
                ? Uni.createFrom().voidItem()
                : recordCommandService.updateKbAutoDetection(kbId, operatorId, autoConflictDetection).replaceWithVoid();

        return upsertStep
                .chain(v -> settingsRepository.findGlobal())
                .chain(globalOpt -> {
                    boolean globalEnabled = globalOpt.isPresent() && globalOpt.get().isAutoEnabled();
                    if (!globalEnabled) {
                        // 全局关时，模型端必须设为 false（不论 KB 级怎么记）
                        return detectionClient.setAutoSwitch(kbId, false)
                                .onFailure().recoverWithUni(e -> {
                                    logger.warn("Sync model auto_switch (global off) failed: kbId={}, err={}",
                                            kbId, e.getMessage());
                                    return Uni.createFrom().item(false);
                                })
                                .replaceWithVoid();
                    }
                    return settingsRepository.findByKbId(kbId)
                            .chain(kbOpt -> {
                                boolean kbEnabled = kbOpt.isPresent() && kbOpt.get().isAutoEnabled();
                                return detectionClient.setAutoSwitch(kbId, kbEnabled)
                                        .onFailure().recoverWithUni(e -> {
                                            logger.warn("Sync model auto_switch failed: kbId={}, err={}", kbId, e.getMessage());
                                            return Uni.createFrom().item(kbEnabled);
                                        })
                                        .replaceWithVoid();
                            });
                })
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Sync auto_switch swallowed: kbId={}, err={}", kbId, e.getMessage());
                    return Uni.createFrom().voidItem();
                })
                .replaceWithVoid();
    }
}
