package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Badcase 处理记录 PO（每次状态变更留一条历史）。
 */
@Entity
@Table(name = "badcase_handlings")
public class BadcaseHandlingPO {

    @Id
    public String id;

    @Column(name = "ticket_id", nullable = false, length = 36)
    public String ticketId;

    @Column(name = "action", nullable = false, length = 20)
    public String action;

    @Column(name = "remark", nullable = false, length = 1000)
    public String remark;

    @Column(name = "handled_by", nullable = false, length = 50)
    public String handledBy;

    @Column(name = "handled_by_name", length = 100)
    public String handledByName;

    @Column(name = "handled_at", nullable = false)
    public LocalDateTime handledAt;
}
