package com.hisilicon.knowledge.platform.llm.command;

/**
 * 洞察提交 badcase 工单命令。
 *
 * @param insightId   洞察记录 id
 * @param problemType 问题类型（前端自选：WRONG_ANSWER/CODE_ERROR/IRRELEVANT_ANSWER/OUTDATED_INFO/IRRELEVANT_RESULT/SENSITIVE_NOT_FLAGGED/NOT_SENSITIVE_FLAGGED）
 * @param description 问题描述（前端填写）
 */
public record InsightBadcaseCommand(
        String insightId,
        String problemType,
        String description) {
}
