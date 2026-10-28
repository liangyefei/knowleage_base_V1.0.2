package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码图谱邻接查询命令（前端 → POST /kg/graph-search → dual-mcp /graph_search）。
 *
 * @param kbId       知识库 ID（→ dual-mcp 的 project）
 * @param q          实体关键字（符号名/文档名/文件名）
 * @param limit      邻接边数上限（可选，null 走模型默认 80）
 * @param candidates 同名/近义其它匹配实体数（可选，null 走模型默认 6）
 */
public record GraphSearchCommand(
        @NotBlank(message = "{app.kb.id.notblank}") String kbId,
        @NotBlank(message = "{app.kg.query.notblank}") String q,
        Integer limit,
        Integer candidates) {
}
