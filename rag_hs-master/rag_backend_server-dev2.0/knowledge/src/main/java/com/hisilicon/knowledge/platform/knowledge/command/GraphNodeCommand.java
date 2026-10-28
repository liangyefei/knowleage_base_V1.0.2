package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码图谱节点详情命令（前端 → POST /kg/graph-node → dual-mcp /graph_node）。
 *
 * @param kbId       知识库 ID（→ dual-mcp 的 project）
 * @param name       实体名（id 为 null 时必填，服务层校验）
 * @param id         节点 id（图上点击时携带，优先于 name）
 * @param filePath   同名消歧提示（后缀匹配，可选）
 * @param withSource 是否附源码段（可选，null 走模型默认 true）
 */
public record GraphNodeCommand(
        @NotBlank(message = "{app.kb.id.notblank}") String kbId,
        String name,
        Integer id,
        String filePath,
        Boolean withSource) {
}
