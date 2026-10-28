package com.hisilicon.knowledge.platform.llm.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层对话消息实体（不可变）
 * <p>会话内的多轮消息（USER / ASSISTANT）。USER 消息携带消息级配置（搜索范围/知识库 id/深思/
 * 文档类型/版本，用于历史回显）；ASSISTANT 消息配置为 null。</p>
 */
public class ConversationMessageEntity {

    /** 用户消息 */
    public static final String ROLE_USER = "USER";

    /** 助手消息 */
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    /** 系统消息 */
    public static final String ROLE_SYSTEM = "SYSTEM";

    private final ConversationMessageId id;
    private final ConversationId conversationId;
    private final String role;
    private final String content;
    private final LocalDateTime createdAt;

    /** 消息级官方库开关（仅 USER 行；ASSISTANT 行 null） */
    private final Boolean searchKbEnabled;
    /** 消息级非官方库开关（仅 USER 行；ASSISTANT 行 null） */
    private final Boolean searchExternalEnabled;
    /** 消息级知识库 id，逗号分隔（仅 USER 行；null/空=不限→走纯 LLM） */
    private final String kbIds;
    /** 消息级深度思考（仅 USER 行；ASSISTANT 行 null） */
    private final Boolean deepThinking;
    /** 消息级文档类型，逗号分隔（仅 USER 行；null=全部类型） */
    private final String docTypes;
    /** 消息级文档版本（仅 USER 行；null=最新版；ASSISTANT 行 null） */
    private final String version;

    private ConversationMessageEntity(Builder builder) {
        this.id = builder.id;
        this.conversationId = builder.conversationId;
        this.role = builder.role;
        this.content = builder.content;
        this.createdAt = builder.createdAt;
        this.searchKbEnabled = builder.searchKbEnabled;
        this.searchExternalEnabled = builder.searchExternalEnabled;
        this.kbIds = builder.kbIds;
        this.deepThinking = builder.deepThinking;
        this.docTypes = builder.docTypes;
        this.version = builder.version;
    }

    /**
     * 领域工厂：以预生成 id 创建 ASSISTANT 消息（无配置）
     * <p>SSE 流需在模型回答落库前先行下发 assistantMessageId（event:message 首事件），
     * 流结束落库时复用该 id，保证下发 id 与落库主键一致。</p>
     */
    public static ConversationMessageEntity create(ConversationId conversationId, ConversationMessageId id,
                                                   String role, String content) {
        return ConversationMessageEntity.builder()
                .id(id)
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域工厂：创建消息（无配置，ASSISTANT 用）
     */
    public static ConversationMessageEntity create(ConversationId conversationId, String role, String content) {
        return ConversationMessageEntity.builder()
                .id(ConversationMessageId.of(UUID.randomUUID().toString()))
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域工厂：创建 USER 消息并携带消息级配置（用于历史回显）
     *
     * <p>G.MET.01：配置项收敛为 {@link SearchScopeConfig}（搜索范围）与
     * {@link MessageFilterConfig}（深度思考/文档类型/版本）两个值对象。</p>
     */
    public static ConversationMessageEntity create(ConversationId conversationId, String role, String content,
                                                   SearchScopeConfig searchScope, MessageFilterConfig filters) {
        return ConversationMessageEntity.builder()
                .id(ConversationMessageId.of(UUID.randomUUID().toString()))
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .searchKbEnabled(searchScope.kbEnabled())
                .searchExternalEnabled(searchScope.externalEnabled())
                .kbIds(searchScope.kbIds())
                .deepThinking(filters.deepThinking())
                .docTypes(filters.docTypes())
                .version(filters.version())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ConversationMessageId getId() { return id; }
    public ConversationId getConversationId() { return conversationId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Boolean getSearchKbEnabled() { return searchKbEnabled; }
    public Boolean getSearchExternalEnabled() { return searchExternalEnabled; }
    public String getKbIds() { return kbIds; }
    public Boolean getDeepThinking() { return deepThinking; }
    public String getDocTypes() { return docTypes; }
    public String getVersion() { return version; }

    public static class Builder {
        private ConversationMessageId id;
        private ConversationId conversationId;
        private String role;
        private String content;
        private LocalDateTime createdAt;
        private Boolean searchKbEnabled;
        private Boolean searchExternalEnabled;
        private String kbIds;
        private Boolean deepThinking;
        private String docTypes;
        private String version;

        public Builder id(ConversationMessageId id) { this.id = id; return this; }
        public Builder conversationId(ConversationId conversationId) { this.conversationId = conversationId; return this; }
        public Builder role(String role) { this.role = role; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder searchKbEnabled(Boolean searchKbEnabled) { this.searchKbEnabled = searchKbEnabled; return this; }
        public Builder searchExternalEnabled(Boolean searchExternalEnabled) { this.searchExternalEnabled = searchExternalEnabled; return this; }
        public Builder kbIds(String kbIds) { this.kbIds = kbIds; return this; }
        public Builder deepThinking(Boolean deepThinking) { this.deepThinking = deepThinking; return this; }
        public Builder docTypes(String docTypes) { this.docTypes = docTypes; return this; }
        public Builder version(String version) { this.version = version; return this; }

        public ConversationMessageEntity build() {
            return new ConversationMessageEntity(this);
        }
    }
}
