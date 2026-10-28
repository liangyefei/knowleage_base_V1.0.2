package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 热门词表（高频用户问题词库；每知识库最多 4 条启用中，应用层 advisory lock 保证）
 *
 * @author hisilicon
 */
@Entity
@Table(name = "hot_words")
public class HotWordPO {

    /** 主键，应用层生成（"hw"+32hex，IdGenerator.hotWord()） */
    @Id
    private String id;

    /** 热门词内容（用户问题文本），同知识库内唯一 */
    @Column(length = 200, nullable = false)
    private String content;

    /** 所属知识库 id，关联 knowledge_bases.id，必填 */
    @Column(name = "kb_id", nullable = false)
    private String kbId;

    /** 排序号（数值小者靠前），列表按 (sort_order, updated_at) 排序 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 状态：1=启用中 / 0=已停用 */
    @Column(nullable = false)
    private Short status;

    /** 创建人账号，取 JWT sub */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
