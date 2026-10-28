package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 对话会话表
 * <p>
 * 记录用户与模型的一次会话及会话级问答文件（每会话最多 1 个，≤10MB，类型白名单与
 * 知识库文档上传一致，不支持压缩包）。搜索范围/芯片/深度思考为消息级配置，随 USER
 * 消息持久化（conversation_messages），不再属于会话。
 * </p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问（Hibernate 属性访问、MapStruct
 * 按属性映射），禁止直接读字段。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "conversations")
public class ConversationPO {

    @Id
    private String id;

    /** 用户账号（JWT sub，后续对接 hispark 用户体系） */
    private String account;

    /** 对话标题（首条问题截取 30 字；手动重命名后不再自动覆盖） */
    private String title;

    /** 标题是否手动重命名过（rename 接口置 true）；true 后首条消息不再自动覆盖 */
    @Column(name = "title_manually_set")
    private Boolean titleManuallySet;

    /** 状态：ACTIVE / ARCHIVED */
    private String status;

    /** 是否置顶（pin 接口置 true）；列表查询置顶会话排前 */
    private Boolean pinned;

    /** 上传文件原始文件名（可空=无文件对话） */
    @Column(name = "file_name")
    private String fileName;

    /** 上传文件大小（bytes） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 文件存储相对路径（SFS，kb/chat/{conversationId}/{文件名}） */
    @Column(name = "file_path")
    private String filePath;

    /** 上传文件 MIME 类型 */
    @Column(name = "mime_type")
    private String mimeType;

    /** 文件类型分类：DOCUMENT/CODE/CONFIG/OTHER */
    @Column(name = "file_type")
    private String fileType;

    /** 模型服务临时知识库 ID（upload_temp_docs 返回值，file_chat 问答必需） */
    @Column(name = "temp_kb_id")
    private String tempKbId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Boolean getTitleManuallySet() {
        return titleManuallySet;
    }

    public void setTitleManuallySet(Boolean titleManuallySet) {
        this.titleManuallySet = titleManuallySet;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getPinned() {
        return pinned;
    }

    public void setPinned(Boolean pinned) {
        this.pinned = pinned;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getTempKbId() {
        return tempKbId;
    }

    public void setTempKbId(String tempKbId) {
        this.tempKbId = tempKbId;
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
