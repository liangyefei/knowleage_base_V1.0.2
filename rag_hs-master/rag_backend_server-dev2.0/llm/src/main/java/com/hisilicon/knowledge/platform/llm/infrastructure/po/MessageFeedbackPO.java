package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 对话回答反馈表
 * <p>一消息一用户一行（唯一约束 uq_message_feedbacks_msg_user），重复反馈覆盖保留最新态。
 * 列定义与 docs/sql/migration_017_message_feedback.sql 保持一致。</p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "message_feedbacks")
public class MessageFeedbackPO {

    @Id
    private String id;

    /** 反馈目标消息 id，关联 conversation_messages.id（仅 ASSISTANT 行） */
    @Column(name = "message_id")
    private String messageId;

    /** 冗余所属会话 id，便于按会话聚合 */
    @Column(name = "conversation_id")
    private String conversationId;

    /** 反馈人账号，取 JWT sub（OperatorIdHolder） */
    @Column(name = "user_id")
    private String userId;

    /** 反馈评级：HELPFUL / UNHELPFUL */
    private String rating;

    /** 没帮助原因，逗号分隔多选（WRONG_ANSWER/OFF_TOPIC/IRRELEVANT/CODE_ERROR/OUTDATED）；HELPFUL 行 NULL */
    private String reasons;

    /** 具体描述，可空（comment 为 SQL 关键字风险点，显式 TEXT 定义，同 content 列经验） */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getReasons() {
        return reasons;
    }

    public void setReasons(String reasons) {
        this.reasons = reasons;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
