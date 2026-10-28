package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 冲突详情 DTO（精简版）。
 * <p>只保留模型端有返回 + 本系统自有的字段；模型端契约未覆盖的字段
 * （发布状态/结构位置/片段更新时间等）不再暴露 null，等模型端扩契约再加。</p>
 */
public record ConflictDetailDTO(
        String id,
        String topic,
        String type,
        String batchNo,
        LocalDateTime detectedStartedAt,
        LocalDateTime detectedFinishedAt,
        Double confidence,
        String suggestion,
        String reason,
        Double similarity,
        List<EvidenceDTO> evidence) {

    /**
     * 冲突证据片段（一条 = 冲突的一侧）。
     *
     * @param docName     文件名（A 侧从 filePath 提取最后一段，B 侧为 old_source_file）
     * @param version     文档版本号（按文件名反查 document_versions 表；查不到为 null）
     * @param excerpt     文本摘录（A 侧 new_text_excerpt / B 侧 old_text_excerpt）
     * @param chunkIndex  chunk 序号（仅 A 侧有值，B 侧为 null）
     * @param isBaseline  true = 本次检测文件侧（file_path），false = 知识库侧（old_source_file）
     * @param sourcePath  库内源路径（仅 B 侧可能有值）
     */
    public record EvidenceDTO(
            String docName,
            String version,
            String excerpt,
            Integer chunkIndex,
            boolean isBaseline,
            String sourcePath) {
    }
}
