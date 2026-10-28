package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 创建 Badcase 工单命令。
 *
 * @param source          来源码（PAGE_QA / MCP_QA / CONFLICT_DETECTION / KNOWLEDGE_SLICE）
 * @param problemType     问题类型码（须与 source 组合合法）
 * @param description     问题描述（1~1000 字）
 * @param kbId            关联知识库 ID（可空）
 * @param kbName          知识库名称（可空，前端传快照）
 * @param relatedDocIds   涉及文档 ID 数组（可空）
 * @param relatedDocNames 涉及文档名称数组（可空，须与 docIds 一一对应）
 * @param sourcePayload   差异化区块 JSON（可空，结构按来源校验）
 */
public record CreateBadcaseCommand(
        @NotBlank String source,
        @NotBlank String problemType,
        @NotBlank @Size(max = 1000) String description,
        String kbId,
        String kbName,
        List<String> relatedDocIds,
        List<String> relatedDocNames,
        Map<String, Object> sourcePayload
) {
}
