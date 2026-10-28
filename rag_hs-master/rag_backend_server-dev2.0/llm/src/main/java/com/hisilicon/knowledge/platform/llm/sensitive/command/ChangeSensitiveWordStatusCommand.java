package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量切换敏感词状态命令（1=启用，2=禁用）
 *
 * @param ids    词库记录 id 列表
 * @param status 目标状态
 */
public record ChangeSensitiveWordStatusCommand(
        @NotEmpty(message = "{app.sensitive.wordIds.notempty}") List<String> ids,
        @NotNull(message = "{app.sensitive.status.notnull}") Short status) {
}
