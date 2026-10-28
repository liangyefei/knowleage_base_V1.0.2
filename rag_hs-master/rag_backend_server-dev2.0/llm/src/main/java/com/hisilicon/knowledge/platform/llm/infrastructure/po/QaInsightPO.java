package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问答智能洞察 PO（对应 qa_insights 表）。
 * <p>问答完成即落库（status=PENDING），定时/手动任务调模型打分类后置 ANALYZED。</p>
 */
@Entity
@Table(name = "qa_insights")
public class QaInsightPO {

    @Id
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "kb_id", length = 36)
    private String kbId;

    @Column(name = "question", columnDefinition = "text", nullable = false)
    private String question;

    @Column(name = "answer", columnDefinition = "text")
    private String answer;

    @Column(name = "is_sensitive", nullable = false)
    private Boolean isSensitive = false;

    @Column(name = "hit_sensitive_words", length = 500)
    private String hitSensitiveWords;

    @Column(name = "user_rating", length = 10)
    private String userRating;

    @Column(name = "status", nullable = false, length = 15)
    private String status = "PENDING";

    @Column(name = "category", length = 30)
    private String category;

    @Column(name = "category_reason", length = 500)
    private String categoryReason;

    @Column(name = "confidence")
    private Integer confidence;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Boolean getIsSensitive() { return isSensitive; }
    public void setIsSensitive(Boolean isSensitive) { this.isSensitive = isSensitive; }
    public String getHitSensitiveWords() { return hitSensitiveWords; }
    public void setHitSensitiveWords(String hitSensitiveWords) { this.hitSensitiveWords = hitSensitiveWords; }
    public String getUserRating() { return userRating; }
    public void setUserRating(String userRating) { this.userRating = userRating; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCategoryReason() { return categoryReason; }
    public void setCategoryReason(String categoryReason) { this.categoryReason = categoryReason; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(LocalDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
