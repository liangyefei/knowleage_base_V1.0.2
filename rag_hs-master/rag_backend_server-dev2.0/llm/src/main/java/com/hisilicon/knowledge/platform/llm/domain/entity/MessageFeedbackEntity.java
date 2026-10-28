package com.hisilicon.knowledge.platform.llm.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层回答反馈实体（不可变）
 * <p>一消息一用户一行（UNIQUE(message_id, user_id)），重复反馈 UPSERT 覆盖保留最新态。
 * HELPFUL 行 reasons/comment 为 null；仅 ASSISTANT 消息可反馈（应用层校验）。</p>
 */
public class MessageFeedbackEntity {

    private final String id;
    private final String messageId;
    private final String conversationId;
    private final String userId;
    private final String rating;
    private final String reasons;
    private final String comment;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private MessageFeedbackEntity(Builder builder) {
        this.id = builder.id;
        this.messageId = builder.messageId;
        this.conversationId = builder.conversationId;
        this.userId = builder.userId;
        this.rating = builder.rating;
        this.reasons = builder.reasons;
        this.comment = builder.comment;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：新建反馈（首次提交）
     *
     * <p>G.MET.01：反馈内容收敛为 {@link FeedbackContent} 值对象。</p>
     */
    public static MessageFeedbackEntity create(String messageId, String conversationId, String userId,
                                               FeedbackContent content) {
        LocalDateTime now = LocalDateTime.now();
        return builder()
                .id(UUID.randomUUID().toString())
                .messageId(messageId)
                .conversationId(conversationId)
                .userId(userId)
                .rating(content.rating())
                .reasons(content.reasons())
                .comment(content.comment())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域工厂：已有反馈更新（覆盖式最新态，保留首次 createdAt）
     */
    public static MessageFeedbackEntity update(MessageFeedbackEntity existing,
                                               String rating, String reasons, String comment) {
        return builder()
                .id(existing.id)
                .messageId(existing.messageId)
                .conversationId(existing.conversationId)
                .userId(existing.userId)
                .rating(rating)
                .reasons(reasons)
                .comment(comment)
                .createdAt(existing.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public String getMessageId() { return messageId; }
    public String getConversationId() { return conversationId; }
    public String getUserId() { return userId; }
    public String getRating() { return rating; }
    public String getReasons() { return reasons; }
    public String getComment() { return comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static class Builder {
        private String id;
        private String messageId;
        private String conversationId;
        private String userId;
        private String rating;
        private String reasons;
        private String comment;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder rating(String rating) { this.rating = rating; return this; }
        public Builder reasons(String reasons) { this.reasons = reasons; return this; }
        public Builder comment(String comment) { this.comment = comment; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public MessageFeedbackEntity build() {
            return new MessageFeedbackEntity(this);
        }
    }
}
