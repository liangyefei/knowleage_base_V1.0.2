package com.hisilicon.knowledge.platform.llm.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 置顶 / 取消置顶会话命令
 *
 * @param conversationId 会话 ID（必填）
 */
public record PinConversationCommand(
        @NotBlank(message = "{app.chat.conversationId.notblank}")
        String conversationId
) {
}
