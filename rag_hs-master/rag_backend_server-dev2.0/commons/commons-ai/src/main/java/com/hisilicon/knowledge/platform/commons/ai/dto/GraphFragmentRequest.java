package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /graph_fragment} 请求体（《API-GRAPH-FRAGMENT.md》§2，2026-10-23）。
 * 字段名即 wire 格式（恰无 snake_case 需求）。
 *
 * <p>入参来源（前端链路，后端不做二次调用）：{@code subject/predicate/object} 来自
 * {@code /graph_kg} 响应 {@code edges[]}（source/type/target 一一对应），前端在图谱页
 * 关系边面板持有后传入；{@code palace} 为图谱页选中知识库的 kbId。</p>
 *
 * @param subject   关系主体实体名（必填）
 * @param predicate 关系谓词；缺省/空串时模型按「主体+客体」匹配（null 不进 wire 格式）
 * @param object    关系客体实体名（可选，null 不进 wire 格式）
 * @param palace    知识库 id（kbId，必填），片段只在该库范围内查找
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphFragmentRequest(String subject, String predicate, String object, String palace) {

    /** 紧凑构造：空白串归一为 null（NON_NULL 下 predicate/object 不进 wire 格式）。 */
    public GraphFragmentRequest {
        if (predicate != null && predicate.isBlank()) {
            predicate = null;
        }
        if (object != null && object.isBlank()) {
            object = null;
        }
    }
}
