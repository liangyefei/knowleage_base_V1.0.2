package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 知识库表
 * <p>
 * 知识库是权限隔离的顶层容器——每个知识库独立绑定 ABAC 策略和 ACL 条目。
 * 扩展属性（所属组织、默认敏感等级等）存储在 knowledge_base_metadata 表。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBasePO {

    @Id
    private String id;

    /** 知识库名称，唯一 */
    private String name;

    /** 描述 */
    private String description;

    /**
     * 物理目录段（创建时唯一化确定，终身不变）：物理目录为 kb/{dir_name}。
     * 快照语义：改名不迁移目录；NULL 为存量行，上传路径解析回退按名称。
     */
    @Column(name = "dir_name")
    private String dirName;

    /** 状态：0=删除, 1=正常 */
    private Short status;

    /**
     * 模型处理状态（回调驱动）：
     * PENDING    - 待处理（createKb/deleteKb 时置，等待 KB_CREATED/KB_DELETED 回调）
     * COMPLETED  - 处理成功（向量库初始化/清理完成）
     * FAILED     - 处理失败（可重试）
     */
    @Column(name = "process_status")
    private String processStatus;

    /** 模型处理失败原因（KB_CREATED/KB_DELETED 回调 FAILED 时记录） */
    @Column(name = "process_error")
    private String processError;

    /** 模型处理失败错误码 */
    @Column(name = "process_error_code")
    private String processErrorCode;

    /** 模型处理完成/失败时间（回调时回传） */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 创建者 */
    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDirName() {
        return dirName;
    }

    public void setDirName(String dirName) {
        this.dirName = dirName;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
    }

    public String getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(String processStatus) {
        this.processStatus = processStatus;
    }

    public String getProcessError() {
        return processError;
    }

    public void setProcessError(String processError) {
        this.processError = processError;
    }

    public String getProcessErrorCode() {
        return processErrorCode;
    }

    public void setProcessErrorCode(String processErrorCode) {
        this.processErrorCode = processErrorCode;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
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
