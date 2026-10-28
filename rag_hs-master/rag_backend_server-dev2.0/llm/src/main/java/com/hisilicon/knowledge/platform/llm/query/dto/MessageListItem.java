package com.hisilicon.knowledge.platform.llm.query.dto;

import java.time.LocalDateTime;

/**
 * 对话消息项
 *
 * <p>消息级配置（searchKbEnabled/searchExternalEnabled/kbIds/deepThinking
 * /docTypes/version）仅 USER 行写入，ASSISTANT 行为 null——前端按 role 判断是否显示配置徽标。</p>
 *
 * @param messageId             消息 ID
 * @param role                  消息角色 USER / ASSISTANT
 * @param content               消息正文
 * @param createdAt             发送时间
 * @param searchKbEnabled       消息级官方库开关（USER 行；ASSISTANT null）
 * @param searchExternalEnabled 消息级非官方库开关（USER 行；ASSISTANT null）
 * @param kbIds                 消息级知识库 id（USER 行；null/空=不限→走纯 LLM）
 * @param deepThinking          消息级深度思考（USER 行；ASSISTANT null）
 * @param docTypes              消息级文档类型，逗号分隔（USER 行；null=全部类型）
 * @param version               消息级文档版本（USER 行；null=最新版；ASSISTANT null）
 * @param feedbackRating        当前用户反馈评级 HELPFUL/UNHELPFUL（未反馈/USER 行 null）
 * @param feedbackReasons       没帮助原因，逗号分隔（仅 UNHELPFUL；其余 null）
 * @param feedbackComment       反馈具体描述（可空；未反馈/USER 行 null）
 * @param feedbackUpdatedAt     最近反馈时间（未反馈/USER 行 null）
 */
public record MessageListItem(
        String messageId,
        String role,
        String content,
        LocalDateTime createdAt,
        Boolean searchKbEnabled,
        Boolean searchExternalEnabled,
        String kbIds,
        Boolean deepThinking,
        String docTypes,
        String version,
        String feedbackRating,
        String feedbackReasons,
        String feedbackComment,
        LocalDateTime feedbackUpdatedAt
) {
}
