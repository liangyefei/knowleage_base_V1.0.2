package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 已索引项目列表（dual-mcp {@code GET /projects} 响应，跨模块共享 DTO）。
 *
 * <p>字段对齐《API-GRAPH.md》§2 响应结构：{@code {"projects":[...]}}，snake_case 经 {@link JsonProperty}
 * 映射；未知字段忽略。响应为直接对象（无信封），HTTP 200=成功。</p>
 *
 * <p>{@code name} 为项目名（kbId 项目带 {@code projects-} 前缀）；{@code kbName} 来自模型侧
 * kbId→知识库名映射（未注册项目为 null），映射由模型侧维护、平台不重复维护。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphProjectsResult(
        List<GraphProject> projects) {

    /**
     * 单个已索引项目。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphProject(
            String name,
            Integer nodes,
            Integer edges,
            @JsonProperty("root_path") String rootPath,
            @JsonProperty("kb_name") String kbName) {
    }
}
