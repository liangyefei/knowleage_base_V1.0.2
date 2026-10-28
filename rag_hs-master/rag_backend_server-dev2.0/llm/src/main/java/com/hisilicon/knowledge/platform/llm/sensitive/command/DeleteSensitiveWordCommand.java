package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 删除敏感词命令
 *
 * @param ids 词库记录 id 列表
 */
public record DeleteSensitiveWordCommand(@NotEmpty(message = "{app.sensitive.wordIds.notempty}") List<String> ids) {
}
