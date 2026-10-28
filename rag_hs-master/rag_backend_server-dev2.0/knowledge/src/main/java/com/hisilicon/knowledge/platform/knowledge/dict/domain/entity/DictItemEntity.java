package com.hisilicon.knowledge.platform.knowledge.dict.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层字典条目实体
 * <p>对应 {@code kb_dict_items} 表。某字典类型下的合法取值集合。
 * 删除前校验业务数据引用，被引用则拒绝；软删后该值不再作为未来选项。</p>
 */
public class DictItemEntity {

    /** 默认状态：启用（正常），出现于活跃查询与值校验 */
    public static final Short STATUS_ACTIVE = 1;

    /** 禁用状态：保留数据、可再启用，不出现在活跃查询中 */
    public static final Short STATUS_DISABLED = 2;

    /** 删除状态：软删除 */
    public static final Short STATUS_DELETED = 0;

    private final DictItemId id;
    private final String typeCode;
    private final String label;
    private final String value;
    private final Integer sortOrder;
    private final Short status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private DictItemEntity(Builder builder) {
        this.id = builder.id;
        this.typeCode = builder.typeCode;
        this.label = builder.label;
        this.value = builder.value;
        this.sortOrder = builder.sortOrder;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新字典条目
     *
     * @param typeCode  所属字典类型编码
     * @param label     显示名称
     * @param value     存储值（写入 metadata 的实际值）
     * @param sortOrder 排序号（null 时由命令服务按"同级最大 + 1"计算）
     * @return 新字典条目实体
     */
    public static DictItemEntity create(String typeCode, String label, String value, Integer sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        return DictItemEntity.builder()
                .id(DictItemId.of(UUID.randomUUID().toString()))
                .typeCode(typeCode)
                .label(label)
                .value(value)
                .sortOrder(sortOrder != null ? sortOrder : 0)
                .status(STATUS_ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：更新字典条目
     * <p>label/value 全量替换；<b>sortOrder 为 null 时保持原值</b>
     * （用户确认的例外：排序字段不参与全量替换语义，不传 = 不变）。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @param label     新的显示名称
     * @param value     新的存储值
     * @param sortOrder 新的排序号（null = 保持原值）
     * @return 更新后的字典条目实体
     */
    public DictItemEntity update(String label, String value, Integer sortOrder) {
        return DictItemEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .label(label)
                .value(value)
                .sortOrder(sortOrder != null ? sortOrder : this.sortOrder)
                .status(this.status)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：软删除
     * <p>状态置为 STATUS_DELETED（0）并刷新更新时间，保留数据记录以便审计追溯。</p>
     *
     * @return 删除后的字典条目实体
     */
    public DictItemEntity delete() {
        return DictItemEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .label(this.label)
                .value(this.value)
                .sortOrder(this.sortOrder)
                .status(STATUS_DELETED)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：切换启用/禁用状态（在 1↔2 之间切换）
     * <p>仅允许在 {@link #STATUS_ACTIVE} 与 {@link #STATUS_DISABLED} 间切换；
     * 软删除（{@link #STATUS_DELETED}）走 {@link #delete()} 通道，本方法拒绝。
     * 原实体不变（不可变设计），返回新实体并刷新更新时间。
     * 再启用（2→1）的唯一性冲突由命令服务在调用前预检。</p>
     *
     * @param targetStatus 目标状态（必须为 {@link #STATUS_ACTIVE} 或 {@link #STATUS_DISABLED}）
     * @return 切换状态后的字典条目实体
     * @throws IllegalStateException targetStatus 非法（非 1/2）
     */
    public DictItemEntity changeStatus(Short targetStatus) {
        if (!STATUS_ACTIVE.equals(targetStatus) && !STATUS_DISABLED.equals(targetStatus)) {
            throw new IllegalStateException("字典条目状态切换目标不合法: " + targetStatus);
        }
        return DictItemEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .label(this.label)
                .value(this.value)
                .sortOrder(this.sortOrder)
                .status(targetStatus)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public DictItemId getId() { return id; }
    public String getTypeCode() { return typeCode; }
    public String getLabel() { return label; }
    public String getValue() { return value; }
    public Integer getSortOrder() { return sortOrder; }
    public Short getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DictItemId id;
        private String typeCode;
        private String label;
        private String value;
        private Integer sortOrder;
        private Short status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(DictItemId id) { this.id = id; return this; }
        public Builder typeCode(String typeCode) { this.typeCode = typeCode; return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder value(String value) { this.value = value; return this; }
        public Builder sortOrder(Integer sortOrder) { this.sortOrder = sortOrder; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public DictItemEntity build() {
            if (id == null) throw new IllegalStateException("字典条目 ID 不能为空");
            if (typeCode == null || typeCode.isBlank()) throw new IllegalStateException("字典条目所属类型编码不能为空");
            if (label == null || label.isBlank()) throw new IllegalStateException("字典条目显示名称不能为空");
            if (value == null || value.isBlank()) throw new IllegalStateException("字典条目值不能为空");
            return new DictItemEntity(this);
        }
    }
}
