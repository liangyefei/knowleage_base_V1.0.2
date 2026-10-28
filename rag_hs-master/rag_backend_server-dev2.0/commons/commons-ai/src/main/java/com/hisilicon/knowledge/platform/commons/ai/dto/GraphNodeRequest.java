package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * dual-mcp {@code /graph_node} 请求体（字段名即 wire 格式，snake_case）。
 *
 * @param name       实体名（<b>模型必填</b>，见下）；空字符串表示仅按 id 查询
 * @param id         节点 id（图上点击时携带，优先于 name）
 * @param project    项目名（= kbId）
 * @param filePath   同名消歧提示（后缀匹配）
 * @param withSource 是否附源码段（模型缺省即为附带，null 时不发该字段走模型默认）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphNodeRequest(
        String name,
        Integer id,
        String project,
        @JsonProperty("file_path") String filePath,
        @JsonProperty("with_source") Boolean withSource) {

    public GraphNodeRequest {
        if (name != null && name.isBlank()) {
            name = null;
        }
        if (project != null && project.isBlank()) {
            project = null;
        }
        if (filePath != null && filePath.isBlank()) {
            filePath = null;
        }
        // 模型侧 name 为必填字段（Pydantic：id-only 请求缺 name 直接 422 {"loc":["body","name"],
        // "msg":"Field required"}）。模型在 id 非空时忽略 name 内容（实测 name="" 可按 id 正常命中），
        // 故 id-only 时显式发空串，不能省略字段。
        if (name == null && id != null) {
            name = "";
        }
    }
}
