package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 创建会话响应
 *
 * <p>搜索范围/芯片/深度思考为消息级配置（随 USER 消息持久化），不在会话级返回。</p>
 *
 * @param conversationId 会话 ID
 * @param title          对话标题
 * @param fileName       文件原始名（可空）
 * @param fileSize       文件大小（可空）
 * @param fileType       文件类型（可空）
 * @param createdAt      创建时间
 */
public record CreateConversationResponse(
        String conversationId,
        String title,
        String fileName,
        Long fileSize,
        String fileType,
        String createdAt
) {
}
