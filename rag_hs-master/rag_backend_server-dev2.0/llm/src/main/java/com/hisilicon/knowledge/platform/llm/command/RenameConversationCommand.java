package com.hisilicon.knowledge.platform.llm.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重命名会话命令
 *
 * @param conversationId 会话 ID（必填）
 * @param title          新标题（必填，≤200 字）
 */
public record RenameConversationCommand(
        @NotBlank(message = "{app.chat.conversationId.notblank}")
        String conversationId,

        @NotBlank(message = "{app.chat.title.notblank}")
        @Size(max = 200, message = "{app.chat.title.size}")
        String title
) {
}
