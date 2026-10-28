package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 文档知识图谱查询结果（dual-mcp {@code POST /graph_kg} 响应，跨模块共享 DTO）。
 *
 * <p>字段对齐《API-GRAPH.md》§5 响应结构：snake_case JSON 经 {@link JsonProperty} 映射；未知字段忽略。
 * dual-mcp 代理 mempalace {@code GET /kg}（direction=both, format=graph），响应为直接对象（无信封）。</p>
 *
 * <p>KG 为 mempalace 全局单图，实体名<b>精确匹配</b>（文档全名/kbId/普通实体）。查不到实体返回
 * {@code 200 + count:0}（非错误），"有结果" = {@code count>0}。mempalace 不可达时响应带
 * {@code error} 字段（正常响应无此字段），llm 侧按 HTTP 状态透传、由字段缺失自然表达。</p>
 *
 * <p>节点 {@code type} 为 7 类规则分类之一（芯片/传感器/外设接口/模块软件/文档/知识库/实体），
 * 配色由前端维护；{@code fullLen} > 80 表示 has_content 长文本值（name 截断展示）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphKgResult(
        String query,
        String palace,
        String source,
        KgNode center,
        Integer count,
        List<KgNode> nodes,
        List<KgEdge> edges,
        String hint,
        @JsonProperty("elapsed_ms") Double elapsedMs) {

    /**
     * KG 节点。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KgNode(
            String id,
            String name,
            @JsonProperty("full_len") Integer fullLen,
            String type,
            Boolean matched) {
    }

    /**
     * KG 边（= 一条事实/三元组，含时间有效性）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KgEdge(
            String source,
            String target,
            String type,
            String label,
            Float confidence,
            @JsonProperty("valid_from") String validFrom,
            @JsonProperty("valid_to") String validTo,
            Boolean current) {
    }
}
