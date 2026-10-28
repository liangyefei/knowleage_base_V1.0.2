package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /graph_search} 请求体（《API-GRAPH.md》§3）。字段名即 wire 格式（snake_case）。
 *
 * @param q          实体关键字（符号名/文档名/文件名，必填）
 * @param project    项目名（= kbId）
 * @param limit      邻接边数上限（1–500，null 走模型默认 80）
 * @param candidates 同名/近义其它匹配实体数（0–20，null 走模型默认 6）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphSearchRequest(
        String q,
        String project,
        Integer limit,
        Integer candidates) {

    /** 紧凑构造：空白串归一为 null（NON_NULL 下不进 wire 格式）。 */
    public GraphSearchRequest {
        if (q != null && q.isBlank()) {
            q = null;
        }
        if (project != null && project.isBlank()) {
            project = null;
        }
    }
}