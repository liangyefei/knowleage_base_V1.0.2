package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 领域层知识库实体
 */
public class KnowledgeBaseEntity {

    /** 默认状态：正常 */
    public static final Short STATUS_ACTIVE = 1;

    /** 删除状态：软删除 */
    public static final Short STATUS_DELETED = 0;

    // ========== 模型处理状态（与 DocumentEntity 对齐） ==========

    /** 模型处理：待处理（createKb/deleteKb 时置，等待回调） */
    public static final String PROCESS_PENDING = "PENDING";
    /** 模型处理：处理成功 */
    public static final String PROCESS_COMPLETED = "COMPLETED";
    /** 模型处理：处理失败（可重试） */
    public static final String PROCESS_FAILED = "FAILED";

    private final KnowledgeBaseId id;
    private final String name;
    private final String description;
    /**
     * 物理目录段（创建时唯一化确定，终身不变）：KB 物理目录为 {@code kb/{dirName}}。
     * 快照语义：改名不迁移目录，上传路径解析优先取本字段；NULL 为存量行，回退按名称解析。
     */
    private final String dirName;
    private final Short status;
    private final String processStatus;
    private final String processError;
    private final String processErrorCode;
    private final java.time.LocalDateTime processedAt;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private KnowledgeBaseEntity(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.dirName = builder.dirName;
        this.status = builder.status;
        this.processStatus = builder.processStatus;
        this.processError = builder.processError;
        this.processErrorCode = builder.processErrorCode;
        this.processedAt = builder.processedAt;
        this.createdBy = builder.createdBy;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新知识库
     * <p>封装默认状态、ID 生成、创建/更新时间等创建规则。</p>
     *
     * @param name        知识库名称
     * @param description 描述（可为空）
     * @param createdBy   创建者用户 ID
     * @return 新知识库实体
     */
    public static KnowledgeBaseEntity create(String name, String description, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(IdGenerator.kb()))
                .name(name)
                .description(description)
                .status(STATUS_ACTIVE)
                .processStatus(PROCESS_PENDING)
                .createdBy(createdBy)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：绑定物理目录段（创建流程专用，仅在首次持久化前调用一次）。
     * <p>目录段由应用服务在磁盘探测唯一化后传入；此后终身不变（含改名/删除）。</p>
     *
     * @param dirName 唯一化后的物理目录段（kb/ 之下的单段）
     * @return 携带目录段的新实体
     */
    public KnowledgeBaseEntity withDirName(String dirName) {
        return KnowledgeBaseEntity.builder()
                .id(this.id)
                .name(this.name)
                .description(this.description)
                .dirName(dirName)
                .status(this.status)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .processErrorCode(this.processErrorCode)
                .processedAt(this.processedAt)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 领域行为：更新基础信息（全量替换）
     * <p>直接以传入的 name、description 替换原值；description 为 null 或空串表示清空描述。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @param name        新的知识库名称
     * @param description 新的描述（可为 null 或空串）
     * @return 更新后的知识库实体
     */
    public KnowledgeBaseEntity update(String name, String description) {
        return KnowledgeBaseEntity.builder()
                .id(this.id)
                .name(name)
                .description(description)
                .dirName(this.dirName)
                .status(this.status)
                .processStatus(this.processStatus)
                .processError(this.processError)
                .processErrorCode(this.processErrorCode)
                .processedAt(this.processedAt)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：软删除
     * <p>状态置为 STATUS_DELETED（0）并刷新更新时间，保留数据记录以便审计追溯。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @return 删除后的知识库实体
     */
    public KnowledgeBaseEntity delete() {
        return KnowledgeBaseEntity.builder()
                .id(this.id)
                .name(this.name)
                .description(this.description)
                .dirName(this.dirName)
                .status(STATUS_DELETED)
                // 软删除后重置为 PENDING：等待模型端 KB_DELETED 回调确认向量清理结果
                .processStatus(PROCESS_PENDING)
                .processError(null)
                .processErrorCode(null)
                .processedAt(null)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：应用模型处理回调结果
     * <p>仅修改 processStatus / processError / processErrorCode / processedAt，
     * 不动生命周期 status（created active / deleted）。原实体不变，返回新实体。</p>
     *
     * @param processStatus    COMPLETED / FAILED
     * @param processError     失败原因（FAILED 时记录）
     * @param processErrorCode 失败错误码
     * @param processedAt      处理完成/失败时间
     * @return 应用回调后的新实体
     */
    public KnowledgeBaseEntity withProcessResult(String processStatus, String processError,
                                                  String processErrorCode, LocalDateTime processedAt) {
        return KnowledgeBaseEntity.builder()
                .id(this.id)
                .name(this.name)
                .description(this.description)
                .dirName(this.dirName)
                .status(this.status)
                .processStatus(processStatus)
                .processError(processError)
                .processErrorCode(processErrorCode)
                .processedAt(processedAt)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public KnowledgeBaseId getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDirName() { return dirName; }
    public Short getStatus() { return status; }
    public String getProcessStatus() { return processStatus; }
    public String getProcessError() { return processError; }
    public String getProcessErrorCode() { return processErrorCode; }
    public java.time.LocalDateTime getProcessedAt() { return processedAt; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private KnowledgeBaseId id;
        private String name;
        private String description;
        private String dirName;
        private Short status;
        private String processStatus;
        private String processError;
        private String processErrorCode;
        private java.time.LocalDateTime processedAt;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(KnowledgeBaseId id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder dirName(String dirName) { this.dirName = dirName; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder processStatus(String processStatus) { this.processStatus = processStatus; return this; }
        public Builder processError(String processError) { this.processError = processError; return this; }
        public Builder processErrorCode(String processErrorCode) { this.processErrorCode = processErrorCode; return this; }
        public Builder processedAt(java.time.LocalDateTime processedAt) { this.processedAt = processedAt; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public KnowledgeBaseEntity build() {
            if (id == null) throw new IllegalStateException("知识库 ID 不能为空");
            if (name == null || name.isBlank()) throw new IllegalStateException("知识库名称不能为空");
            return new KnowledgeBaseEntity(this);
        }
    }
}
