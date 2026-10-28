package com.hisilicon.knowledge.platform.llm.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 删除会话命令
 *
 * @param conversationId 会话 ID（必填）
 */
public record DeleteConversationCommand(
        @NotBlank(message = "{app.chat.conversationId.notblank}")
        String conversationId
) {
}
