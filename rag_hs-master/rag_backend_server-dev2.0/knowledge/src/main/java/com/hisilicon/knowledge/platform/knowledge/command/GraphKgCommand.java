package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 文档知识图谱查询命令（前端 → POST /kg/graph-kg → dual-mcp /graph_kg）。
 *
 * @param kbId 知识库 ID（可选；作为模型侧 palace 按库过滤。缺省=全库模式，向后兼容）
 * @param q    KG 实体名（精确匹配：文档全名/kbId/普通实体）
 */
public record GraphKgCommand(
        String kbId,
        @NotBlank(message = "{app.kg.query.notblank}") String q) {
}
