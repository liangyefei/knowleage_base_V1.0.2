package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑敏感词命令（全量替换：分类 + 词文本 + 状态 + 备注）
 *
 * @param id      词库记录 id
 * @param tagId   目标标签 id
 * @param word    敏感词
 * @param status  状态：1=启用 / 2=停用（必填）
 * @param remarks 备注，可空（≤512）；null 表示清空
 */
public record EditSensitiveWordCommand(
        @NotBlank(message = "{app.sensitive.wordId.notblank}") String id,
        @NotBlank(message = "{app.sensitive.tagId.notblank}") String tagId,
        @NotBlank(message = "{app.sensitive.word.notblank}") @Size(max = 255, message = "{app.sensitive.word.size}") String word,
        @NotNull(message = "{app.sensitive.status.notnull}") Short status,
        @Size(max = 512, message = "{app.sensitive.remarks.size}") String remarks) {
}
