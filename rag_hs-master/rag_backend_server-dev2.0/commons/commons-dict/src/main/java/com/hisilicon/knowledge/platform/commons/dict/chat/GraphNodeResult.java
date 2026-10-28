package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 代码图谱节点详情（dual-mcp {@code POST /graph_node} 响应，跨模块共享 DTO）。
 *
 * <p><b>平台契约（对前端）</b>：{@code node} 为基础字段，增强信息（{@code source}/{@code in_types}/
 * {@code out_types}/{@code props}）提升到顶层。模型实际把这几项放在 {@code node} 内部
 * （2026-10 实测：{@code node.source / node.in_types / node.out_types / node.props}），由 llm 侧
 * {@code KnowledgeGraphClientImpl} 在反序列化后提升到顶层，同时兼容模型将来把字段挪回顶层；
 * 详见 {@code KnowledgeGraphClientImpl#toGraphNode}。</p>
 *
 * <p>{@code source}（完整源码段，2600 字符上限）仅 {@code with_source=true} 时出现（模型缺省即为 true）；
 * {@code fileVersion} 仅模型侧项目配置了版本映射时出现，二者均可为 null。
 * {@code inTypes}/{@code outTypes} 为邻边类型→边数统计（如 in_types: {"CALLS": 29, "DEFINES": 1}）。
 * {@code props} 为节点属性袋（如 complexity/lines/is_exported/is_test/is_entry_point，或文件节点的
 * extension），键值随节点类型变化，故用 {@code Map<String,Object>} 透传。
 * {@code extra} 为模型 {@code node} 内<b>未纳入本契约</b>的字段原样透传（模型新增字段自动出现在这里，
 * 后端无需改代码）；同时由 llm 侧漂移告警提示该字段已出现，是否提升为稳定字段由平台决定。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphNodeResult(
        GraphSearchResult.GraphNode node,
        String source,
        @JsonProperty("file_version") String fileVersion,
        @JsonProperty("in_types") Map<String, Integer> inTypes,
        @JsonProperty("out_types") Map<String, Integer> outTypes,
        Map<String, Object> props,
        Map<String, Object> extra,
        @JsonProperty("elapsed_ms") Double elapsedMs) {
}
