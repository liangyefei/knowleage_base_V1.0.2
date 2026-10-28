package com.hisilicon.knowledge.platform.llm.query.dto;

import java.time.LocalDateTime;

/**
 * 未命中列表行（kb_hit=false，供补充知识、优化检索的工作流使用）
 *
 * @param messageId  消息 id（=assistantMessageId，关联 conversation_messages.id）
 * @param query      用户问题原文
 * @param account    提问账号
 * @param source     来源渠道（CHAT_QA/RECEPTION_BOT/AGENT_CALL）
 * @param kbName     所属知识库名称快照（多库逗号分隔；无快照回退 id 列表）
 * @param answerText 模型回答内容（关联 conversation_messages 的 ASSISTANT 消息正文）
 * @param createdAt  查询时间
 */
public record MissItem(String messageId, String query, String account,
                       String source, String kbName, String answerText, LocalDateTime createdAt) {
}
