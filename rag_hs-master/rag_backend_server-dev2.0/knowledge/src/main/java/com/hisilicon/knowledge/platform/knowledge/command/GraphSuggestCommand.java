package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 搜索联想命令（前端 → POST /kg/graph-suggest → dual-mcp /graph_suggest）。
 *
 * @param kbId  知识库 ID（→ dual-mcp 的 project，代码源作用域）
 * @param q     实体名片段
 * @param limit 候选条数（可选，1–30，null 走模型默认 10）
 */
public record GraphSuggestCommand(
        @NotBlank(message = "{app.kb.id.notblank}") String kbId,
        @NotBlank(message = "{app.kg.query.notblank}") String q,
        Integer limit) {
}
