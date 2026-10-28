package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 代码图谱邻接查询结果（dual-mcp {@code POST /graph_search} 响应，跨模块共享 DTO）。
 *
 * <p>字段对齐《API-GRAPH.md》§3 响应结构：snake_case JSON 经 {@link JsonProperty} 映射为 camelCase Java 字段；
 * 未知字段忽略（{@link JsonIgnoreProperties}，防模型字段漂移）。llm 反序列化模型响应后由 knowledge 端点透传返回。</p>
 *
 * <p>响应为直接对象（无 code/msg/data 信封），HTTP 200=成功。无命中时 {@code center} 为 null 且
 * {@code hint} 携带提示文案（非错误，由编排层决定是否转 A0430）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphSearchResult(
        String query,
        String project,
        GraphNode center,
        List<GraphNode> candidates,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        String hint,
        @JsonProperty("elapsed_ms") Double elapsedMs) {

    /**
     * 节点（center/candidates/nodes 共用同一结构）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphNode(
            Integer id,
            String label,
            String name,
            @JsonProperty("qualified_name") String qualifiedName,
            @JsonProperty("file_path") String filePath,
            @JsonProperty("start_line") Integer startLine,
            @JsonProperty("end_line") Integer endLine,
            Boolean matched,
            @JsonProperty("deg_local") Integer degLocal,
            @JsonProperty("in_degree") Integer inDegree,
            @JsonProperty("out_degree") Integer outDegree) {
    }

    /**
     * 边（adjacency 边；type 如 CALLS / DOCUMENTS_CODE / IMPORTS）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphEdge(
            Integer source,
            Integer target,
            String type,
            @JsonProperty("ref_name") String refName,
            Integer line) {
    }
}
