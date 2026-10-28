package com.hisilicon.knowledge.platform.llm.sensitive.command;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 删除敏感词标签命令（软删，级联删除该标签下全部词）
 *
 * @param ids 标签 id 列表
 */
public record DeleteSensitiveTagCommand(@NotEmpty(message = "{app.sensitive.tagIds.notempty}") List<String> ids) {
}
