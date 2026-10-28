package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 审核历史表
 * <p>
 * 记录文档的完整审核过程，包括审核人、操作、意见、时间戳。
 * 文档生命周期中每次状态变更（如 DRAFT→REVIEWING→PUBLISHED）的审核记录均在此表。
 * 审核记录不可删除、不可修改，用于审计追溯。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "review_history")
public class ReviewHistoryPO {

    @Id
    private String id;

    /** 文档 ID，关联 documents.id */
    @Column(name = "document_id")
    private String documentId;

    /** 审核人用户 ID */
    @Column(name = "reviewer_id")
    private String reviewerId;

    /** 生命周期动作：SUBMIT_REVIEW / CANCEL_REVIEW / APPROVE / REJECT / ARCHIVE / DEPRECATE / EDIT 等 */
    private String action;

    /** 从哪个状态变更 */
    private String fromStatus;

    /** 变更到哪个状态 */
    private String toStatus;

    /** 动作发生时文档的版本号（documents.current_version 快照） */
    private String version;

    /** 审核意见/操作说明 */
    private String comment;

    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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
}
