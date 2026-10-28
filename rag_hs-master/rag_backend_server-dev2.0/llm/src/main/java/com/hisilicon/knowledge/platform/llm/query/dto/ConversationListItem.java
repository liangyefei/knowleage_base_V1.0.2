package com.hisilicon.knowledge.platform.llm.query.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项
 *
 * <p>搜索范围/芯片/深度思考为消息级配置（随 USER 消息持久化），不在会话级返回。</p>
 *
 * @param conversationId 会话 ID
 * @param title          对话标题
 * @param status         状态 ACTIVE / ARCHIVED
 * @param pinned         是否置顶（列表查询置顶会话排前）
 * @param fileName       文件原始名（可空）
 * @param fileSize       文件大小（可空）
 * @param fileType       文件类型（可空）
 * @param createdAt      创建时间
 */
public record ConversationListItem(
        String conversationId,
        String title,
        String status,
        Boolean pinned,
        String fileName,
        Long fileSize,
        String fileType,
        LocalDateTime createdAt
) {
}
