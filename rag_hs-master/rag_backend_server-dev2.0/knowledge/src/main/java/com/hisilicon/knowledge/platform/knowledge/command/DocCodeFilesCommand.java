package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 映射文件清单命令（前端 → POST /kg/doc-code-files → dual-mcp /doc_code/files，《API-DOCCODE-FILES.md》）。
 *
 * @param kbId 知识库 ID（→ dual-mcp 的 project；kbId 不存在时模型侧 404 → C0001）
 * @param type 列表类型（可选）：code=被文档引用过的代码文件（模型默认）；doc=引用过代码符号的文档文件；
 *             其他值 → A0420（平台前置校验）
 */
public record DocCodeFilesCommand(
        @NotBlank(message = "{app.kb.id.notblank}") String kbId,
        String type) {
}
