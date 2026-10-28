package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 消息 ID 值对象
 */
public record ConversationMessageId(String value) {

    public static ConversationMessageId of(String value) {
        return new ConversationMessageId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
