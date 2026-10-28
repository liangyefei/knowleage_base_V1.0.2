package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 对话消息表
 * <p>会话内的多轮消息（用户/助手）。模型失败时 ASSISTANT 消息不落库（用户可重新提问）。</p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessagePO {

    @Id
    private String id;

    /** 所属会话 ID，关联 conversations.id */
    @Column(name = "conversation_id")
    private String conversationId;

    /** 消息角色：USER / ASSISTANT / SYSTEM */
    private String role;

    /** 消息正文（DDL 为 TEXT，须显式 columnDefinition 否则 Hibernate 默认 varchar(255) 校验失败） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 消息级官方库开关（仅 USER 行写入；ASSISTANT 行 NULL） */
    @Column(name = "search_kb_enabled")
    private Boolean searchKbEnabled;

    /** 消息级非官方库开关（仅 USER 行写入；ASSISTANT 行 NULL） */
    @Column(name = "search_external_enabled")
    private Boolean searchExternalEnabled;

    /** 消息级知识库 id，逗号分隔（仅 USER 行写入；NULL/空=不限→走纯 LLM） */
    @Column(name = "kb_ids")
    private String kbIds;

    /** 消息级深度思考（仅 USER 行写入；ASSISTANT 行 NULL） */
    @Column(name = "deep_thinking")
    private Boolean deepThinking;

    /** 消息级文档类型，逗号分隔（DOCUMENT/CODE/CONFIG/OTHER；仅 USER 行写入；NULL=全部类型） */
    @Column(name = "doc_types")
    private String docTypes;

    /** 消息级文档版本（仅 USER 行写入；NULL=最新版；ASSISTANT 行 NULL） */
    @Column(name = "version")
    private String version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getSearchKbEnabled() {
        return searchKbEnabled;
    }

    public void setSearchKbEnabled(Boolean searchKbEnabled) {
        this.searchKbEnabled = searchKbEnabled;
    }

    public Boolean getSearchExternalEnabled() {
        return searchExternalEnabled;
    }

    public void setSearchExternalEnabled(Boolean searchExternalEnabled) {
        this.searchExternalEnabled = searchExternalEnabled;
    }

    public String getKbIds() {
        return kbIds;
    }

    public void setKbIds(String kbIds) {
        this.kbIds = kbIds;
    }

    public Boolean getDeepThinking() {
        return deepThinking;
    }

    public void setDeepThinking(Boolean deepThinking) {
        this.deepThinking = deepThinking;
    }

    public String getDocTypes() {
        return docTypes;
    }

    public void setDocTypes(String docTypes) {
        this.docTypes = docTypes;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
