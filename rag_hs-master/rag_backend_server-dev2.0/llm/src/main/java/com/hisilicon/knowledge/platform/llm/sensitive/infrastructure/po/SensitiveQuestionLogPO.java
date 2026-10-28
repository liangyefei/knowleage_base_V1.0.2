package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 不合规提问记录表（kb_sensitive_question_logs）
 * <p>智能问答提问侧敏感词拦截后写入，供提问记录页审计查询。</p>
 */
@Entity
@Table(name = "kb_sensitive_question_logs", indexes = {
        @Index(name = "idx_sensitive_question_time", columnList = "request_time DESC"),
        @Index(name = "idx_sensitive_question_account", columnList = "account")
})
public class SensitiveQuestionLogPO {

    @Id
    private String id;

    /** 用户提问内容 */
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 提问账号 */
    @Column(name = "account", length = 128)
    private String account;

    /** 命中的敏感词 */
    @Column(name = "word", length = 255)
    private String word;

    /** 涉敏文本标签 */
    @Column(name = "label", length = 255)
    private String label;

    /** 标签标识 */
    @Column(name = "identification", length = 64)
    private String identification;

    /** 请求时间 */
    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public LocalDateTime getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(LocalDateTime requestTime) {
        this.requestTime = requestTime;
    }
}
