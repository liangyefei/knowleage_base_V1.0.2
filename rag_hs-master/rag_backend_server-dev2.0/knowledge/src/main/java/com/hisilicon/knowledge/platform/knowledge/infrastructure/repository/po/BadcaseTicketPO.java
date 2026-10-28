package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Badcase 工单 PO（主表，聚合根）。
 * <p>差异化区块数据存 source_payload JSONB（见设计文档 §3.1）。</p>
 * <p>jsonb 列在 PO 层统一映射为 String，由应用层 ObjectMapper 序列化/反序列化：
 * Hibernate Reactive 对 List/Map 类型的 jsonb 反序列化存在 bug（JsonArray 被当 JsonObject 处理）。</p>
 */
@Entity
@Table(name = "badcase_tickets")
public class BadcaseTicketPO {

    @Id
    public String id;

    @Column(name = "ticket_no", nullable = false, length = 20)
    public String ticketNo;

    @Column(name = "source", nullable = false, length = 30)
    public String source;

    @Column(name = "problem_type", nullable = false, length = 40)
    public String problemType;

    @Column(name = "description", nullable = false, length = 1000)
    public String description;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "PENDING";

    @Column(name = "kb_id", length = 36)
    public String kbId;

    @Column(name = "kb_name_snapshot", length = 200)
    public String kbNameSnapshot;

    /** 涉及文档 ID 数组 JSON，如 ["uuid-1","uuid-2"]。PG 列 jsonb，强制按 varchar 读写（应用层自行序列化）。 */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "related_doc_ids", columnDefinition = "jsonb")
    public String relatedDocIds;

    /** 涉及文档名称快照数组 JSON。PG 列 jsonb，强制按 varchar 读写。 */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "related_doc_names", columnDefinition = "jsonb")
    public String relatedDocNames;

    /** 差异化区块 JSONB：结构按来源不同。PG 列 jsonb，强制按 varchar 读写。 */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_payload", columnDefinition = "jsonb")
    public String sourcePayload;

    @Column(name = "latest_handling_remark", length = 1000)
    public String latestHandlingRemark;

    @Column(name = "latest_handled_by", length = 50)
    public String latestHandledBy;

    @Column(name = "latest_handled_by_name", length = 100)
    public String latestHandledByName;

    @Column(name = "latest_handled_at")
    public LocalDateTime latestHandledAt;

    @Column(name = "submitted_by", nullable = false, length = 50)
    public String submittedBy;

    @Column(name = "submitted_by_name", length = 100)
    public String submittedByName;

    @Column(name = "submitted_at", nullable = false)
    public LocalDateTime submittedAt;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;
}
