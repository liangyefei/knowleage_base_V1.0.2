package com.hisilicon.knowledge.platform.llm.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交回答反馈命令
 *
 * @param messageId 反馈目标消息 ID（必填，须为 ASSISTANT 回答）
 * @param rating    反馈评级（必填，HELPFUL / UNHELPFUL）
 * @param reasons   没帮助原因，逗号分隔多选（仅 UNHELPFUL 时有效；HELPFUL 服务端强制置空）
 * @param comment   具体描述（可选，≤2000 字）
 */
public record FeedbackMessageCommand(
        @NotBlank(message = "{app.chat.messageId.notblank}")
        String messageId,

        @NotBlank(message = "{app.chat.rating.notblank}")
        String rating,

        String reasons,

        @Size(max = 2000, message = "{app.chat.feedbackComment.size}")
        String comment
) {
}
