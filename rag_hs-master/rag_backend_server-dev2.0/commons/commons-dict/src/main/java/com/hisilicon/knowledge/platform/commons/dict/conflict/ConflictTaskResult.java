package com.hisilicon.knowledge.platform.commons.dict.conflict;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * 模型端任务完整结果（对应 CONFLICT-DETECTION-API §2.4）。
 * 模型端返回 snake_case，这里显式加 @JsonProperty 映射。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictTaskResult(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("knowledge_base_name") String knowledgeBaseName,
        String status,
        @JsonProperty("conflict_count") Integer conflictCount,
        List<FileGroup> files,
        List<Conflict> conflicts) {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileGroup(
            @JsonProperty("file_path") String filePath,
            String status,
            String error,
            @JsonProperty("total_chunks") Integer totalChunks,
            @JsonProperty("done_chunks") Integer doneChunks,
            @JsonProperty("conflict_count") Integer conflictCount) {
    }

    /**
     * 单条冲突（对应 §2.4.1）。
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conflict(
            String type,
            @JsonProperty("conflict_point") String conflictPoint,
            Double confidence,
            String suggestion,
            String reason,
            @JsonProperty("new_text_excerpt") String newTextExcerpt,
            @JsonProperty("old_text_excerpt") String oldTextExcerpt,
            @JsonProperty("old_source_file") String oldSourceFile,
            @JsonProperty("old_source_path") String oldSourcePath,
            @JsonProperty("new_chunk_index") Integer newChunkIndex,
            Double similarity,
            @JsonProperty("file_path") String filePath) {
    }
}
