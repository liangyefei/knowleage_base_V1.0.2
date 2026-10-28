package com.hisilicon.knowledge.platform.commons.dict.conflict;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 模型端冲突检测任务状态快照（对应 CONFLICT-DETECTION-API §2.3）。
 *
 * <p>HTTP 200 包裹：{@code {code, msg, data: {...}}}，反序列化时由 rest-client 层解包 data。
 * 模型端返回 snake_case，这里显式加 @JsonProperty 映射。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictTaskSnapshot(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("knowledge_base_name") String knowledgeBaseName,
        String status,
        @JsonProperty("total_chunks") Integer totalChunks,
        @JsonProperty("done_chunks") Integer doneChunks,
        @JsonProperty("conflict_count") Integer conflictCount,
        String error,
        @JsonProperty("result_file") String resultFile) {

    /** 任务是否已到终态（模型端状态机：QUEUED/RUNNING → SUCCEEDED/FAILED）。 */
    public boolean isTerminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }
}
