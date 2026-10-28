package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskSnapshot;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 冲突检测端口的真实远程实现（rest-client，对接模型端 V1.2 契约）。
 * <p>仅在 {@code kb.conflict.model.client-mode=remote} 时激活。</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "kb.conflict.model.client-mode", stringValue = "remote")
public class RemoteConflictDetectionClient implements ConflictDetectionClient {

    private static final Logger logger = LoggerFactory.getLogger(RemoteConflictDetectionClient.class);

    private final ModelConflictApi api;

    public RemoteConflictDetectionClient(@RestClient ModelConflictApi api) {
        this.api = api;
    }

    @Override
    public Uni<String> submitTask(String kbId, String filePath) {
        logger.info("Submit single conflict task to model: kbId={}, filePath={}", kbId, filePath);
        return api.submitTask(Map.of("knowledge_base_name", kbId, "file_path", filePath))
                .onItem().transform(env -> requireAccepted(env, "submitTask"))
                .onFailure().transform(e -> wrap(e, "submitTask"));
    }

    @Override
    public Uni<String> submitBatch(String kbId, List<String> filePaths) {
        logger.info("Submit batch conflict task to model: kbId={}, fileCount={}", kbId, filePaths == null ? 0 : filePaths.size());
        return api.submitBatch(Map.of("knowledge_base_name", kbId, "file_paths", filePaths))
                .onItem().transform(env -> requireAccepted(env, "submitBatch"))
                .onFailure().transform(e -> wrap(e, "submitBatch"));
    }

    @Override
    public Uni<ConflictTaskSnapshot> pollTask(String modelTaskId) {
        return api.getTaskStatus(modelTaskId)
                .onItem().transform(env -> {
                    if (env == null || env.data == null) {
                        throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, "model pollTask empty response");
                    }
                    return env.data;
                })
                .onFailure().transform(e -> wrap(e, "pollTask:" + modelTaskId));
    }

    @Override
    public Uni<ConflictTaskResult> fetchResults(String modelTaskId) {
        return api.getTaskResults(modelTaskId)
                .onItem().transform(env -> {
                    if (env == null || env.data == null) {
                        throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, "model fetchResults empty response");
                    }
                    return env.data;
                })
                .onFailure().transform(e -> wrap(e, "fetchResults:" + modelTaskId));
    }

    @Override
    public Uni<Boolean> setAutoSwitch(String kbId, boolean enabled) {
        logger.info("Set conflict auto-switch: kbId={}, enabled={}", kbId, enabled);
        return api.setAutoSwitch(Map.of("knowledge_base_name", kbId, "enabled", enabled))
                .onItem().transform(env -> {
                    if (env == null || env.data == null || env.data.enabled == null) {
                        throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, "model auto_switch empty response");
                    }
                    return env.data.enabled;
                })
                .onFailure().transform(e -> wrap(e, "setAutoSwitch:" + kbId));
    }

    private static String requireAccepted(ModelConflictApi.Envelope<ModelConflictApi.TaskCreated> env, String op) {
        if (env == null || env.code == null || env.code != 200 || env.data == null || env.data.task_id == null) {
            String msg = env == null ? "null envelope" : env.msg;
            throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, "model " + op + " not accepted: " + msg);
        }
        return env.data.task_id;
    }

    private static RuntimeException wrap(Throwable e, String op) {
        logger.warn("Conflict model call failed: op={}, err={}", op, e.getMessage());
        return new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, "model service call failed: " + e.getMessage());
    }
}
