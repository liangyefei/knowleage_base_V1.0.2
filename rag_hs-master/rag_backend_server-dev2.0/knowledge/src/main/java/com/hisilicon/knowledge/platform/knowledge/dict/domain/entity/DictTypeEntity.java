package com.hisilicon.knowledge.platform.knowledge.dict.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域层字典类型实体
 * <p>对应 {@code kb_dict_types} 表。定义一个属性类别（如"产品"、"文档类型"），
 * 并声明该类别管控哪些 metadata 键名（{@code controlledMetaKeys}，逗号分隔）。</p>
 * <p>typeCode 创建后不可修改（系统预置与自定义类型统一规则，标识符不可变）；
 * 预置类型（isPreset=true，系统初始化种子数据）另不可删除。</p>
 */
public class DictTypeEntity {

    /** 默认状态：启用（正常），出现于活跃查询与值校验 */
    public static final Short STATUS_ACTIVE = 1;

    /** 禁用状态：保留数据、可再启用，不出现在活跃查询中 */
    public static final Short STATUS_DISABLED = 2;

    /** 删除状态：软删除 */
    public static final Short STATUS_DELETED = 0;

    private final DictTypeId id;
    private final String typeCode;
    private final String typeName;
    private final String controlledMetaKeys;
    private final Short status;
    private final Boolean isPreset;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private DictTypeEntity(Builder builder) {
        this.id = builder.id;
        this.typeCode = builder.typeCode;
        this.typeName = builder.typeName;
        this.controlledMetaKeys = builder.controlledMetaKeys;
        this.status = builder.status;
        this.isPreset = builder.isPreset;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新字典类型
     *
     * @param typeCode           类型编码（唯一，小写下划线）
     * @param typeName           类型显示名
     * @param controlledMetaKeys 管控的 metadata 键名（逗号分隔，可为 null/空）
     * @return 新字典类型实体
     */
    public static DictTypeEntity create(String typeCode, String typeName, String controlledMetaKeys) {
        LocalDateTime now = LocalDateTime.now();
        return DictTypeEntity.builder()
                .id(DictTypeId.of(UUID.randomUUID().toString()))
                .typeCode(typeCode)
                .typeName(typeName)
                .controlledMetaKeys(controlledMetaKeys)
                .status(STATUS_ACTIVE)
                .isPreset(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：更新字典类型（全量替换）
     * <p>typeName/controlledMetaKeys 按传入值替换；typeCode 不可修改（标识符不可变，
     * 创建后保持原值）；controlledMetaKeys 为 null/空 = 清空管控（仅展示用）。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @param typeName           新的类型显示名
     * @param controlledMetaKeys 新的受控 meta_key 列表（null/空 = 清空）
     * @return 更新后的字典类型实体
     */
    public DictTypeEntity update(String typeName, String controlledMetaKeys) {
        return DictTypeEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .typeName(typeName)
                .controlledMetaKeys(controlledMetaKeys)
                .status(this.status)
                .isPreset(this.isPreset)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：软删除
     * <p>状态置为 STATUS_DELETED（0）并刷新更新时间，保留数据记录以便审计追溯。
     * 预置类型（isPreset=true）不可删除，由命令服务在调用前拒绝。</p>
     *
     * @return 删除后的字典类型实体
     */
    public DictTypeEntity delete() {
        return DictTypeEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .typeName(this.typeName)
                .controlledMetaKeys(this.controlledMetaKeys)
                .status(STATUS_DELETED)
                .isPreset(this.isPreset)
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
     * @return 切换状态后的字典类型实体
     * @throws IllegalStateException targetStatus 非法（非 1/2）
     */
    public DictTypeEntity changeStatus(Short targetStatus) {
        if (!STATUS_ACTIVE.equals(targetStatus) && !STATUS_DISABLED.equals(targetStatus)) {
            throw new IllegalStateException("字典类型状态切换目标不合法: " + targetStatus);
        }
        return DictTypeEntity.builder()
                .id(this.id)
                .typeCode(this.typeCode)
                .typeName(this.typeName)
                .controlledMetaKeys(this.controlledMetaKeys)
                .status(targetStatus)
                .isPreset(this.isPreset)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public DictTypeId getId() { return id; }
    public String getTypeCode() { return typeCode; }
    public String getTypeName() { return typeName; }
    public String getControlledMetaKeys() { return controlledMetaKeys; }
    public Short getStatus() { return status; }
    public Boolean getIsPreset() { return isPreset; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DictTypeId id;
        private String typeCode;
        private String typeName;
        private String controlledMetaKeys;
        private Short status;
        private Boolean isPreset = false;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(DictTypeId id) { this.id = id; return this; }
        public Builder typeCode(String typeCode) { this.typeCode = typeCode; return this; }
        public Builder typeName(String typeName) { this.typeName = typeName; return this; }
        public Builder controlledMetaKeys(String controlledMetaKeys) { this.controlledMetaKeys = controlledMetaKeys; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder isPreset(Boolean isPreset) { this.isPreset = isPreset; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public DictTypeEntity build() {
            if (id == null) throw new IllegalStateException("字典类型 ID 不能为空");
            if (typeCode == null || typeCode.isBlank()) throw new IllegalStateException("字典类型编码不能为空");
            if (typeName == null || typeName.isBlank()) throw new IllegalStateException("字典类型名称不能为空");
            return new DictTypeEntity(this);
        }
    }
}
