package com.hisilicon.knowledge.platform.llm.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层会话实体（不可变）
 * <p>记录用户与模型的一次会话及其会话级问答文件。搜索范围/芯片/深度思考为消息级配置，
 * 随 USER 消息持久化（见 {@link ConversationMessageEntity}），不再属于会话。</p>
 */
public class ConversationEntity {

    /** 活跃状态 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 归档状态（软删除） */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 标题自动生成：首条消息截取长度 */
    public static final int TITLE_MAX_LENGTH = 30;

    /** 标题入库上限（对齐 conversations.title varchar(200)，超长截断） */
    public static final int TITLE_DB_LENGTH = 200;

    /** 默认标题（创建时未传 title 或空白） */
    private static final String DEFAULT_TITLE = "新对话";

    private final ConversationId id;
    private final String account;
    private final String title;
    /** 标题是否手动重命名过（rename 接口置 true）；true 后首条消息不再自动覆盖 */
    private final Boolean titleManuallySet;
    private final String status;
    /** 是否置顶（pin 接口置 true）；列表查询置顶会话排前 */
    private final Boolean pinned;
    private final String fileName;
    private final Long fileSize;
    private final String filePath;
    private final String mimeType;
    private final String fileType;
    private final String tempKbId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private ConversationEntity(Builder builder) {
        this.id = builder.id;
        this.account = builder.account;
        this.title = builder.title;
        this.titleManuallySet = builder.titleManuallySet;
        this.status = builder.status;
        this.pinned = builder.pinned;
        this.fileName = builder.fileName;
        this.fileSize = builder.fileSize;
        this.filePath = builder.filePath;
        this.mimeType = builder.mimeType;
        this.fileType = builder.fileType;
        this.tempKbId = builder.tempKbId;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建会话（无文件）。搜索范围/芯片/深度思考为消息级配置，不在会话级设定。
     * <p>title 规范化：trim、空白默认「新对话」、超 {@link #TITLE_DB_LENGTH} 截断——
     * 保证不依赖调用方校验即可满足 conversations.title varchar(200) 约束。</p>
     */
    public static ConversationEntity create(String account, String title) {
        LocalDateTime now = LocalDateTime.now();
        return ConversationEntity.builder()
                .id(ConversationId.of(UUID.randomUUID().toString()))
                .account(account)
                .title(normalizeTitle(title))
                .status(STATUS_ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 标题规范化：trim → 空白默认标题 → 超长截断。
     * <p>截断后若末字符为未配对高代理项（拆散了 emoji 等增补字符），去掉该字符：
     * PG 按字符计数虽不会超宽，但未配对代理项经 UTF-8 编码写入会失败。</p>
     */
    private static String normalizeTitle(String title) {
        if (title == null) {
            return DEFAULT_TITLE;
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (trimmed.length() <= TITLE_DB_LENGTH) {
            return trimmed;
        }
        String truncated = trimmed.substring(0, TITLE_DB_LENGTH);
        char last = truncated.charAt(truncated.length() - 1);
        if (Character.isHighSurrogate(last)) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }

    /**
     * 领域行为：绑定会话文件（创建时或会话中上传）
     * <p>原实体不变，返回新实体。G.MET.01：文件描述收敛为 {@link ConversationFile}，
     * 模型侧临时知识库 id 作为上传产物单独传入。</p>
     */
    public ConversationEntity attachFile(ConversationFile file, String tempKbId) {
        return ConversationEntity.builder()
                .id(this.id)
                .account(this.account)
                .title(this.title)
                .titleManuallySet(this.titleManuallySet)
                .status(this.status)
                .pinned(this.pinned)
                .fileName(file.fileName())
                .fileSize(file.fileSize())
                .filePath(file.filePath())
                .mimeType(file.mimeType())
                .fileType(file.fileType())
                .tempKbId(tempKbId)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：发送消息后刷新最后消息时间 + 首条消息自动生成标题
     * <p>title 为 null 表示不更新标题（仅刷新 updatedAt）。</p>
     */
    public ConversationEntity touch(String newTitle) {
        return ConversationEntity.builder()
                .id(this.id)
                .account(this.account)
                .title(newTitle != null ? newTitle : this.title)
                .titleManuallySet(this.titleManuallySet)
                .status(this.status)
                .pinned(this.pinned)
                .fileName(this.fileName)
                .fileSize(this.fileSize)
                .filePath(this.filePath)
                .mimeType(this.mimeType)
                .fileType(this.fileType)
                .tempKbId(this.tempKbId)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：归档（软删除）
     * <p>ACTIVE → ARCHIVED，文件保留在 SFS。原实体不变，返回新实体。</p>
     */
    public ConversationEntity archive() {
        return ConversationEntity.builder()
                .id(this.id)
                .account(this.account)
                .title(this.title)
                .titleManuallySet(this.titleManuallySet)
                .status(STATUS_ARCHIVED)
                .pinned(this.pinned)
                .fileName(this.fileName)
                .fileSize(this.fileSize)
                .filePath(this.filePath)
                .mimeType(this.mimeType)
                .fileType(this.fileType)
                .tempKbId(this.tempKbId)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：重命名标题（手动）
     * <p>设置标题并标记 titleManuallySet=true——之后首条消息自动生成标题不再覆盖手动标题。</p>
     */
    public ConversationEntity rename(String newTitle) {
        return ConversationEntity.builder()
                .id(this.id)
                .account(this.account)
                .title(newTitle)
                .titleManuallySet(Boolean.TRUE)
                .status(this.status)
                .pinned(this.pinned)
                .fileName(this.fileName)
                .fileSize(this.fileSize)
                .filePath(this.filePath)
                .mimeType(this.mimeType)
                .fileType(this.fileType)
                .tempKbId(this.tempKbId)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：置顶 / 取消置顶
     * <p>置顶（pinned=true）后会话在列表中排在非置顶之前；取消置顶（pinned=false）恢复普通排序。
     * 重复置顶/取消幂等，直接覆写同一状态。原实体不变，返回新实体。</p>
     */
    public ConversationEntity pin(boolean pinned) {
        return ConversationEntity.builder()
                .id(this.id)
                .account(this.account)
                .title(this.title)
                .titleManuallySet(this.titleManuallySet)
                .status(this.status)
                .pinned(pinned)
                .fileName(this.fileName)
                .fileSize(this.fileSize)
                .filePath(this.filePath)
                .mimeType(this.mimeType)
                .fileType(this.fileType)
                .tempKbId(this.tempKbId)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ConversationId getId() { return id; }
    public String getAccount() { return account; }
    public String getTitle() { return title; }
    public Boolean getTitleManuallySet() { return titleManuallySet; }
    public String getStatus() { return status; }
    public Boolean getPinned() { return pinned; }
    public String getFileName() { return fileName; }
    public Long getFileSize() { return fileSize; }
    public String getFilePath() { return filePath; }
    public String getMimeType() { return mimeType; }
    public String getFileType() { return fileType; }
    public String getTempKbId() { return tempKbId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static class Builder {
        private ConversationId id;
        private String account;
        private String title;
        /** 默认 false；DB 老数据可能为 null，构造时归一 */
        private Boolean titleManuallySet = Boolean.FALSE;
        private String status;
        /** 默认 false；DB 老数据可能为 null，构造时归一 */
        private Boolean pinned = Boolean.FALSE;
        private String fileName;
        private Long fileSize;
        private String filePath;
        private String mimeType;
        private String fileType;
        private String tempKbId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(ConversationId id) { this.id = id; return this; }
        public Builder account(String account) { this.account = account; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder titleManuallySet(Boolean titleManuallySet) { this.titleManuallySet = titleManuallySet; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder pinned(Boolean pinned) { this.pinned = pinned; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder fileType(String fileType) { this.fileType = fileType; return this; }
        public Builder tempKbId(String tempKbId) { this.tempKbId = tempKbId; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public ConversationEntity build() {
            return new ConversationEntity(this);
        }
    }
}
