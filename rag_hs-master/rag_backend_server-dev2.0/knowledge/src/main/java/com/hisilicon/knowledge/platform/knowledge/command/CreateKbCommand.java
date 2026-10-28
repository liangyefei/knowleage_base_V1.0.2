package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建知识库命令
 *
 * @param kbName     知识库名称（必填）
 * @param description 描述（可为空）
 */
public record CreateKbCommand(
        @NotBlank(message = "{app.kb.name.notblank}")
        @SizeWithName(fieldName = "kbName", min = 3, max = 50)
        String kbName,

        @SizeWithName(fieldName = "description", max = 200)
        String description) {
}
