package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 领域层热门词实体（不可变）
 * <p>热门词是高频用户问题词库，按知识库归属；每知识库启用中至多 5 条（应用层事务内保证）。</p>
 */
public class HotWordEntity {

    /** 状态：启用中 */
    public static final Short STATUS_ENABLED = 1;

    /** 状态：已停用 */
    public static final Short STATUS_DISABLED = 0;

    /** 内容最大长度（列 content varchar(200)） */
    public static final int CONTENT_MAX_LENGTH = 200;

    private final HotWordId id;
    private final String content;
    private final String kbId;
    private final Integer sortOrder;
    private final Short status;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private HotWordEntity(Builder builder) {
        this.id = builder.id;
        this.content = builder.content;
        this.kbId = builder.kbId;
        this.sortOrder = builder.sortOrder;
        this.status = builder.status;
        this.createdBy = builder.createdBy;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新热门词
     *
     * @param content   热门词内容（非空，长度 ≤ 200）
     * @param kbId      所属知识库 ID（非空）
     * @param sortOrder 排序号（null 时默认 0）
     * @param status    状态，仅允许 0/1，非法抛 {@link IllegalArgumentException}
     * @param createdBy 创建者用户 ID
     * @return 新热门词实体
     */
    public static HotWordEntity create(String content, String kbId, Integer sortOrder, Short status, String createdBy) {
        if (status == null || (status.intValue() != STATUS_ENABLED.intValue()
                && status.intValue() != STATUS_DISABLED.intValue())) {
            throw new IllegalArgumentException("热门词状态非法，仅允许 0（停用）或 1（启用）");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("热门词内容不能为空");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("热门词内容长度不能超过 " + CONTENT_MAX_LENGTH);
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("所属知识库不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        return HotWordEntity.builder()
                .id(HotWordId.of(IdGenerator.hotWord()))
                .content(content)
                .kbId(kbId)
                .sortOrder(sortOrder != null ? sortOrder : 0)
                .status(status)
                .createdBy(createdBy)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：更新内容。原实体不变，返回新实体。
     *
     * @param content 新内容（非空，长度 ≤ 200）
     * @return 更新后的新实体
     */
    public HotWordEntity withContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("热门词内容不能为空");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("热门词内容长度不能超过 " + CONTENT_MAX_LENGTH);
        }
        return copy(this.builderFrom(), content, this.sortOrder, this.status, LocalDateTime.now());
    }

    /**
     * 领域行为：更新排序号。原实体不变，返回新实体。
     *
     * @param sortOrder 新排序号（null 时默认 0）
     * @return 更新后的新实体
     */
    public HotWordEntity withSortOrder(Integer sortOrder) {
        return copy(this.builderFrom(), this.content, sortOrder != null ? sortOrder : 0,
                this.status, LocalDateTime.now());
    }

    /**
     * 领域行为：更换所属知识库。原实体不变，返回新实体。
     * <p>唯一性与目标知识库存在性由应用服务层校验，此方法只做基础合法性检查。</p>
     *
     * @param kbId 新知识库 ID（非空）
     * @return 更换归属后的新实体
     */
    public HotWordEntity withKbId(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("所属知识库不能为空");
        }
        return this.builderFrom()
                .content(this.content)
                .sortOrder(this.sortOrder)
                .status(this.status)
                .updatedAt(LocalDateTime.now())
                .kbId(kbId)
                .build();
    }

    /**
     * 领域行为：直接设置状态（编辑全量替换用）。原实体不变，返回新实体。
     *
     * @param status 目标状态，仅允许 0/1，非法抛 {@link IllegalArgumentException}
     * @return 指定状态的新实体
     */
    public HotWordEntity withStatus(Short status) {
        if (status == null || (status.intValue() != STATUS_ENABLED.intValue()
                && status.intValue() != STATUS_DISABLED.intValue())) {
            throw new IllegalArgumentException("热门词状态非法，仅允许 0（停用）或 1（启用）");
        }
        return copy(this.builderFrom(), this.content, this.sortOrder, status, LocalDateTime.now());
    }

    /**
     * 领域行为：启用。原实体不变，返回新实体。
     */
    public HotWordEntity enable() {
        return copy(this.builderFrom(), this.content, this.sortOrder, STATUS_ENABLED, LocalDateTime.now());
    }

    /**
     * 领域行为：停用。原实体不变，返回新实体。
     */
    public HotWordEntity disable() {
        return copy(this.builderFrom(), this.content, this.sortOrder, STATUS_DISABLED, LocalDateTime.now());
    }

    private Builder builderFrom() {
        return HotWordEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .createdBy(this.createdBy)
                .createdAt(this.createdAt);
    }

    private static HotWordEntity copy(Builder builder, String content, Integer sortOrder,
                                      Short status, LocalDateTime updatedAt) {
        return builder
                .content(content)
                .sortOrder(sortOrder)
                .status(status)
                .updatedAt(updatedAt)
                .build();
    }

    // ========== getters ==========

    public HotWordId getId() { return id; }
    public String getContent() { return content; }
    public String getKbId() { return kbId; }
    public Integer getSortOrder() { return sortOrder; }
    public Short getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private HotWordId id;
        private String content;
        private String kbId;
        private Integer sortOrder;
        private Short status;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(HotWordId id) { this.id = id; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public Builder sortOrder(Integer sortOrder) { this.sortOrder = sortOrder; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public HotWordEntity build() {
            if (id == null) throw new IllegalStateException("热门词 ID 不能为空");
            if (content == null || content.isBlank()) throw new IllegalStateException("热门词内容不能为空");
            if (kbId == null || kbId.isBlank()) throw new IllegalStateException("所属知识库不能为空");
            if (status == null || (status.intValue() != STATUS_ENABLED.intValue()
                && status.intValue() != STATUS_DISABLED.intValue())) {
                throw new IllegalStateException("热门词状态非法，仅允许 0（停用）或 1（启用）");
            }
            return new HotWordEntity(this);
        }
    }
}
