package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 搜索联想结果（dual-mcp {@code POST /graph_suggest} 响应，跨模块共享 DTO）。
 *
 * <p>字段对齐《API-GRAPH2.md》§4.5 响应结构：snake_case JSON 经 {@link JsonProperty} 映射；未知字段忽略。
 * 响应为直接对象（无信封）。</p>
 *
 * <p>双源聚合：代码图谱（左边界词段匹配，name 与 file_path 双列）优先，KG 补充（仅精确命中可联想）。
 * {@code suggestions[].source} 取值 {@code code}（代码图谱）或 {@code kg}（文档图谱），前端按此分流查询；
 * {@code label} 在 code 侧为英文类型（Function 等），kg 侧为 7 类中文类型；{@code deg} code 侧为全局度数、
 * kg 侧为 q 邻域内度数。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphSuggestResult(
        String query,
        String project,
        List<Suggestion> suggestions,
        @JsonProperty("elapsed_ms") Double elapsedMs) {

    /**
     * 联想候选。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Suggestion(
            String name,
            String label,
            String source,
            String file,
            Integer deg) {
    }
}
