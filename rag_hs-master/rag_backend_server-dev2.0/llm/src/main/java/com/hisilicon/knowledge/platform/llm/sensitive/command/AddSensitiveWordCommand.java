package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增敏感词命令
 *
 * @param tagId   标签 id
 * @param word    敏感词
 * @param status  状态：1=启用 / 2=停用（必填）
 * @param remarks 备注，可空（≤512）
 */
public record AddSensitiveWordCommand(
        @NotBlank(message = "{app.sensitive.tagId.notblank}") String tagId,
        @NotBlank(message = "{app.sensitive.word.notblank}") @Size(max = 255, message = "{app.sensitive.word.size}") String word,
        @NotNull(message = "{app.sensitive.status.notnull}") Short status,
        @Size(max = 512, message = "{app.sensitive.remarks.size}") String remarks) {
}
