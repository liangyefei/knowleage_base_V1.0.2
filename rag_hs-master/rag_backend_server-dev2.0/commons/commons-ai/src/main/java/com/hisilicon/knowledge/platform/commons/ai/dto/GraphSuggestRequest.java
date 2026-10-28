package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /graph_suggest} 请求体（《API-GRAPH3.md》§4.5）。字段名即 wire 格式（单词无 snake_case）。
 *
 * @param q       实体名片段（必填）
 * @param project 项目（= kbId，代码源作用域；KG 为全局单图不受其限制）
 * @param limit   候选条数（1–30，null 走模型默认 10）
 * @param palace  知识库 id（= kbId，KG 源按库过滤；null 走模型缺省推导。平台显式传 kbId——
 *                文档规定显式传入优先，避免依赖服务端对 project 形态的推导规则）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphSuggestRequest(
        String q,
        String project,
        Integer limit,
        String palace) {

    /** 紧凑构造：空白串归一为 null（NON_NULL 下不进 wire 格式）。 */
    public GraphSuggestRequest {
        if (q != null && q.isBlank()) {
            q = null;
        }
        if (project != null && project.isBlank()) {
            project = null;
        }
        if (palace != null && palace.isBlank()) {
            palace = null;
        }
    }
}