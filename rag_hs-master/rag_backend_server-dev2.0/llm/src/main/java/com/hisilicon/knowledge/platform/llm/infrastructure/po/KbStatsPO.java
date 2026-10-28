package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 问答 KB 统计表
 * <p>一回答一行（message_id 主键 = 平台侧 assistantMessageId）。
 * 列定义与 docs/sql/migration_019_kb_stats.sql 保持一致。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "qa_kb_stats")
public class KbStatsPO {

    /** ASSISTANT 消息 id（= conversation_messages.id，主键兼外键） */
    @Id
    public String messageId;

    /** 是否触发 RAG 检索 */
    @Column(name = "rag_triggered")
    public Boolean ragTriggered;

    /** 知识库是否命中 */
    @Column(name = "kb_hit")
    public Boolean kbHit;

    /** 未命中原因（kb_hit=false 时模型给出） */
    @Column(name = "no_hit_reason")
    public String noHitReason;

    /** 召回片段条数 */
    @Column(name = "retrieved_count")
    public Integer retrievedCount;

    /** 实际注入上下文的片段条数 */
    @Column(name = "used_count")
    public Integer usedCount;

    /** 命中的知识库列表，逗号分隔（DDL 为 TEXT，须显式 columnDefinition） */
    @Column(columnDefinition = "TEXT")
    public String hitKbs;

    /** 改写后的检索查询（TEXT 列显式定义） */
    @Column(columnDefinition = "TEXT")
    public String rewrittenQuery;

    /** top 片段相似度分数 */
    @Column(name = "top_score")
    public BigDecimal topScore;

    /** 路由原因 */
    @Column(name = "route_reason")
    public String routeReason;

    /** 检索/生成是否降级 */
    public Boolean degraded;

    /** 模型下发的 kb_stats 原始 JSON 留档（TEXT 列显式定义） */
    @Column(name = "kb_stats_json", columnDefinition = "TEXT")
    public String kbStatsJson;

    /** 用户问题原文（落库时冗余，仪表盘/未命中列表单表查询；TEXT 列显式定义） */
    @Column(columnDefinition = "TEXT")
    public String queryText;

    /** 提问账号（会话归属账号） */
    public String account;

    /** 来源渠道：CHAT_QA/RECEPTION_BOT/AGENT_CALL（缺省 CHAT_QA） */
    public String source;

    /** 未命中处理状态：PENDING/PROCESSING/RESOLVED（缺省 PENDING；仅 kb_hit=false 行有业务意义） */
    @Column(name = "handle_status")
    public String handleStatus;

    /** 用户选择的知识库 id 列表，逗号分隔（芯片维度命中率分母口径；TEXT 列显式定义） */
    @Column(name = "selected_kbs", columnDefinition = "TEXT")
    public String selectedKbs;

    /** 知识库名称快照（落库时 selected_kbs ∪ hit_kbs 的 id→name JSON；解析失败回退 id；TEXT 列显式定义） */
    @Column(name = "kb_names_json", columnDefinition = "TEXT")
    public String kbNamesJson;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
