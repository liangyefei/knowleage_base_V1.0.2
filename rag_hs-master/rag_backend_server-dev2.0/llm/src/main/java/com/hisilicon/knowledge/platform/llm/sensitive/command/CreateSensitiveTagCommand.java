package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建敏感词标签命令
 *
 * @param textLabel           文本标签（展示名，如"涉政"）
 * @param labelIdentification 标签标识（如 POLITICAL）
 * @param remarks             说明（可选）
 * @param status              状态：1=启用 / 2=禁用（必填，不接受 0=删除）
 */
public record CreateSensitiveTagCommand(
        @NotBlank(message = "{app.sensitive.textLabel.notblank}") @Size(max = 255, message = "{app.sensitive.textLabel.size}") String textLabel,
        @NotBlank(message = "{app.sensitive.identification.notblank}") @Size(max = 64, message = "{app.sensitive.identification.size}") String labelIdentification,
        @Size(max = 512, message = "{app.sensitive.remarks.size}") String remarks,
        @NotNull(message = "{app.sensitive.status.notnull}") Short status) {
}
