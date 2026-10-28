package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 切换敏感词标签状态命令（1=启用，2=禁用）
 *
 * @param id     标签 id
 * @param status 目标状态
 */
public record ChangeSensitiveTagStatusCommand(
        @NotBlank(message = "{app.sensitive.tagId.notblank}") String id,
        @NotNull(message = "{app.sensitive.status.notnull}") Short status) {
}
