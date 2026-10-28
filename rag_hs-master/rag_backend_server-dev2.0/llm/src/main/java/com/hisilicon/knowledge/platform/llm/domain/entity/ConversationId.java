package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 会话 ID 值对象
 */
public record ConversationId(String value) {

    public static ConversationId of(String value) {
        return new ConversationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
