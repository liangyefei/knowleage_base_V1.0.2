package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskSnapshot;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 冲突检测端口的桩实现（已被 RemoteConflictDetectionClient 里的本地固定数据取代，
 * 保留作 fallback；正常情况下不会被激活）。
 */
@ApplicationScoped
@DefaultBean
public class StubConflictDetectionClient implements ConflictDetectionClient {

    private static final Logger logger = LoggerFactory.getLogger(StubConflictDetectionClient.class);

    @Override
    public Uni<String> submitTask(String kbId, String filePath) {
        logger.warn("[stub] submitTask called (should not happen): kbId={}, filePath={}", kbId, filePath);
        return Uni.createFrom().item("stub-task-" + System.currentTimeMillis());
    }

    @Override
    public Uni<String> submitBatch(String kbId, List<String> filePaths) {
        logger.warn("[stub] submitBatch called (should not happen): kbId={}, fileCount={}",
                kbId, filePaths == null ? 0 : filePaths.size());
        return Uni.createFrom().item("stub-batch-" + System.currentTimeMillis());
    }

    @Override
    public Uni<ConflictTaskSnapshot> pollTask(String modelTaskId) {
        return Uni.createFrom().item(new ConflictTaskSnapshot(modelTaskId, null, "SUCCEEDED", 0, 0, 0, null, null));
    }

    @Override
    public Uni<ConflictTaskResult> fetchResults(String modelTaskId) {
        return Uni.createFrom().item(new ConflictTaskResult(modelTaskId, null, "SUCCEEDED", 0, List.of(), List.of()));
    }

    @Override
    public Uni<Boolean> setAutoSwitch(String kbId, boolean enabled) {
        return Uni.createFrom().item(enabled);
    }
}
