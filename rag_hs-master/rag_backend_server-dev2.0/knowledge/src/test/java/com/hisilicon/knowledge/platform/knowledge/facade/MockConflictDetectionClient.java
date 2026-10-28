package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskSnapshot;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * knowledge 模块单测桩：{@link ConflictDetectionClient} 的真实实现都在 llm 模块
 * （{@code RemoteConflictDetectionClient} / {@code StubConflictDetectionClient}），
 * knowledge 单测的 Quarkus 部署拿不到 bean，会以 UnsatisfiedDependency 直接失败。
 * 这里提供一个仅测试期可见的空实现，业务测试用不到其返回值。
 */
@ApplicationScoped
public class MockConflictDetectionClient implements ConflictDetectionClient {

    @Override
    public Uni<String> submitTask(String kbId, String filePath) {
        return Uni.createFrom().item("mock-task-id");
    }

    @Override
    public Uni<String> submitBatch(String kbId, List<String> filePaths) {
        return Uni.createFrom().item("mock-batch-task-id");
    }

    @Override
    public Uni<ConflictTaskSnapshot> pollTask(String modelTaskId) {
        return Uni.createFrom().item(new ConflictTaskSnapshot(
                modelTaskId, "SUCCEEDED", "MOCK", 0, 0, 0, null, null));
    }

    @Override
    public Uni<ConflictTaskResult> fetchResults(String modelTaskId) {
        return Uni.createFrom().item(new ConflictTaskResult(
                modelTaskId, "SUCCEEDED", "MOCK", 0, List.of(), List.of()));
    }

    @Override
    public Uni<Boolean> setAutoSwitch(String kbId, boolean enabled) {
        return Uni.createFrom().item(enabled);
    }
}
