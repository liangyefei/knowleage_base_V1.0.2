package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 更新知识库命令（全量替换语义）
 *
 * @param kbId        知识库 ID（必填）
 * @param kbName      知识库名称（必填，3-50 字符）
 * @param description 描述（可选，缺省或空串表示清空描述）
 */
public record UpdateKbCommand(
        @NotBlank(message = "{app.kb.id.notblank}")
        String kbId,

        @NotBlank(message = "{app.kb.name.notblank}")
        @SizeWithName(fieldName = "kbName", min = 3, max = 50)
        String kbName,

        @SizeWithName(fieldName = "description", max = 200)
        String description) {
}
