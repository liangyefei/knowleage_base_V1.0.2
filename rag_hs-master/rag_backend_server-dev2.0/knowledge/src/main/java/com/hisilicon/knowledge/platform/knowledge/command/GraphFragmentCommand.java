package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 文档图谱关系片段查询命令（前端 → POST /kg/graph-fragment → dual-mcp /graph_fragment）。
 *
 * <p>subject/predicate/object 来自前端先调 POST /kg/graph-kg 响应的 edges[]
 * （source/type/target 一一对应，前端在图谱页关系边面板持有后传入），后端不做二次调用。</p>
 *
 * @param kbId      知识库 ID（模型侧 palace，片段只在该库范围内查找，必填）
 * @param subject   关系主体实体名（必填）
 * @param predicate 关系谓词（可选；空白=模型按「主体+客体」匹配）
 * @param object    关系客体实体名（可选）
 */
public record GraphFragmentCommand(
        @NotBlank(message = "{app.kb.id.notblank}") String kbId,
        @NotBlank(message = "{app.kg.query.notblank}") String subject,
        String predicate,
        String object) {
}
