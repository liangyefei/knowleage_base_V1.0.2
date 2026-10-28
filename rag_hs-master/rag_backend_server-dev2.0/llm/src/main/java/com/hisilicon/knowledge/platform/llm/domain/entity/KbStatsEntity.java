package com.hisilicon.knowledge.platform.llm.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领域层问答 KB 统计实体（不可变）
 * <p>一条 ASSISTANT 回答最多一行（message_id 主键 = 平台侧 assistantMessageId =
 * conversation_messages.id；模型响应中的 message_id 恒为空串，禁止用于关联）。
 * 无 kb_stats 的回答不落行。列定义与 docs/sql/migration_019 + migration_020 保持一致。</p>
 */
public class KbStatsEntity {

    /** 未命中处理状态：待处理（缺省） */
    public static final String STATUS_PENDING = "PENDING";
    /** 未命中处理状态：处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 未命中处理状态：已补充 */
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 来源渠道：智能问答（缺省） */
    public static final String SOURCE_CHAT_QA = "CHAT_QA";
    /** 来源渠道：前台机器人 */
    public static final String SOURCE_RECEPTION_BOT = "RECEPTION_BOT";
    /** 来源渠道：Agent 调用 */
    public static final String SOURCE_AGENT_CALL = "AGENT_CALL";

    /** ASSISTANT 消息 id（= qa_kb_stats.message_id 主键） */
    private final String messageId;
    private final Boolean ragTriggered;
    private final Boolean kbHit;
    private final String noHitReason;
    private final Integer retrievedCount;
    private final Integer usedCount;
    private final String hitKbs;
    private final String rewrittenQuery;
    private final BigDecimal topScore;
    private final String routeReason;
    private final Boolean degraded;
    private final String kbStatsJson;
    /** 用户问题原文（落库时冗余，仪表盘/未命中列表单表查询） */
    private final String queryText;
    /** 提问账号（会话归属账号） */
    private final String account;
    /** 来源渠道：CHAT_QA/RECEPTION_BOT/AGENT_CALL */
    private final String source;
    /** 未命中处理状态：PENDING/PROCESSING/RESOLVED */
    private final String handleStatus;
    /** 用户选择的知识库 id 列表，逗号分隔（芯片维度命中率分母口径） */
    private final String selectedKbs;
    /** 知识库名称快照（落库时 selected_kbs ∪ hit_kbs 的 id→name JSON；解析失败回退 id） */
    private final String kbNamesJson;
    /** 模型回答内容（非本表列，仓储查询时 join conversation_messages 的 ASSISTANT 消息正文回填） */
    private String answerText;
    private final LocalDateTime createdAt;

    private KbStatsEntity(Builder builder) {
        this.messageId = builder.messageId;
        this.ragTriggered = builder.ragTriggered;
        this.kbHit = builder.kbHit;
        this.noHitReason = builder.noHitReason;
        this.retrievedCount = builder.retrievedCount;
        this.usedCount = builder.usedCount;
        this.hitKbs = builder.hitKbs;
        this.rewrittenQuery = builder.rewrittenQuery;
        this.topScore = builder.topScore;
        this.routeReason = builder.routeReason;
        this.degraded = builder.degraded;
        this.kbStatsJson = builder.kbStatsJson;
        this.queryText = builder.queryText;
        this.account = builder.account;
        this.source = builder.source;
        this.handleStatus = builder.handleStatus;
        this.selectedKbs = builder.selectedKbs;
        this.kbNamesJson = builder.kbNamesJson;
        this.answerText = builder.answerText;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getMessageId() { return messageId; }
    public Boolean getRagTriggered() { return ragTriggered; }
    public Boolean getKbHit() { return kbHit; }
    public String getNoHitReason() { return noHitReason; }
    public Integer getRetrievedCount() { return retrievedCount; }
    public Integer getUsedCount() { return usedCount; }
    public String getHitKbs() { return hitKbs; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public BigDecimal getTopScore() { return topScore; }
    public String getRouteReason() { return routeReason; }
    public Boolean getDegraded() { return degraded; }
    public String getKbStatsJson() { return kbStatsJson; }
    public String getQueryText() { return queryText; }
    public String getAccount() { return account; }
    public String getSource() { return source; }
    public String getHandleStatus() { return handleStatus; }
    public String getSelectedKbs() { return selectedKbs; }
    public String getKbNamesJson() { return kbNamesJson; }
    public String getAnswerText() { return answerText; }
    /** 仓储 join 回填专用（非构造期字段，MapStruct 不映射） */
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public static class Builder {
        private String messageId;
        private Boolean ragTriggered;
        private Boolean kbHit;
        private String noHitReason;
        private Integer retrievedCount;
        private Integer usedCount;
        private String hitKbs;
        private String rewrittenQuery;
        private BigDecimal topScore;
        private String routeReason;
        private Boolean degraded;
        private String kbStatsJson;
        private String queryText;
        private String account;
        private String source;
        private String handleStatus;
        private String selectedKbs;
        private String kbNamesJson;
        private String answerText;
        private LocalDateTime createdAt;

        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder ragTriggered(Boolean ragTriggered) { this.ragTriggered = ragTriggered; return this; }
        public Builder kbHit(Boolean kbHit) { this.kbHit = kbHit; return this; }
        public Builder noHitReason(String noHitReason) { this.noHitReason = noHitReason; return this; }
        public Builder retrievedCount(Integer retrievedCount) { this.retrievedCount = retrievedCount; return this; }
        public Builder usedCount(Integer usedCount) { this.usedCount = usedCount; return this; }
        public Builder hitKbs(String hitKbs) { this.hitKbs = hitKbs; return this; }
        public Builder rewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; return this; }
        public Builder topScore(BigDecimal topScore) { this.topScore = topScore; return this; }
        public Builder routeReason(String routeReason) { this.routeReason = routeReason; return this; }
        public Builder degraded(Boolean degraded) { this.degraded = degraded; return this; }
        public Builder kbStatsJson(String kbStatsJson) { this.kbStatsJson = kbStatsJson; return this; }
        public Builder queryText(String queryText) { this.queryText = queryText; return this; }
        public Builder account(String account) { this.account = account; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder handleStatus(String handleStatus) { this.handleStatus = handleStatus; return this; }
        public Builder selectedKbs(String selectedKbs) { this.selectedKbs = selectedKbs; return this; }
        public Builder kbNamesJson(String kbNamesJson) { this.kbNamesJson = kbNamesJson; return this; }
        public Builder answerText(String answerText) { this.answerText = answerText; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public KbStatsEntity build() {
            return new KbStatsEntity(this);
        }
    }
}
