package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /graph_kg} 请求体（《API-GRAPH3.md》§5）。字段名即 wire 格式。
 *
 * @param q      KG 实体名（精确匹配：文档全名/kbId/普通实体，必填）
 * @param palace 知识库 id（kbId）。带=仅查该库图谱；null/空白=全库（向后兼容，不进 wire 格式）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphKgRequest(String q, String palace) {

    /** 紧凑构造：空白串归一为 null（NON_NULL 下不进 wire 格式）。 */
    public GraphKgRequest {
        if (palace != null && palace.isBlank()) {
            palace = null;
        }
    }
}
